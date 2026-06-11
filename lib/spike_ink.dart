// SPIKE — does the Supernote drawPath low-latency ink work over a FLUTTER
// window? Run/build with: flutter run -t lib/spike_ink.dart
//
// On launch it configures drawPath (reset -> pen -> disable the toolbar strip)
// and does an EinkManager full refresh so the UI appears. Then DRAW WITH THE
// STYLUS on the white canvas: drawPath should paint low-latency strokes itself
// (system-level), while Flutter independently receives the pointer stream
// (count + pressure shown) — proving we can capture geometry for persistence.
//
// "Clear" calls drawPath clearScreen (code 6) + a full refresh.
// "App render" also draws Flutter's captured strokes, to compare with drawPath.
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const _drawpath = MethodChannel('com.afluffywaffle.layuv/drawpath');
const _eink = MethodChannel('com.afluffywaffle.layuv/eink_spike');

void main() => runApp(const _InkSpikeApp());

class _InkSpikeApp extends StatelessWidget {
  const _InkSpikeApp();
  @override
  Widget build(BuildContext context) =>
      const MaterialApp(debugShowCheckedModeBanner: false, home: _InkSpikePage());
}

class _InkSpikePage extends StatefulWidget {
  const _InkSpikePage();
  @override
  State<_InkSpikePage> createState() => _InkSpikePageState();
}

class _InkSpikePageState extends State<_InkSpikePage> with WidgetsBindingObserver {
  final _canvasKey = GlobalKey();
  final List<List<Offset>> _strokes = [];
  List<Offset>? _current;
  bool _appRender = false;
  int _points = 0;
  String _lastKind = '-';
  double _lastPressure = 0;
  String _status = 'configuring…';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) => _configure());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState s) {
    if (s == AppLifecycleState.resumed) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _configure());
    }
  }

  Future<void> _configure() async {
    try {
      // This device gives degenerate Flutter metrics (MediaQuery dpr=1.0/size=0,
      // and the canvas box reports height 0 at first layout), so a measured
      // fraction is unreliable. For the spike, disable a FIXED top strip (~the
      // toolbar); the Android side converts the fraction via displayMetrics.
      // Production reader should measure the chrome height properly once.
      const canvasTopFraction = 0.11;
      debugPrint('INKSPIKE cfg frac=$canvasTopFraction (fixed)');

      final r = await _drawpath.invokeMethod<String>('configure', {
        'penType': 10, 'penWidth': 200, 'penColor': 0,
        'canvasTopFraction': canvasTopFraction,
      });
      await _eink.invokeMethod<String>('fullRefresh');
      setState(() => _status = 'cfg → $r');
    } catch (e) {
      setState(() => _status = 'configure ERROR: $e');
    }
  }

  Future<void> _clear() async {
    try {
      final r = await _drawpath.invokeMethod<String>('clear');
      await _eink.invokeMethod<String>('fullRefresh');
      setState(() {
        _strokes.clear();
        _current = null;
        _points = 0;
        _status = 'clear → $r';
      });
    } catch (e) {
      setState(() => _status = 'clear ERROR: $e');
    }
  }

  void _down(PointerDownEvent e) {
    _lastKind = e.kind.name;
    _lastPressure = e.pressure;
    _current = [e.localPosition];
    _points++;
    if (_appRender) setState(() {});
  }

  void _move(PointerMoveEvent e) {
    _current?.add(e.localPosition);
    _lastPressure = e.pressure;
    _points++;
    if (_appRender) setState(() {});
  }

  void _up(PointerUpEvent e) {
    if (_current != null) _strokes.add(_current!);
    _current = null;
    setState(() => _status =
        'stroke done: kind=$_lastKind points=$_points strokes=${_strokes.length} p=${_lastPressure.toStringAsFixed(2)}');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.white,
      body: SafeArea(
        child: Column(
          children: [
            Container(
              color: const Color(0xFFE8E2D6),
              padding: const EdgeInsets.all(8),
              child: Column(
                children: [
                  Text(_status, style: const TextStyle(fontSize: 12, color: Colors.black)),
                  const SizedBox(height: 4),
                  Text('kind=$_lastKind  points=$_points  strokes=${_strokes.length}  pressure=${_lastPressure.toStringAsFixed(2)}',
                      style: const TextStyle(fontSize: 12, color: Colors.black54)),
                  const SizedBox(height: 6),
                  Wrap(spacing: 8, runSpacing: 8, children: [
                    _btn('Re-init', _configure),
                    _btn('Clear', _clear),
                    _btn('app render: ${_appRender ? "ON" : "off"}',
                        () => setState(() => _appRender = !_appRender)),
                  ]),
                ],
              ),
            ),
            Expanded(
              child: Listener(
                key: _canvasKey,
                behavior: HitTestBehavior.opaque,
                onPointerDown: _down,
                onPointerMove: _move,
                onPointerUp: _up,
                child: CustomPaint(
                  painter: _appRender ? _InkPainter(_strokes, _current) : null,
                  child: const SizedBox.expand(),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _btn(String label, VoidCallback onTap) => SizedBox(
        height: 48,
        child: OutlinedButton(
          onPressed: onTap,
          style: OutlinedButton.styleFrom(
            foregroundColor: Colors.black,
            side: const BorderSide(color: Colors.black54),
          ),
          child: Text(label, style: const TextStyle(fontSize: 13)),
        ),
      );
}

class _InkPainter extends CustomPainter {
  _InkPainter(this.strokes, this.current);
  final List<List<Offset>> strokes;
  final List<Offset>? current;

  @override
  void paint(Canvas canvas, Size size) {
    final p = Paint()
      ..color = Colors.black
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
    void drawStroke(List<Offset> s) {
      if (s.length < 2) return;
      final path = Path()..moveTo(s.first.dx, s.first.dy);
      for (final o in s.skip(1)) {
        path.lineTo(o.dx, o.dy);
      }
      canvas.drawPath(path, p);
    }
    for (final s in strokes) {
      drawStroke(s);
    }
    if (current != null) drawStroke(current!);
  }

  @override
  bool shouldRepaint(covariant _InkPainter old) => true;
}
