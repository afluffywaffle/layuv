import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'platform_utils.dart';

/// Supernote (Ratta) low-latency dotted-lasso pen + EPD refresh control for the
/// reader's text selection. Wraps the native `drawpath` (binder) and `eink`
/// (EinkManager) method channels validated on the Manta 2026-06-11:
///
/// - The hardware pen drawn during stylus drags is set to **penType 4 = the
///   native dotted LASSO** — a fast, transient, dotted stroke that reads as
///   "selecting" (not a permanent underline) and clears on lift.
/// - [clearInk] flushes drawPath's retained ink buffer (binder code 6) — needed
///   because drawPath redraws its buffer on every refresh.
/// - [fullClear] is the gesture-bar-equivalent true full-clear (the soft
///   `sendOneFullFrame` does NOT clear ghosts): clearScreen → setScreenMode(
///   CLEAR/0) → screenRefresh(force, mode 1).
///
/// Everything is a no-op off e-ink and swallows channel errors (e-ink hardware
/// is best-effort; the Flutter selection highlight works regardless).
class EinkPen {
  EinkPen._();

  static const _drawpath = MethodChannel('com.afluffywaffle.layuv/drawpath');
  static const _eink = MethodChannel('com.afluffywaffle.layuv/eink_spike');

  static bool _configured = false;

  /// True once the lasso pen has been configured on this device.
  static bool get isConfigured => _configured;

  /// Configure the hardware pen as the dotted lasso (penType 4), full-screen
  /// writable (no disabled regions for now). Idempotent — call once when an
  /// e-ink reader loads. Off e-ink this does nothing.
  static Future<void> configureLasso() async {
    if (!isEink) return;
    try {
      await _drawpath.invokeMethod<String>('configure', <String, dynamic>{
        'penType': 4, // Supernote's native dotted lasso stroke
        'penWidth': 200,
        'penColor': 0, // black
        'canvasTopFraction': 0.0, // no disabled top strip
      });
      _configured = true;
    } catch (e) {
      debugPrint('EinkPen.configureLasso failed: $e');
    }
  }

  /// Flush drawPath's retained ink buffer (binder code 6) so the transient
  /// lasso disappears, leaving the committed Flutter highlight alone. Call on
  /// selection commit / cancel / handle-adjust end.
  static Future<void> clearInk() async {
    if (!isEink || !_configured) return;
    try {
      await _drawpath.invokeMethod<String>('clear');
    } catch (e) {
      debugPrint('EinkPen.clearInk failed: $e');
    }
  }

  /// A clean full-screen EPD refresh (EinkManager.sendOneFullFrame) that flushes
  /// the current Flutter frame to the panel. Needed after a state change the
  /// e-ink panel won't otherwise repaint — e.g. an annotation applied by a pen
  /// CIRCLE/scribble, which (unlike a finger tap) issues no touch event for the
  /// OS to refresh on. Lighter than [fullClear] (no clear/flash). No-op off
  /// e-ink; safe to over-call (idempotent frame flush).
  static Future<void> refresh() async {
    if (!isEink) return;
    try {
      await _eink.invokeMethod<String>('fullRefresh');
    } catch (e) {
      debugPrint('EinkPen.refresh failed: $e');
    }
  }

  /// The gesture-bar-equivalent true full-clear (clean GC16 flash). Flushes the
  /// ink buffer, sets the CLEAR waveform (mode 0), then a flashing full refresh
  /// (mode 1). Use to wipe accumulated ghosts / for a guaranteed-clean frame.
  /// Heavier than [clearInk] (full-screen flash) — not for routine commits.
  static Future<void> fullClear() async {
    if (!isEink) return;
    try {
      await _drawpath.invokeMethod<String>('clear');
      await _eink.invokeMethod<String>(
          'setScreenMode', <String, dynamic>{'name': 'EINK_SCREEN_MODE_CLEAR'});
      await _eink.invokeMethod<String>(
          'screenRefresh', <String, dynamic>{'force': true, 'mode': 1});
    } catch (e) {
      debugPrint('EinkPen.fullClear failed: $e');
    }
  }
}
