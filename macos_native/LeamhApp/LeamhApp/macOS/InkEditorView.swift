import SwiftUI
import AppKit

/// Ink editor (macOS). macOS has no PencilKit drawing canvas (PKCanvasView is iOS-only), so this is
/// a custom NSView that captures mouse / trackpad / external-tablet strokes — useful when a user
/// drives the Mac from a drawing tablet (or a Supernote acting as one).
///
/// Strokes are saved in a macOS-native `macink` JSON blob so the Mac can re-edit its own ink, plus a
/// transparent PNG read by Word/Pages/iPad/Android (the same cross-platform degradation the iPad ink
/// uses: PNG always displays; the strokes blob is only re-editable on the platform that wrote it).
struct InkEditorView: View {
    let annotation: Annotation
    @EnvironmentObject var store: DocumentStore
    @Environment(\.dismiss) private var dismiss

    @StateObject private var holder = InkCanvasHolder()
    @State private var initialStrokes: [InkStroke] = []
    @State private var backdropPNG: Data?
    @State private var couldLoad = false
    @State private var loaded = false
    @State private var penWidth: CGFloat = 2.5
    @State private var eraser = false

    /// True when this is existing ink we couldn't load for editing — display only, never overwrite.
    private var viewOnly: Bool { annotation.hasInk && !couldLoad }

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider()
            Group {
                if loaded {
                    InkCanvasRepresentable(holder: holder,
                                           initialStrokes: initialStrokes,
                                           backdropPNG: backdropPNG,
                                           readOnly: viewOnly,
                                           penWidth: penWidth,
                                           eraser: eraser)
                } else {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .background(store.paperTheme.paper)
            if !viewOnly { Divider(); toolBar }
        }
        .frame(minWidth: 640, idealWidth: 760, minHeight: 420, idealHeight: 520)
        .task {
            if !loaded {
                let json = await store.loadInkStrokesJSON(annotation.id)
                if let strokes = MacInkRasterizer.strokes(fromJSON: json) {
                    initialStrokes = strokes
                    couldLoad = true
                } else if annotation.hasInk {
                    backdropPNG = await store.loadInkPng(annotation.id)   // view-only legacy/foreign ink
                }
                loaded = true
            }
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Button("Cancel") { store.cancelInk(annotation); dismiss() }
                .keyboardShortcut(.cancelAction)
            Spacer()
            Text("Ink note").font(.headline)
            Spacer()
            // Delete the whole ink annotation (the only other removal path is erasing every stroke).
            if annotation.hasInk {
                Button(role: .destructive) {
                    store.deleteAnnotation(id: annotation.id)
                    dismiss()
                } label: {
                    Image(systemName: "trash")
                }
                .help("Delete ink note")
            }
            Button(viewOnly ? "Close" : "Done") { finish() }
                .keyboardShortcut(.defaultAction)
                .disabled(!loaded)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }

    private var toolBar: some View {
        HStack(spacing: 16) {
            Picker("", selection: $eraser) {
                Label("Pen", systemImage: "pencil.tip").tag(false)
                Label("Eraser", systemImage: "eraser").tag(true)
            }
            .pickerStyle(.segmented)
            .fixedSize()

            if !eraser {
                HStack(spacing: 8) {
                    Image(systemName: "lineweight").foregroundStyle(.secondary)
                    Slider(value: $penWidth, in: 1...8).frame(width: 140)
                }
            }
            Spacer()
            Button(role: .destructive) {
                holder.controller?.clear()
            } label: {
                Label("Clear", systemImage: "trash")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private func finish() {
        if viewOnly { dismiss(); return }
        let strokes = holder.controller?.strokes ?? []
        if strokes.isEmpty {
            if annotation.hasInk {
                store.deleteAnnotation(id: annotation.id)   // erased everything → clear the note
            } else {
                store.cancelInk(annotation)                 // brand-new, nothing drawn → discard
            }
        } else if let png = MacInkRasterizer.png(from: strokes) {
            let json = MacInkRasterizer.strokesJSON(from: strokes)
            Task { await store.finishInk(annotationId: annotation.id, png: png, strokesJSON: json) }
        } else {
            store.cancelInk(annotation)
        }
        dismiss()
    }
}

/// Bridges the canvas out of the representable so the SwiftUI Done button can read the strokes.
final class InkCanvasHolder: ObservableObject {
    weak var controller: InkCanvasView?
}

struct InkCanvasRepresentable: NSViewRepresentable {
    let holder: InkCanvasHolder
    let initialStrokes: [InkStroke]
    let backdropPNG: Data?
    let readOnly: Bool
    let penWidth: CGFloat
    let eraser: Bool

    func makeNSView(context: Context) -> InkCanvasView {
        let v = InkCanvasView()
        v.strokes = initialStrokes
        v.backdrop = backdropPNG.flatMap(NSImage.init(data:))
        v.readOnly = readOnly
        v.penWidth = penWidth
        v.eraser = eraser
        holder.controller = v
        return v
    }

    func updateNSView(_ v: InkCanvasView, context: Context) {
        v.penWidth = penWidth
        v.eraser = eraser
    }
}

// MARK: - Stroke model + canvas

/// One sampled point with the effective stroke width at that point (base pen width modulated by
/// tablet pressure, so Apple-Pencil-over-Sidecar / Wacom / Supernote input varies line weight).
struct InkPoint: Equatable {
    var x: CGFloat
    var y: CGFloat
    var w: CGFloat
    var cg: CGPoint { CGPoint(x: x, y: y) }
}

struct InkStroke: Equatable {
    var points: [InkPoint]
}

/// Flat NSView that captures freehand strokes (mouse / trackpad / tablet) and renders them with
/// pressure-varying width. Eraser mode removes whole strokes the cursor passes near. Coordinates are
/// view-local; the rasteriser normalises them when exporting.
final class InkCanvasView: NSView {
    var strokes: [InkStroke] = [] { didSet { needsDisplay = true } }
    var backdrop: NSImage?
    var readOnly = false
    var penWidth: CGFloat = 2.5
    var eraser = false

    private var current: InkStroke?

    override var isFlipped: Bool { true }
    override var acceptsFirstResponder: Bool { true }

    override func draw(_ dirtyRect: NSRect) {
        AppTheme.currentTheme.nsPaper.setFill()
        dirtyRect.fill()
        backdrop?.draw(in: bounds.insetBy(dx: 16, dy: 16),
                       from: .zero, operation: .sourceOver, fraction: 1,
                       respectFlipped: true, hints: nil)

        AppTheme.currentTheme.nsInk.setStroke()
        for stroke in strokes { Self.draw(stroke) }
        if let current { Self.draw(current) }
    }

    /// Variable-width render: each segment is its own path at the mean of its endpoints' widths.
    static func draw(_ stroke: InkStroke) {
        let pts = stroke.points
        guard pts.count > 1 else {
            if let p = pts.first {
                let dot = NSBezierPath(ovalIn: NSRect(x: p.x - p.w/2, y: p.y - p.w/2, width: p.w, height: p.w))
                dot.fill()
            }
            return
        }
        for i in 1..<pts.count {
            let a = pts[i - 1], b = pts[i]
            let seg = NSBezierPath()
            seg.lineWidth = max(0.5, (a.w + b.w) / 2)
            seg.lineCapStyle = .round
            seg.lineJoinStyle = .round
            seg.move(to: a.cg)
            seg.line(to: b.cg)
            seg.stroke()
        }
    }

    func clear() { strokes = []; current = nil }

    /// Effective width for an event: tablet points scale the pen width by pressure (~0.35×–1.6×);
    /// mouse/trackpad (no real pressure) use the pen width flat.
    private func width(for event: NSEvent) -> CGFloat {
        let isTablet = event.subtype == .tabletPoint
        guard isTablet else { return penWidth }
        let p = max(0, min(1, CGFloat(event.pressure)))
        return penWidth * (0.35 + 1.25 * p)
    }

    private func point(_ event: NSEvent) -> InkPoint {
        let pt = convert(event.locationInWindow, from: nil)
        return InkPoint(x: pt.x, y: pt.y, w: width(for: event))
    }

    override func mouseDown(with event: NSEvent) {
        guard !readOnly else { return }
        if eraser { eraseNear(convert(event.locationInWindow, from: nil)); return }
        current = InkStroke(points: [point(event)])
    }

    override func mouseDragged(with event: NSEvent) {
        guard !readOnly else { return }
        if eraser { eraseNear(convert(event.locationInWindow, from: nil)); return }
        current?.points.append(point(event))
        needsDisplay = true
    }

    override func mouseUp(with event: NSEvent) {
        guard !readOnly, !eraser, let stroke = current else { return }
        if stroke.points.count > 1 { strokes.append(stroke) }
        current = nil
        needsDisplay = true
    }

    private func eraseNear(_ pt: CGPoint) {
        let hitRadius: CGFloat = 12
        let before = strokes.count
        strokes.removeAll { stroke in
            stroke.points.contains { hypot($0.x - pt.x, $0.y - pt.y) < hitRadius + $0.w }
        }
        if strokes.count != before { needsDisplay = true }
    }
}
