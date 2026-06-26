import SwiftUI
import UIKit

// MARK: - View model

@MainActor
private final class AskAiViewModel: ObservableObject {
    @Published var turns: [AiTurn] = []
    @Published var partialText  = ""
    @Published var isStreaming  = false
    @Published var errorMessage: String?
    @Published var pendingRewrite: String?
    @Published var lastTruncated = false
    @Published var draftURL: URL?

    private var activeTask: Task<Void, Never>?

    // MARK: - Public actions

    func loadHistory(from store: DocumentStore) async {
        turns = await store.loadAiChat()
        refreshRewriteState()
    }

    /// Sends the manuscript seed prompt (chapter + annotations) as the first user turn.
    func sendSeed(store: DocumentStore) async {
        guard let doc = store.document else { return }
        let annotations = store.annotations.map(\.annotation)
        let seed = ManuscriptSerializer.buildPrompt(plainText: doc.plainText,
                                                     annotations: annotations)
        var images: [Data] = []
        for id in seed.inkAnnotationIds {
            if let png = await store.loadInkPng(id) { images.append(png) }
        }
        turns.append(AiTurn(role: AiTurn.roleUser, text: seed.text))
        await runStream(store: store, images: images)
    }

    func sendMessage(_ text: String, store: DocumentStore) async {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        turns.append(AiTurn(role: AiTurn.roleUser, text: trimmed))
        await runStream(store: store, images: [])
    }

    func continueTruncated(store: DocumentStore) async {
        turns.append(AiTurn(role: AiTurn.roleUser,
                            text: "Please continue from where you left off."))
        await runStream(store: store, images: [])
    }

    func saveDraft(store: DocumentStore) async {
        guard let rewrite = pendingRewrite else { return }
        do {
            draftURL = try await store.saveAiDraft(rewrite: rewrite)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func clearConversation(store: DocumentStore) async {
        turns = []
        pendingRewrite = nil
        lastTruncated = false
        await store.saveAiChat([])
    }

    func cancelStream() {
        activeTask?.cancel()
    }

    // MARK: - Streaming

    private func runStream(store: DocumentStore, images: [Data]) async {
        isStreaming   = true
        partialText   = ""
        pendingRewrite = nil
        lastTruncated = false
        errorMessage  = nil

        let task: Task<Void, Never> = Task { @MainActor in
            do {
                let sseStream = try OpenAiCompatibleProvider.stream(turns: self.turns,
                                                                     images: images)
                var full      = ""
                var truncated = false
                for try await token in sseStream {
                    if token == OpenAiCompatibleProvider.truncatedMarker {
                        truncated = true
                        break
                    }
                    full += token
                    self.partialText = full
                }
                self.turns.append(AiTurn(role: AiTurn.roleAssistant,
                                         text: full, truncated: truncated))
                self.partialText  = ""
                self.lastTruncated = truncated
                self.refreshRewriteState()
                await store.saveAiChat(self.turns)
            } catch is CancellationError {
                // User cancelled — discard partial text without an error banner.
                self.partialText = ""
            } catch {
                self.errorMessage = error.localizedDescription
            }
            self.isStreaming = false
        }
        activeTask = task
        await task.value
    }

    private func refreshRewriteState() {
        pendingRewrite = turns.last.flatMap { t -> String? in
            guard t.role == AiTurn.roleAssistant else { return nil }
            return RewriteProtocol.parse(t.text).rewrite
        }
    }
}

// MARK: - Root view

struct AskAiView: View {
    @EnvironmentObject private var store: DocumentStore
    @Environment(\.dismiss)  private var dismiss
    @StateObject private var vm = AskAiViewModel()

    @State private var inputText      = ""
    @State private var showSettings   = false
    @State private var showClearAlert = false
    @State private var scrollProxy: ScrollViewProxy?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                conversationArea
                Divider()
                inputBar
            }
            .navigationTitle("Ask AI")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Done") { dismiss() }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button {
                            showSettings = true
                        } label: {
                            Label("AI Settings", systemImage: "gear")
                        }
                        if !vm.turns.isEmpty {
                            Button(role: .destructive) {
                                showClearAlert = true
                            } label: {
                                Label("Clear Conversation", systemImage: "trash")
                            }
                        }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                }
            }
            .sheet(isPresented: $showSettings) {
                AiSettingsView()
            }
            .sheet(item: $vm.draftURL) { url in
                ShareSheet(items: [url])
            }
            .alert("Error", isPresented: Binding(
                get: { vm.errorMessage != nil },
                set: { if !$0 { vm.errorMessage = nil } }
            )) {
                Button("OK") { vm.errorMessage = nil }
            } message: {
                Text(vm.errorMessage ?? "")
            }
            .confirmationDialog("Clear the entire conversation?",
                                isPresented: $showClearAlert,
                                titleVisibility: .visible) {
                Button("Clear", role: .destructive) {
                    Task { await vm.clearConversation(store: store) }
                }
                Button("Cancel", role: .cancel) {}
            }
        }
        .task {
            await vm.loadHistory(from: store)
            if vm.turns.isEmpty { await startIfConfigured() }
        }
    }

    // MARK: - Conversation area

    private var conversationArea: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 12) {
                    ForEach(Array(vm.turns.enumerated()), id: \.offset) { i, turn in
                        turnView(turn: turn, index: i)
                    }
                    if !vm.partialText.isEmpty {
                        streamingBubble
                    }
                    if vm.turns.isEmpty && !vm.isStreaming {
                        emptyState
                    }
                    Color.clear.frame(height: 1).id("bottom")
                }
                .padding(.vertical, 12)
            }
            .onChange(of: vm.partialText) { _, _ in
                withAnimation { proxy.scrollTo("bottom") }
            }
            .onChange(of: vm.turns.count) { _, _ in
                withAnimation { proxy.scrollTo("bottom") }
            }
        }
    }

    @ViewBuilder
    private func turnView(turn: AiTurn, index: Int) -> some View {
        if turn.role == AiTurn.roleUser {
            userBubble(turn: turn, index: index)
        } else {
            assistantBubble(turn: turn, isLast: index == vm.turns.count - 1)
        }
    }

    private func userBubble(turn: AiTurn, index: Int) -> some View {
        let isSeed = index == 0 && turn.text.contains("=== CHAPTER ===")
        let displayText = isSeed
            ? "📖 Manuscript sent"
            : turn.text

        return HStack {
            Spacer(minLength: 60)
            Text(displayText)
                .font(.body)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color.accentColor.opacity(0.15))
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .frame(maxWidth: 480, alignment: .trailing)
        }
        .padding(.horizontal)
    }

    private func assistantBubble(turn: AiTurn, isLast: Bool) -> some View {
        let parsed = RewriteProtocol.parse(turn.text)
        return VStack(alignment: .leading, spacing: 8) {
            if !parsed.conversation.isEmpty {
                Text(parsed.conversation)
                    .font(.body)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(Color(.systemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 16))
                    .frame(maxWidth: 520, alignment: .leading)
            }
            if parsed.rewrite != nil && isLast {
                rewriteCard
            }
            if turn.truncated && isLast && !vm.isStreaming {
                continueButton
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal)
    }

    private var streamingBubble: some View {
        HStack(alignment: .top, spacing: 8) {
            ProgressView()
                .scaleEffect(0.75)
                .padding(.top, 4)
            Text(vm.partialText.isEmpty ? " " : vm.partialText)
                .font(.body)
                .frame(maxWidth: 520, alignment: .leading)
        }
        .padding(.horizontal)
    }

    private var rewriteCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Rewrite ready", systemImage: "doc.text.fill")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.teal)
            Text("The revised chapter is ready. Save it as a new DOCX draft to review in the Files app.")
                .font(.caption)
                .foregroundStyle(.secondary)
            Button {
                Task { await vm.saveDraft(store: store) }
            } label: {
                Label("Save as Draft…", systemImage: "arrow.down.doc")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(.teal)
        }
        .padding(14)
        .background(Color.teal.opacity(0.08))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .frame(maxWidth: 480)
    }

    private var continueButton: some View {
        Button {
            Task { await vm.continueTruncated(store: store) }
        } label: {
            Label("Continue…", systemImage: "arrow.forward")
        }
        .buttonStyle(.bordered)
        .tint(.secondary)
    }

    private var emptyState: some View {
        VStack(spacing: 16) {
            Image(systemName: "bubble.left.and.sparkles")
                .font(.system(size: 48))
                .foregroundStyle(.tertiary)
            Text("Ask AI to revise this chapter")
                .font(.headline)
            Text("Your chapter text and annotations will be sent to the configured AI endpoint.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            if !AiProviderSettings.shared.isConfigured {
                Button {
                    showSettings = true
                } label: {
                    Label("Configure AI Endpoint", systemImage: "gear")
                }
                .buttonStyle(.bordered)
            }
        }
        .padding(.top, 60)
        .frame(maxWidth: .infinity)
    }

    // MARK: - Input bar

    private var inputBar: some View {
        HStack(alignment: .bottom, spacing: 10) {
            TextField("Reply…", text: $inputText, axis: .vertical)
                .lineLimit(1...6)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 20))
                .disabled(vm.isStreaming)

            if vm.isStreaming {
                Button {
                    vm.cancelStream()
                } label: {
                    Image(systemName: "stop.circle.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(.red)
                }
            } else {
                Button {
                    let text = inputText
                    inputText = ""
                    Task { await vm.sendMessage(text, store: store) }
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                         ? Color.secondary : Color.accentColor)
                }
                .disabled(inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 10)
        .background(Color(.systemBackground))
    }

    // MARK: - Helpers

    private func startIfConfigured() async {
        guard AiProviderSettings.shared.isConfigured, store.document != nil else { return }
        await vm.sendSeed(store: store)
    }
}

// MARK: - Share sheet

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

// MARK: - URL: Identifiable for .sheet(item:)

extension URL: @retroactive Identifiable {
    public var id: String { absoluteString }
}
