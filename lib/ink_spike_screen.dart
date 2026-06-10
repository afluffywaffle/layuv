import 'package:flutter/material.dart';
import 'package:flutter/gestures.dart';
import 'utils/ink_channel.dart';

/// Temporary spike screen — assesses whether Supernote's fast partial
/// e-ink refresh (A2) is triggered by Flutter's canvas path.
/// Draw freely; if strokes follow the pen with low latency and no full-screen
/// flash, the Flutter canvas path is viable. If drawing feels sluggish or
/// triggers full-screen refresh, a native SurfaceView fallback is needed.
/// Delete this file when the spike result is recorded in the tracker.
class InkSpikeScreen extends StatefulWidget {
  const InkSpikeScreen({super.key});

  @override
  State<InkSpikeScreen> createState() => _InkSpikeScreenState();
}

class _InkSpikeScreenState extends State<InkSpikeScreen> {
  final List<List<Offset>> _strokes = [];
  List<Offset> _current = [];
  String _lastKind = '—';

  static const _strokePadding = 8.0;
  int _moveCount = 0;
  double _dirtyL = 0, _dirtyT = 0, _dirtyR = 0, _dirtyB = 0;

  void _resetDirty(Offset p) {
    _dirtyL = p.dx; _dirtyT = p.dy;
    _dirtyR = p.dx; _dirtyB = p.dy;
  }

  void _expandDirty(Offset p) {
    if (p.dx < _dirtyL) _dirtyL = p.dx;
    if (p.dy < _dirtyT) _dirtyT = p.dy;
    if (p.dx > _dirtyR) _dirtyR = p.dx;
    if (p.dy > _dirtyB) _dirtyB = p.dy;
  }

  void _flushEpd() {
    epdInvalidateRect(
      _dirtyL - _strokePadding,
      _dirtyT - _strokePadding,
      _dirtyR + _strokePadding,
      _dirtyB + _strokePadding,
    );
  }

  String _kindLabel(PointerDeviceKind kind) => switch (kind) {
        PointerDeviceKind.stylus => 'stylus',
        PointerDeviceKind.invertedStylus => 'invertedStylus',
        PointerDeviceKind.touch => 'touch',
        PointerDeviceKind.mouse => 'mouse',
        _ => 'unknown',
      };

  void _onPointerDown(PointerDownEvent e) {
    debugPrint('InkSpike pointer down: kind=${_kindLabel(e.kind)} buttons=${e.buttons}');
    _current = [e.localPosition];
    _moveCount = 0;
    _resetDirty(e.localPosition);
    setState(() {
      _strokes.add(_current);
      _lastKind = _kindLabel(e.kind);
    });
  }

  void _onPointerMove(PointerMoveEvent e) {
    _expandDirty(e.localPosition);
    _moveCount++;
    setState(() => _current.add(e.localPosition));
    if (_moveCount % 3 == 0) {
      _flushEpd();
      _resetDirty(e.localPosition);
    }
  }

  void _onPointerUp(PointerUpEvent e) {
    _expandDirty(e.localPosition);
    _flushEpd();
    _current = [];
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      appBar: AppBar(
        backgroundColor: Colors.white,
        foregroundColor: Colors.black87,
        elevation: 0,
        title: Text(
          'Ink spike — kind: $_lastKind',
          style: const TextStyle(fontFamily: 'Literata', fontSize: 16),
        ),
        actions: [
          TextButton(
            onPressed: () => setState(() => _strokes.clear()),
            child: const Text(
              'Clear',
              style: TextStyle(fontFamily: 'Literata', color: Colors.black54),
            ),
          ),
        ],
      ),
      body: Listener(
        onPointerDown: _onPointerDown,
        onPointerMove: _onPointerMove,
        onPointerUp: _onPointerUp,
        child: CustomPaint(
          painter: _StrokePainter(_strokes),
          child: const SizedBox.expand(),
        ),
      ),
    );
  }
}

class _StrokePainter extends CustomPainter {
  final List<List<Offset>> strokes;

  _StrokePainter(this.strokes);

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = Colors.black
      ..strokeWidth = 2.0
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round
      ..style = PaintingStyle.stroke;

    for (final stroke in strokes) {
      if (stroke.isEmpty) continue;
      if (stroke.length == 1) {
        canvas.drawCircle(stroke.first, 1.5, paint);
        continue;
      }
      final path = Path()..moveTo(stroke.first.dx, stroke.first.dy);
      for (final pt in stroke.skip(1)) {
        path.lineTo(pt.dx, pt.dy);
      }
      canvas.drawPath(path, paint);
    }
  }

  @override
  bool shouldRepaint(_StrokePainter old) => true;
}
