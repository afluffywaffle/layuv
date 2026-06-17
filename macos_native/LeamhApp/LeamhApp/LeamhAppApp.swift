import SwiftUI

@main
struct LeamhAppApp: App {
    @StateObject private var store = DocumentStore()

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environmentObject(store)
        }
        .windowResizability(.contentSize)
        .commands {
            CommandGroup(replacing: .newItem) { }
            CommandGroup(after: .newItem) {
                Button("Open…") { store.openFilePanel() }
                    .keyboardShortcut("o")
            }
            CommandGroup(after: .saveItem) {
                Button("Save") { Task { await store.save() } }
                    .keyboardShortcut("s")
            }
        }
    }
}
