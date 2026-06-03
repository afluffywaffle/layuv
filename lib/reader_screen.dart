import 'package:flutter/material.dart';
import 'package:docx_to_text/docx_to_text.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:async';
import 'dart:io';

import 'models/annotation.dart';
import 'models/reading_position.dart';
import 'models/docx_store.dart';
import 'reader/appbar_pill.dart';
import 'reader/scroll_reader.dart';
import 'reader/screen_flip_reader.dart';
import 'reader/page_flip_reader.dart';
import 'reader/annotation_toolbar.dart';
import 'reader/annotation_panel.dart';
import 'reader/annotations_panel.dart';
import 'utils/platform_utils.dart';

class ReaderScreen extends StatefulWidget {
  final String filePath;

  const ReaderScreen({super.key, required this.filePath});

  @override
  State<ReaderScreen> createState() => _ReaderScreenState();
}

class _ReaderScreenState extends State<ReaderScreen> {
  late Future<String> _fileContentFuture;
  late ReadingMode _readingMode;
  bool _modeSetByUser = false;
  late DocxStore _store;
  List<Annotation> _annotations = [];
  ReadingPosition? _savedPosition;
  Timer? _positionSaveTimer;
  AnnotationTool? _lockedTool;
  Timer? _toolbarDebounce;
  OverlayEntry? _toolbarOverlay;
  bool _dismissToolbarOnTapOutside = true;
  bool _showAnnotationsPanel = false;
  double _panelWidth = 320.0;
  double _currentFraction = 0.0;
  final _jumpNotifier = ValueNotifier<double?>(null);
  String? _emphasizedAnnotationId;
  Timer? _emphasisTimer;
  bool _twoColumnEnabled = true;

  @override
  void initState() {
    super.initState();
    _store = DocxStore(filePath: widget.filePath);
    _readingMode = _defaultMode();
    _fileContentFuture = _readFile();
    _loadPrefs();
  }

  @override
  void dispose() {
    _positionSaveTimer?.cancel();
    _toolbarDebounce?.cancel();
    _emphasisTimer?.cancel();
    _toolbarOverlay?.remove();
    _jumpNotifier.dispose();
    super.dispose();
  }

  ReadingMode _defaultMode() =>
      (Platform.isMacOS || Platform.isIOS) ? ReadingMode.screenFlip : ReadingMode.pageFlip;

  Future<void> _loadPrefs() async {
    final prefs = await SharedPreferences.getInstance();
    final annotations = await _store.loadAnnotations();
    final position = await _store.loadPosition();
    if (!mounted) return;
    setState(() {
      _annotations = annotations;
      _savedPosition = position;
      _dismissToolbarOnTapOutside = prefs.getBool('dismissToolbarOnTapOutside') ?? true;
      _twoColumnEnabled = prefs.getBool('two_column_enabled') ?? true;
      if (!_modeSetByUser) {
        final saved = prefs.getString('reading_mode');
        if (saved != null) {
          _readingMode = ReadingMode.values.byName(saved);
        } else {
          prefs.setString('reading_mode', _readingMode.name);
        }
      }
    });
  }

  Future<void> _reloadAnnotations() async {
    final annotations = await _store.loadAnnotations();
    if (mounted) setState(() => _annotations = annotations);
  }

  void _toggleTwoColumn() {
    setState(() => _twoColumnEnabled = !_twoColumnEnabled);
    SharedPreferences.getInstance()
        .then((p) => p.setBool('two_column_enabled', _twoColumnEnabled));
  }

  void _setReadingMode(ReadingMode mode) {
    setState(() {
      _readingMode = mode;
      _modeSetByUser = true;
    });
    SharedPreferences.getInstance()
        .then((p) => p.setString('reading_mode', mode.name));
  }

  void _onPositionChanged(ReadingPosition position) {
    _currentFraction = position.fraction;
    _positionSaveTimer?.cancel();
    _positionSaveTimer = Timer(
      const Duration(seconds: 1),
      () => _store.savePosition(position),
    );
  }

  Future<String> _readFile() async {
    try {
      final bytes = await File(widget.filePath).readAsBytes();
      final text = docxToText(bytes);
      return text.isNotEmpty ? text : 'No text found in DOCX file.';
    } catch (e) {
      return 'Error reading file: $e';
    }
  }

  void _openAnnotationPanel({
    required String selectedText,
    required String prefix,
    required String suffix,
    AnnotationTool initialTool = AnnotationTool.highlight,
    Annotation? existing,
  }) {
    if (selectedText.trim().isEmpty) return;
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      isDismissible: false,
      backgroundColor: Colors.transparent,
      builder: (_) => AnnotationPanel(
        selectedText: selectedText,
        prefix: prefix,
        suffix: suffix,
        store: _store,
        initialTool: existing?.tool ?? initialTool,
        existing: existing,
        onSaved: _reloadAnnotations,
      ),
    );
  }

  void _onToolSelected(
    AnnotationTool tool,
    String selectedText,
    String prefix,
    String suffix,
  ) {
    if (tool == AnnotationTool.bookmark) {
      _saveImmediate(
        tool: AnnotationTool.bookmark,
        selectedText: selectedText,
        prefix: prefix,
        suffix: suffix,
      );
    } else if (tool == AnnotationTool.comment) {
      _openAnnotationPanel(
        selectedText: selectedText,
        prefix: prefix,
        suffix: suffix,
        initialTool: AnnotationTool.highlight,
      );
    } else {
      _saveImmediate(
        tool: tool,
        selectedText: selectedText,
        prefix: prefix,
        suffix: suffix,
      );
    }
  }

  Future<void> _saveImmediate({
    required AnnotationTool tool,
    required String selectedText,
    required String prefix,
    required String suffix,
  }) async {
    if (selectedText.trim().isEmpty) return;
    await _store.saveAnnotation(Annotation(
      id: newId(),
      selectedText: selectedText,
      prefix: prefix,
      suffix: suffix,
      tool: tool,
      timestamp: DateTime.now(),
      position: _currentFraction,
    ));
    await _reloadAnnotations();
  }

  void _onSelection(String selectedText, String prefix, String suffix, Offset anchor) {
    _toolbarDebounce?.cancel();
    if (selectedText.trim().isEmpty) {
      _dismissToolbar();
      return;
    }

    if (_lockedTool != null) {
      _onToolSelected(_lockedTool!, selectedText, prefix, suffix);
      return;
    }

    _toolbarDebounce = Timer(const Duration(milliseconds: 300), () {
      _showToolbarOverlay(
        anchor: anchor,
        selectedText: selectedText,
        prefix: prefix,
        suffix: suffix,
      );
    });
  }

  void _showToolbarOverlay({
    required Offset anchor,
    required String selectedText,
    required String prefix,
    required String suffix,
  }) {
    if (_toolbarOverlay != null) return;
    _dismissToolbar();
    final anchors = TextSelectionToolbarAnchors(primaryAnchor: anchor);
    _toolbarOverlay = OverlayEntry(
      builder: (ctx) => AnnotationToolbar(
        anchors: anchors,
        onDismiss: _dismissToolbar,
        dismissOnTapOutside: _dismissToolbarOnTapOutside,
        onToolSelected: (tool) {
          _dismissToolbar();
          _onToolSelected(tool, selectedText, prefix, suffix);
        },
        onLockTool: (tool) {
          _dismissToolbar();
          setState(() => _lockedTool = tool);
        },
      ),
    );
    Overlay.of(context).insert(_toolbarOverlay!);
  }

  void _toggleAnnotationsPanel() {
    setState(() => _showAnnotationsPanel = !_showAnnotationsPanel);
  }

  Widget _buildAnnotationsPanel() {
    if (isEink) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        Navigator.push(
          context,
          MaterialPageRoute(
            builder: (_) => Scaffold(
              body: AnnotationsPanel(
                store: _store,
                onJumpTo: (pos) {},
                onClose: () {
                  Navigator.pop(context);
                  setState(() => _showAnnotationsPanel = false);
                },
              ),
            ),
          ),
        ).then((_) => setState(() => _showAnnotationsPanel = false));
      });
      return const SizedBox.shrink();
    }

    return Positioned(
      top: 16,
      right: 16,
      bottom: 16,
      width: _panelWidth,
      child: Row(
        children: [
          GestureDetector(
            behavior: HitTestBehavior.translucent,
            onHorizontalDragUpdate: (details) {
              setState(() {
                _panelWidth = (_panelWidth - details.delta.dx).clamp(240.0, 800.0);
              });
            },
            child: MouseRegion(
              cursor: SystemMouseCursors.resizeLeftRight,
              child: SizedBox(
                width: 8,
                height: double.infinity,
                child: Center(
                  child: Container(
                    width: 2,
                    height: 32,
                    decoration: BoxDecoration(
                      color: Colors.black12,
                      borderRadius: BorderRadius.circular(1),
                    ),
                  ),
                ),
              ),
            ),
          ),
          Expanded(
            child: DecoratedBox(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(16),
                boxShadow: [
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.12),
                    blurRadius: 24,
                    offset: const Offset(0, 4),
                  ),
                  BoxShadow(
                    color: Colors.black.withValues(alpha: 0.06),
                    blurRadius: 6,
                    offset: const Offset(0, 1),
                  ),
                ],
              ),
              child: Material(
                elevation: 0,
                color: const Color(0xFFF5F0E8),
                borderRadius: BorderRadius.circular(16),
                clipBehavior: Clip.antiAlias,
                child: AnnotationsPanel(
                  store: _store,
                  onJumpTo: (pos) {
                    _jumpNotifier.value = pos;
                    Future.microtask(() => _jumpNotifier.value = null);
                    final ann = _annotations
                        .where((a) => (a.position - pos).abs() < 0.001)
                        .firstOrNull;
                    if (ann != null) {
                      _emphasisTimer?.cancel();
                      setState(() => _emphasizedAnnotationId = ann.id);
                      _emphasisTimer = Timer(
                        const Duration(seconds: 3),
                        () => setState(() => _emphasizedAnnotationId = null),
                      );
                    }
                  },
                  onClose: _toggleAnnotationsPanel,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  void _dismissToolbar() {
    _toolbarDebounce?.cancel();
    _toolbarOverlay?.remove();
    _toolbarOverlay = null;
  }

  Future<void> _confirmClose() async {
    final shouldPop = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFFF5F0E8),
        title: const Text('Close document?',
            style: TextStyle(fontFamily: 'Literata')),
        content: const Text('Your annotations are saved.',
            style: TextStyle(fontFamily: 'Literata', color: Colors.black54)),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Stay',
                style: TextStyle(fontFamily: 'Literata', color: Colors.black54)),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Close',
                style: TextStyle(fontFamily: 'Literata', color: Colors.black87)),
          ),
        ],
      ),
    );
    if (shouldPop == true && mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: false,
      onPopInvokedWithResult: (didPop, _) async {
        if (didPop) return;
        await _confirmClose();
      },
      child: Scaffold(
        backgroundColor: const Color(0xFFF5F0E8),
        appBar: AppBar(
          backgroundColor: const Color(0xFFF5F0E8),
          elevation: 0,
          automaticallyImplyLeading: false,
          actions: [
            Padding(
              padding: const EdgeInsets.only(right: 16, top: 8, bottom: 8),
              child: AppBarPill(
                readingMode: _readingMode,
                lockedTool: _lockedTool,
                onClose: _confirmClose,
                onModeSelected: _setReadingMode,
                onUnlock: () => setState(() => _lockedTool = null),
                onAnnotations: _toggleAnnotationsPanel,
                twoColumnEnabled: _twoColumnEnabled,
                onToggleTwoColumn: _toggleTwoColumn,
              ),
            ),
          ],
        ),
        body: FutureBuilder<String>(
          future: _fileContentFuture,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return Center(child: Text('Error: ${snapshot.error}'));
            }
            final content = snapshot.data ?? '';
            Widget reader;
            switch (_readingMode) {
              case ReadingMode.scroll:
                reader = ScrollReader(
                  content: content,
                  annotations: _annotations,
                  savedPosition: _savedPosition,
                  onSelection: _onSelection,
                  onDismiss: _dismissToolbar,
                  onAnnotationTap: (a) => _openAnnotationPanel(
                    selectedText: a.selectedText,
                    prefix: a.prefix,
                    suffix: a.suffix,
                    existing: a,
                  ),
                  onPositionChanged: _onPositionChanged,
                  jumpNotifier: _jumpNotifier,
                  emphasizedAnnotationId: _emphasizedAnnotationId,
                );
              case ReadingMode.screenFlip:
                reader = ScreenFlipReader(
                  content: content,
                  annotations: _annotations,
                  savedPosition: _savedPosition,
                  onSelection: _onSelection,
                  onAnnotationTap: (a) => _openAnnotationPanel(
                    selectedText: a.selectedText,
                    prefix: a.prefix,
                    suffix: a.suffix,
                    existing: a,
                  ),
                  onPositionChanged: _onPositionChanged,
                  jumpNotifier: _jumpNotifier,
                  emphasizedAnnotationId: _emphasizedAnnotationId,
                );
              case ReadingMode.pageFlip:
                reader = PageFlipReader(
                  content: content,
                  annotations: _annotations,
                  savedPosition: _savedPosition,
                  onSelection: _onSelection,
                  onAnnotationTap: (a) => _openAnnotationPanel(
                    selectedText: a.selectedText,
                    prefix: a.prefix,
                    suffix: a.suffix,
                    existing: a,
                  ),
                  onPositionChanged: _onPositionChanged,
                  jumpNotifier: _jumpNotifier,
                  emphasizedAnnotationId: _emphasizedAnnotationId,
                  twoColumn: _twoColumnEnabled,
                );
            }
            return Stack(
              children: [
                reader,
                if (_showAnnotationsPanel) _buildAnnotationsPanel(),
              ],
            );
          },
        ),
      ),
    );
  }
}
