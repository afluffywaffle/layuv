import SwiftUI
import PencilKit

/// Apple Pencil ink editor (iPad). A PKCanvasView + the system PKToolPicker (pen/marker/eraser,
/// colours, ruler; Pencil pressure/tilt, hover preview, and double-tap tool switch are handled by
/// PencilKit natively). Loads any existing drawing for re-edit; Done rasterises + persists via the
/// store; Cancel/empty discards a brand-new annotation.
///
/// Ink whose re-editable strokes blob is absent or from another platform (e.g. Android's vector
/// JSON) is shown VIEW-ONLY over its flattened PNG so it can't be silently overwritten or deleted.
struct InkEditorView: View {
    let annotation: Annotation
    @EnvironmentObject var store: DocumentStore
    @Environment(\.dismiss) private var dismiss

    @StateObject private var holder = InkCanvasHolder()
    @State private var initialDrawing = PKDrawing()
    @State private var backdropPNG: Data?
    @State private var couldLoad = false
    @State private var loaded = false

    /// True when this is existing ink we couldn't load for editing — display only, never overwrite.
    private var viewOnly: Bool { annotation.hasInk && !couldLoad }

    var body: some View {
        NavigationStack {
            Group {
                if loaded {
                    InkCanvasRepresentable(holder: holder,
                                           initialDrawing: initialDrawing,
                                           backdropPNG: backdropPNG,
                                           readOnly: viewOnly)
                        .ignoresSafeArea(edges: .bottom)
                } else {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .navigationTitle("Ink note")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        store.cancelInk(annotation)
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(viewOnly ? "Close" : "Done") { finish() }.disabled(!loaded)
                }
            }
        }
        .task {
            if !loaded {
                let json = await store.loadInkStrokesJSON(annotation.id)
                if let drawing = InkRasterizer.drawing(fromJSON: json) {
                    initialDrawing = drawing
                    couldLoad = true
                } else if annotation.hasInk {
                    backdropPNG = await store.loadInkPng(annotation.id)   // view-only legacy/foreign ink
                }
                loaded = true
            }
        }
    }

    private func finish() {
        if viewOnly { dismiss(); return }   // never overwrite ink we couldn't load for editing
        let drawing = holder.controller?.canvas.drawing ?? PKDrawing()
        if drawing.strokes.isEmpty {
            if annotation.hasInk {
                store.deleteAnnotation(id: annotation.id)   // erased all loaded strokes → clear the note
            } else {
                store.cancelInk(annotation)                 // brand-new, nothing drawn → discard
            }
        } else if let png = InkRasterizer.png(from: drawing) {
            let strokes = InkRasterizer.strokesJSON(from: drawing)
            Task { await store.finishInk(annotationId: annotation.id, png: png, strokesJSON: strokes) }
        } else {
            store.cancelInk(annotation)
        }
        dismiss()
    }
}

/// Bridges the PKCanvasView out of the representable so the SwiftUI Done button can read the drawing.
final class InkCanvasHolder: ObservableObject {
    weak var controller: InkCanvasViewController?
}

struct InkCanvasRepresentable: UIViewControllerRepresentable {
    let holder: InkCanvasHolder
    let initialDrawing: PKDrawing
    let backdropPNG: Data?
    let readOnly: Bool

    func makeUIViewController(context: Context) -> InkCanvasViewController {
        let vc = InkCanvasViewController(initialDrawing: initialDrawing,
                                        backdropPNG: backdropPNG,
                                        readOnly: readOnly)
        holder.controller = vc
        return vc
    }

    func updateUIViewController(_ vc: InkCanvasViewController, context: Context) {}
}

final class InkCanvasViewController: UIViewController {
    let canvas = PKCanvasView()
    private var toolPicker: PKToolPicker?
    private let initialDrawing: PKDrawing
    private let backdropPNG: Data?
    private let readOnly: Bool

    init(initialDrawing: PKDrawing, backdropPNG: Data?, readOnly: Bool) {
        self.initialDrawing = initialDrawing
        self.backdropPNG = backdropPNG
        self.readOnly = readOnly
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = AppTheme.warmPaperUI

        // View-only legacy/foreign ink: show its flattened PNG behind a non-interactive canvas.
        if let backdropPNG, let image = UIImage(data: backdropPNG) {
            let imageView = UIImageView(image: image)
            imageView.contentMode = .scaleAspectFit
            imageView.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(imageView)
            NSLayoutConstraint.activate([
                imageView.topAnchor.constraint(equalTo: view.topAnchor, constant: 16),
                imageView.bottomAnchor.constraint(equalTo: view.bottomAnchor, constant: -16),
                imageView.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
                imageView.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            ])
        }

        canvas.drawing = initialDrawing
        canvas.drawingPolicy = .anyInput            // draw with finger or Apple Pencil
        canvas.backgroundColor = backdropPNG == nil ? AppTheme.warmPaperUI : .clear
        canvas.isOpaque = backdropPNG == nil
        canvas.isUserInteractionEnabled = !readOnly
        canvas.alwaysBounceVertical = false
        canvas.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(canvas)
        NSLayoutConstraint.activate([
            canvas.topAnchor.constraint(equalTo: view.topAnchor),
            canvas.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            canvas.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            canvas.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !readOnly else { return }             // no editing tools for view-only ink
        let picker = PKToolPicker()
        picker.addObserver(canvas)
        picker.setVisible(true, forFirstResponder: canvas)
        canvas.becomeFirstResponder()
        toolPicker = picker
    }
}
