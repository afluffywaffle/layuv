import Flutter
import UIKit

class BookmarkChannel {
  static let channelName = "com.afluffywaffle.layuv/bookmarks"

  static func register(with messenger: FlutterBinaryMessenger) {
    let channel = FlutterMethodChannel(name: channelName, binaryMessenger: messenger)
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
        result(nil)
      default:
        result(FlutterMethodNotImplemented)
      }
    }
  }

  private static func saveBookmark(path: String, result: @escaping FlutterResult) {
    let url = URL(fileURLWithPath: path)
    do {
      let bookmarkData = try url.bookmarkData(
        options: [],
        includingResourceValuesForKeys: nil,
        relativeTo: nil
      )
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
        options: [],
        relativeTo: nil,
        bookmarkDataIsStale: &isStale
      )
      result(url.path)
    } catch {
      result(FlutterError(code: "RESOLVE_FAILED", message: error.localizedDescription, details: nil))
    }
  }
}
