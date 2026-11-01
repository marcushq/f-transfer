# Fuji Transfer for iOS

> **Tiếng Việt:** bản iOS của Fuji Transfer, viết bằng Swift/SwiftUI, chuyển cùng giao thức với bản Android. Cần một máy Mac có Xcode để build; làm theo các bước dưới đây. Đã build bằng Xcode và chạy trên iPhone với X-S20. Nếu gặp lỗi, hãy gửi nhật ký kết nối (trong app: Xem nhật ký kết nối › Chia sẻ).

A Swift/SwiftUI port of the Android app in this repository: it moves photos from a Fujifilm X
camera (tested protocol: X-S20) to the iPhone over the camera's Wi-Fi, using Bluetooth LE to
wake the Wi-Fi on newer bodies.

**Status:** builds with Xcode and runs on an iPhone with an X-S20. Ported line by line from the
Android code; bug reports with the connection log are welcome.

## Build

Requirements: a Mac with **Xcode 15 or newer**, an iPhone on **iOS 17+** (background downloads need iOS 26+), and an Apple ID.

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
| `Session/MetadataCache.swift` | Names, dates, orientations and thumbnails kept between connections | `MetadataCache.kt` |
| `Session/SavedLibrary.swift`, `UI/SavedView.swift`, `UI/PhotoViewer.swift` | The "Đã lưu" tab: photos the app saved, found again in the Photos library by id | `SavedPhotos.kt`, `ui/SavedScreen.kt`, `ui/PhotoViewer.kt` |
| `Session/PhotoSaver.swift` | Saves to Photos; trims letterbox bars off thumbnails | `PhotoStore.kt`, `Thumbs.kt` |
| `UI/` | SwiftUI screens, same look as Android (orange accent, Inter Tight / JetBrains Mono) | `ui/` |

## Not in this first version
- **Background transfer before iOS 26:** older iOS suspends an app about 30 s after it leaves
  the screen. From iOS 26 a started download keeps going in the background
  (`BGContinuedProcessingTask`) and iOS shows its progress; when that isn't granted, a Live
  Activity (`FujiTransferActivity/`) shows it in the Dynamic Island and on the Lock Screen.
- HEIF→JPEG conversion: iOS opens HEIF itself and converts when sharing.
- The Android-only extras: picture-in-picture / bubble progress, and the light/dark switch
  (the iOS app follows the phone's setting).

## Credits
Protocol after the MIT-licensed [libfuji](https://github.com/petabyt/libfuji), with details from
gkoh/furble and Th0rgal/fuji-bridge. Fonts: Inter Tight and JetBrains Mono (SIL OFL, see
`../licenses/`). The X-S20 product photo comes from fujifilm-x.com and is © FUJIFILM.
Not affiliated with FUJIFILM.
