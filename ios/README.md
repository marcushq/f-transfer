# Fuji Transfer for iOS

> **Tiếng Việt:** bản iOS của Fuji Transfer, viết bằng Swift/SwiftUI, chuyển cùng giao thức với bản Android. Cần một máy Mac có Xcode để build; làm theo các bước dưới đây. Mã nguồn **chưa được build hay chạy thử** — người build vui lòng gửi lại lỗi biên dịch (nếu có) và nhật ký kết nối (trong app: Xem nhật ký kết nối › Chia sẻ).

A Swift/SwiftUI port of the Android app in this repository: it moves photos from a Fujifilm X
camera (tested protocol: X-S20) to the iPhone over the camera's Wi-Fi, using Bluetooth LE to
wake the Wi-Fi on newer bodies.

**Status: first port, not yet compiled.** It was written on a Windows machine without Xcode,
translated line by line from the Android code that works with an X-S20. Expect a few compile
errors on the first build; please report them (or fix and open a pull request).

## Build

Requirements: a Mac with **Xcode 15 or newer**, an iPhone on **iOS 16+**, and an Apple ID.

```sh
brew install xcodegen      # once
cd ios
xcodegen                   # generates FujiTransfer.xcodeproj from project.yml
open FujiTransfer.xcodeproj
```

In Xcode:
1. Select the **FujiTransfer** target › **Signing & Capabilities** › pick your **Team**. If the
   bundle id `dev.fujitransfer.ios` is taken, change it to something unique.
2. **Paid Apple Developer account only:** click **+ Capability** › **Hotspot Configuration**.
   This lets the app join the camera's Wi-Fi by itself. A free account can't sign it; the app
   then asks the user to join the camera's network in Settings › Wi-Fi (everything else works).
3. Plug in the iPhone, choose it as the run destination, press **Run** (⌘R).
   With a free account, trust the developer on the phone: Settings › General › VPN & Device
   Management. Free-account installs expire after 7 days.

Bluetooth, local network and Photos permissions are requested on first use (texts in `Info.plist`).

## Use
- **Máy đời mới** (X-S20, X-T5, X-H2, X100VI…): first time, on the camera: MENU › NETWORK/USB
  SETTING › Bluetooth/SMARTPHONE SETTING › **PAIRING REGISTRATION**. Then tap **Kết nối** and
  confirm pairing on both devices. The app asks the camera to start Wi-Fi and joins it.
- **Đã vào Wi-Fi** (older bodies, or without Hotspot Configuration): join the camera's
  `FUJIFILM-…` network in Settings › Wi-Fi, come back and tap **Kết nối**.
- Tap photos to select, then **Tải N ảnh về máy**. Originals are saved to the Photos app.

## Layout
| File | What it does | Android counterpart |
|---|---|---|
| `Protocol/PtpLink.swift` | PTP containers over a blocking BSD socket | `protocol/PtpLink.kt` |
| `Protocol/FujiCamera.swift` | Handshake, XApp gallery mode, listing, thumbnails, downloads with the X-S20 workarounds (wrong announced sizes, GetObjectInfo before reads, FF D9 check) | `protocol/FujiCamera.kt` |
| `Connection/FujiBluetooth.swift` | CoreBluetooth: pairing, Wi-Fi wake (`03 00`), SSID/password | `FujiBluetooth.kt` |
| `Connection/CameraWifi.swift` | Joins the camera AP (NEHotspotConfiguration) | `CameraWifi.kt` |
| `Session/Session.swift` | Connect flow, thumbnail loading, downloads | `MainViewModel.kt` |
| `Session/PhotoSaver.swift` | Saves to Photos; trims letterbox bars off thumbnails | `PhotoStore.kt`, `Thumbs.kt` |
| `UI/` | SwiftUI screens, same look as Android (orange accent, Inter Tight / JetBrains Mono) | `ui/` |

## Not in this first version
- **Background transfer:** iOS suspends an app about 30 s after it leaves the screen, and offers
  no background mode for a direct camera socket. The app keeps the screen on while
  downloading and asks iOS for its short background grace period. A Live Activity (Dynamic
  Island) could show progress while the app runs; it is not built yet.
- Thumbnail rotation for portrait shots, the metadata/thumbnail cache, day grouping and filters,
  camera-side resizing (S/XS), the "Đã lưu" tab, HEIF→JPEG conversion (iOS reads HEIF natively).

## Credits
Protocol after the MIT-licensed [libfuji](https://github.com/petabyt/libfuji), with details from
gkoh/furble and Th0rgal/fuji-bridge. Fonts: Inter Tight and JetBrains Mono (SIL OFL, see
`../licenses/`). The X-S20 product photo comes from fujifilm-x.com and is © FUJIFILM.
Not affiliated with FUJIFILM.
