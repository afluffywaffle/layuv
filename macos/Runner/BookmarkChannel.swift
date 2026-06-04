import Cocoa
import FlutterMacOS

class BookmarkChannel {
  static let channelName = "com.afluffywaffle.layuv/bookmarks"

  static func register(with registrar: FlutterPluginRegistrar) {
    let channel = FlutterMethodChannel(
      name: channelName,
      binaryMessenger: registrar.messenger
    )
    channel.setMethodCallHandler { call, result in
      switch call.method {
      case "saveBookmark":
        guard let path = call.arguments as? String else {
          result(FlutterError(code: "INVALID_ARG", message: "path required", details: nil))
          return
        }
        Self.saveBookmark(path: path, result: result)
      case "resolveBookmark":
        guard let path = call.arguments as? String else {
          result(FlutterError(code: "INVALID_ARG", message: "path required", details: nil))
          return
        }
        Self.resolveBookmark(path: path, result: result)
      default:
        result(FlutterMethodNotImplemented)
      }
    }
  }

  private static func saveBookmark(path: String, result: @escaping FlutterResult) {
    let url = URL(fileURLWithPath: path)
    do {
      _ = url.startAccessingSecurityScopedResource()
      let bookmarkData = try url.bookmarkData(
        options: .withSecurityScope,
        includingResourceValuesForKeys: nil,
        relativeTo: nil
      )
      url.stopAccessingSecurityScopedResource()
      let key = "bookmark_\(path.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? path)"
      UserDefaults.standard.set(bookmarkData, forKey: key)
      result(nil)
    } catch {
      result(FlutterError(code: "BOOKMARK_FAILED", message: error.localizedDescription, details: nil))
    }
  }

  private static func resolveBookmark(path: String, result: @escaping FlutterResult) {
    let key = "bookmark_\(path.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? path)"
    guard let bookmarkData = UserDefaults.standard.data(forKey: key) else {
      result(nil)
      return
    }
    do {
      var isStale = false
      let url = try URL(
        resolvingBookmarkData: bookmarkData,
        options: .withSecurityScope,
        relativeTo: nil,
        bookmarkDataIsStale: &isStale
      )
      if isStale {
        let fresh = try url.bookmarkData(
          options: .withSecurityScope,
          includingResourceValuesForKeys: nil,
          relativeTo: nil
        )
        UserDefaults.standard.set(fresh, forKey: key)
      }
      _ = url.startAccessingSecurityScopedResource()
      result(url.path)
    } catch {
      result(FlutterError(code: "RESOLVE_FAILED", message: error.localizedDescription, details: nil))
    }
  }
}
