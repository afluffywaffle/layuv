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
            CommandMenu("Format") {
                Picker("Font", selection: $store.fontChoice) {
                    ForEach(FontChoice.allCases, id: \.rawValue) { choice in
                        Text(choice.label).tag(choice)
                    }
                }
                .pickerStyle(.inline)
                Divider()
                Picker("Text Size", selection: $store.bodyTextSize) {
                    ForEach(BodyTextSize.allCases, id: \.rawValue) { size in
                        Text(size.label).tag(size)
                    }
                }
                .pickerStyle(.inline)
            }
        }
    }
}
