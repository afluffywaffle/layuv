// SPIKE — standalone entrypoint to test Ratta-native e-ink refresh from Flutter.
// Run/build with: flutter run -t lib/spike_eink.dart   (or --target on build apk)
//
// The question this answers: do Flutter "page flips" refresh CLEANLY on the
// Supernote when we drive android.os.EinkManager.sendOneFullFrame() ourselves —
// i.e., is the native-port's "Flutter fights e-ink" premise actually moot?
//
// Flip pages with "Auto refresh" ON (we call fullRefresh after each swap) vs OFF
// (let the system decide) and compare ghosting. Try the screen-mode buttons too.
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const _eink = MethodChannel('com.afluffywaffle.layuv/eink_spike');

void main() => runApp(const _SpikeApp());

class _SpikeApp extends StatelessWidget {
  const _SpikeApp();
  @override
  Widget build(BuildContext context) => const MaterialApp(
        debugShowCheckedModeBanner: false,
        home: _SpikePage(),
      );
}

class _SpikePage extends StatefulWidget {
  const _SpikePage();
  @override
  State<_SpikePage> createState() => _SpikePageState();
}

class _SpikePageState extends State<_SpikePage> with WidgetsBindingObserver {
  static const _paper = Color(0xFFF5F0E8);
  int _page = 0;
  int _stress = 0; // 0=text, 1=solid black, 2=checkerboard
  bool _autoRefresh = true;
  String _status = 'ready — flip pages and watch for ghosting';

  // Genuinely distinct content per page (rotating famous openings) so a flip is
  // a real full-page change, not just a number tick.
  static const _blocks = <String>[
    'It was the best of times, it was the worst of times, it was the age of wisdom, it was the age of foolishness, it was the epoch of belief, it was the epoch of incredulity.',
    'Call me Ishmael. Some years ago, having little or no money in my purse, and nothing particular to interest me on shore, I thought I would sail about a little and see the watery part of the world.',
    'In a hole in the ground there lived a hobbit. Not a nasty, dirty, wet hole, filled with the ends of worms and an oozy smell, nor yet a dry, bare, sandy hole with nothing in it to sit down on or to eat.',
    'Happy families are all alike; every unhappy family is unhappy in its own way. Everything was in confusion in the house. The wife had discovered that the husband was carrying on an intrigue.',
    'It is a truth universally acknowledged, that a single man in possession of a good fortune, must be in want of a wife. However little known the feelings of such a man may be on first entering a neighbourhood.',
    'The sky above the port was the color of television, tuned to a dead channel. It was not a pleasant smell, the smell of the city, but it was the smell he had grown up with, and he barely noticed it now.',
  ];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // The key test: does an explicit EinkManager full refresh make the freshly
    // painted Flutter frame actually appear on the EPD? Fire once the first
    // frame is up, then again shortly after to be safe.
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      await _call('fullRefresh');
      await Future<void>.delayed(const Duration(milliseconds: 400));
      await _call('fullRefresh');
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _call('fullRefresh'));
    }
  }

  String _pageText(int p) {
    final b = StringBuffer();
    b.writeln('— PAGE ${p + 1} —\n');
    for (int i = 0; i < 8; i++) {
      final blk = _blocks[(p + i) % _blocks.length];
      b.writeln('${i + 1}.  $blk\n');
    }
    return b.toString();
  }

  Future<void> _call(String method, [Map<String, dynamic>? args]) async {
    try {
      final r = await _eink.invokeMethod<String>(method, args);
      setState(() => _status = '$method → $r');
    } catch (e) {
      setState(() => _status = '$method ERROR: $e');
    }
  }

  Future<void> _flip(int delta) async {
    setState(() => _page = (_page + delta).clamp(0, 9999));
    if (_autoRefresh) {
      // Refresh AFTER the new frame is on screen.
      WidgetsBinding.instance.addPostFrameCallback((_) => _call('fullRefresh'));
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _paper,
      body: SafeArea(
        child: Column(
          children: [
            // ---- controls (kept compact) ----
            Container(
              color: const Color(0xFFE8E2D6),
              padding: const EdgeInsets.all(8),
              child: Column(
                children: [
                  Text(_status, style: const TextStyle(fontSize: 12, color: Colors.black)),
                  const SizedBox(height: 6),
                  Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      _btn('◀ Prev', () => _flip(-1)),
                      _btn('Next ▶', () => _flip(1)),
                      _btn('Full refresh', () => _call('fullRefresh')),
                      _btn('mode CLEAR', () => _call('setScreenMode', {'name': 'EINK_SCREEN_MODE_CLEAR'})),
                      _btn('mode SPEED', () => _call('setScreenMode', {'name': 'EINK_SCREEN_MODE_SPEED'})),
                      _btn('mode SMOOTH', () => _call('setScreenMode', {'name': 'EINK_SCREEN_MODE_SMOOTH'})),
                      _btn('mode DEFAULT', () => _call('setScreenMode', {'name': 'EINK_SCREEN_MODE_DEFAULT'})),
                      _btn('auto: ${_autoRefresh ? "ON" : "off"}',
                          () => setState(() => _autoRefresh = !_autoRefresh)),
                      _btn('stress: ${const ["text", "BLACK", "CHECKER"][_stress]}', () {
                        setState(() => _stress = (_stress + 1) % 3);
                        if (_autoRefresh) {
                          WidgetsBinding.instance
                              .addPostFrameCallback((_) => _call('fullRefresh'));
                        }
                      }),
                    ],
                  ),
                ],
              ),
            ),
            // ---- the "page" (text, or a high-contrast ghosting torture test) ----
            Expanded(
              child: switch (_stress) {
                1 => Container(color: Colors.black),
                2 => CustomPaint(painter: _CheckerPainter(), child: const SizedBox.expand()),
                _ => Container(
                    width: double.infinity,
                    padding: const EdgeInsets.all(20),
                    child: Text(
                      _pageText(_page),
                      style: const TextStyle(fontSize: 18, height: 1.5, color: Colors.black),
                    ),
                  ),
              },
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

/// High-contrast checkerboard — the harshest e-ink ghosting test. Flip
/// CHECKER → text and look for any residual grid.
class _CheckerPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    const cell = 64.0;
    final black = Paint()..color = Colors.black;
    final cols = (size.width / cell).ceil();
    final rows = (size.height / cell).ceil();
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        if ((r + c) % 2 == 0) {
          canvas.drawRect(Rect.fromLTWH(c * cell, r * cell, cell, cell), black);
        }
      }
    }
  }

  @override
  bool shouldRepaint(covariant _CheckerPainter oldDelegate) => false;
}
