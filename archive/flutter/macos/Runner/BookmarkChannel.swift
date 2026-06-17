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
        guard let data = call.arguments as? String else {
          result(FlutterError(code: "INVALID_ARG", message: "data required", details: nil))
          return
        }
        Self.resolveBookmark(data: data, result: result)
      case "stopAccessing":
        guard let path = call.arguments as? String else {
          result(FlutterError(code: "INVALID_ARG", message: "path required", details: nil))
          return
        }
        Self.stopAccessing(path: path, result: result)
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
      result(bookmarkData.base64EncodedString())
    } catch {
      result(FlutterError(code: "BOOKMARK_FAILED", message: error.localizedDescription, details: nil))
    }
  }

  private static func resolveBookmark(data: String, result: @escaping FlutterResult) {
    guard let bookmarkData = Data(base64Encoded: data) else {
      result(FlutterError(code: "INVALID_DATA", message: "invalid base64", details: nil))
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
      _ = url.startAccessingSecurityScopedResource()
      if isStale {
        do {
          let fresh = try url.bookmarkData(
            options: .withSecurityScope,
            includingResourceValuesForKeys: nil,
            relativeTo: nil
          )
          result(["path": url.path, "refreshedBookmark": fresh.base64EncodedString()])
        } catch {
          url.stopAccessingSecurityScopedResource()
          result(FlutterError(code: "RESOLVE_FAILED", message: error.localizedDescription, details: nil))
        }
      } else {
        result(["path": url.path, "refreshedBookmark": NSNull()])
      }
    } catch {
      result(FlutterError(code: "RESOLVE_FAILED", message: error.localizedDescription, details: nil))
    }
  }

  private static func stopAccessing(path: String, result: @escaping FlutterResult) {
    URL(fileURLWithPath: path).stopAccessingSecurityScopedResource()
    result(nil)
  }
}
