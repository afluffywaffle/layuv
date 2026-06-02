import 'package:flutter/material.dart';
import 'package:docx_to_text/docx_to_text.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:async';
import 'dart:io';

import 'models/annotation.dart';
import 'models/reading_position.dart';
import 'models/annotation_store.dart';
import 'reader/appbar_pill.dart';
import 'reader/scroll_reader.dart';
import 'reader/screen_flip_reader.dart';
import 'reader/page_flip_reader.dart';
import 'reader/annotation_toolbar.dart';
import 'reader/annotation_panel.dart';

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
  late AnnotationStore _store;
  List<Annotation> _annotations = [];
  ReadingPosition? _savedPosition;
  Timer? _positionSaveTimer;
  AnnotationTool? _lockedTool;
  Timer? _toolbarDebounce;
  OverlayEntry? _toolbarOverlay;
  DateTime _lastSelectionTime = DateTime.fromMillisecondsSinceEpoch(0);

  @override
  void initState() {
    super.initState();
    _store = AnnotationStore(filePath: widget.filePath);
    _readingMode = _defaultMode();
    _fileContentFuture = _readFile();
    _loadPrefs();
  }

  @override
  void dispose() {
    _positionSaveTimer?.cancel();
    _toolbarDebounce?.cancel();
    _toolbarOverlay?.remove();
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

  Future<String> _readFile() async {
    try {
      final file = File(widget.filePath);
      final ext = widget.filePath.toLowerCase().split('.').last;
      if (ext == 'docx') {
        final bytes = await file.readAsBytes();
        final text = docxToText(bytes);
        return text.isNotEmpty ? text : 'No text found in DOCX file.';
      }
      return await file.readAsString();
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
    ));
    await _reloadAnnotations();
  }

  void _onSelection(String selectedText, String prefix, String suffix, Offset anchor) {
    if (selectedText.trim().isEmpty) {
      _dismissToolbar();
      return;
    }

    final now = DateTime.now();
    if (now.difference(_lastSelectionTime).inMilliseconds < 500) return;
    _lastSelectionTime = now;

    if (_lockedTool != null) {
      _onToolSelected(_lockedTool!, selectedText, prefix, suffix);
      return;
    }

    _showToolbarOverlay(
      anchor: anchor,
      selectedText: selectedText,
      prefix: prefix,
      suffix: suffix,
    );
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
            switch (_readingMode) {
              case ReadingMode.scroll:
                return ScrollReader(
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
                );
              case ReadingMode.screenFlip:
                return ScreenFlipReader(
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
                );
              case ReadingMode.pageFlip:
                return PageFlipReader(
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
                );
            }
          },
        ),
      ),
    );
  }
}
