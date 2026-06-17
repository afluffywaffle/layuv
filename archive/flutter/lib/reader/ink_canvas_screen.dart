import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../utils/eink_pen.dart';
import '../utils/platform_utils.dart';

const _drawpath = MethodChannel('com.afluffywaffle.layuv/drawpath');
const _eink = MethodChannel('com.afluffywaffle.layuv/eink_spike');

enum _InkTool { thin, thick, eraser }

// drawPath penWidth per ink tool. Tune these on-device until hardware stroke
// width matches _flutterStrokeWidth visually after pen-up.
const _drawpathPenWidth = {
  _InkTool.thin: 150,
  _InkTool.thick: 450,
};

// Flutter strokeWidth (dp) per ink tool — must match drawPath visually.
const _flutterStrokeWidth = {
  _InkTool.thin: 1.0,
  _InkTool.thick: 3.5,
};

// drawPath eraser: white pen on the canvas so the user sees hardware ink being
// "erased" in real time. On pen-up, clear drawPath buffer + redraw Flutter.
const _drawpathEraserWidth = 400;
const _drawpathEraserColor = 254; // white

// Each recorded stroke carries the tool so replay uses the right stroke width.
typedef _Stroke = ({List<Offset> pts, _InkTool tool});

// Rule line spacing in logical pixels. 0 = no lines.
const _ruleSpacing = {'wide': 40.0, 'college': 32.0};
const _ruleStyles = ['none', 'wide', 'college'];

// Combined toolbar (64dp) + text reference box (96dp) excluded from drawPath.
const int _kTopExclusionDp = 160;

// Approximate chars to show per page in the reference box at 13sp.
const int _kCharsPerPage = 200;

/// Full-screen ink canvas for Supernote. Shows the annotated text as a
/// reference at the top. Supports thin/thick pen and eraser, optional rule
/// lines, and exports a transparent PNG (rule lines excluded) on Done.
class InkCanvasScreen extends StatefulWidget {
  const InkCanvasScreen({super.key, required this.selectedText});
  final String selectedText;

  @override
  State<InkCanvasScreen> createState() => _InkCanvasScreenState();
}

class _InkCanvasScreenState extends State<InkCanvasScreen>
    with WidgetsBindingObserver {
  final List<_Stroke> _strokes = [];
  List<Offset>? _currentStroke;
  _InkTool _tool = _InkTool.thin;
  String _ruleStyle = 'none'; // 'none' | 'wide' | 'college'
  bool _saving = false;
  int _textPage = 0;
  Size _canvasSize = Size.zero;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) => _configure());
    _loadPrefs();
  }

  Future<void> _loadPrefs() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      _ruleStyle = prefs.getString('ink_rule_lines') ?? 'none';
    });
    // E-ink: the prefs read resolves asynchronously, usually AFTER the initial
    // _configure() full-refresh has already fired — so the freshly-painted rule
    // lines never reach the panel. Push one refresh once they're laid out.
    if (isEink && _ruleStyle != 'none') {
      WidgetsBinding.instance.addPostFrameCallback((_) async {
        if (mounted) await EinkPen.refresh();
      });
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    EinkPen.configureLasso();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      WidgetsBinding.instance.addPostFrameCallback((_) async {
        await _configure();
        if (mounted) {
          setState(() {});
          await EinkPen.refresh();
        }
      });
    }
  }

  Future<void> _configure() async {
    if (!isEink) return;
    final isEraser = _tool == _InkTool.eraser;
    final penWidth =
        isEraser ? _drawpathEraserWidth : (_drawpathPenWidth[_tool] ?? 150);
    final penColor = isEraser ? _drawpathEraserColor : 0;
    try {
      await _drawpath.invokeMethod<String>('configureForInk', <String, dynamic>{
        'toolbarDp': _kTopExclusionDp,
        'penWidth': penWidth,
        'penColor': penColor,
      });
      // Full refresh after switching to a pen (resets EPD from eraser mode).
      // Skip for eraser — drawPath white-pen provides live feedback directly.
      if (!isEraser) {
        await _eink.invokeMethod<String>('fullRefresh');
      }
    } catch (e) {
      debugPrint('InkCanvas configure error: $e');
    }
  }

  Future<void> _setTool(_InkTool tool) async {
    if (_tool == tool) return;
    final wasEraser = _tool == _InkTool.eraser;
    setState(() => _tool = tool);
    if (wasEraser && isEink) {
      // Clear white eraser strokes from drawPath buffer before pen config + refresh.
      try {
        await _drawpath.invokeMethod<String>('clear');
      } catch (e) {
        debugPrint('InkCanvas clear on tool switch: $e');
      }
    }
    await _configure();
  }

  void _cycleRules() {
    final next = _ruleStyles[(_ruleStyles.indexOf(_ruleStyle) + 1) % _ruleStyles.length];
    setState(() => _ruleStyle = next);
  }

  Future<void> _clear() async {
    try {
      if (isEink) {
        await _drawpath.invokeMethod<String>('clear');
        await _eink.invokeMethod<String>('fullRefresh');
      }
      if (!mounted) return;
      setState(() {
        _strokes.clear();
        _currentStroke = null;
      });
    } catch (e) {
      debugPrint('InkCanvas clear error: $e');
    }
  }

  Future<void> _done() async {
    if (_saving) return;
    setState(() => _saving = true);
    try {
      final bytes = await _renderToPng();
      if (isEink) {
        await _drawpath.invokeMethod<String>('clear');
      }
      if (!mounted) return;
      Navigator.pop(context, bytes);
    } catch (e) {
      debugPrint('InkCanvas done error: $e');
      if (mounted) setState(() => _saving = false);
    }
  }

  Future<Uint8List?> _renderToPng() async {
    final hasInk = _strokes.any((s) => s.tool != _InkTool.eraser);
    if (!hasInk) return null;
    final w = _canvasSize.width;
    final h = _canvasSize.height;
    if (w <= 0 || h <= 0) return null;

    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder, Rect.fromLTWH(0, 0, w, h));
    // Strokes in chronological order. Eraser punches transparent holes with
    // BlendMode.clear; ink drawn after an erase survives correctly.
    for (final stroke in _strokes) {
      if (stroke.pts.length < 2) continue;
      final Paint paint;
      if (stroke.tool == _InkTool.eraser) {
        paint = Paint()
          ..blendMode = BlendMode.clear
          ..color = Colors.transparent
          ..style = PaintingStyle.stroke
          ..strokeWidth = 24
          ..strokeCap = StrokeCap.round
          ..strokeJoin = StrokeJoin.round;
      } else {
        paint = Paint()
          ..color = Colors.black
          ..style = PaintingStyle.stroke
          ..strokeWidth = _flutterStrokeWidth[stroke.tool] ?? 1.0
          ..strokeCap = StrokeCap.round
          ..strokeJoin = StrokeJoin.round;
      }
      _drawSmoothedPath(canvas, stroke.pts, paint);
    }
    final picture = recorder.endRecording();
    final image = await picture.toImage(w.ceil(), h.ceil());
    final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
    image.dispose();
    picture.dispose();
    return byteData?.buffer.asUint8List();
  }

  // --- Pointer handling ---

  void _onPointerDown(PointerDownEvent e) {
    _currentStroke = [e.localPosition];
  }

  void _onPointerMove(PointerMoveEvent e) {
    _currentStroke?.add(e.localPosition);
    // On e-ink: drawPath provides real-time hardware ink for both pen
    // (black) and eraser (white pen) modes — no Flutter repaint needed.
    if (!isEink) setState(() {});
  }

  void _onPointerUp(PointerUpEvent e) {
    if (_currentStroke != null && _currentStroke!.length >= 2) {
      _strokes.add((pts: _currentStroke!, tool: _tool));
    }
    _currentStroke = null;
    setState(() {});
    if (_tool == _InkTool.eraser && isEink) {
      // Clear the white drawPath eraser strokes, then EPD-refresh to show
      // the corrected Flutter layer (eraser strokes applied via BlendMode).
      _drawpath
          .invokeMethod<String>('clear')
          .then((_) => EinkPen.refresh())
          .catchError((Object e) => debugPrint('erase refresh error: $e'));
    }
  }

  void _onPointerCancel(PointerCancelEvent e) {
    _currentStroke = null;
    setState(() {});
  }

  // --- Text page helpers ---

  String get _pageText {
    final start = _textPage * _kCharsPerPage;
    if (start >= widget.selectedText.length) return widget.selectedText;
    final end = (start + _kCharsPerPage).clamp(0, widget.selectedText.length);
    return widget.selectedText.substring(start, end);
  }

  bool get _hasNextPage =>
      (_textPage + 1) * _kCharsPerPage < widget.selectedText.length;
  bool get _hasPrevPage => _textPage > 0;

  // --- Build ---

  @override
  Widget build(BuildContext context) {
    final rulesLabel = switch (_ruleStyle) {
      'wide' => 'Wide',
      'college' => 'College',
      _ => 'Lines',
    };

    return Scaffold(
      backgroundColor: const Color(0xFFF5F0E8),
      body: SafeArea(
        child: Column(
          children: [
            SizedBox(
              height: 64,
              child: Row(
                children: [
                  _toolToggle('Thin', _tool == _InkTool.thin,
                      () => _setTool(_InkTool.thin)),
                  _toolToggle('Thick', _tool == _InkTool.thick,
                      () => _setTool(_InkTool.thick)),
                  _toolToggle('Erase', _tool == _InkTool.eraser,
                      () => _setTool(_InkTool.eraser)),
                  const Spacer(),
                  _toolbarButton(rulesLabel, _cycleRules),
                  _toolbarButton('Clear', _strokes.isEmpty ? null : _clear),
                  _toolbarButton(
                      _saving ? 'Saving…' : 'Done', _saving ? null : _done),
                ],
              ),
            ),
            _TextReferenceBox(
              text: _pageText,
              hasPrev: _hasPrevPage,
              hasNext: _hasNextPage,
              onPrev: () => setState(() => _textPage--),
              onNext: () => setState(() => _textPage++),
            ),
            Expanded(
              child: LayoutBuilder(
                builder: (context, constraints) {
                  _canvasSize =
                      Size(constraints.maxWidth, constraints.maxHeight);
                  return Listener(
                    behavior: HitTestBehavior.opaque,
                    onPointerDown: _onPointerDown,
                    onPointerMove: _onPointerMove,
                    onPointerUp: _onPointerUp,
                    onPointerCancel: _onPointerCancel,
                    child: CustomPaint(
                      painter: _CanvasPainter(
                        strokes: _strokes,
                        current: _currentStroke,
                        tool: _tool,
                        ruleStyle: _ruleStyle,
                      ),
                      child: const SizedBox.expand(),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _toolToggle(String label, bool selected, VoidCallback onTap) {
    return GestureDetector(
      onTap: onTap,
      child: SizedBox(
        width: 80,
        height: 64,
        child: Center(
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
            decoration: selected
                ? BoxDecoration(
                    border: Border.all(color: Colors.black87),
                    borderRadius: BorderRadius.circular(6),
                  )
                : null,
            child: Text(
              label,
              style: TextStyle(
                fontFamily: 'SourceSans3',
                fontSize: 14,
                fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
                color: Colors.black87,
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _toolbarButton(String label, VoidCallback? onTap) {
    return SizedBox(
      width: 80,
      height: 64,
      child: GestureDetector(
        onTap: onTap,
        child: Center(
          child: Text(
            label,
            style: TextStyle(
              fontFamily: 'SourceSans3',
              fontSize: 14,
              color: onTap != null ? Colors.black87 : Colors.black38,
            ),
          ),
        ),
      ),
    );
  }
}

void _drawSmoothedPath(Canvas canvas, List<Offset> pts, Paint paint) {
  if (pts.length < 2) return;
  final path = Path()..moveTo(pts.first.dx, pts.first.dy);
  if (pts.length == 2) {
    path.lineTo(pts.last.dx, pts.last.dy);
  } else {
    for (int i = 1; i < pts.length - 1; i++) {
      final mid = Offset(
        (pts[i].dx + pts[i + 1].dx) / 2,
        (pts[i].dy + pts[i + 1].dy) / 2,
      );
      path.quadraticBezierTo(pts[i].dx, pts[i].dy, mid.dx, mid.dy);
    }
    path.lineTo(pts.last.dx, pts.last.dy);
  }
  canvas.drawPath(path, paint);
}

class _TextReferenceBox extends StatelessWidget {
  const _TextReferenceBox({
    required this.text,
    required this.hasPrev,
    required this.hasNext,
    required this.onPrev,
    required this.onNext,
  });

  final String text;
  final bool hasPrev;
  final bool hasNext;
  final VoidCallback onPrev;
  final VoidCallback onNext;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 96,
      child: Container(
        decoration: BoxDecoration(
          color: Colors.black.withValues(alpha: 0.04),
          border: Border(
            bottom: BorderSide(color: Colors.black.withValues(alpha: 0.14)),
          ),
        ),
        padding: const EdgeInsets.fromLTRB(16, 10, 4, 10),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Expanded(
              child: Text(
                '"$text"',
                style: const TextStyle(
                  fontFamily: 'Literata',
                  fontSize: 13,
                  fontStyle: FontStyle.italic,
                  color: Colors.black54,
                  height: 1.45,
                ),
                maxLines: 4,
                overflow: TextOverflow.ellipsis,
              ),
            ),
            if (hasPrev || hasNext)
              Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (hasPrev)
                    GestureDetector(
                      onTap: onPrev,
                      child: const SizedBox(
                        width: 44,
                        height: 44,
                        child: Icon(Icons.keyboard_arrow_up,
                            size: 22, color: Colors.black54),
                      ),
                    ),
                  if (hasNext)
                    GestureDetector(
                      onTap: onNext,
                      child: const SizedBox(
                        width: 44,
                        height: 44,
                        child: Icon(Icons.keyboard_arrow_down,
                            size: 22, color: Colors.black54),
                      ),
                    ),
                ],
              ),
          ],
        ),
      ),
    );
  }
}

class _CanvasPainter extends CustomPainter {
  const _CanvasPainter({
    required this.strokes,
    required this.current,
    required this.tool,
    required this.ruleStyle,
  });

  final List<_Stroke> strokes;
  final List<Offset>? current;
  final _InkTool tool;
  final String ruleStyle;

  @override
  void paint(Canvas canvas, Size size) {
    _paintRuleLines(canvas, size);
    _paintStrokes(canvas);
  }

  void _paintRuleLines(Canvas canvas, Size size) {
    final spacing = _ruleSpacing[ruleStyle] ?? 0.0;
    if (spacing <= 0) return;
    final paint = Paint()
      ..color = Colors.black.withValues(alpha: 0.30)
      ..strokeWidth = 1.0
      ..style = PaintingStyle.stroke;
    double y = spacing;
    while (y < size.height) {
      canvas.drawLine(Offset(0, y), Offset(size.width, y), paint);
      y += spacing;
    }
  }

  void _paintStrokes(Canvas canvas) {
    for (final stroke in strokes) {
      if (stroke.pts.length < 2) continue;
      _drawSmoothedPath(canvas, stroke.pts, _paintForTool(stroke.tool));
    }
    final cur = current;
    if (cur != null && cur.length >= 2) {
      _drawSmoothedPath(canvas, cur, _paintForTool(tool));
    }
  }

  static Paint _paintForTool(_InkTool t) {
    if (t == _InkTool.eraser) {
      return Paint()
        ..color = const Color(0xFFF5F0E8)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 24
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round;
    }
    return Paint()
      ..color = Colors.black
      ..style = PaintingStyle.stroke
      ..strokeWidth = _flutterStrokeWidth[t] ?? 1.0
      ..strokeCap = StrokeCap.round
      ..strokeJoin = StrokeJoin.round;
  }

  @override
  bool shouldRepaint(covariant _CanvasPainter old) => true;
}
