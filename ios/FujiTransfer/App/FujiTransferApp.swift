import SwiftUI

@main
struct FujiTransferApp: App {
    @StateObject private var session = Session()

    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(session)
        }
    }
}

/// Connect screen until a camera answers, then its photos.
struct RootView: View {
    @EnvironmentObject var session: Session
    @State private var showLog = false

    var body: some View {
        Group {
            if session.phase == .connected {
                GalleryView(showLog: $showLog)
            } else {
                ConnectView(showLog: $showLog)
            }
        }
        .sheet(isPresented: $showLog) { LogView().environmentObject(session) }
    }
}
