import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import '../models/annotation_store_interface.dart';

const _channel = MethodChannel('com.afluffywaffle.layuv/ink');

/// Asks the Android EPD driver to enter fast partial-refresh mode for the
/// current Activity. No-op on non-Android platforms; swallows errors on
/// devices that don't support the Onyx SDK.
Future<void> setEpdFastMode() async {
  if (!Platform.isAndroid) return;
  try {
    await _channel.invokeMethod<void>('setEpdFastMode');
  } on PlatformException catch (e) {
    debugPrint('InkChannel setEpdFastMode: ${e.message}');
  }
}

/// Resets the EPD driver to its default refresh mode (no-op — SDK lacks reset API).
Future<void> clearEpdMode() async {
  if (!Platform.isAndroid) return;
  try {
    await _channel.invokeMethod<void>('clearEpdMode');
  } on PlatformException catch (e) {
    debugPrint('InkChannel clearEpdMode: ${e.message}');
  }
}

/// Requests a partial fast-refresh of the given rect (logical pixels) on the
/// Flutter rendering surface via EpdController.invalidate(). Fire-and-forget —
/// do not await; latency would defeat the purpose.
void epdInvalidateRect(double left, double top, double right, double bottom) {
  if (!Platform.isAndroid) return;
  _channel.invokeMethod<void>('epdInvalidateRect', [left, top, right, bottom]);
}

/// Presents the ink canvas (iOS PencilKit / Android native canvas), then
/// persists the PNG via [store]. Returns true if ink was captured and saved,
/// false if the user dismissed without drawing or the platform is unsupported.
Future<bool> captureInk({
  required String annotationId,
  required AnnotationStoreInterface store,
}) async {
  if (!Platform.isIOS && !Platform.isAndroid) return false;
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
