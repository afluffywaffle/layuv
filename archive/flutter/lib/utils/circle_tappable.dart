import 'package:flutter/gestures.dart';
import 'package:flutter/widgets.dart';

/// Fires [onTap] when a pointer goes down and comes back up within
/// [circleRadius] logical pixels of the down position, in less than
/// [kLongPressTimeout].
///
/// Unlike [GestureDetector.onTap], this is implemented with a [Listener]
/// (below the gesture arena), so the pointer is captured at pen-down and all
/// subsequent move/up events arrive here even if the stroke exits the widget's
/// bounds mid-circle.  This is how pen circle-around-icon works: the pen may
/// loop outside the button's hit area, but the up event is still compared
/// against the down position.
///
/// The long-press guard (no fire if press >= [kLongPressTimeout]) lets an
/// outer [GestureDetector.onLongPressStart] win without double-firing.
class CircleTappable extends StatefulWidget {
  const CircleTappable({
    super.key,
    required this.child,
    required this.onTap,
    this.circleRadius = 90.0,
  });

  final Widget child;
  final VoidCallback onTap;

  /// Maximum distance (logical px) between pen-down and pen-up that still
  /// counts as a tap or circle gesture.  Default 90dp covers circles drawn
  /// around a 64dp toolbar icon.
  final double circleRadius;

  @override
  State<CircleTappable> createState() => _CircleTappableState();
}

class _CircleTappableState extends State<CircleTappable> {
  Offset? _downLocal;
  int _downMs = 0;
  int? _ptr;

  @override
  Widget build(BuildContext context) {
    return Listener(
      behavior: HitTestBehavior.opaque,
      onPointerDown: (e) {
        _ptr = e.pointer;
        _downLocal = e.localPosition;
        _downMs = DateTime.now().millisecondsSinceEpoch;
      },
      onPointerUp: (e) {
        if (e.pointer != _ptr) return;
        final down = _downLocal;
        _downLocal = null;
        _ptr = null;
        if (down == null) return;
        if (DateTime.now().millisecondsSinceEpoch - _downMs >=
            kLongPressTimeout.inMilliseconds) {
          return; // long-press was handled by an outer GestureDetector
        }
        final dx = e.localPosition.dx - down.dx;
        final dy = e.localPosition.dy - down.dy;
        if (dx * dx + dy * dy <= widget.circleRadius * widget.circleRadius) {
          widget.onTap();
        }
      },
      onPointerCancel: (e) {
        if (e.pointer == _ptr) {
          _downLocal = null;
          _ptr = null;
        }
      },
      child: widget.child,
    );
  }
}
