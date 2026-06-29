import SwiftUI
import AppKit

/// Ask-AI conversation sheet (macOS). Mirrors the iOS AskAiView: a scrolling conversation,
/// streaming assistant reply, a "Save as Draft" rewrite card, and a reply input bar. Uses the
/// shared AskAiViewModel; the only platform differences are the chrome (custom header instead of
/// a navigation bar) and "Save as Draft" routing through an NSSavePanel rather than a share sheet.
struct AskAiView: View {
    @EnvironmentObject private var store: DocumentStore
    @Environment(\.dismiss)  private var dismiss
    @StateObject private var vm = AskAiViewModel()

    @State private var inputText      = ""
    @State private var showSettings   = false
    @State private var showClearAlert = false

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            conversationArea
            Divider()
            inputBar
        }
        .frame(minWidth: 520, idealWidth: 620, minHeight: 460, idealHeight: 640)
        .background(AppTheme.panelBackground)
        .sheet(isPresented: $showSettings) {
            AiSettingsView().environmentObject(store)
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
        .onChange(of: vm.draftURL) { _, url in
            if let url { presentSavePanel(for: url) }
        }
        .task {
            await vm.loadHistory(from: store)
            if vm.turns.isEmpty { await startIfConfigured() }
        }
    }

    // MARK: - Header

    private var header: some View {
        HStack {
            Text("Ask AI")
                .font(.headline)
            Spacer()
            Menu {
                Button {
                    showSettings = true
                } label: {
                    Label("AI Settings…", systemImage: "gearshape")
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
            .menuStyle(.borderlessButton)
            .fixedSize()
            Button("Done") { dismiss() }
                .keyboardShortcut(.cancelAction)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
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
        let displayText = isSeed ? "📖 Manuscript sent" : turn.text

        return HStack {
            Spacer(minLength: 60)
            Text(displayText)
                .textSelection(.enabled)
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
                    .textSelection(.enabled)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                    .background(AppTheme.controlFieldBackground)
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
                .scaleEffect(0.6)
                .padding(.top, 4)
            Text(vm.partialText.isEmpty ? " " : vm.partialText)
                .textSelection(.enabled)
                .frame(maxWidth: 520, alignment: .leading)
        }
        .padding(.horizontal)
    }

    private var rewriteCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("Rewrite ready", systemImage: "doc.text.fill")
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.teal)
            Text("The revised chapter is ready. Save it as a new DOCX draft to review.")
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
                    Label("Configure AI Endpoint", systemImage: "gearshape")
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
                .textFieldStyle(.plain)
                .lineLimit(1...6)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(AppTheme.controlFieldBackground)
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .disabled(vm.isStreaming)
                .onSubmit { send() }

            if vm.isStreaming {
                Button {
                    vm.cancelStream()
                } label: {
                    Image(systemName: "stop.circle.fill")
                        .font(.system(size: 26))
                        .foregroundStyle(.red)
                }
                .buttonStyle(.plain)
            } else {
                Button {
                    send()
                } label: {
                    Image(systemName: "arrow.up.circle.fill")
                        .font(.system(size: 26))
                        .foregroundStyle(inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                                         ? Color.secondary : Color.accentColor)
                }
                .buttonStyle(.plain)
                .disabled(inputText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    // MARK: - Helpers

    private func send() {
        let text = inputText
        inputText = ""
        Task { await vm.sendMessage(text, store: store) }
    }

    private func startIfConfigured() async {
        guard AiProviderSettings.shared.isConfigured, store.document != nil else { return }
        await vm.sendSeed(store: store)
    }

    /// Save the freshly built draft DOCX to a user-chosen location, then clear the trigger.
    private func presentSavePanel(for tempURL: URL) {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = tempURL.lastPathComponent
        panel.allowedContentTypes = [.init(filenameExtension: "docx")].compactMap { $0 }
        if panel.runModal() == .OK, let dest = panel.url {
            try? FileManager.default.removeItem(at: dest)
            try? FileManager.default.copyItem(at: tempURL, to: dest)
        }
        vm.draftURL = nil
    }
}
