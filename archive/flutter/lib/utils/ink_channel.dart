import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import '../models/annotation_store_interface.dart';

const _channel = MethodChannel('com.afluffywaffle.layuv/ink');

/// Presents the iOS PencilKit ink canvas, then persists the PNG via [store].
/// Returns true if ink was captured and saved, false if the user dismissed
/// without drawing or the platform is unsupported.
///
/// iOS only. (Android ink — Supernote handwriting — will come back through the
/// drawPath low-latency pen, not the removed Onyx canvas.)
Future<bool> captureInk({
  required String annotationId,
  required AnnotationStoreInterface store,
}) async {
  if (!Platform.isIOS) return false;
  try {
    final result = await _channel.invokeMethod<dynamic>('presentInkCanvas');
    if (result == null) return false;
    final List<int> pngBytes;
    if (result is Uint8List) {
      pngBytes = result;
    } else if (result is List) {
      pngBytes = result.cast<int>();
    } else {
      return false;
    }
    if (pngBytes.isEmpty) return false;
    await store.saveInkPng(annotationId, pngBytes);
    return true;
  } on PlatformException catch (e) {
    debugPrint('InkChannel error: ${e.message}');
    return false;
  }
}
