import Cocoa
import FlutterMacOS

class MainFlutterWindow: NSWindow {
  override func awakeFromNib() {
    let flutterViewController = FlutterViewController()
    let windowFrame = self.frame
    self.contentViewController = flutterViewController
    self.setFrame(windowFrame, display: true)

    RegisterGeneratedPlugins(registry: flutterViewController)

    // Register security-scoped bookmark channel
    BookmarkChannel.register(with: flutterViewController.registrar(forPlugin: "BookmarkChannel"))

    super.awakeFromNib()
  }
}
