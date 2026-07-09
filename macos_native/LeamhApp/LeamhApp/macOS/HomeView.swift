import SwiftUI
import AppKit
import UniformTypeIdentifiers

/// Hides the window-toolbar background so the reader shows through and the buttons float as Liquid
/// Glass (macOS 15+). No-op on macOS 14 (the deployment floor) — the toolbar keeps its solid bar.
private struct HiddenToolbarBackground: ViewModifier {
    func body(content: Content) -> some View {
        if #available(macOS 15.0, *) {
            content.toolbarBackgroundVisibility(.hidden, for: .windowToolbar)
        } else {
            content
        }
    }
}

/// macOS root, mirroring the iPad layout: a NavigationSplitView whose sidebar is the
/// 3-tab panel (Annotations / Bookmarks / Find) and whose detail is the reader. Recents
/// live in the detail empty state (the sidebar is the panel now, as on iPad). The reader
/// coordinator is hoisted here so the sidebar's Find/Bookmarks tabs can drive the reader.
struct HomeView: View {
    @EnvironmentObject var store: DocumentStore
    @StateObject private var readerCoordinator = ReaderCoordinator()

    var body: some View {
        NavigationSplitView {
            SidebarPanelView(
                onFind:               { readerCoordinator.find() },
                onScrollTo:           { readerCoordinator.scrollTo(annotationId: $0.id) },
                onOpenFile:           { store.openFilePanel() },
                onScrollToCharOffset: { readerCoordinator.scrollToCharOffset($0) },
                onGoToPage:           { readerCoordinator.goToPage($0) },
                currentPage: Binding(
                    get: { readerCoordinator.currentPage },
                    set: { readerCoordinator.goToPage($0) }
                ),
                pageCount: Binding(
                    get: { readerCoordinator.pageCount },
                    set: { _ in }
                ),
                paged: Binding(
                    get: { readerCoordinator.paged },
                    set: { _ in }
                )
            )
            .navigationSplitViewColumnWidth(min: 240, ideal: 300)
        } detail: {
            if store.isLoading {
                ProgressView("Loading…")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(AppTheme.warmPaper)
            } else if store.document != nil {
                ReaderScreen(coordinator: readerCoordinator)
            } else {
                emptyState
            }
        }
    }

    private var emptyState: some View {
        VStack(spacing: 24) {
            ContentUnavailableView {
                Label("No Document Open", systemImage: "doc.text")
            } description: {
                Text("Open a DOCX file to begin reading.")
            } actions: {
                Button("Open…") { store.openFilePanel() }
                    .buttonStyle(.borderedProminent)
            }

            if !store.recentURLs.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Recent")
                        .font(AppTheme.chromeBold())
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 4)
                    ForEach(store.recentURLs.prefix(6), id: \.self) { url in
                        Button {
                            Task { await store.load(url: url) }
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "doc.text")
                                    .foregroundStyle(.secondary)
                                Text(url.deletingPathExtension().lastPathComponent)
                                    .font(AppTheme.body(size: 15))
                                    .lineLimit(1)
                                Spacer()
                            }
                            .contentShape(Rectangle())
                            .padding(.vertical, 6)
                            .padding(.horizontal, 8)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .frame(maxWidth: 360)
                .padding(.horizontal)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(AppTheme.warmPaper)
    }
}

// MARK: - Reader layout

struct ReaderScreen: View {
    @EnvironmentObject var store: DocumentStore
    @ObservedObject var coordinator: ReaderCoordinator
    @Environment(\.colorScheme) private var colorScheme

    @AppStorage("com.afluffywaffle.layuv.navMode") private var navModeRaw = NavMode.scroll.rawValue
    private var navMode: NavMode { NavMode(rawValue: navModeRaw) ?? .scroll }
    private var effectiveTheme: PaperTheme { store.effectiveTheme(systemDark: colorScheme == .dark) }

    @State private var showAskAi              = false
    @State private var showAiSettings         = false
    @State private var showImportRewrite      = false
    @State private var showExportFolderPicker = false
    @State private var showImportFolderPicker = false

    private var docxType: UTType { UTType(filenameExtension: "docx") ?? .data }

    var body: some View {
        ReaderView(coordinator: coordinator, navMode: navMode, twoColumn: store.twoColumnPaged,
                   theme: effectiveTheme, font: store.fontChoice, leftHanded: store.leftHandedNav)
            // Top fade: content dissolves up into the toolbar so text never butts a hard edge under
            // the floating glass buttons — the reader's reading zone stays cleanly below the tools.
            .overlay(alignment: .top) {
                let paper = effectiveTheme.paper
                LinearGradient(
                    stops: [
                        .init(color: paper,             location: 0.00),
                        .init(color: paper,             location: 0.30),
                        .init(color: paper.opacity(0.92), location: 0.45),
                        .init(color: paper.opacity(0.70), location: 0.60),
                        .init(color: paper.opacity(0.42), location: 0.74),
                        .init(color: paper.opacity(0.18), location: 0.86),
                        .init(color: paper.opacity(0.05), location: 0.94),
                        .init(color: paper.opacity(0.0),  location: 1.00),
                    ],
                    startPoint: .top, endPoint: .bottom
                )
                .frame(height: 150)
                .allowsHitTesting(false)
                .ignoresSafeArea(.container, edges: .top)
            }
            .toolbar {
                // Page navigation (screen-flip mode only).
                if coordinator.paged {
                    ToolbarItemGroup(placement: .navigation) {
                        Button { coordinator.previousPage() } label: {
                            Image(systemName: "chevron.left")
                        }
                        .disabled(coordinator.currentPage <= 0)
                        Text("\(coordinator.currentPage + 1) / \(coordinator.pageCount)")
                            .font(AppTheme.chrome())
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                        Button { coordinator.nextPage() } label: {
                            Image(systemName: "chevron.right")
                        }
                        .disabled(coordinator.currentPage >= coordinator.pageCount - 1)
                    }
                }
                // Typography: font, size, two-column (paged mode).
                ToolbarItem(placement: .automatic) {
                    Menu {
                        Section("Font") {
                            ForEach(FontChoice.allCases, id: \.rawValue) { choice in
                                Button {
                                    store.fontChoice = choice
                                } label: {
                                    // Preview each name in its own typeface.
                                    if store.fontChoice == choice {
                                        Label { Text(choice.label).font(choice.previewFont) }
                                        icon: { Image(systemName: "checkmark") }
                                    } else {
                                        Text(choice.label).font(choice.previewFont)
                                    }
                                }
                            }
                        }
                        Picker("Text Size", selection: $store.bodyTextSize) {
                            ForEach(BodyTextSize.allCases, id: \.rawValue) { Text($0.label).tag($0) }
                        }
                        .pickerStyle(.inline)
                        Picker("Line Spacing", selection: $store.lineSpacing) {
                            ForEach(LineSpacing.allCases, id: \.rawValue) { Text($0.label).tag($0) }
                        }
                        .pickerStyle(.inline)
                        Picker("Paper Theme", selection: $store.paperTheme) {
                            ForEach(PaperTheme.allCases, id: \.rawValue) { Text($0.label).tag($0) }
                        }
                        .pickerStyle(.inline)
                        Section {
                            Toggle("Two Columns (Page Flip)", isOn: $store.twoColumnPaged)
                        }
                    } label: {
                        Image(systemName: "textformat")
                    }
                    .help("Typography")
                }
                // Navigation mode.
                ToolbarItem(placement: .automatic) {
                    Menu {
                        Picker("Navigation Mode", selection: $navModeRaw) {
                            ForEach(NavMode.allCases, id: \.rawValue) {
                                Label($0.label, systemImage: $0.icon).tag($0.rawValue)
                            }
                        }
                        .pickerStyle(.inline)
                    } label: {
                        Image(systemName: navMode.icon)
                    }
                    .help("Navigation mode: \(navMode.label)")
                }
                ToolbarItem(placement: .automatic) {
                    Menu {
                        aiMenuItems
                    } label: {
                        Image(systemName: "bubble.left.and.text.bubble.right")
                    }
                    .help("AI")
                }
                ToolbarItem(placement: .automatic) {
                    Button("Save", systemImage: "square.and.arrow.down") {
                        Task { await store.save() }
                    }
                }
                // Overflow ("More") menu — open + reader prefs, mirroring Android's overflow menu.
                ToolbarItem(placement: .automatic) {
                    Menu {
                        Button { store.openFilePanel() } label: {
                            Label("Open…", systemImage: "folder")
                        }
                        Divider()
                        Toggle("Night Mode (Follow System Dark)", isOn: $store.followsDarkMode)
                        Toggle("Left-Handed Navigation", isOn: $store.leftHandedNav)
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .help("More")
                }
            }
            // Fade the toolbar bar so the warm-paper reader shows through; the buttons float as
            // Liquid Glass over the content (macOS 26). The reader's top inset keeps text clear of them.
            .modifier(HiddenToolbarBackground())
            .sheet(item: $store.editingAnnotation) { annotation in
                AnnotationEditSheet(annotation: annotation)
                    .environmentObject(store)
                    .preferredColorScheme(.light)
            }
            .sheet(item: $store.inkEditingAnnotation) { annotation in
                InkEditorView(annotation: annotation)
                    .environmentObject(store)
                    .preferredColorScheme(.light)
            }
            .sheet(isPresented: $showAskAi) {
                AskAiView()
                    .environmentObject(store)
                    .preferredColorScheme(.light)
            }
            .sheet(isPresented: $showAiSettings) {
                AiSettingsView()
                    .environmentObject(store)
                    .preferredColorScheme(.light)
            }
            // Import rewrite — pick the AI-rewritten DOCX, overwrite the open document.
            .fileImporter(isPresented: $showImportRewrite,
                          allowedContentTypes: [docxType],
                          allowsMultipleSelection: false) { result in
                if case .success(let urls) = result, let url = urls.first {
                    Task { try? await store.importRewrite(from: url) }
                }
            }
            // Folder pickers for the AI export / import destinations.
            .fileImporter(isPresented: $showExportFolderPicker,
                          allowedContentTypes: [.folder],
                          allowsMultipleSelection: false) { result in
                if case .success(let urls) = result, let url = urls.first {
                    store.setAiExportFolder(url)
                }
            }
            .fileImporter(isPresented: $showImportFolderPicker,
                          allowedContentTypes: [.folder],
                          allowsMultipleSelection: false) { result in
                if case .success(let urls) = result, let url = urls.first {
                    store.setAiImportFolder(url)
                }
            }
    }

    // MARK: - AI menu (mirrors iOS / Android's AI submenu)

    @ViewBuilder private var aiMenuItems: some View {
        Button { showAskAi = true } label: {
            Label("AI Chat", systemImage: "bubble.left.and.text.bubble.right")
        }
        .disabled(!AiProviderSettings.shared.isConfigured)
        Button { showAiSettings = true } label: {
            Label("AI Settings…", systemImage: "gearshape")
        }
        Divider()
        Button { exportAi() } label: {
            Label("Export for AI…", systemImage: "square.and.arrow.up")
        }
        Button { importRewrite() } label: {
            Label("Import rewrite…", systemImage: "square.and.arrow.down")
        }
        Divider()
        Button { showExportFolderPicker = true } label: {
            Label(folderLabel("Set AI export folder…", store.aiExportFolder), systemImage: "folder")
        }
        Button { showImportFolderPicker = true } label: {
            Label(folderLabel("Set import folder…", store.aiImportFolder), systemImage: "folder")
        }
    }

    /// Export for AI: write directly into the chosen folder if set, else prompt for one with a save panel.
    private func exportAi() {
        Task {
            if let folder = store.aiExportFolder {
                _ = await store.exportForAi(toFolder: folder)
            } else {
                let panel = NSOpenPanel()
                panel.canChooseDirectories = true
                panel.canChooseFiles = false
                panel.prompt = "Export Here"
                panel.message = "Choose a folder to write the AI export (Markdown + ink PNGs)."
                if panel.runModal() == .OK, let folder = panel.url {
                    _ = await store.exportForAi(toFolder: folder)
                }
            }
        }
    }

    /// Import rewrite: auto-find "<doc> Draft.docx" in the import folder, else open a picker.
    private func importRewrite() {
        if let found = store.autoFindRewrite() {
            Task { try? await store.importRewrite(from: found) }
        } else {
            showImportRewrite = true
        }
    }

    private func folderLabel(_ base: String, _ url: URL?) -> String {
        guard let url else { return base }
        let parent = url.deletingLastPathComponent().lastPathComponent
        let name   = url.lastPathComponent
        return parent.isEmpty ? "\(base)  (\(name))" : "\(base)  (\(parent)/\(name))"
    }
}
