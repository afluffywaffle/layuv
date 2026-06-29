import SwiftUI

@main
struct LeamhApp_iOS_App: App {
    @StateObject private var store = DocumentStore()

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environmentObject(store)
                // Layuv is a warm-paper reading surface (light by design); lock to a light
                // appearance so dark mode doesn't wash out ink on paper or clash the chrome.
                .preferredColorScheme(.light)
        }
    }
}
