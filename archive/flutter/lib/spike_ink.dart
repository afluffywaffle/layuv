// SPIKE — can the Supernote drawPath low-latency ink render a DASHED/DOTTED
// stroke? If so, we can use it as a fast, transient, "selection" affordance
// (dotted = selecting, not permanent underlining) instead of the OS's solid
// stylus-gesture stroke. Run/build with: flutter run -t lib/spike_ink.dart
//
// EXPERIMENT: sweep `penType` (and width) via the steppers and DRAW with the
// stylus on the white canvas. Report which penType (if any) renders dotted/
// dashed vs solid, and whether you see ONE stroke (drawPath only) or TWO
// (drawPath + the system stylus-gesture stroke).
//
// "Clear" calls drawPath clearScreen (code 6) + a full refresh. "app render"
// also draws Flutter's captured strokes, to compare with the hardware ink.
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const _drawpath = MethodChannel('com.afluffywaffle.layuv/drawpath');
const _eink = MethodChannel('com.afluffywaffle.layuv/eink_spike');

// Sample reader-style prose rendered behind the canvas, to test whether the
// dotted lasso ghosts when drawn + cleared OVER content (not a blank canvas).
const _sampleText =
    'The vellum crackled softly as he turned each page in the otherwise silent '
    'scriptorium. To annotate is to refuse passivity, to insist that the text is '
    'not finished, that reading is a kind of writing. There is a peculiar '
    'intimacy in reading another person’s marginal notes — you are '
    'sharing their surprise, their frustration. Modern conservation had '
    'stabilized the pages, but the damage from the earlier rebinding was '
    'irreversible. The binding was Coptic, the script Carolingian, yet the '
    'illuminations suggested a provenance neither could explain. A single red '
    'thread marked a passage the original reader had found remarkable; she found '
    'it remarkable too. She compared the letterforms against the atlas of '
    'scripts, column by column, ruling out one monastery after another. He set '
    'down his magnifying glass and admitted, quietly, that the attribution would '
    'have to remain open. Every annotation told a story within the story — '
    'a reader’s doubt, a scribe’s correction, a priest’s warning.';

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

  // Experiment knobs. penType 4 = Supernote's dotted lasso (the experiment win):
  // 3=eraser, 4=lasso-dotted, 15=thin, 17=marker, 6-14 empty. Boot into 4.
  int _penType = 4;
  int _penWidth = 200;
  static const int _penColor = 0; // black

  // Full refresh on clear = clean over content but flashes; off = fast but may
  // ghost over text (the docs-app behavior). Toggle to compare over the prose.
  bool _fullRefreshOnClear = true;

  // True-full-clear hunt: sendOneFullFrame is a SOFT refresh (doesn't clear heavy
  // ghosts). Sweep screenRefresh(mode) + setScreenMode(CLEAR/...) to find the
  // app-driven clean clear that matches the system gesture bar.
  int _refreshMode = 0;

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
      // Disable a fixed top strip so the control bar isn't inkable (device gives
      // degenerate Flutter metrics; the Android side converts the fraction via
      // displayMetrics). Widened for the bigger experiment control bar.
      const canvasTopFraction = 0.2;
      debugPrint('INKSPIKE cfg penType=$_penType width=$_penWidth frac=$canvasTopFraction');

      final r = await _drawpath.invokeMethod<String>('configure', {
        'penType': _penType, 'penWidth': _penWidth, 'penColor': _penColor,
        'canvasTopFraction': canvasTopFraction,
      });
      await _eink.invokeMethod<String>('fullRefresh');
      if (!mounted) return;
      setState(() => _status = 'cfg type=$_penType w=$_penWidth → $r');
    } catch (e) {
      if (!mounted) return;
      setState(() => _status = 'configure ERROR: $e');
    }
  }

  Future<void> _clear() async {
    try {
      final r = await _drawpath.invokeMethod<String>('clear');
      if (_fullRefreshOnClear) await _eink.invokeMethod<String>('fullRefresh');
      if (!mounted) return;
      setState(() {
        _strokes.clear();
        _current = null;
        _points = 0;
        _status = 'clear → $r';
      });
    } catch (e) {
      if (!mounted) return;
      setState(() => _status = 'clear ERROR: $e');
    }
  }

  // Change the pen type/width and re-apply (clears first so each pen draws on a
  // fresh canvas for clean comparison).
  Future<void> _bumpType(int d) async {
    _penType = (_penType + d).clamp(0, 99);
    await _clear();
    await _configure();
  }

  Future<void> _bumpWidth(int d) async {
    _penWidth = (_penWidth + d).clamp(10, 600);
    await _clear();
    await _configure();
  }

  Future<void> _setType(int t) async {
    _penType = t.clamp(0, 99);
    await _clear();
    await _configure();
  }

  void _bumpMode(int d) =>
      setState(() => _refreshMode = (_refreshMode + d).clamp(0, 12));

  Future<void> _screenRefresh() async {
    try {
      final r = await _eink.invokeMethod<String>(
          'screenRefresh', {'force': true, 'mode': _refreshMode});
      if (!mounted) return;
      setState(() => _status = 'screenRefresh(mode=$_refreshMode) → $r');
    } catch (e) {
      if (!mounted) return;
      setState(() => _status = 'screenRefresh ERROR: $e');
    }
  }

  Future<void> _setMode(String name) async {
    try {
      final r = await _eink.invokeMethod<String>('setScreenMode', {'name': name});
      if (!mounted) return;
      setState(() => _status = 'setMode($name) → $r');
    } catch (e) {
      if (!mounted) return;
      setState(() => _status = 'setMode ERROR: $e');
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
        'stroke: kind=$_lastKind points=$_points strokes=${_strokes.length} (type=$_penType w=$_penWidth)');
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
                  Text('penType=$_penType  width=$_penWidth   |   kind=$_lastKind  points=$_points  strokes=${_strokes.length}  p=${_lastPressure.toStringAsFixed(2)}',
                      style: const TextStyle(fontSize: 12, color: Colors.black54)),
                  const SizedBox(height: 6),
                  Wrap(spacing: 8, runSpacing: 8, children: [
                    _btn('Re-init', _configure),
                    _btn('Clear', _clear),
                    _btn('type -1', () => _bumpType(-1)),
                    _btn('type +1', () => _bumpType(1)),
                    _btn('type +5', () => _bumpType(5)),
                    _btn('→4 lasso', () => _setType(4)),
                    _btn('→15 thin', () => _setType(15)),
                    _btn('→17 marker', () => _setType(17)),
                    _btn('width -50', () => _bumpWidth(-50)),
                    _btn('width +50', () => _bumpWidth(50)),
                    _btn('refresh on clear: ${_fullRefreshOnClear ? "ON" : "off"}',
                        () => setState(() => _fullRefreshOnClear = !_fullRefreshOnClear)),
                    _btn('rmode -1', () => _bumpMode(-1)),
                    _btn('rmode +1', () => _bumpMode(1)),
                    _btn('screenRefresh($_refreshMode)', _screenRefresh),
                    _btn('mode CLEAR', () => _setMode('EINK_SCREEN_MODE_CLEAR')),
                    _btn('mode SMOOTH', () => _setMode('EINK_SCREEN_MODE_SMOOTH')),
                    _btn('mode SPEED', () => _setMode('EINK_SCREEN_MODE_SPEED')),
                    _btn('mode DEFAULT', () => _setMode('EINK_SCREEN_MODE_DEFAULT')),
                    _btn('app render: ${_appRender ? "ON" : "off"}',
                        () => setState(() => _appRender = !_appRender)),
                  ]),
                ],
              ),
            ),
            Expanded(
              child: Stack(
                children: [
                  // Reader-style text behind the canvas — lasso OVER this and
                  // clear to check for ghosting on dense content.
                  Positioned.fill(
                    child: Container(
                      color: Colors.white,
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: const [
                          // Zone 1: prose text.
                          Text(
                            _sampleText,
                            style: TextStyle(
                              fontFamily: 'Literata',
                              fontSize: 16,
                              height: 1.6,
                              color: Colors.black,
                            ),
                          ),
                          SizedBox(height: 24),
                          // Zone 2: graphics (checkboxes) — the "heavy" content.
                          Row(
                            children: [
                              Icon(Icons.check_box, size: 56, color: Colors.black),
                              SizedBox(width: 18),
                              Icon(Icons.check_box, size: 56, color: Colors.black),
                              SizedBox(width: 18),
                              Icon(Icons.check_box_outline_blank, size: 56, color: Colors.black),
                              SizedBox(width: 18),
                              Icon(Icons.check_box, size: 56, color: Colors.black),
                              SizedBox(width: 18),
                              Icon(Icons.check_box_outline_blank, size: 56, color: Colors.black),
                            ],
                          ),
                          // Zone 3: the rest stays white.
                        ],
                      ),
                    ),
                  ),
                  Positioned.fill(
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
