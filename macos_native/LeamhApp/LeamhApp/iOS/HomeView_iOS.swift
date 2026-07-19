import SwiftUI
import UniformTypeIdentifiers

/// iOS root, adaptive across iPad (regular width) and iPhone (compact width).
///
/// - Regular (iPad): NavigationSplitView with the 3-tab sidebar panel
///   (Annotations / Bookmarks / Find) and the reader in the detail column.
/// - Compact (iPhone): a reader-first NavigationStack. The panel is reached via a
///   toolbar button that presents it as a sheet (Find lives on the reader toolbar,
///   so the sheet never covers the system find bar).
///
/// File open via `.fileImporter`; the annotation edit sheet, ink editor, Ask-AI panel
/// and export share-sheet are hosted at this root so they don't collide with the
/// split-view / inspector behaviour in either width.
struct HomeView: View {
    @EnvironmentObject var store: DocumentStore
    @Environment(\.horizontalSizeClass) private var hSize

    @State private var showImporter           = false
    @State private var showAskAi              = false
    @State private var exportItems: [Any]     = []
    @State private var showExport             = false
    @State private var showPanel              = false   // iPhone: panel-as-sheet
    // Lifted from ReaderScreen so the sidebar's Find tab can trigger the reader's find bar,
    // and the Bookmarks tab can scroll the reader to a tapped annotation.
    @State private var findTrigger            = 0
    @State private var scrollToAnnotationId: String? = nil
    @State private var scrollToCharOffsetValue: Int? = nil
    @State private var goToPageValue: Int? = nil
    // Page state reported by the reader for the sidebar shuttle.
    @State private var readerCurrentPage = 0
    @State private var readerPageCount   = 1
    // System find is per-text-view, so it can't search across the discrete pages of a paged mode.
    // Tapping Find in a paged mode transiently drops the reader to scroll (full-document system
    // find); picking a nav mode from the menu clears the override and returns to pages.
    @AppStorage("com.afluffywaffle.layuv.navMode") private var navModeRaw = NavMode.scroll.rawValue
    @State private var searchScrollOverride   = false

    // AI menu (mirrors Android's AI submenu)
    @State private var showAiSettings         = false
    @State private var showImportRewrite      = false
    @State private var showExportFolderPicker = false
    @State private var showImportFolderPicker = false

    private var docxType: UTType { UTType(filenameExtension: "docx") ?? .data }
    /// Open accepts DOCX plus plain text (.txt/.md/écri) — text is converted to a working .docx.
    private var openTypes: [UTType] {
        [UTType(filenameExtension: "docx"), .plainText, .text,
         UTType(filenameExtension: "md"), UTType(filenameExtension: "markdown")].compactMap { $0 }
    }

    private func triggerFind() {
        if NavMode(rawValue: navModeRaw) != .scroll { searchScrollOverride = true }
        findTrigger += 1
    }

    /// Export for AI: write directly into the chosen folder if set, else fall back to the share sheet.
    private func exportAi() {
        Task {
            if let folder = store.aiExportFolder {
                _ = await store.exportForAi(toFolder: folder)
            } else {
                let (text, images) = await store.buildAiExportItems()
                var urls: [Any] = []
                if let md = writeTmp(text.data(using: .utf8) ?? Data(), filename: "leamh_export.md") {
                    urls.append(md)
                }
                for (name, data) in images {
                    if let u = writeTmp(data, filename: name) { urls.append(u) }
                }
                exportItems = urls
                showExport  = true
            }
        }
    }

    /// Export Annotations Only: write only the anchor list (no chapter text) into the chosen folder
    /// if set, else fall back to the share sheet. Mirrors macOS `exportAnnotationsOnly`.
    private func exportAnnotationsOnly() {
        Task {
            if let folder = store.aiExportFolder {
                _ = await store.exportAnnotationsOnly(toFolder: folder)
            } else {
                let (text, images) = await store.buildAnnotationsOnlyExport()
                var urls: [Any] = []
                if let md = writeTmp(text.data(using: .utf8) ?? Data(), filename: "leamh_annotations.md") {
                    urls.append(md)
                }
                for (name, data) in images {
                    if let u = writeTmp(data, filename: name) { urls.append(u) }
                }
                exportItems = urls
                showExport  = true
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

    private func writeTmp(_ data: Data, filename: String) -> URL? {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(filename)
        return (try? data.write(to: url)) != nil ? url : nil
    }

    var body: some View {
        Group {
            if hSize == .compact {
                compactRoot
            } else {
                regularRoot
            }
        }
        .fileImporter(isPresented: $showImporter,
                      allowedContentTypes: openTypes,
                      allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let url = urls.first {
                Task { await store.openAny(url: url) }
            }
        }
        // Annotation edit sheet + ink cover hosted at the root so they don't conflict
        // with multitasking / compact-width split-view / inspector behaviour.
        .sheet(item: $store.editingAnnotation) { annotation in
            AnnotationEditSheet(annotation: annotation)
                .environmentObject(store)
                .preferredColorScheme(.light)
        }
        .fullScreenCover(item: $store.inkEditingAnnotation) { annotation in
            InkEditorView(annotation: annotation)
                .environmentObject(store)
                .preferredColorScheme(.light)
        }
        // AI panels
        .sheet(isPresented: $showAskAi) {
            AskAiView()
                .environmentObject(store)
                .preferredColorScheme(.light)
        }
        .sheet(isPresented: $showExport) {
            ShareSheet(items: exportItems)
        }
        .sheet(isPresented: $showAiSettings) {
            AiSettingsView()
                .environmentObject(store)
                .preferredColorScheme(.light)
        }
        // Consolidated app + per-document settings (mirrors macOS). Presented at the root so it's
        // reachable with or without a document and always sees this window's store.
        .sheet(isPresented: $store.showSettings) {
            AppSettingsView()
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

    // MARK: - Regular width (iPad)

    private var regularRoot: some View {
        NavigationSplitView {
            SidebarPanelView(
                onFind:               { triggerFind() },
                onScrollTo:           { ann in scrollToAnnotationId = ann.id },
                onOpenFile:           { showImporter = true },
                onScrollToCharOffset: { scrollToCharOffsetValue = $0 },
                onGoToPage:           { goToPageValue = $0 },
                currentPage:          $readerCurrentPage,
                pageCount:            .constant(readerPageCount),
                paged:                .constant(NavMode(rawValue: navModeRaw)?.isPaged ?? false)
            )
        } detail: {
            readerDetail(onShowPanel: nil)
        }
    }

    // MARK: - Compact width (iPhone)

    private var compactRoot: some View {
        NavigationStack {
            readerDetail(onShowPanel: store.document != nil ? { showPanel = true } : nil)
        }
        .sheet(isPresented: $showPanel) {
            NavigationStack {
                SidebarPanelView(
                    onFind:               { triggerFind() },
                    onScrollTo:           { ann in showPanel = false; scrollToAnnotationId = ann.id },
                    onOpenFile:           { showPanel = false; showImporter = true },
                    onScrollToCharOffset: { scrollToCharOffsetValue = $0; showPanel = false },
                    onGoToPage:           { goToPageValue = $0; showPanel = false },
                    currentPage:          $readerCurrentPage,
                    pageCount:            .constant(readerPageCount),
                    paged:                .constant(NavMode(rawValue: navModeRaw)?.isPaged ?? false),
                    showFindTab:          false
                )
                .toolbar {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Done") { showPanel = false }
                    }
                }
            }
            .presentationDetents([.large, .medium])
            .preferredColorScheme(.light)
        }
    }

    // MARK: - Shared reader column

    @ViewBuilder
    private func readerDetail(onShowPanel: (() -> Void)?) -> some View {
        if store.isLoading {
            ProgressView("Loading…")
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(AppTheme.warmPaper)
        } else if let doc = store.document {
            ReaderScreen(document: doc,
                         findTrigger: findTrigger,
                         scrollToAnnotationId: scrollToAnnotationId,
                         scrollToCharOffsetValue: scrollToCharOffsetValue,
                         goToPageValue: goToPageValue,
                         onShowPanel: onShowPanel,
                         onFind:   { triggerFind() },
                         onAskAi:  { showAskAi = true },
                         onAiSettings: { showAiSettings = true },
                         onExportAi: { exportAi() },
                         onExportAnnotationsOnly: { exportAnnotationsOnly() },
                         onImportRewrite: { importRewrite() },
                         onOpenSettings: { store.showSettings = true },
                         onSetExportFolder: { showExportFolderPicker = true },
                         onSetImportFolder: { showImportFolderPicker = true },
                         searchScrollOverride: searchScrollOverride,
                         onClearSearch: { searchScrollOverride = false },
                         onPageChanged: { pg, cnt in readerCurrentPage = pg; readerPageCount = cnt },
                         onJumpToOffset: { scrollToCharOffsetValue = $0 },
                         onGoToPage: { goToPageValue = $0 })
        } else {
            emptyState
        }
    }

    private var emptyState: some View {
        VStack(spacing: 24) {
            ContentUnavailableView {
                Label("No Document Open", systemImage: "doc.text")
            } description: {
                Text("Open a DOCX file to begin reading.")
            } actions: {
                Button("Open…") { showImporter = true }
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

// MARK: - Reader column

private struct ReaderScreen: View {
    @EnvironmentObject var store: DocumentStore
    let document: LoadedDocument
    let findTrigger: Int
    let scrollToAnnotationId: String?
    let scrollToCharOffsetValue: Int?
    let goToPageValue: Int?
    /// Non-nil only in compact width (iPhone) — shows a toolbar button to present the panel sheet.
    let onShowPanel: (() -> Void)?
    let onFind: () -> Void
    let onAskAi: () -> Void
    let onAiSettings: () -> Void
    let onExportAi: () -> Void
    let onExportAnnotationsOnly: () -> Void
    let onImportRewrite: () -> Void
    let onOpenSettings: () -> Void
    let onSetExportFolder: () -> Void
    let onSetImportFolder: () -> Void
    /// While searching in a paged mode the reader is forced to scroll (global system find);
    /// `onClearSearch` returns to the saved nav mode.
    let searchScrollOverride: Bool
    let onClearSearch: () -> Void
    let onPageChanged: ((Int, Int) -> Void)?
    let onJumpToOffset: (Int) -> Void
    let onGoToPage: (Int) -> Void

    @State private var showResumeBanner = false
    @State private var resumeGen        = 0
    // Suppress the resume banner while the system find bar is up so it can't cover it
    // (state-driven, so it holds in any orientation — fixes Find being hidden in landscape).
    @State private var isFindActive     = false
    // Transient page scrubber, revealed by tapping the toolbar page counter (paged modes).
    @State private var showPageScrubber = false

    // Live page-scrub via drag on the toolbar page counter (paged modes only).
    @State private var isScrubbingPageLabel = false
    @State private var scrubStartPage       = 0
    // Local mirror of the reader's page state, for the toolbar counter + scrub.
    @State private var localPage        = 0
    @State private var localPageCount   = 1

    @Environment(\.colorScheme) private var colorScheme
    @AppStorage("com.afluffywaffle.layuv.navMode") private var navModeRaw = NavMode.scroll.rawValue
    private var navMode: NavMode { NavMode(rawValue: navModeRaw) ?? .scroll }
    private var effectiveNavMode: NavMode { searchScrollOverride ? .scroll : navMode }
    /// Reader theme honouring the Follow-System-Dark-Mode preference (mirrors macOS).
    private var effectiveTheme: PaperTheme { store.effectiveTheme(systemDark: colorScheme == .dark) }

    private var isCompact: Bool { onShowPanel != nil }
    private var docTitle: String {
        store.currentURL?.deletingPathExtension().lastPathComponent ?? "Layuv"
    }

    var body: some View {
        ReaderTextView(document: document,
                       annotations: store.annotations,
                       documentURL: store.currentURL,
                       bodyPointSize: store.bodyTextSize.points,
                       twoColumnPaged: store.twoColumnPaged,
                       navMode: effectiveNavMode,
                       paperTheme: effectiveTheme,
                       findTrigger: findTrigger,
                       scrollToAnnotationId: scrollToAnnotationId,
                       scrollToCharOffsetValue: scrollToCharOffsetValue,
                       goToPageValue: goToPageValue,
                       onPageChanged: { pg, cnt in
                           localPage = pg; localPageCount = cnt
                           onPageChanged?(pg, cnt)
                       },
                       markerFraction: store.readingMarkerFraction)
            .overlay(alignment: .top) { resumeBanner }
            .overlay(alignment: .bottom) { pageScrubber }
            .onChange(of: store.currentURL) { maybeShowResumeBanner() }
            .onAppear { maybeShowResumeBanner() }
            .ignoresSafeArea(.container, edges: .bottom)
            .navigationTitle(docTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // iPhone: panel (Annotations / Bookmarks) button.
                if let onShowPanel {
                    ToolbarItem(placement: .topBarLeading) {
                        Button { onShowPanel() } label: {
                            Image(systemName: "list.bullet")
                        }
                        .accessibilityLabel("Annotations panel")
                    }
                }

                // Locked-tool chip — shown only while a tool is locked. Tap to unlock.
                // Mirrors macOS / Android's locked-tool slot.
                if let locked = store.lockedTool {
                    ToolbarItem(placement: .topBarLeading) {
                        Button { store.lockedTool = nil } label: {
                            Label("\(locked.chipLabel) locked", systemImage: "lock.fill")
                                .font(.caption.weight(.semibold))
                        }
                        .accessibilityLabel("\(locked.chipLabel) tool locked — tap to unlock")
                    }
                }

                // Page counter with drag-to-scrub (paged modes only; scroll mode has the scrollbar).
                if effectiveNavMode.isPaged && localPageCount > 1 {
                    ToolbarItem(placement: .principal) {
                        Text("\(localPage + 1) / \(localPageCount)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                            .contentShape(Rectangle())
                            // Tap reveals a transient scrubber over the reader; drag still fine-scrubs.
                            .onTapGesture {
                                withAnimation(.easeOut(duration: 0.2)) { showPageScrubber.toggle() }
                            }
                            .gesture(
                                DragGesture(minimumDistance: 4)
                                    .onChanged { value in
                                        if !isScrubbingPageLabel {
                                            isScrubbingPageLabel = true
                                            scrubStartPage = localPage
                                        }
                                        let pointsPerPage: CGFloat = 22
                                        let delta = Int((value.translation.width / pointsPerPage).rounded())
                                        let target = min(max(scrubStartPage + delta, 0), localPageCount - 1)
                                        if target != localPage { onGoToPage(target) }
                                    }
                                    .onEnded { _ in isScrubbingPageLabel = false }
                            )
                    }
                }

                // Find in text (quick shortcut; on iPad the sidebar Find tab also fires this).
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        // Clear the resume banner before presenting Find so it can't sit over
                        // the system find bar, and keep it suppressed while Find is active.
                        isFindActive = true
                        dismissResumeBanner()
                        onFind()
                    } label: {
                        Image(systemName: "magnifyingglass")
                    }
                    .accessibilityLabel("Find")
                }

                if isCompact {
                    // iPhone: fold the secondary actions into one overflow menu to fit the nav bar.
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Menu("Font") { fontMenuItems }
                            Menu("Text Size") { sizeMenuItems }
                            Menu("Line Spacing") { lineSpacingMenuItems }
                            Menu("Paper Theme") { themeMenuItems }
                            Menu("Navigation Mode") {
                                ForEach(NavMode.allCases, id: \.rawValue) { mode in
                                    Button {
                                        navModeRaw = mode.rawValue
                                        onClearSearch()
                                    } label: {
                                        if navModeRaw == mode.rawValue {
                                            Label(mode.label, systemImage: "checkmark")
                                        } else {
                                            Label(mode.label, systemImage: mode.icon)
                                        }
                                    }
                                }
                            }
                            Divider()
                            Menu("AI") { aiMenuItems }
                            Divider()
                            Button { onOpenSettings() } label: {
                                Label("Settings…", systemImage: "gearshape")
                            }
                        } label: {
                            Image(systemName: "ellipsis.circle")
                        }
                        .accessibilityLabel("More")
                    }
                } else {
                    // iPad: font, nav mode, Ask AI, Export as individual toolbar items.
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Menu("Font") { fontMenuItems }
                            Menu("Text Size") { sizeMenuItems }
                            Menu("Line Spacing") { lineSpacingMenuItems }
                            Menu("Paper Theme") { themeMenuItems }
                            Divider()
                            Button {
                                store.twoColumnPaged.toggle()
                            } label: {
                                if store.twoColumnPaged {
                                    Label("Two Columns", systemImage: "checkmark")
                                } else {
                                    Text("Two Columns")
                                }
                            }
                        } label: {
                            Image(systemName: "textformat")
                        }
                        .accessibilityLabel("Typography")
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            ForEach(NavMode.allCases, id: \.rawValue) { mode in
                                Button {
                                    navModeRaw = mode.rawValue
                                } label: {
                                    if navModeRaw == mode.rawValue {
                                        Label(mode.label, systemImage: "checkmark")
                                    } else {
                                        Label(mode.label, systemImage: mode.icon)
                                    }
                                }
                            }
                        } label: {
                            Image(systemName: navMode.icon)
                        }
                        .accessibilityLabel("Navigation mode: \(navMode.label)")
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            aiMenuItems
                        } label: {
                            Image(systemName: "bubble.left.and.text.bubble.right")
                        }
                        .accessibilityLabel("AI")
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button { onOpenSettings() } label: {
                            Image(systemName: "gearshape")
                        }
                        .accessibilityLabel("Settings")
                    }
                }
            }
    }

    @ViewBuilder private var fontMenuItems: some View {
        ForEach(FontChoice.allCases, id: \.rawValue) { choice in
            Button {
                store.fontChoice = choice
            } label: {
                if store.fontChoice == choice {
                    Label(choice.label, systemImage: "checkmark")
                } else {
                    Text(choice.label)
                }
            }
        }
    }

    @ViewBuilder private var sizeMenuItems: some View {
        ForEach(BodyTextSize.allCases, id: \.rawValue) { size in
            Button {
                store.bodyTextSize = size
            } label: {
                if store.bodyTextSize == size {
                    Label(size.label, systemImage: "checkmark")
                } else {
                    Text(size.label)
                }
            }
        }
    }

    @ViewBuilder private var lineSpacingMenuItems: some View {
        ForEach(LineSpacing.allCases, id: \.rawValue) { spacing in
            Button {
                store.lineSpacing = spacing
            } label: {
                if store.lineSpacing == spacing {
                    Label(spacing.label, systemImage: "checkmark")
                } else {
                    Text(spacing.label)
                }
            }
        }
    }

    @ViewBuilder private var themeMenuItems: some View {
        ForEach(PaperTheme.allCases, id: \.rawValue) { theme in
            Button {
                store.paperTheme = theme
            } label: {
                if store.paperTheme == theme {
                    Label(theme.label, systemImage: "checkmark")
                } else {
                    Text(theme.label)
                }
            }
        }
    }

    // MARK: - AI menu (mirrors Android's AI submenu)

    @ViewBuilder private var aiMenuItems: some View {
        Button { onAskAi() } label: {
            Label("AI Chat", systemImage: "bubble.left.and.text.bubble.right")
        }
        .disabled(!AiProviderSettings.shared.isConfigured)
        Button { onAiSettings() } label: {
            Label("AI Settings…", systemImage: "gearshape")
        }
        Divider()
        Button { onExportAi() } label: {
            Label("Export for AI…", systemImage: "square.and.arrow.up")
        }
        Button { onExportAnnotationsOnly() } label: {
            Label("Export Annotations Only…", systemImage: "list.bullet.rectangle")
        }
        Button { onImportRewrite() } label: {
            Label("Import rewrite…", systemImage: "square.and.arrow.down")
        }
        Divider()
        Button { onSetExportFolder() } label: {
            Label(folderLabel("Set AI export folder…", store.aiExportFolder), systemImage: "folder")
        }
        Button { onSetImportFolder() } label: {
            Label(folderLabel("Set import folder…", store.aiImportFolder), systemImage: "folder")
        }
    }

    private func folderLabel(_ base: String, _ url: URL?) -> String {
        guard let url else { return base }
        let parent = url.deletingLastPathComponent().lastPathComponent
        let name   = url.lastPathComponent
        return parent.isEmpty ? "\(base)  (\(name))" : "\(base)  (\(parent)/\(name))"
    }

    // MARK: - Resume banner

    @ViewBuilder private var resumeBanner: some View {
        if showResumeBanner && !isFindActive {
            HStack(spacing: 10) {
                Image(systemName: "bookmark.fill")
                    .foregroundStyle(.secondary)
                Text("Pick up where you left off?")
                    .font(.callout.weight(.medium))
                Button("Jump") {
                    jumpToReadingMarker()
                    dismissResumeBanner()
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.small)
                Button {
                    dismissResumeBanner()
                } label: {
                    Image(systemName: "xmark")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.regularMaterial, in: Capsule())
            .overlay(Capsule().strokeBorder(.quaternary))
            .shadow(color: .black.opacity(0.12), radius: 10, y: 3)
            .padding(.top, 8)
            .transition(.move(edge: .top).combined(with: .opacity))
        }
    }

    // MARK: - Transient page scrubber (tap the toolbar page counter to reveal)

    @ViewBuilder private var pageScrubber: some View {
        if showPageScrubber && effectiveNavMode.isPaged && localPageCount > 1 {
            PageShuttleView(
                currentPage: $localPage,
                pageCount: localPageCount,
                onGoToPage: { onGoToPage($0) }
            )
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(.quaternary))
            .shadow(color: .black.opacity(0.12), radius: 10, y: 3)
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
            .transition(.move(edge: .bottom).combined(with: .opacity))
        }
    }

    private func maybeShowResumeBanner() {
        // A newly-opened document starts a fresh reading session — Find is no longer active.
        isFindActive = false
        guard let f = store.readingMarkerFraction, f > 0.02 else {
            if showResumeBanner { withAnimation { showResumeBanner = false } }
            return
        }
        resumeGen += 1
        let gen = resumeGen
        withAnimation(.easeOut(duration: 0.3)) { showResumeBanner = true }
        DispatchQueue.main.asyncAfter(deadline: .now() + 6) {
            if gen == resumeGen { withAnimation { showResumeBanner = false } }
        }
    }

    private func dismissResumeBanner() {
        resumeGen += 1
        withAnimation { showResumeBanner = false }
    }

    private func jumpToReadingMarker() {
        guard let f = store.readingMarkerFraction else { return }
        let len = (document.plainText as NSString).length
        guard len > 0 else { return }
        let offset = min(len - 1, max(0, Int((f * Double(len)).rounded())))
        onJumpToOffset(offset)
    }
}

// MARK: - Locked-tool chip label

extension AnnotationTool {
    /// Short human label for the reader's locked-tool toolbar chip (mirrors macOS).
    var chipLabel: String {
        switch self {
        case .highlight:       return "Highlight"
        case .underline:       return "Underline"
        case .doubleUnderline: return "Double Underline"
        case .strikethrough:   return "Strikethrough"
        case .wavyUnderline:   return "Wavy Underline"
        case .bookmark:        return "Bookmark"
        case .inkAnnotation:   return "Ink"
        case .comment:         return "Comment"
        case .blockquote:      return "Paragraph"
        }
    }
}
