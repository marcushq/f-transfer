import SwiftUI

@main
struct FujiTransferApp: App {
    @StateObject private var session = Session()
    @StateObject private var library = SavedLibrary()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        TransferProgress.register()
    }

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(session).environmentObject(library)
                #if DEBUG
                .task {
                    let args = ProcessInfo.processInfo.arguments
                    if args.contains("-demoGallery") { session.demoGallery() }
                    if args.contains("-demoTransfer") { session.demoTransfer() }
                    if args.contains("-demoSaved") { await library.demoRecordAll() }
                }
                #endif
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { session.saveCache() }
        }
    }
}

enum Tab { case camera, saved }

/// Two tabs: the camera (connect screen until a camera answers, then its photos) and the
/// photos already saved on the phone.
struct RootView: View {
    @EnvironmentObject var session: Session
    @State private var showLog = false
    @State private var tab: Tab = ProcessInfo.processInfo.arguments.contains("-openSaved") ? .saved : .camera

    var body: some View {
        TabView(selection: $tab) {
            Group {
                if session.phase == .connected {
                    GalleryView(showLog: $showLog)
                } else {
                    ConnectView(showLog: $showLog)
                }
            }
            .tabItem {
                Label(session.phase == .connected ? session.cameraName : "Máy ảnh", systemImage: "camera")
            }
            .tag(Tab.camera)

            SavedView()
                .tabItem { Label("Đã lưu", systemImage: "photo.on.rectangle") }
                .tag(Tab.saved)
        }
        .tint(Film.accentText)
        .sheet(isPresented: $showLog) { LogView().environmentObject(session) }
    }
}
