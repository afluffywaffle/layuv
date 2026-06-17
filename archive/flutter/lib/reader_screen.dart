import 'package:flutter/material.dart';
import 'package:docx_to_text/docx_to_text.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:async';
import 'dart:io';

import 'models/annotation.dart';
import 'models/docx_store.dart';
import 'models/reading_position.dart';
import 'utils/annotation_utils.dart' show DocxFormatSpan;
import 'utils/eink_pen.dart';
import 'reader/appbar_pill.dart';
import 'reader/scroll_reader.dart';
import 'reader/screen_flip_reader.dart';
import 'reader/page_flip_reader.dart';
import 'reader/reader_view.dart';
import 'reader/annotation_toolbar.dart';
import 'reader/annotation_panel.dart';
import 'reader/annotations_panel.dart';
import 'reader/reader_dialogs.dart';
import 'reader/reader_chrome.dart';
import 'utils/platform_utils.dart';
import 'eink_settings_screen.dart';

class ReaderScreen extends StatefulWidget {
  final String filePath;

  const ReaderScreen({super.key, required this.filePath});

  @override
  State<ReaderScreen> createState() => _ReaderScreenState();
}

class _ReaderScreenState extends State<ReaderScreen> with WidgetsBindingObserver {
  late Future<({String text, List<DocxFormatSpan> spans, String? docTitle})> _fileContentFuture;
  late ReadingMode _readingMode;
  late DocxStore _store;
  List<Annotation> _annotations = [];
  ReadingPosition? _savedPosition;
  Timer? _positionSaveTimer;
  AnnotationTool? _lockedTool;
  String? _lastAnnotationId;
  Timer? _saveDebounce;
  bool _saveDirty = false;
  Timer? _toolbarDebounce;
  OverlayEntry? _toolbarOverlay;
  OverlayEntry? _undoOverlay;
  OverlayEntry? _actionsOverlay;
  bool _dismissToolbarOnTapOutside = true;
  bool _showAnnotationsPanel = false;
  bool _annotationsRouteOpen = false; // e-ink: guards against double-pushing the panel route
  double _panelWidth = 320.0;
  final _jumpNotifier = ValueNotifier<double?>(null);
  final _cancelSelectionNotifier = ValueNotifier<int>(0);
  String? _emphasizedAnnotationId;
  Timer? _emphasisTimer;
  bool _twoColumnEnabled = true;
  String _einkNavSide = 'both';
  bool _einkNavReversed = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _store = DocxStore(filePath: widget.filePath);
    _readingMode = _defaultMode();
    _fileContentFuture = _readFile();
    _loadPrefs();
  }

  @override
  void reassemble() {
    super.reassemble();
    // Hot reload repaints Flutter's buffer but the e-ink panel won't refresh on
    // its own, so a code-only change (e.g. how a highlight draws) looks stale
    // until the next interaction. Force a clean frame so reloads are visible.
    // Debug-only: reassemble() is never called in a release build.
    _refreshEinkAfterFrame();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _positionSaveTimer?.cancel();
    _toolbarDebounce?.cancel();
    _emphasisTimer?.cancel();
    _saveDebounce?.cancel();
    // Best-effort persist of any pending coalesced save. Fire-and-forget: the
    // store outlives this widget, so the write still completes after dispose.
    if (_saveDirty) {
      _store.saveAll(List.of(_annotations));
    }
    _toolbarOverlay?.remove();
    _undoOverlay?.remove();
    _actionsOverlay?.remove();
    _jumpNotifier.dispose();
    _cancelSelectionNotifier.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    super.didChangeAppLifecycleState(state);
    // Persist pending annotation changes before the OS can suspend or kill us
    // while backgrounded — this closes the debounce-window data-loss gap.
    if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.hidden ||
        state == AppLifecycleState.detached) {
      _flushPendingSaves();
    }
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
      final savedLockedTool = prefs.getString('locked_tool');
      if (savedLockedTool != null) {
        try {
          _lockedTool = AnnotationTool.values.byName(savedLockedTool);
        } catch (_) {}
      }
      if (isEink) {
        _readingMode = ReadingMode.pageFlip;
        _einkNavSide = prefs.getString('eink_nav_side') ?? 'both';
        _einkNavReversed = prefs.getBool('eink_nav_reversed') ?? false;
      } else {
        // _loadPrefs runs once from initState before any user interaction, so a
        // saved reading_mode always wins here; the user's later choice is
        // persisted by _setReadingMode and reloaded on next open.
        final saved = prefs.getString('reading_mode');
        if (saved != null) {
          _readingMode = ReadingMode.values.byName(saved);
        } else {
          prefs.setString('reading_mode', _readingMode.name);
        }
      }
    });
  }

  // Flush a clean full e-ink frame after a non-touch mutation (apply/undo/
  // delete/reload/actions toolbar). A bare invalidate() does not refresh the
  // panel on e-ink; EinkPen.refresh() is itself a no-op off e-ink, so the
  // isEink gate keeps it cheap. Centralized to avoid copy-paste drift.
  void _refreshEinkAfterFrame() {
    if (isEink) {
      WidgetsBinding.instance.addPostFrameCallback((_) => EinkPen.refresh());
    }
  }

  Future<void> _reloadAnnotations() async {
    final annotations = await _store.loadAnnotations();
    if (!mounted) return;
    final shouldClosePanel = annotations.isEmpty && _showAnnotationsPanel;
    setState(() {
      _annotations = annotations;
      if (shouldClosePanel) _showAnnotationsPanel = false;
    });
    // On e-ink the annotations panel is a Navigator route (full-screen push).
    // Setting _showAnnotationsPanel = false only removes the SizedBox.shrink()
    // placeholder — we also need to pop the route so the panel actually closes.
    if (shouldClosePanel && isEink && mounted) {
      Navigator.of(context).maybePop();
    }
    // A pen circle/scribble applies an annotation without issuing a touch event,
    // so the e-ink panel won't repaint it on its own. Flush a clean full frame
    // once the new annotation has painted. (Touch-driven applies already refresh
    // via the OS; the extra flush is a harmless idempotent frame send.)
    _refreshEinkAfterFrame();
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
      _einkNavReversed = prefs.getBool('eink_nav_reversed') ?? false;
    });
  }

  void _setReadingMode(ReadingMode mode) {
    setState(() => _readingMode = mode);
    SharedPreferences.getInstance()
        .then((p) => p.setString('reading_mode', mode.name));
  }

  void _onPositionChanged(ReadingPosition position) {
    _positionSaveTimer?.cancel();
    _positionSaveTimer = Timer(
      const Duration(seconds: 1),
      () => _store.savePosition(position),
    );
    // Dismiss any stale toolbar overlay on page/jump navigation; _lockedTool is preserved.
    if (_toolbarOverlay != null) _dismissToolbar();
    _dismissUndoToolbar();
    _dismissActionsToolbar();
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
    // The panel writes the store directly then reloads from disk; flush any
    // pending coalesced save first so un-flushed annotations aren't clobbered.
    _flushPendingSaves();
    final panel = AnnotationPanel(
      selectedText: selectedText,
      prefix: prefix,
      suffix: suffix,
      store: _store,
      initialTool: existing?.tool ?? initialTool,
      existing: existing,
      fraction: fraction,
      onSaved: _reloadAnnotations,
    );
    // e-ink: full-screen push route (no sheet). Desktop: modal bottom sheet.
    if (isEink) {
      Navigator.of(context).push(
        PageRouteBuilder(
          transitionDuration: Duration.zero,
          reverseTransitionDuration: Duration.zero,
          pageBuilder: (_, _, _) => Scaffold(body: panel),
        ),
      );
      return;
    }
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      isDismissible: false,
      backgroundColor: Colors.transparent,
      builder: (_) => panel,
    );
  }

  void _onToolSelected(
    AnnotationTool tool,
    String selectedText,
    String prefix,
    String suffix,
    double fraction,
    Offset anchor,
  ) {
    if (tool == AnnotationTool.bookmark) {
      _saveImmediate(
        tool: AnnotationTool.bookmark,
        selectedText: selectedText,
        prefix: prefix,
        suffix: suffix,
        fraction: fraction,
        anchor: anchor,
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
        anchor: anchor,
      );
    }
  }

  void _saveImmediate({
    required AnnotationTool tool,
    required String selectedText,
    required String prefix,
    required String suffix,
    required double fraction,
    required Offset anchor,
  }) {
    if (selectedText.trim().isEmpty) return;
    final ann = Annotation(
      id: newId(),
      selectedText: selectedText,
      prefix: prefix,
      suffix: suffix,
      tool: tool,
      timestamp: DateTime.now(),
      position: fraction,
    );
    // Show the annotation immediately without waiting for disk I/O.
    final t0 = DateTime.now().microsecondsSinceEpoch;
    setState(() {
      _annotations = [..._annotations, ann];
      _lastAnnotationId = ann.id;
    });
    // Pop up the undo affordance over the just-applied annotation (same anchor
    // the selection toolbar used). Inserted synchronously so the e-ink refresh
    // below captures the annotation AND the undo pill in one frame.
    _showUndoToolbar(anchor);
    if (isEink) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        final dt = (DateTime.now().microsecondsSinceEpoch - t0) / 1000.0;
        debugPrint('ANNOT optimistic-paint=${dt.toStringAsFixed(1)}ms');
        EinkPen.refresh();
      });
    }
    // Persistence is debounced: a full DOCX encode costs seconds and would
    // freeze the UI on every annotation. The in-memory list is authoritative,
    // so a rapid burst coalesces into ONE write after the user pauses.
    _scheduleSave();
  }

  // Debounced/coalesced disk persistence of the authoritative in-memory list.
  // Each individual write is a full DOCX re-encode (2.7–6s), so we never write
  // per annotation; we write once ~1.5s after the last change.
  void _scheduleSave() {
    _saveDirty = true;
    _saveDebounce?.cancel();
    _saveDebounce = Timer(const Duration(milliseconds: 1500), _flushSave);
  }

  Future<void> _flushSave() async {
    _saveDebounce?.cancel();
    _saveDebounce = null;
    if (!_saveDirty) return;
    // Clear up-front: a change arriving during the (awaited) write re-dirties
    // and re-arms via _scheduleSave, so the trailing change is never dropped.
    _saveDirty = false;
    final snapshot = List<Annotation>.of(_annotations);
    final t1 = DateTime.now().microsecondsSinceEpoch;
    await _store.saveAll(snapshot);
    if (!mounted) return;
    debugPrint('ANNOT flush=${((DateTime.now().microsecondsSinceEpoch - t1) / 1000.0).toStringAsFixed(1)}ms (${snapshot.length} annotations)');
  }

  // Force an immediate flush of any unpersisted change. Called before any OTHER
  // write path runs (the comment/edit panel, which writes the store directly
  // and reloads from disk) so un-flushed annotations are never lost, before the
  // document closes, and when the app is backgrounded. Gated on _saveDirty so a
  // read-only close stays instant (no needless full-DOCX encode).
  Future<void> _flushPendingSaves() async {
    if (_saveDirty) await _flushSave();
  }

  // [cancelSelection] true (genuine dismiss: tap-outside, tool picked, locked,
  // empty selection) clears the reader's selection via the notifier. The
  // defensive call at the top of _showToolbarOverlay passes false so SHOWING a
  // toolbar doesn't wipe the very selection it's for (the dart:ui ReaderView
  // treats the notifier as "clear selection"; the SelectableText readers only
  // cancel a debounce, so false is a no-op for them).
  void _dismissToolbar({bool cancelSelection = true}) {
    _toolbarDebounce?.cancel();
    _toolbarOverlay?.remove();
    _toolbarOverlay = null;
    // Clear any drawPath lasso strokes drawn during a circle-over-toolbar gesture.
    if (isEink) EinkPen.clearInk();
    if (cancelSelection) _cancelSelectionNotifier.value++;
  }

  void _onSelection(String selectedText, String prefix, String suffix, Offset anchor, double fraction) {
    // Any new selection (the start of another annotation) tears down a lingering
    // undo pill or annotation-actions toolbar, mirroring how the selection
    // toolbar is replaced.
    _dismissUndoToolbar();
    _dismissActionsToolbar();
    if (selectedText.trim().isEmpty) {
      _toolbarDebounce?.cancel();
      _toolbarDebounce = null;
      _dismissToolbar();
      return;
    }

    if (_lockedTool != null) {
      _toolbarDebounce?.cancel();
      _onToolSelected(_lockedTool!, selectedText, prefix, suffix, fraction, anchor);
      return;
    }

    _toolbarDebounce?.cancel();
    void showToolbar() {
      _showToolbarOverlay(anchor: anchor, selectedText: selectedText, prefix: prefix, suffix: suffix, fraction: fraction);
    }
    // ReaderView (e-ink) commits the selection once, on lift — nothing to
    // debounce, so show the toolbar in the SAME frame as the committed band
    // (one EPD refresh instead of band-then-toolbar). The SelectableText readers
    // fire a rapid onSelection stream, so they keep a short coalescing debounce.
    if (isEink) {
      showToolbar();
    } else {
      _toolbarDebounce = Timer(const Duration(milliseconds: 120), showToolbar);
    }
  }

  void _showToolbarOverlay({
    required Offset anchor,
    required String selectedText,
    required String prefix,
    required String suffix,
    required double fraction,
  }) {
    _dismissToolbar(cancelSelection: false); // keep the selection we're showing for
    if (_toolbarOverlay != null) return;
    final anchors = TextSelectionToolbarAnchors(primaryAnchor: anchor);
    _toolbarOverlay = OverlayEntry(
      builder: (ctx) => AnnotationToolbar(
        anchors: anchors,
        onDismiss: _dismissToolbar,
        dismissOnTapOutside: _dismissToolbarOnTapOutside,
        // Always translucent so a new pen-drag reaches the reader and starts a
        // new selection (which auto-dismisses this toolbar via onSelectionStart).
        passThrough: true,
        onToolSelected: (tool) {
          _dismissToolbar();
          _onToolSelected(tool, selectedText, prefix, suffix, fraction, anchor);
        },
        onLockTool: (tool) {
          _dismissToolbar();
          setState(() => _lockedTool = tool);
          SharedPreferences.getInstance().then((p) => p.setString('locked_tool', tool.name));
        },
      ),
    );
    Overlay.of(context).insert(_toolbarOverlay!);
  }

  // The undo affordance pops up right after an annotation is applied (same
  // overlay lifecycle as the selection toolbar, but triggered on annotation-made
  // instead of text-selected). It is dismissed as soon as the next selection
  // begins, on navigation, or on tap-outside.
  void _showUndoToolbar(Offset anchor) {
    _dismissUndoToolbar();
    if (_lastAnnotationId == null) return;
    _undoOverlay = OverlayEntry(
      builder: (_) => UndoToolbar(
        anchor: anchor,
        onUndo: _undoLastAnnotation,
      ),
    );
    Overlay.of(context).insert(_undoOverlay!);
  }

  void _dismissUndoToolbar() {
    _undoOverlay?.remove();
    _undoOverlay = null;
  }

  // Remove the most recently added annotation (highlight/underline/etc. and
  // bookmarks — the immediate-save tools). Comment annotations go through the
  // panel and aren't tracked here. Optimistic UI: drop it locally, persist via
  // the same coalesced flush (saveAll writes the now-smaller list), then flush a
  // clean e-ink frame. If the annotation was never flushed to disk, undoing it
  // costs no disk write at all.
  void _undoLastAnnotation() {
    final id = _lastAnnotationId;
    if (id == null) return;
    setState(() {
      _annotations = _annotations.where((a) => a.id != id).toList();
      _lastAnnotationId = null;
    });
    _scheduleSave();
    _dismissUndoToolbar();
    _refreshEinkAfterFrame();
  }

  // ── Annotation actions (tapping an existing annotation) ──────────────────
  String get _deleteConfirmSkipKey => 'delete_confirm_skip:${widget.filePath}';

  // Tapping an annotation opens a small Comment/Delete toolbar anchored at the
  // tap, instead of jumping straight into the edit panel.
  void _showAnnotationActionsToolbar(Annotation a, Offset anchor) {
    _dismissToolbar(cancelSelection: false); // any stale selection toolbar
    _dismissUndoToolbar();
    _dismissActionsToolbar();
    _actionsOverlay = OverlayEntry(
      builder: (_) => AnnotationActionToolbar(
        anchor: anchor,
        onComment: () {
          _dismissActionsToolbar();
          _openAnnotationPanel(
            selectedText: a.selectedText,
            prefix: a.prefix,
            suffix: a.suffix,
            fraction: a.position,
            existing: a,
          );
        },
        onDelete: () {
          _dismissActionsToolbar();
          _confirmDeleteAnnotation(a);
        },
        onDismiss: _dismissActionsToolbar,
      ),
    );
    Overlay.of(context).insert(_actionsOverlay!);
    _refreshEinkAfterFrame();
  }

  void _dismissActionsToolbar() {
    _actionsOverlay?.remove();
    _actionsOverlay = null;
  }

  // Delete is guarded by a confirmation (it is NOT covered by the add-undo
  // pill). The dialog offers a per-document "don't ask again" so frequent
  // editors aren't nagged.
  Future<void> _confirmDeleteAnnotation(Annotation a) async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    if (prefs.getBool(_deleteConfirmSkipKey) ?? false) {
      _deleteAnnotation(a);
      return;
    }
    final dontAskAgain = await showDialog<bool>(
      context: context,
      builder: (_) => const DeleteAnnotationDialog(),
    );
    if (dontAskAgain == null || !mounted) return;
    if (dontAskAgain) {
      final p = await SharedPreferences.getInstance();
      await p.setBool(_deleteConfirmSkipKey, true);
      if (!mounted) return;
    }
    _deleteAnnotation(a);
  }

  // Optimistic delete: drop from the authoritative in-memory list and persist
  // via the coalesced flush (saveAll writes the smaller set).
  void _deleteAnnotation(Annotation a) {
    setState(() {
      _annotations = _annotations.where((x) => x.id != a.id).toList();
      if (_lastAnnotationId == a.id) _lastAnnotationId = null;
    });
    _scheduleSave();
    _refreshEinkAfterFrame();
  }

  void _toggleAnnotationsPanel() {
    // Opening the list panel: flush any pending coalesced in-memory save FIRST,
    // exactly as _openAnnotationPanel does before the single-annotation panel
    // (see the barrier at the top of _openAnnotationPanel). The AnnotationsPanel
    // reads from disk and writes the store directly (delete/edit), so a still-
    // armed debounced saveAll could later snapshot a stale _annotations list and
    // full-replace the file, resurrecting a panel-deleted annotation. Flushing
    // here cancels that debounce and commits the current list before the panel
    // can touch disk. Fire-and-forget is sufficient: _flushSave cancels the
    // timer and captures the snapshot synchronously before its first await.
    if (!_showAnnotationsPanel) _flushPendingSaves();
    setState(() => _showAnnotationsPanel = !_showAnnotationsPanel);
  }

  Widget _buildAnnotationsPanel() {
    if (isEink) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        // build() re-invokes this on every rebuild while the panel is open (e.g.
        // an in-panel delete -> _reloadAnnotations setState), so guard the push
        // to fire exactly once. _annotationsRouteOpen is cleared when the route
        // pops, so reopening later works.
        if (!mounted || !_showAnnotationsPanel || _annotationsRouteOpen) return;
        _annotationsRouteOpen = true;
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
        ).then((_) {
          _annotationsRouteOpen = false;
          if (mounted) setState(() => _showAnnotationsPanel = false);
        });
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
                        () {
                          if (!mounted) return;
                          setState(() => _emphasizedAnnotationId = null);
                        },
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
    // Ask first — the dialog appears instantly (no pre-flush freeze).
    final shouldPop = await showDialog<bool>(
      context: context,
      builder: (_) => const CloseDocumentDialog(),
    );
    if (shouldPop != true || !mounted) return;

    // Only now that the user committed to closing do we persist pending changes.
    // The encode runs off the UI isolate (Phase 2), so we can show a live
    // "Saving…" indicator instead of freezing. Skip it entirely when clean.
    if (_saveDirty) {
      final savingClosed = showDialog<void>(
        context: context,
        barrierDismissible: false,
        builder: (_) => const SavingDialog(),
      );
      try {
        await _flushPendingSaves();
      } finally {
        if (mounted) Navigator.of(context, rootNavigator: true).pop();
        await savingClosed;
      }
      if (!mounted) return;
    }
    Navigator.of(context).pop();
  }

  // AppBarPill cluster shown on the RIGHT of the bottom bar.
  Widget _buildAppBarPill() {
    return AppBarPill(
      readingMode: _readingMode,
      lockedTool: _lockedTool,
      onClose: _confirmClose,
      onModeSelected: _setReadingMode,
      onUnlock: () {
        setState(() => _lockedTool = null);
        SharedPreferences.getInstance().then((p) => p.remove('locked_tool'));
      },
      onAnnotations: _toggleAnnotationsPanel,
      onUndo: _undoLastAnnotation,
      canUndo: _lastAnnotationId != null,
      hasAnnotations: _annotations.isNotEmpty,
      twoColumnEnabled: _twoColumnEnabled,
      onToggleTwoColumn: _toggleTwoColumn,
      onEinkSettings: isEink ? _openEinkSettings : null,
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
                        onAnnotationTap: (a, anchor) =>
                            _showAnnotationActionsToolbar(a, anchor),
                        onPositionChanged: _onPositionChanged,
                        jumpNotifier: _jumpNotifier,
                        emphasizedAnnotationId: _emphasizedAnnotationId,
                        cancelSelectionNotifier: _cancelSelectionNotifier,
                      ),
                    ),
                    ReaderBottomBar(
                      leading: ReaderTitleText(displayTitle),
                      trailing: _buildAppBarPill(),
                    ),
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
                        onAnnotationTap: (a, anchor) =>
                            _showAnnotationActionsToolbar(a, anchor),
                        onPositionChanged: _onPositionChanged,
                        jumpNotifier: _jumpNotifier,
                        emphasizedAnnotationId: _emphasizedAnnotationId,
                        cancelSelectionNotifier: _cancelSelectionNotifier,
                      ),
                    ),
                    ReaderBottomBar(
                      leading: ReaderTitleText(displayTitle),
                      trailing: _buildAppBarPill(),
                    ),
                  ],
                );
              case ReadingMode.pageFlip:
                // e-ink uses the dart:ui Paragraph reader (ReaderView): one
                // whole-book layout, clip+translate columns, cross-column
                // selection, no page-boundary clipping. Desktop keeps the
                // SelectableText-based PageFlipReader for mouse selection. The
                // two share most constructor args, but ReaderView additionally
                // takes onSelectionStart and einkNavReversed (PageFlipReader has
                // neither) — so the branches are NOT interchangeable.
                reader = isEink
                    ? ReaderView(
                        content: content,
                        formatSpans: formatSpans,
                        annotations: _annotations,
                        savedPosition: _savedPosition,
                        onSelection: _onSelection,
                        onAnnotationTap: (a, anchor) =>
                            _showAnnotationActionsToolbar(a, anchor),
                        onPositionChanged: _onPositionChanged,
                        jumpNotifier: _jumpNotifier,
                        // A new selection drag passes through the translucent
                        // toolbar barrier; tear down the now-stale toolbar as the
                        // drag begins (without clearing the selection being
                        // formed). No-op when no toolbar is up.
                        onSelectionStart: () {
                          if (_toolbarOverlay != null) {
                            _dismissToolbar(cancelSelection: false);
                          }
                          // Starting another annotation dismisses the undo pill
                          // and any annotation-actions toolbar.
                          _dismissUndoToolbar();
                          _dismissActionsToolbar();
                        },
                        emphasizedAnnotationId: _emphasizedAnnotationId,
                        twoColumn: _twoColumnEnabled,
                        cancelSelectionNotifier: _cancelSelectionNotifier,
                        einkNavSide: _einkNavSide,
                        einkNavReversed: _einkNavReversed,
                        bottomLeading: ReaderTitleText(displayTitle),
                        bottomTrailing: _buildAppBarPill(),
                      )
                    : PageFlipReader(
                        content: content,
                        formatSpans: formatSpans,
                        annotations: _annotations,
                        savedPosition: _savedPosition,
                        onSelection: _onSelection,
                        onAnnotationTap: (a, anchor) =>
                            _showAnnotationActionsToolbar(a, anchor),
                        onPositionChanged: _onPositionChanged,
                        jumpNotifier: _jumpNotifier,
                        emphasizedAnnotationId: _emphasizedAnnotationId,
                        twoColumn: _twoColumnEnabled,
                        cancelSelectionNotifier: _cancelSelectionNotifier,
                        einkNavSide: _einkNavSide,
                        bottomLeading: ReaderTitleText(displayTitle),
                        bottomTrailing: _buildAppBarPill(),
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
