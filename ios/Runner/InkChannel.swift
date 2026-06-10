import Flutter
import UIKit
import PencilKit

class InkChannel: NSObject {
  static let channelName = "com.afluffywaffle.layuv/ink"

  static func register(with messenger: FlutterBinaryMessenger) {
    let channel = FlutterMethodChannel(name: channelName, binaryMessenger: messenger)
    channel.setMethodCallHandler { call, result in
      guard call.method == "presentInkCanvas" else {
        result(FlutterMethodNotImplemented)
        return
      }
      Self.presentInkCanvas(result: result)
    }
  }

  private static func presentInkCanvas(result: @escaping FlutterResult) {
    guard let rootVC = UIApplication.shared.windows.first?.rootViewController else {
      result(FlutterError(code: "NO_ROOT_VC", message: "No root view controller", details: nil))
      return
    }

    let coordinator = InkCoordinator(result: result)
    let vc = InkViewController(coordinator: coordinator)
    vc.modalPresentationStyle = .fullScreen
    rootVC.present(vc, animated: true)
  }
}

// Holds the result callback so InkViewController can call it on dismiss.
private class InkCoordinator {
  let result: FlutterResult
  init(result: @escaping FlutterResult) { self.result = result }
}

private class InkViewController: UIViewController, PKCanvasViewDelegate {
  private let coordinator: InkCoordinator
  private let canvasView = PKCanvasView()
  private let toolPicker = PKToolPicker()

  init(coordinator: InkCoordinator) {
    self.coordinator = coordinator
    super.init(nibName: nil, bundle: nil)
  }

  required init?(coder: NSCoder) { fatalError() }

  override func viewDidLoad() {
    super.viewDidLoad()
    view.backgroundColor = .white

    // Canvas
    canvasView.backgroundColor = .white
    canvasView.translatesAutoresizingMaskIntoConstraints = false
    canvasView.delegate = self
    canvasView.drawingPolicy = .anyInput
    view.addSubview(canvasView)

    // Done button
    let doneButton = UIButton(type: .system)
    doneButton.setTitle("Done", for: .normal)
    doneButton.titleLabel?.font = UIFont.systemFont(ofSize: 17, weight: .semibold)
    doneButton.addTarget(self, action: #selector(done), for: .touchUpInside)
    doneButton.translatesAutoresizingMaskIntoConstraints = false
    view.addSubview(doneButton)

    NSLayoutConstraint.activate([
      doneButton.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 12),
      doneButton.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -20),
      canvasView.topAnchor.constraint(equalTo: doneButton.bottomAnchor, constant: 8),
      canvasView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
      canvasView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
      canvasView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
    ])
  }

  override func viewDidAppear(_ animated: Bool) {
    super.viewDidAppear(animated)
    toolPicker.setVisible(true, forFirstResponder: canvasView)
    toolPicker.addObserver(canvasView)
    // Default to black ink pen
    toolPicker.selectedTool = PKInkingTool(.pen, color: .black, width: 3)
    canvasView.becomeFirstResponder()
  }

  @objc private func done() {
    let scale = UIScreen.main.scale
    let image = canvasView.drawing.image(from: canvasView.drawing.bounds.isEmpty
      ? CGRect(origin: .zero, size: canvasView.bounds.size)
      : canvasView.drawing.bounds, scale: scale)

    let whiteBackground = UIGraphicsImageRenderer(size: canvasView.bounds.size).image { ctx in
      UIColor.white.setFill()
      ctx.fill(CGRect(origin: .zero, size: canvasView.bounds.size))
      image.draw(in: CGRect(origin: .zero, size: canvasView.bounds.size))
    }

    guard let pngData = whiteBackground.pngData() else {
      dismiss(animated: true) {
        self.coordinator.result(FlutterError(code: "PNG_FAILED", message: "Could not encode PNG", details: nil))
      }
      return
    }
    let bytes = FlutterStandardTypedData(bytes: pngData)
    dismiss(animated: true) {
      self.coordinator.result(bytes)
    }
  }
}
