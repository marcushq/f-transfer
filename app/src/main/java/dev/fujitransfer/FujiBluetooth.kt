package dev.fujitransfer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The camera's access point, as read over Bluetooth. */
data class CameraAp(val ssid: String, val password: String)

open class BleException(message: String) : Exception(message)

/**
 * Wakes the Wi-Fi of newer bodies (X-S20, X-T5, X-H2, X100VI…), which only start their access point
 * when a paired phone asks over Bluetooth LE. Photos never travel over Bluetooth.
 *
 * Follows gkoh/furble (pairing), petabyt/libfuji (Wi-Fi wake) and Th0rgal/fuji-bridge (secure pairing,
 * function code 3 for image import).
 */
@SuppressLint("MissingPermission") // Callers request BLUETOOTH_SCAN / BLUETOOTH_CONNECT first.
class FujiBluetooth(private val context: Context, private val log: (String) -> Unit) {
    companion object {
        const val COMPANY_ID = 0x04D8
        const val CLIENT_NAME = "Fuji Transfer"

        private fun u(s: String): UUID = UUID.fromString(s)
        val SECURE_STATUS = u("f557d96b-8284-4667-8793-b971c1deca2a")
        val BASIC_TOKEN = u("aba356eb-9633-4e60-b73f-f52516dbd671")
        val IDENTITY = u("85b9163e-62d1-49ff-a6f5-054b4630d4a1")
        val INDICATION1 = u("a68e3f66-0fcc-4395-8d4c-aa980b5877fa")
        val SSID = u("bf6dc9cf-3606-4ec9-a4c8-d77576e93ea4")
        val PASSWORD = u("e809256a-915c-4967-92e8-53b7d4cad213")
        /** FUNCTION_LAUNCH_REQUEST: 3 = in-camera viewing / image import. */
        val WIFI_WAKE = u("600655e6-3637-42f1-8fb2-44efc5c63b13")
        val IMPORT_FUNCTION = byteArrayOf(0x03, 0x00)
        val CCCD = u("00002902-0000-1000-8000-00805f9b34fb")

        /** What XApp listens to; a body may wait for its client to subscribe before serving Wi-Fi. */
        val SUBSCRIPTIONS = listOf(
            INDICATION1,
            u("bd17ba04-b76b-4892-a545-b73ba1f74dae"),
            u("f9150137-5d40-4801-a8dc-f7fc5b01da50"),
            u("ad06c7b7-f41a-46f4-a29a-712055319122"),
            u("7170fd5a-56d9-4c19-b043-7a7047d8e1a0"),
            SSID,
            u("75823784-fbb7-4b71-abae-cd9a34072e3c"),
            u("e6692c5c-b7cd-44f4-95fc-eda07ce32560"),
            u("aab609c4-94dd-4d89-bc60-665d5090b828"),
            u("2a125640-706d-4dd1-b420-c0f4ab93c361"),
            u("82a9f452-c5ce-4ef5-8203-3fc9a47f8171"),
            u("deef7187-3f43-4364-9e22-11a8c8a15951"),
            u("c95d91ae-b247-4d6d-8661-7dd5d6a0f85b"),
        )
    }

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = context.getSharedPreferences("bluetooth", Context.MODE_PRIVATE)

    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<Pair<Int, ByteArray?>>? = null
    private var connected = CompletableDeferred<Boolean>()
    private var servicesReady = CompletableDeferred<Boolean>()
    private val notifications = ConcurrentHashMap<UUID, Channel<ByteArray>>()

    val isAvailable get() = adapter != null
    val isEnabled get() = adapter?.isEnabled == true

    private class Found(val device: BluetoothDevice, val token: ByteArray?)

    /** Finds the camera, says hello, asks it to start Wi-Fi and returns the network to join. */
    suspend fun wakeWifi(firstScanMs: Long = 25_000, onPairing: () -> Unit): CameraAp {
        if (adapter == null) throw BleException("Điện thoại này không có Bluetooth.")
        if (!adapter.isEnabled) throw BleException("Bluetooth đang tắt. Hãy bật Bluetooth rồi thử lại.")

        var lastError: Exception = BleException("Không tìm thấy máy ảnh Fujifilm qua Bluetooth.")
        // A body drops the link right after a new pairing is confirmed, then waits for the bonded phone.
        repeat(3) { attempt ->
            val found = scan(if (attempt == 0) firstScanMs else 15_000)
            log("BLE: found ${found.device.name ?: found.device.address} (attempt ${attempt + 1}, bond=${found.device.bondState})")
            try {
                connect(found.device)
                return handshake(found, onPairing).also {
                    prefs.edit().putString("address", found.device.address).apply()
                }
            } catch (e: BleException) {
                lastError = e
                log("BLE: ${e.message}")
                close()
                if (attempt < 2) delay(2_000)
            }
        }
        throw lastError
    }

    private suspend fun scan(timeoutMs: Long): Found {
        val scanner = adapter!!.bluetoothLeScanner ?: throw BleException("Không dùng được Bluetooth để tìm máy ảnh.")
        val remembered = prefs.getString("address", null)
        val result = CompletableDeferred<Found>()
        val seen = mutableSetOf<String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, r: ScanResult) {
                val data = r.scanRecord?.getManufacturerSpecificData(COMPANY_ID) ?: return
                // Manufacturer data after the company id: type byte, then (type 0x02) the 4-byte pairing token.
                val token = if (data.size >= 5 && data[0].toInt() == 0x02) data.copyOfRange(1, 5) else null
                if (seen.add(r.device.address)) {
                    log("BLE: advert ${r.device.name ?: "?"} ${r.device.address} rssi ${r.rssi} data ${data.joinToString(" ") { "%02x".format(it) }}")
                }
                // Prefer the body we paired with last time, but take any Fujifilm after a moment.
                if (remembered == null || r.device.address == remembered || token != null) {
                    result.complete(Found(r.device, token))
                }
            }

            override fun onScanFailed(errorCode: Int) {
                result.completeExceptionally(BleException("Tìm Bluetooth thất bại ($errorCode)."))
            }
        }
        val filter = ScanFilter.Builder().setManufacturerData(COMPANY_ID, ByteArray(0)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        log("BLE: scanning for Fujifilm cameras…")
        scanner.startScan(listOf(filter), settings, cb)
        try {
            // After 6 s accept any Fujifilm body, even if it isn't the remembered one.
            withTimeoutOrNull(6_000) { result.await() }?.let { return it }
            val anyCb = seen.firstOrNull()
            if (anyCb != null && !result.isCompleted) {
                result.complete(Found(adapter.getRemoteDevice(anyCb), null))
            }
            return withTimeoutOrNull(timeoutMs - 6_000) { result.await() }
                ?: throw BleException("Không tìm thấy máy ảnh Fujifilm qua Bluetooth. Máy ảnh đã bật và đã bật Bluetooth trong cài đặt chưa?")
        } finally {
            scanner.stopScan(cb)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            log("BLE: connection state $newState (status $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected.complete(false)
                servicesReady.complete(false)
                pending?.complete(-1 to null)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesReady.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            pending?.complete(status to value)
        }

        @Deprecated("Pre-Android 13")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            pending?.complete(status to c.value)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status to null)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status to null)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            notifications[c.uuid]?.trySend(value)
        }

        @Deprecated("Pre-Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            notifications[c.uuid]?.trySend(c.value ?: ByteArray(0))
        }
    }

    private suspend fun connect(device: BluetoothDevice) {
        connected = CompletableDeferred()
        servicesReady = CompletableDeferred()
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = withTimeoutOrNull(15_000) { connected.await() } ?: false
        if (!ok) throw BleException("Không kết nối được qua Bluetooth. Nếu máy ảnh đang ghép đôi với điện thoại khác, hãy ghép đôi lại với điện thoại này (máy ảnh: PAIRING REGISTRATION).")
        delay(300)
        gatt?.discoverServices()
        val ready = withTimeoutOrNull(15_000) { servicesReady.await() } ?: false
        if (!ready) throw BleException("Không đọc được dịch vụ Bluetooth của máy ảnh.")
        log("BLE: services: " + gatt!!.services.joinToString { it.uuid.toString().take(8) })
    }

    private fun chr(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.services?.firstNotNullOfOrNull { s -> s.getCharacteristic(uuid) }

    private suspend fun op(start: () -> Boolean): Pair<Int, ByteArray?> = opLock.withLock {
        val d = CompletableDeferred<Pair<Int, ByteArray?>>()
        pending = d
        try {
            if (!start()) return@withLock (-2 to null)
            withTimeoutOrNull(15_000) { d.await() } ?: (-3 to null)
        } finally {
            pending = null
        }
    }

    private suspend fun read(uuid: UUID): ByteArray {
        val c = chr(uuid) ?: throw BleException("Máy ảnh không có đặc tính Bluetooth $uuid")
        val (status, value) = op { gatt!!.readCharacteristic(c) }
        if (status != BluetoothGatt.GATT_SUCCESS) throw GattStatus(status)
        return value ?: ByteArray(0)
    }

    private suspend fun write(uuid: UUID, value: ByteArray) {
        val c = chr(uuid) ?: throw BleException("Máy ảnh không có đặc tính Bluetooth $uuid")
        val (status, _) = op {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt!!.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
            } else {
                @Suppress("DEPRECATION")
                c.value = value
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt!!.writeCharacteristic(c)
            }
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw GattStatus(status)
    }

    private suspend fun subscribe(uuid: UUID) {
        val c = chr(uuid) ?: return
        notifications.getOrPut(uuid) { Channel(Channel.UNLIMITED) }
        gatt!!.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCCD) ?: return
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        }
        op {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt!!.writeDescriptor(d, value) == 0
            } else {
                @Suppress("DEPRECATION")
                d.value = value
                @Suppress("DEPRECATION")
                gatt!!.writeDescriptor(d)
            }
        }
    }

    private class GattStatus(val status: Int) : BleException("Lỗi Bluetooth (GATT status $status)") {
        /** Insufficient authentication / encryption: the bond is not there yet. */
        val pairingPending get() = status == 5 || status == 15 || status == 137
    }

    private suspend fun handshake(found: Found, onPairing: () -> Unit): CameraAp {
        if (chr(SECURE_STATUS) != null) {
            // Secure bodies: the first encrypted read triggers Android's pairing dialog.
            var status: ByteArray? = null
            for (i in 0 until 40) {
                try {
                    status = read(SECURE_STATUS)
                    break
                } catch (e: GattStatus) {
                    if (!e.pairingPending) throw e
                    if (i == 0) {
                        log("BLE: pairing needed — confirm the code on the phone and the camera")
                        onPairing()
                        if (found.device.bondState == BluetoothDevice.BOND_NONE) found.device.createBond()
                    }
                    delay(2_000)
                }
            }
            val s = status ?: throw BleException("Ghép đôi chưa hoàn tất.")
            log("BLE: secure status ${s.joinToString(" ") { "%02x".format(it) }}")
            if (s.size == 4) s[3] = 0x20
            write(SECURE_STATUS, s)
        } else if (found.token != null) {
            write(BASIC_TOKEN, found.token)
            log("BLE: pairing token sent")
        }
        write(IDENTITY, CLIENT_NAME.toByteArray())
        log("BLE: identified as \"$CLIENT_NAME\"")

        SUBSCRIPTIONS.forEach { subscribe(it) }

        val ssid = text(read(SSID))
        log("BLE: camera Wi-Fi \"$ssid\"")
        val answers = notifications.getOrPut(INDICATION1) { Channel(Channel.UNLIMITED) }
        while (answers.tryReceive().isSuccess) { /* drop answers from before the wake */ }

        write(WIFI_WAKE, IMPORT_FUNCTION)
        log("BLE: asked the camera to start Wi-Fi (03 00)")
        val password = try { text(read(PASSWORD)) } catch (e: BleException) { "" }
        log("BLE: password ${if (password.isEmpty()) "none" else "${password.length} characters"}")

        // AP_STATE: 01 = access point up, 00 = not (yet). An X-S20 answers 00 within milliseconds
        // and 01 about 7 s later, so 00 means "keep waiting", not "give up". Ask again every 5 s.
        val deadline = System.currentTimeMillis() + 25_000
        var lastAsk = System.currentTimeMillis()
        var sawAnswer = false
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) {
                if (sawAnswer) throw BleException("Máy ảnh báo đang bận và không bật Wi-Fi. Hãy thoát menu/chế độ xem ảnh trên máy ảnh rồi thử lại.")
                log("BLE: no Wi-Fi answer in 25 s, trying anyway")
                break
            }
            val answer = withTimeoutOrNull(minOf(left, 5_000)) { answers.receive() }
            if (answer != null) {
                sawAnswer = true
                log("BLE: Wi-Fi answer ${answer.joinToString(" ") { "%02x".format(it) }}")
                if (answer.firstOrNull()?.toInt() != 0) break
            }
            if (System.currentTimeMillis() - lastAsk >= 5_000) {
                lastAsk = System.currentTimeMillis()
                log("BLE: Wi-Fi not up yet, asking again")
                write(WIFI_WAKE, IMPORT_FUNCTION)
            }
        }
        if (ssid.isEmpty()) throw BleException("Máy ảnh không gửi tên Wi-Fi.")
        return CameraAp(ssid, password)
    }

    private fun text(b: ByteArray) = String(b.takeWhile { it != 0.toByte() }.toByteArray(), Charsets.UTF_8).trim()

    /** Keep the link until the Wi-Fi session answers; a body that loses its app on both radios gives up. */
    fun close() {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        notifications.clear()
    }
}
