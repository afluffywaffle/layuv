import 'dart:io';
import 'package:flutter/services.dart';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

class BookmarkService {
  static const _channel = MethodChannel('com.afluffywaffle.layuv/bookmarks');
  static const _lastPathKey = 'bookmark_last_path';
  static const _dataPrefix = 'bookmark_data_';

  static bool get _supported => Platform.isMacOS || Platform.isIOS;

  static String _dataKey(String path) => '$_dataPrefix${Uri.encodeComponent(path)}';

  Future<void> saveBookmark(String path) async {
    if (!_supported) return;
    try {
      final base64 = await _channel.invokeMethod<String>('saveBookmark', path);
      if (base64 == null) return;
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_dataKey(path), base64);
      await prefs.setString(_lastPathKey, path);
    } catch (e) {
      debugPrint('BookmarkService.saveBookmark: $e');
    }
  }

  Future<String?> resolveBookmark(String path) async {
    if (!_supported) return null;
    final prefs = await SharedPreferences.getInstance();
    final base64 = prefs.getString(_dataKey(path));
    if (base64 == null) return null;
    final result = await _channel.invokeMapMethod<String, Object?>('resolveBookmark', base64);
    if (result == null) return null;
    final resolved = result['path'] as String?;
    final refreshed = result['refreshedBookmark'] as String?;
    if (refreshed != null) {
      await prefs.setString(_dataKey(path), refreshed);
    }
    return resolved;
  }

  /// Resolves the last opened file's bookmark and returns an accessible path.
  /// Returns null if no last file is stored. Throws on resolution failure so
  /// callers can clear the stale bookmark.
  Future<String?> resolveLastFile() async {
    if (!_supported) return null;
    final prefs = await SharedPreferences.getInstance();
    final path = prefs.getString(_lastPathKey);
    if (path == null) return null;
    return await resolveBookmark(path);
  }

  /// Clears the stored bookmark and last-path record for [path].
  Future<void> clearBookmark(String path) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_dataKey(path));
    if (prefs.getString(_lastPathKey) == path) {
      await prefs.remove(_lastPathKey);
    }
  }

  Future<void> stopAccessing(String path) async {
    if (!_supported) return;
    try {
      await _channel.invokeMethod('stopAccessing', path);
    } catch (e) {
      debugPrint('BookmarkService.stopAccessing: $e');
    }
  }
}
