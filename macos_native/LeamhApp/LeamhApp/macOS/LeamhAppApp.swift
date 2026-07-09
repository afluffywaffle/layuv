import SwiftUI

@main
struct LeamhAppApp: App {
    @StateObject private var store = DocumentStore()

    var body: some Scene {
        WindowGroup {
            HomeView()
                .environmentObject(store)
                // Layuv is a warm-paper reading surface (light by design): pin to light UNLESS the
                // user opts into following the OS dark mode (then the reader uses the Night paper).
                .preferredColorScheme(store.followsDarkMode ? nil : .light)
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
                Divider()
                Picker("Line Spacing", selection: $store.lineSpacing) {
                    ForEach(LineSpacing.allCases, id: \.rawValue) { spacing in
                        Text(spacing.label).tag(spacing)
                    }
                }
                .pickerStyle(.inline)
                Divider()
                Picker("Paper Theme", selection: $store.paperTheme) {
                    ForEach(PaperTheme.allCases, id: \.rawValue) { theme in
                        Text(theme.label).tag(theme)
                    }
                }
                .pickerStyle(.inline)
                Divider()
                Toggle("Follow System Dark Mode (Night)", isOn: $store.followsDarkMode)
                Toggle("Left-Handed Navigation (WASD)", isOn: $store.leftHandedNav)
            }
        }
    }
}
