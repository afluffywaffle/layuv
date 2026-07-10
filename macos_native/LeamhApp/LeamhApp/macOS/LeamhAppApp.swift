import SwiftUI

final class LeamhAppDelegate: NSObject, NSApplicationDelegate {
    /// The SwiftUI open-window action, captured from a live window's environment. Finder/`open`
    /// file requests are routed through this so each file lands in its OWN window (multi-window
    /// model) instead of mutating a single shared document store.
    var openWindow: OpenWindowAction? {
        didSet { flushPendingURLs() }
    }
    private var pendingURLs: [URL] = []

    func application(_ application: NSApplication, open urls: [URL]) {
        pendingURLs.append(contentsOf: urls)
        flushPendingURLs()
    }

    func application(_ sender: NSApplication, openFiles filenames: [String]) {
        pendingURLs.append(contentsOf: filenames.map { URL(fileURLWithPath: $0) })
        flushPendingURLs()
        sender.reply(toOpenOrPrint: .success)
    }

    private func flushPendingURLs() {
        guard let openWindow, !pendingURLs.isEmpty else { return }
        let urls = pendingURLs
        pendingURLs.removeAll()
        // WindowGroup(for: URL.self) dedupes by value, so opening a file already shown just
        // focuses its existing window instead of spawning a duplicate.
        for url in urls { openWindow(value: url) }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }
}

@main
struct LeamhAppApp: App {
    @NSApplicationDelegateAdaptor(LeamhAppDelegate.self) private var appDelegate

    var body: some Scene {
        // Value-based window group: each window owns its own DocumentStore, keyed by the file
        // URL it was opened with. Opening another file spawns a NEW window and never disturbs
        // the document already shown in existing windows.
        WindowGroup(for: URL.self) { $url in
            HomeWindow(url: url, appDelegate: appDelegate)
        }
        .windowResizability(.contentSize)
        .commands {
            CommandGroup(replacing: .newItem) { }
            DocumentCommands()
        }
    }
}

/// One window's worth of state: a private DocumentStore that loads the window's URL. The store is
/// exposed to the menu bar via `focusedSceneObject` so File/Format commands act on the focused
/// window, and the open-window action is handed to the app delegate for Finder file opens.
private struct HomeWindow: View {
    let url: URL?
    /// The REAL delegate adaptor instance. `NSApp.delegate` is SwiftUI's own forwarding
    /// AppDelegate (not our type), so we must inject the adaptor directly rather than casting it.
    let appDelegate: LeamhAppDelegate
    @StateObject private var store = DocumentStore()
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        HomeView()
            .environmentObject(store)
            .focusedSceneObject(store)
            // Layuv is a warm-paper reading surface (light by design): pin to light UNLESS the
            // user opts into following the OS dark mode (then the reader uses the Night paper).
            .preferredColorScheme(store.followsDarkMode ? nil : .light)
            .onAppear {
                appDelegate.openWindow = openWindow
            }
            .task(id: url) {
                if let url, store.currentURL != url {
                    await store.openAny(url: url)
                }
            }
    }
}

/// Menu-bar commands routed to the focused window's DocumentStore. `Open…` and recents open a NEW
/// window rather than replacing the focused document.
private struct DocumentCommands: Commands {
    @FocusedObject private var store: DocumentStore?
    @Environment(\.openWindow) private var openWindow

    var body: some Commands {
        // Replace the default "Settings…" (⌘,) with our consolidated in-window sheet, so it can
        // read/write the focused window's document for the per-document section.
        CommandGroup(replacing: .appSettings) {
            Button("Settings…") { store?.showSettings = true }
                .keyboardShortcut(",", modifiers: .command)
                .disabled(store == nil)
        }
        CommandGroup(after: .newItem) {
            Button("Open…") {
                if let url = DocumentStore.runOpenPanel() { openWindow(value: url) }
            }
            .keyboardShortcut("o")
        }
        CommandGroup(after: .saveItem) {
            Button("Save") { if let store { Task { await store.save() } } }
                .keyboardShortcut("s")
                .disabled(store == nil)
        }
        CommandMenu("Format") {
            if let store {
                Picker("Font", selection: Binding(get: { store.fontChoice }, set: { store.fontChoice = $0 })) {
                    ForEach(FontChoice.allCases, id: \.rawValue) { Text($0.label).tag($0) }
                }
                .pickerStyle(.inline)
                Divider()
                Picker("Text Size", selection: Binding(get: { store.bodyTextSize }, set: { store.bodyTextSize = $0 })) {
                    ForEach(BodyTextSize.allCases, id: \.rawValue) { Text($0.label).tag($0) }
                }
                .pickerStyle(.inline)
                Divider()
                Picker("Line Spacing", selection: Binding(get: { store.lineSpacing }, set: { store.lineSpacing = $0 })) {
                    ForEach(LineSpacing.allCases, id: \.rawValue) { Text($0.label).tag($0) }
                }
                .pickerStyle(.inline)
                Divider()
                Picker("Paper Theme", selection: Binding(get: { store.paperTheme }, set: { store.paperTheme = $0 })) {
                    ForEach(PaperTheme.allCases, id: \.rawValue) { Text($0.label).tag($0) }
                }
                .pickerStyle(.inline)
                Divider()
                Toggle("Follow System Dark Mode (Night)", isOn: Binding(get: { store.followsDarkMode }, set: { store.followsDarkMode = $0 }))
                Toggle("Left-Handed Navigation (WASD)", isOn: Binding(get: { store.leftHandedNav }, set: { store.leftHandedNav = $0 }))
            }
        }
    }
}
