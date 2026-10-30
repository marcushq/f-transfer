package dev.fujitransfer.protocol

/**
 * Constants for Fujifilm's Wi-Fi flavour of PTP.
 *
 * Fuji uses an ISO PTP/IP style init handshake, then plain USB-style PTP containers
 * (12-byte little-endian header) on the same TCP socket. Values follow the published,
 * MIT-licensed libfuji client (github.com/petabyt/libfuji).
 */
object Fuji {
    const val CAMERA_HOST = "192.168.0.1"
    const val CMD_PORT = 55740
    const val EVENT_PORT = 55741
    const val LIVEVIEW_PORT = 55742

    const val PROTOCOL_VERSION = 0x8f53e4f2.toInt()
    const val CAM_CONNECT_REMOTE_VER = 0x2000C

    /** GetPartialObject window. Fuji caps reads at 1 MB. */
    const val MAX_PARTIAL = 0x100000

    /** Ceiling when reading past a too-small announced length; far above any X-series JPEG. */
    const val MAX_JPEG_BYTES = 64L * 1024 * 1024

    // Container types
    const val TYPE_COMMAND = 1
    const val TYPE_DATA = 2
    const val TYPE_RESPONSE = 3

    // PTP/IP init packet types
    const val INIT_COMMAND_REQ = 1
    const val INIT_FAIL = 5

    // Operation codes
    const val OC_OPEN_SESSION = 0x1002
    const val OC_CLOSE_SESSION = 0x1003
    const val OC_GET_OBJECT_INFO = 0x1008
    const val OC_GET_THUMB = 0x100A
    const val OC_GET_PROP_VALUE = 0x1015
    const val OC_SET_PROP_VALUE = 0x1016
    const val OC_TERMINATE_OPEN_CAPTURE = 0x1018
    const val OC_GET_PARTIAL_OBJECT = 0x101B
    const val OC_INITIATE_OPEN_CAPTURE = 0x101C
    const val OC_GET_OBJECT_PROP_VALUE = 0x9803

    // XApp-era vendor operations (X-S20, X-T5, X-H2, X100VI…)
    const val OC_GET_IMAGE_IMPORT_FOLDERS = 0x9050
    const val OC_GET_IMAGE_IMPORT_DATES = 0x9053
    const val OC_GET_EXTENSION_OBJECT_INFO = 0x9054
    const val OC_GET_EXTENSION_THUMB = 0x9055

    // Object properties
    const val OPC_OBJECT_SIZE = 0xDC04
    const val OPC_OBJECT_FILE_NAME = 0xDC07
    const val OPC_DATE_CREATED = 0xDC08
    /** Length of the resized copy while resizing (XApp reads it instead of ObjectSize). */
    const val OPC_COMPRESSED_SIZE = 0xD802

    // Response codes
    const val RC_OK = 0x2001
    const val RC_SESSION_ALREADY_OPEN = 0x201E
    const val RC_GENERAL_ERROR = 0x2002
    const val RC_INVALID_OBJECT_HANDLE = 0x2009
    const val RC_STORE_NOT_AVAILABLE = 0x2013
    const val RC_DEVICE_BUSY = 0x2019
    const val RC_INVALID_PARAMETER = 0x201D

    /** Codes that mean "not now" rather than "never": the body is still reading the card. */
    fun isTransient(rc: Int) = rc == RC_DEVICE_BUSY || rc == RC_STORE_NOT_AVAILABLE

    // Device properties
    const val DPC_EVENTS_LIST = 0xD212
    const val DPC_OBJECT_COUNT = 0xD222
    /** XApp's ImageForceCompression: 2 = send the original, 1 = resized. */
    const val DPC_COMPRESS_SMALL = 0xD226
    const val DPC_CORRECT_FILE_SIZE = 0xD227
    const val DPC_UNKNOWN_D22B = 0xD22B
    /** XApp's resize size while D226 = 1: 1 = S, 0 = XS. */
    const val DPC_RESIZE_RATE = 0xD22E
    const val DPC_IMAGE_IMPORT_COUNT = 0xD620
    const val DPC_IMAGE_IMPORT_HANDLES = 0xD621
    const val DPC_REMOTE_PHOTO_VIEW_EX = 0xDF28
    const val DPC_CAMERA_STATE = 0xDF00
    const val DPC_CLIENT_STATE = 0xDF01
    const val DPC_IMAGE_GET_VERSION = 0xDF21
    const val DPC_GET_OBJECT_VERSION = 0xDF22
    const val DPC_REMOTE_VERSION = 0xDF24
    const val DPC_REMOTE_GET_OBJECT_VERSION = 0xDF25

    // Camera states (DF00)
    const val STATE_WAIT_FOR_ACCESS = 0
    const val STATE_MULTIPLE_TRANSFER = 1
    const val STATE_FULL_ACCESS = 2
    const val STATE_PC_AUTO_SAVE = 3
    const val STATE_REMOTE_ACCESS = 6

    // Client states (DF01)
    const val CLIENT_VIEW_MULTIPLE = 1
    const val CLIENT_VIEW_ALL_IMGS = 2
    const val CLIENT_OLD_REMOTE = 3
    const val CLIENT_REMOTE_MODE = 5
    const val CLIENT_REMOTE_IMG_VIEW = 11
    const val CLIENT_REMOTE_IMG_VIEW_XAPP = 20

    fun hex(v: Int) = "0x" + Integer.toHexString(v).uppercase()
}

class PtpException(message: String, val code: Int = 0) : Exception(message)

/**
 * What the camera sends over Wi-Fi. Resized copies are made by the camera itself, like
 * XApp's "Resize" setting, and only for JPEG; HEIF and RAF always come as shot.
 */
enum class TransferSize(val resizeRate: Int?) {
    ORIGINAL(null),
    SMALL(1),
    EXTRA_SMALL(0),
}
