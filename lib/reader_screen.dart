import 'package:flutter/material.dart';
import 'package:docx_to_text/docx_to_text.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:async';
import 'dart:io';

import 'models/annotation.dart';
import 'models/docx_store.dart';
import 'models/reading_position.dart';
import 'utils/annotation_utils.dart' show DocxFormatSpan;
import 'reader/appbar_pill.dart';
import 'reader/scroll_reader.dart';
import 'reader/screen_flip_reader.dart';
import 'reader/page_flip_reader.dart';
import 'reader/annotation_toolbar.dart';
import 'reader/annotation_panel.dart';
import 'reader/annotations_panel.dart';
import 'utils/platform_utils.dart';
import 'eink_settings_screen.dart';

class ReaderScreen extends StatefulWidget {
  final String filePath;

  const ReaderScreen({super.key, required this.filePath});

  @override
  State<ReaderScreen> createState() => _ReaderScreenState();
}

class _ReaderScreenState extends State<ReaderScreen> {
  late Future<({String text, List<DocxFormatSpan> spans, String? docTitle})> _fileContentFuture;
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
  final _jumpNotifier = ValueNotifier<double?>(null);
  final _cancelSelectionNotifier = ValueNotifier<int>(0);
  String? _emphasizedAnnotationId;
  Timer? _emphasisTimer;
  bool _twoColumnEnabled = true;
  DateTime _suppressToolbarUntil = DateTime.fromMillisecondsSinceEpoch(0);
  String _einkNavSide = 'both';

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
    _cancelSelectionNotifier.dispose();
    super.dispose();
  }

  ReadingMode _defaultMode() {
    if (isEink) return ReadingMode.pageFlip;
    return (Platform.isMacOS || Platform.isIOS) ? ReadingMode.screenFlip : ReadingMode.pageFlip;
  }

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
      if (isEink) {
        _readingMode = ReadingMode.pageFlip;
        _einkNavSide = prefs.getString('eink_nav_side') ?? 'both';
      } else if (!_modeSetByUser) {
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

  Future<void> _openEinkSettings() async {
    await Navigator.push(
      context,
      PageRouteBuilder(
        pageBuilder: (_, _, _) => const EinkSettingsScreen(),
        transitionDuration: Duration.zero,
        reverseTransitionDuration: Duration.zero,
      ),
    );
    if (!mounted) return;
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      _einkNavSide = prefs.getString('eink_nav_side') ?? 'both';
    });
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
    _positionSaveTimer?.cancel();
    _positionSaveTimer = Timer(
      const Duration(seconds: 1),
      () => _store.savePosition(position),
    );
  }

  Future<({String text, List<DocxFormatSpan> spans, String? docTitle})> _readFile() async {
    try {
      final bytes = await File(widget.filePath).readAsBytes();
      final text = docxToText(bytes);
      final spans = DocxStore.extractFormatSpans(bytes);
      final docTitle = DocxStore.extractTitle(bytes);
      return (
        text: text.isNotEmpty ? text : 'No text found in DOCX file.',
        spans: spans,
        docTitle: docTitle,
      );
    } catch (e) {
      return (text: 'Error reading file: $e', spans: const <DocxFormatSpan>[], docTitle: null);
    }
  }

  void _openAnnotationPanel({
    required String selectedText,
    required String prefix,
    required String suffix,
    required double fraction,
    AnnotationTool initialTool = AnnotationTool.highlight,
    Annotation? existing,
  }) {
    if (selectedText.trim().isEmpty) return;
    if (isEink) {
      Navigator.of(context).push(
        PageRouteBuilder(
          transitionDuration: Duration.zero,
          reverseTransitionDuration: Duration.zero,
          pageBuilder: (_, _, _) => Scaffold(
            body: AnnotationPanel(
              selectedText: selectedText,
              prefix: prefix,
              suffix: suffix,
              store: _store,
              initialTool: existing?.tool ?? initialTool,
              existing: existing,
              fraction: fraction,
              onSaved: _reloadAnnotations,
            ),
          ),
        ),
      );
      return;
    }
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
        fraction: fraction,
        onSaved: _reloadAnnotations,
      ),
    );
  }

  void _onToolSelected(
    AnnotationTool tool,
    String selectedText,
    String prefix,
    String suffix,
    double fraction,
  ) {
    if (tool == AnnotationTool.bookmark) {
      _saveImmediate(
        tool: AnnotationTool.bookmark,
        selectedText: selectedText,
        prefix: prefix,
        suffix: suffix,
        fraction: fraction,
      );
    } else if (tool == AnnotationTool.comment) {
      _openAnnotationPanel(
        selectedText: selectedText,
        prefix: prefix,
        suffix: suffix,
        fraction: fraction,
        initialTool: AnnotationTool.highlight,
      );
    } else {
      _saveImmediate(
        tool: tool,
        selectedText: selectedText,
        prefix: prefix,
        suffix: suffix,
        fraction: fraction,
      );
    }
  }

  Future<void> _saveImmediate({
    required AnnotationTool tool,
    required String selectedText,
    required String prefix,
    required String suffix,
    required double fraction,
  }) async {
    if (selectedText.trim().isEmpty) return;
    await _store.saveAnnotation(Annotation(
      id: newId(),
      selectedText: selectedText,
      prefix: prefix,
      suffix: suffix,
      tool: tool,
      timestamp: DateTime.now(),
      position: fraction,
    ));
    await _reloadAnnotations();
  }

  void _dismissToolbar() {
    _toolbarDebounce?.cancel();
    _toolbarOverlay?.remove();
    _toolbarOverlay = null;
    _suppressToolbarUntil = DateTime.now().add(const Duration(milliseconds: 600));
    _cancelSelectionNotifier.value++;
  }

  void _onSelection(String selectedText, String prefix, String suffix, Offset anchor, double fraction) {
    final suppressed = DateTime.now().isBefore(_suppressToolbarUntil);
    if (suppressed) return;

    if (selectedText.trim().isEmpty) {
      _toolbarDebounce?.cancel();
      _toolbarDebounce = null;
      _dismissToolbar();
      return;
    }

    if (_lockedTool != null) {
      _onToolSelected(_lockedTool!, selectedText, prefix, suffix, fraction);
      return;
    }

    _toolbarDebounce?.cancel();
    _toolbarDebounce = Timer(const Duration(milliseconds: 300), () {
      final suppressed2 = DateTime.now().isBefore(_suppressToolbarUntil);
      if (suppressed2) return;
      _showToolbarOverlay(anchor: anchor, selectedText: selectedText, prefix: prefix, suffix: suffix, fraction: fraction);
    });
  }

  void _showToolbarOverlay({
    required Offset anchor,
    required String selectedText,
    required String prefix,
    required String suffix,
    required double fraction,
  }) {
    _dismissToolbar();
    if (_toolbarOverlay != null) return;
    final anchors = TextSelectionToolbarAnchors(primaryAnchor: anchor);
    _toolbarOverlay = OverlayEntry(
      builder: (ctx) => AnnotationToolbar(
        anchors: anchors,
        onDismiss: _dismissToolbar,
        dismissOnTapOutside: _dismissToolbarOnTapOutside,
        onToolSelected: (tool) {
          _dismissToolbar();
          _onToolSelected(tool, selectedText, prefix, suffix, fraction);
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
          PageRouteBuilder(
            transitionDuration: Duration.zero,
            reverseTransitionDuration: Duration.zero,
            pageBuilder: (_, _, _) => Scaffold(
              body: AnnotationsPanel(
                store: _store,
                onJumpTo: (pos) {},
                onClose: () {
                  Navigator.pop(context);
                  setState(() => _showAnnotationsPanel = false);
                },
                onChanged: _reloadAnnotations,
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
                  onChanged: _reloadAnnotations,
                ),
              ),
            ),
          ),
        ],
      ),
    );
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

  // Shared trailing content: truncated title + AppBarPill, used in all modes.
  Widget _buildTrailingContent(String title) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 160),
          child: Text(
            title,
            style: const TextStyle(
              fontFamily: 'SourceSans3',
              fontSize: 13,
              color: Colors.black45,
            ),
            overflow: TextOverflow.ellipsis,
            maxLines: 1,
          ),
        ),
        const SizedBox(width: 8),
        AppBarPill(
          readingMode: _readingMode,
          lockedTool: _lockedTool,
          onClose: _confirmClose,
          onModeSelected: _setReadingMode,
          onUnlock: () => setState(() => _lockedTool = null),
          onAnnotations: _toggleAnnotationsPanel,
          twoColumnEnabled: _twoColumnEnabled,
          onToggleTwoColumn: _toggleTwoColumn,
          onEinkSettings: isEink ? _openEinkSettings : null,
        ),
        const SizedBox(width: 8),
      ],
    );
  }

  // Bottom bar for scroll / screen-flip modes (pageFlip uses PageFlipReader's
  // own counter zone via bottomTrailing).
  Widget _buildBottomBar(String title) {
    return SizedBox(
      height: 52,
      child: Stack(
        children: [
          Positioned(
            right: 0,
            top: 0,
            bottom: 0,
            child: Center(child: _buildTrailingContent(title)),
          ),
        ],
      ),
    );
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
        body: FutureBuilder<({String text, List<DocxFormatSpan> spans, String? docTitle})>(
          future: _fileContentFuture,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(child: CircularProgressIndicator());
            }
            if (snapshot.hasError) {
              return Center(child: Text('Error: ${snapshot.error}'));
            }
            final content = snapshot.data?.text ?? '';
            final formatSpans = snapshot.data?.spans ?? const <DocxFormatSpan>[];
            final rawName = widget.filePath.split(Platform.pathSeparator).last;
            final nameNoExt = rawName.endsWith('.docx')
                ? rawName.substring(0, rawName.length - 5)
                : rawName;
            final displayTitle = (snapshot.data?.docTitle?.isNotEmpty == true)
                ? snapshot.data!.docTitle!
                : nameNoExt;
            Widget reader;
            switch (_readingMode) {
              case ReadingMode.scroll:
                reader = Column(
                  children: [
                    Expanded(
                      child: ScrollReader(
                        content: content,
                        formatSpans: formatSpans,
                        annotations: _annotations,
                        savedPosition: _savedPosition,
                        onSelection: _onSelection,
                        onDismiss: _dismissToolbar,
                        onAnnotationTap: (a) => _openAnnotationPanel(
                          selectedText: a.selectedText,
                          prefix: a.prefix,
                          suffix: a.suffix,
                          fraction: a.position,
                          existing: a,
                        ),
                        onPositionChanged: _onPositionChanged,
                        jumpNotifier: _jumpNotifier,
                        emphasizedAnnotationId: _emphasizedAnnotationId,
                        cancelSelectionNotifier: _cancelSelectionNotifier,
                      ),
                    ),
                    _buildBottomBar(displayTitle),
                  ],
                );
              case ReadingMode.screenFlip:
                reader = Column(
                  children: [
                    Expanded(
                      child: ScreenFlipReader(
                        content: content,
                        formatSpans: formatSpans,
                        annotations: _annotations,
                        savedPosition: _savedPosition,
                        onSelection: _onSelection,
                        onAnnotationTap: (a) => _openAnnotationPanel(
                          selectedText: a.selectedText,
                          prefix: a.prefix,
                          suffix: a.suffix,
                          fraction: a.position,
                          existing: a,
                        ),
                        onPositionChanged: _onPositionChanged,
                        jumpNotifier: _jumpNotifier,
                        emphasizedAnnotationId: _emphasizedAnnotationId,
                        cancelSelectionNotifier: _cancelSelectionNotifier,
                      ),
                    ),
                    _buildBottomBar(displayTitle),
                  ],
                );
              case ReadingMode.pageFlip:
                reader = PageFlipReader(
                  content: content,
                  formatSpans: formatSpans,
                  annotations: _annotations,
                  savedPosition: _savedPosition,
                  onSelection: _onSelection,
                  onAnnotationTap: (a) => _openAnnotationPanel(
                    selectedText: a.selectedText,
                    prefix: a.prefix,
                    suffix: a.suffix,
                    fraction: a.position,
                    existing: a,
                  ),
                  onPositionChanged: _onPositionChanged,
                  jumpNotifier: _jumpNotifier,
                  emphasizedAnnotationId: _emphasizedAnnotationId,
                  twoColumn: _twoColumnEnabled,
                  cancelSelectionNotifier: _cancelSelectionNotifier,
                  einkNavSide: _einkNavSide,
                  bottomTrailing: _buildTrailingContent(displayTitle),
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
