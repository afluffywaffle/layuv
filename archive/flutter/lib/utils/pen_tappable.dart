import 'dart:async';

import 'package:flutter/gestures.dart';
import 'package:flutter/widgets.dart';

import 'platform_utils.dart';

/// A tap region that works under the Supernote e-ink pen layer.
///
/// That layer holds the stylus pen-UP over Flutter UI, so a normal
/// `GestureDetector`/`InkWell` tap never resolves until the *next* pointer event
/// arrives — a pen tap on a button appears to do nothing until you tap again.
///
/// For a stylus on e-ink this commits [onTap] on a brief dwell after pen-down
/// (or on the pen-up, whichever comes first), so a pen tap activates a control
/// immediately. Movement past a small slop cancels it (it was a drag / scroll,
/// not a tap). Finger and non-e-ink input use a normal tap-on-release. It is a
/// drop-in replacement for `GestureDetector(onTap:)` / `InkWell(onTap:)` on the
/// e-ink UI chrome (it intentionally has no ripple — e-ink shows none anyway).
class PenTappable extends StatefulWidget {
  const PenTappable({
    super.key,
    required this.child,
    this.onTap,
    this.behavior = HitTestBehavior.opaque,
  });

  final Widget child;
  final VoidCallback? onTap;
  final HitTestBehavior behavior;

  @override
  State<PenTappable> createState() => _PenTappableState();
}

class _PenTappableState extends State<PenTappable> {
  static const Duration _penDwell = Duration(milliseconds: 90);
  static const double _slop = 14;

  int? _ptr;
  bool _fired = false;
  bool _moved = false;
  Offset _down = Offset.zero;
  Timer? _dwell;

  @override
  void dispose() {
    _dwell?.cancel();
    super.dispose();
  }

  void _fire() {
    if (_fired) return;
    _fired = true;
    _dwell?.cancel();
    widget.onTap?.call();
  }

  void _onDown(PointerDownEvent e) {
    if (widget.onTap == null) return;
    // A new pointer always supersedes any prior (possibly still-pending due to a
    // held pen-up) gesture, so the next tap is never blocked.
    _dwell?.cancel();
    _ptr = e.pointer;
    _fired = false;
    _moved = false;
    _down = e.position;
    final stylus = e.kind == PointerDeviceKind.stylus ||
        e.kind == PointerDeviceKind.invertedStylus;
    if (isEink && stylus) {
      _dwell = Timer(_penDwell, () {
        if (mounted && !_moved && e.pointer == _ptr) _fire();
      });
    }
  }

  void _onMove(PointerMoveEvent e) {
    if (e.pointer != _ptr || _fired) return;
    if (!_moved && (e.position - _down).distance > _slop) {
      _moved = true;
      _dwell?.cancel(); // a drag / scroll, not a tap
    }
  }

  void _onUp(PointerUpEvent e) {
    if (e.pointer != _ptr) return; // stale (held) up from a superseded gesture
    if (!_fired && !_moved) _fire();
    _reset();
  }

  void _onCancel(PointerCancelEvent e) {
    if (e.pointer != _ptr) return;
    _reset();
  }

  void _reset() {
    _dwell?.cancel();
    _ptr = null;
    _moved = false;
    _fired = false;
  }

  @override
  Widget build(BuildContext context) {
    return Listener(
      behavior: widget.behavior,
      onPointerDown: _onDown,
      onPointerMove: _onMove,
      onPointerUp: _onUp,
      onPointerCancel: _onCancel,
      child: widget.child,
    );
  }
}
