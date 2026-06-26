import SwiftUI

@main
struct LeamhApp_iOS_App: App {
    @StateObject private var store = DocumentStore()

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environmentObject(store)
        }
    }
}
