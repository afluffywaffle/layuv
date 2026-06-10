import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
import '../models/annotation.dart';
import '../models/reading_position.dart';
import '../utils/annotation_utils.dart';
import '../utils/platform_utils.dart';

typedef _Page = ({String text, int offset});

class PageFlipReader extends StatefulWidget {
  final String content;
  final List<DocxFormatSpan> formatSpans;
  final List<Annotation> annotations;
  final ReadingPosition? savedPosition;
  final void Function(String selectedText, String prefix, String suffix, Offset anchor, double fraction) onSelection;
  final void Function(Annotation) onAnnotationTap;
  final void Function(ReadingPosition) onPositionChanged;
  final ValueNotifier<double?> jumpNotifier;
  final String? emphasizedAnnotationId;
  final bool twoColumn;
  final ValueNotifier<int>? cancelSelectionNotifier;
  final String einkNavSide;
  final Widget? bottomTrailing;

  const PageFlipReader({
    super.key,
    required this.content,
    this.formatSpans = const [],
    required this.annotations,
    required this.savedPosition,
    required this.onSelection,
    required this.onAnnotationTap,
    required this.onPositionChanged,
    required this.jumpNotifier,
    this.emphasizedAnnotationId,
    this.twoColumn = false,
    this.cancelSelectionNotifier,
    this.einkNavSide = 'both',
    this.bottomTrailing,
  });

  @override
  State<PageFlipReader> createState() => PageFlipReaderState();
}

class PageFlipReaderState extends State<PageFlipReader> {
  late PageController _pageController;
  int _currentPage = 0;
  List<_Page> _pages = const [(text: '', offset: 0)];
  double? _textAreaHeight;
  bool? _lastTwoColumn;
  double? _lastAvailableWidth;
  DateTime _lastWheelEvent = DateTime.fromMillisecondsSinceEpoch(0);
  bool _actuallyTwoCol = false;
  double _paginatedColWidth = 0;
  Timer? _selectionDebounce;
  final Map<int, GlobalKey> _pageKeys = {};
  final Offset _lastAnchor = Offset.zero;
  Offset? _stylusStartPos;
  Offset? _touchStartPos;
  bool _showJumpUI = false;
  final _jumpScrubFractionNotifier = ValueNotifier<double>(0.0);

  static const double _padding = 32.0;
  static const double _counterZone = 52.0;
  static const double _navStripWidth = 64.0;
  static const double _einkStripWidth = 80.0;

  @override
  void initState() {
    super.initState();
    final initialPage = widget.savedPosition?.page ?? 0;
    _currentPage = initialPage;
    _pageController = PageController(initialPage: initialPage);
    widget.jumpNotifier.addListener(_onJumpRequested);
    widget.cancelSelectionNotifier?.addListener(_onCancelSelection);
  }

  void _onCancelSelection() {
    _selectionDebounce?.cancel();
    _selectionDebounce = null;
  }

  @override
  void dispose() {
    _selectionDebounce?.cancel();
    _pageController.dispose();
    widget.jumpNotifier.removeListener(_onJumpRequested);
    widget.cancelSelectionNotifier?.removeListener(_onCancelSelection);
    _jumpScrubFractionNotifier.dispose();
    super.dispose();
  }

  void _onJumpRequested() {
    final fraction = widget.jumpNotifier.value;
    if (fraction == null) return;
    final page = _actuallyTwoCol
        ? (fraction * (_pages.length - 1) / 2).round().clamp(0, ((_pages.length / 2).ceil()) - 1)
        : ((fraction * (_pages.length - 1)).round()).clamp(0, _pages.length - 1);
    if (isEink) {
      _pageController.jumpToPage(page);
    } else {
      _pageController.animateToPage(
        page,
        duration: const Duration(milliseconds: 400),
        curve: Curves.easeInOut,
      );
    }
  }

  List<_Page> _paginate(String text, double maxWidth, double maxHeight) {
    if (text.isEmpty || maxWidth <= 0 || maxHeight <= 0) {
      return const [(text: '', offset: 0)];
    }

    final colWidth = _colWidth(maxWidth);
    _paginatedColWidth = colWidth;
    final pages = <_Page>[];
    int start = 0;

    while (start < text.length) {
      final remaining = text.substring(start);
      final painter = TextPainter(
        text: TextSpan(text: remaining, style: kReaderTextStyle),
        textDirection: TextDirection.ltr,
      )..layout(maxWidth: colWidth);

      if (painter.height <= maxHeight) {
        pages.add((text: remaining, offset: start));
        break;
      }

      final lineHeight = kReaderTextStyle.height! * kReaderTextStyle.fontSize!;
      final safeHeight = maxHeight - (lineHeight * 4);
      final pos = painter.getPositionForOffset(Offset(colWidth, safeHeight));
      final lineBoundary = painter.getLineBoundary(pos);
      int end = lineBoundary.start;
      if (end <= 0) end = lineBoundary.end > 0 ? lineBoundary.end : 1;

      pages.add((
        text: remaining.substring(0, end).trimRight(),
        offset: start,
      ));
      start += end;
      while (start < text.length && text[start] == '\n') {
        start++;
      }
    }

    return pages.isEmpty ? const [(text: '', offset: 0)] : pages;
  }

  double _colWidth(double availableWidth) {
    final half = (availableWidth - 24) / 2;
    return (widget.twoColumn && half >= 200) ? half : availableWidth;
  }

  void _goToPage(int page) {
    final target = page.clamp(0, _pages.length - 1);
    if (isEink) {
      _pageController.jumpToPage(target);
    } else {
      _pageController.animateToPage(
        target,
        duration: const Duration(milliseconds: 400),
        curve: Curves.easeInOut,
      );
    }
  }

  void _onPageChanged(int index) {
    setState(() => _currentPage = index);
    final total = _pages.length;
    widget.onPositionChanged(ReadingPosition(
      mode: ReadingMode.pageFlip,
      page: index,
      scrollOffset: 0,
      fraction: total > 1
          ? ((_actuallyTwoCol ? index * 2 : index) / (total - 1)).clamp(0.0, 1.0)
          : 0.0,
    ));
  }

  Offset _anchorForKey(GlobalKey key, TextSelection selection) {
    final renderBox = key.currentContext?.findRenderObject() as RenderBox?;
    if (renderBox == null) return _lastAnchor;
    RenderEditable? renderEditable;
    void visit(RenderObject obj) {
      if (renderEditable != null) return;
      if (obj is RenderEditable) {
        renderEditable = obj;
      } else {
        obj.visitChildren(visit);
      }
    }
    renderBox.visitChildren(visit);
    if (renderEditable == null) return _lastAnchor;
    final caretRect = renderEditable!.getLocalRectForCaret(
      TextPosition(offset: selection.start),
    );
    return renderEditable!.localToGlobal(caretRect.topCenter);
  }

  void _onEinkPointerDown(PointerDownEvent e) {
    if (_showJumpUI) return;
    if (e.kind == PointerDeviceKind.stylus) {
      _stylusStartPos = e.position;
      _selectRangeForStylus(e.position, e.position);
    } else {
      _touchStartPos = e.position;
    }
  }

  void _onEinkPointerMove(PointerMoveEvent e) {
    if (_showJumpUI) return;
    if (e.kind != PointerDeviceKind.stylus) return;
    final start = _stylusStartPos;
    if (start == null) return;
    _selectRangeForStylus(start, e.position);
  }

  void _onEinkPointerUp(PointerUpEvent e) {
    if (_showJumpUI) {
      _stylusStartPos = null;
      _touchStartPos = null;
      return;
    }
    if (e.kind == PointerDeviceKind.stylus) {
      final start = _stylusStartPos;
      _stylusStartPos = null;
      if (start == null) return;
      final end = e.position;
      // Fire selection after the frame so SelectableText's pointer-up handler
      // has already run. _computeAndFireSelection bypasses the onSelectionChanged
      // debounce chain entirely.
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        _computeAndFireSelection(start, end);
      });
    } else {
      final start = _touchStartPos;
      _touchStartPos = null;
      if (start == null) return;
      final dx = e.position.dx - start.dx;
      final dy = e.position.dy - start.dy;
      if (dx.abs() > 50 && dx.abs() > dy.abs() * 1.5) {
        _goToPage(_currentPage + (dx < 0 ? 1 : -1));
      }
    }
  }

  void _onEinkPointerCancel(PointerCancelEvent e) {
    if (_showJumpUI) {
      _stylusStartPos = null;
      _touchStartPos = null;
      return;
    }
    if (e.kind == PointerDeviceKind.stylus) {
      _stylusStartPos = null;
    } else {
      _touchStartPos = null;
    }
  }

  void _selectRangeForStylus(Offset globalFrom, Offset globalTo) {
    for (final key in _pageKeys.values) {
      final box = key.currentContext?.findRenderObject() as RenderBox?;
      if (box == null) continue;
      final localFrom = box.globalToLocal(globalFrom);
      if (!box.paintBounds.contains(localFrom)) continue;
      RenderEditable? re;
      void visit(RenderObject obj) {
        if (re != null) return;
        if (obj is RenderEditable) {
          re = obj;
        } else {
          obj.visitChildren(visit);
        }
      }
      box.visitChildren(visit);
      if (re == null) break;
      // selectPositionAt avoids word-expansion jumping that selectWordsInRange causes
      re!.selectPositionAt(
        from: re!.globalToLocal(globalFrom),
        to: re!.globalToLocal(globalTo),
        cause: SelectionChangedCause.drag,
      );
      break;
    }
  }

  // Called from postFrameCallback on stylus pointer-up.
  // Bypasses the onSelectionChanged debounce chain entirely, so the selection
  // cannot be collapsed between firing and the callback arriving.
  void _computeAndFireSelection(Offset globalFrom, Offset globalTo) {
    for (final entry in _pageKeys.entries) {
      final pageIndex = entry.key;
      final key = entry.value;
      final box = key.currentContext?.findRenderObject() as RenderBox?;
      if (box == null) continue;
      final localFrom = box.globalToLocal(globalFrom);
      if (!box.paintBounds.contains(localFrom)) continue;

      RenderEditable? re;
      void visit(RenderObject obj) {
        if (re != null) return;
        if (obj is RenderEditable) {
          re = obj;
        } else {
          obj.visitChildren(visit);
        }
      }
      box.visitChildren(visit);
      if (re == null) break;
      if (pageIndex >= _pages.length) break;

      final page = _pages[pageIndex];
      final text = page.text;
      if (text.isEmpty) break;

      final fromPos = re!.getPositionForPoint(globalFrom);
      final toPos = re!.getPositionForPoint(globalTo);

      final rawStart = fromPos.offset.clamp(0, text.length);
      final rawEnd = toPos.offset.clamp(0, text.length);
      final selStart = rawStart <= rawEnd ? rawStart : rawEnd;
      final selEnd = rawStart <= rawEnd ? rawEnd : rawStart;

      if (selStart >= selEnd) break;

      final snapped = snapToWordBoundaries(text, selStart, selEnd);
      final selectedText = text.substring(snapped.start, snapped.end);
      if (selectedText.trim().isEmpty) break;

      final prefix = text.substring(
        (snapped.start - 20).clamp(0, snapped.start),
        snapped.start,
      );
      final suffix = text.substring(
        snapped.end,
        (snapped.end + 20).clamp(snapped.end, text.length),
      );

      final anchor = _anchorForKey(
        key,
        TextSelection(baseOffset: snapped.start, extentOffset: snapped.end),
      );
      final total = _pages.length;
      final fraction = total > 1
          ? ((_actuallyTwoCol ? pageIndex * 2 : pageIndex) / (total - 1))
              .clamp(0.0, 1.0)
          : 0.0;

      widget.onSelection(selectedText, prefix, suffix, anchor, fraction);
      break;
    }
  }

  int get _visualPageCount =>
      _actuallyTwoCol ? (_pages.length / 2).ceil() : _pages.length;

  void _openJumpUI() {
    if (_pages.length <= 1) return;
    final count = _visualPageCount;
    _jumpScrubFractionNotifier.value =
        count > 1 ? _currentPage / (count - 1) : 0.0;
    setState(() => _showJumpUI = true);
  }

  void _commitJump() {
    final count = _visualPageCount;
    final target =
        (_jumpScrubFractionNotifier.value * (count - 1)).round().clamp(0, count - 1);
    _goToPage(target);
    setState(() => _showJumpUI = false);
  }

  void _jumpBack() {
    final count = _visualPageCount;
    if (count <= 1) return;
    final step = 1.0 / (count - 1);
    _jumpScrubFractionNotifier.value =
        (_jumpScrubFractionNotifier.value - step).clamp(0.0, 1.0);
  }

  void _jumpForward() {
    final count = _visualPageCount;
    if (count <= 1) return;
    final step = 1.0 / (count - 1);
    _jumpScrubFractionNotifier.value =
        (_jumpScrubFractionNotifier.value + step).clamp(0.0, 1.0);
  }

  Widget _buildScrubber() {
    return LayoutBuilder(builder: (ctx, constraints) {
      final trackW = constraints.maxWidth;
      return ValueListenableBuilder<double>(
        valueListenable: _jumpScrubFractionNotifier,
        builder: (ctx, fraction, _) {
          final handleX = (fraction * trackW).clamp(0.0, trackW);
          return GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTapDown: (d) {
              _jumpScrubFractionNotifier.value =
                  (d.localPosition.dx / trackW).clamp(0.0, 1.0);
            },
            onTapUp: (_) { if (!isEink) _commitJump(); },
            onHorizontalDragUpdate: (d) {
              _jumpScrubFractionNotifier.value =
                  (d.localPosition.dx / trackW).clamp(0.0, 1.0);
            },
            onHorizontalDragEnd: (_) { if (!isEink) _commitJump(); },
            child: SizedBox(
              height: 44,
              child: Stack(
                clipBehavior: Clip.none,
                children: [
                  Positioned(
                    left: 0,
                    right: 0,
                    top: 21,
                    height: 2,
                    child: Container(
                      decoration: BoxDecoration(
                        color: Colors.black26,
                        borderRadius: BorderRadius.circular(1),
                      ),
                    ),
                  ),
                  Positioned(
                    left: (handleX - 14).clamp(0.0, trackW - 28),
                    top: 2,
                    child: Container(
                      width: 28,
                      height: 40,
                      decoration: BoxDecoration(
                        color: Colors.black87,
                        borderRadius: BorderRadius.circular(6),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          );
        },
      );
    });
  }

  Widget _buildJumpOverlay() {
    final count = _visualPageCount;
    return ValueListenableBuilder<double>(
      valueListenable: _jumpScrubFractionNotifier,
      builder: (ctx, fraction, _) {
        final previewVisual =
            (fraction * (count > 1 ? count - 1 : 0)).round().clamp(0, count - 1);
        final previewRaw = (_actuallyTwoCol ? previewVisual * 2 : previewVisual)
            .clamp(0, _pages.length - 1);
        final raw = _pages.isNotEmpty ? _pages[previewRaw].text : '';
        final snippet = (raw.length > 500 ? raw.substring(0, 500) : raw)
            .replaceAll('\n', ' ')
            .trim();

        return Container(
          decoration: const BoxDecoration(
            color: Color(0xFFF5F0E8),
            boxShadow: [
              BoxShadow(
                color: Color(0x18000000),
                blurRadius: 16,
                offset: Offset(0, -4),
              ),
            ],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Padding(
                padding: const EdgeInsets.only(top: 12),
                child: Align(
                  alignment: Alignment.center,
                  child: FractionallySizedBox(
                    widthFactor: 0.50,
                    child: Container(
                      padding: const EdgeInsets.all(12),
                      decoration: BoxDecoration(
                        color: Colors.black.withValues(alpha: 0.04),
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Expanded(
                            child: Text(
                              snippet.isEmpty ? '—' : snippet,
                              style: kReaderTextStyle,
                            ),
                          ),
                          const SizedBox(width: 12),
                          Text(
                            '${previewVisual + 1} / $count',
                            style: const TextStyle(
                              fontFamily: 'SourceSans3',
                              fontSize: 13,
                              color: Colors.black45,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ),
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Align(
                  alignment: Alignment.center,
                  child: FractionallySizedBox(
                    widthFactor: 0.6,
                    child: _buildScrubber(),
                  ),
                ),
              ),
              SizedBox(
                height: _counterZone,
                child: isEink
                    ? Row(
                        children: [
                          Expanded(
                            child: GestureDetector(
                              behavior: HitTestBehavior.opaque,
                              onTap: () => setState(() => _showJumpUI = false),
                              child: const Center(
                                child: Text(
                                  'Cancel',
                                  style: TextStyle(
                                    fontFamily: 'SourceSans3',
                                    fontSize: 14,
                                    color: Colors.black45,
                                  ),
                                ),
                              ),
                            ),
                          ),
                          GestureDetector(
                            onTap: _jumpBack,
                            child: const Padding(
                              padding: EdgeInsets.symmetric(horizontal: 16),
                              child: Icon(Icons.chevron_left,
                                  size: 36, color: Colors.black54),
                            ),
                          ),
                          GestureDetector(
                            onTap: _jumpForward,
                            child: const Padding(
                              padding: EdgeInsets.symmetric(horizontal: 16),
                              child: Icon(Icons.chevron_right,
                                  size: 36, color: Colors.black54),
                            ),
                          ),
                          Expanded(
                            child: GestureDetector(
                              behavior: HitTestBehavior.opaque,
                              onTap: _commitJump,
                              child: const Center(
                                child: Text(
                                  'Confirm',
                                  style: TextStyle(
                                    fontFamily: 'SourceSans3',
                                    fontSize: 14,
                                    color: Colors.black87,
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ],
                      )
                    : GestureDetector(
                        onTap: () => setState(() => _showJumpUI = false),
                        child: const Center(
                          child: Text(
                            'Cancel',
                            style: TextStyle(
                              fontFamily: 'SourceSans3',
                              fontSize: 14,
                              color: Colors.black45,
                            ),
                          ),
                        ),
                      ),
              ),
            ],
          ),
        );
      },
    );
  }

  Widget _buildPageContent(
    _Page page, {
    required int pageIndex,
    required double textWidth,
    required double maxHeight,
  }) {
    final marginIndicators = buildMarginIndicators(
      sliceContent: page.text,
      fullContent: widget.content,
      annotations: widget.annotations,
      sliceOffset: page.offset,
      lineHeight: kReaderTextStyle.height! * kReaderTextStyle.fontSize!,
      maxWidth: textWidth,
      emphasizedAnnotationId: widget.emphasizedAnnotationId,
    );

    return SizedBox(
      width: textWidth,
      height: maxHeight,
      child: Stack(
        clipBehavior: Clip.hardEdge,
        children: [
          DefaultSelectionStyle(
            selectionColor: const Color(0xFFF5D76E),
            child: SelectableText.rich(
              key: _pageKeys.putIfAbsent(pageIndex, GlobalKey.new),
              buildAnnotatedText(
                sliceContent: page.text,
                fullContent: widget.content,
                annotations: widget.annotations,
                sliceOffset: page.offset,
                baseStyle: kReaderTextStyle,
                onAnnotationTap: widget.onAnnotationTap,
                formatSpans: widget.formatSpans,
              ),
              onSelectionChanged: (selection, _) {
                // E-ink selection is driven directly by _computeAndFireSelection
                // on pointer-up; skip the debounce chain here entirely.
                if (isEink) return;
                if (!selection.isValid || selection.isCollapsed) {
                  _selectionDebounce?.cancel();
                  return;
                }
                _selectionDebounce?.cancel();
                _selectionDebounce = Timer(const Duration(milliseconds: 350), () {
                  if (!mounted) return;
                  final text = page.text;
                  final snapped = snapToWordBoundaries(text, selection.start, selection.end);
                  final selectedText = text.substring(snapped.start, snapped.end);
                  if (selectedText.trim().isEmpty) return;
                  final prefix = text.substring((snapped.start - 20).clamp(0, snapped.start), snapped.start);
                  final suffix = text.substring(snapped.end, (snapped.end + 20).clamp(snapped.end, text.length));
                  final key = _pageKeys[pageIndex];
                  final anchor = key != null ? _anchorForKey(key, selection) : _lastAnchor;
                  final total = _pages.length;
                  final fraction = total > 1
                      ? ((_actuallyTwoCol ? pageIndex * 2 : pageIndex) / (total - 1)).clamp(0.0, 1.0)
                      : 0.0;
                  widget.onSelection(selectedText, prefix, suffix, anchor, fraction);
                });
              },
              contextMenuBuilder: (context, editableTextState) {
                return const SizedBox.shrink();
              },
              scrollPhysics: const NeverScrollableScrollPhysics(),
            ),
          ),
          ...marginIndicators.map((m) => Positioned(
            left: 4,
            top: m.topOffset,
            child: Text(
              m.label,
              style: m.emphasized
                  ? marginIndicatorStyle.copyWith(
                      color: const Color(0xCC000000),
                      fontSize: 13,
                    )
                  : marginIndicatorStyle,
            ),
          )),
        ],
      ),
    );
  }

  Widget _buildPageCounter() {
    return GestureDetector(
      onTap: _openJumpUI,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        decoration: BoxDecoration(
          color: Colors.black12,
          borderRadius: BorderRadius.circular(20),
        ),
        child: Text(
          '${_currentPage + 1} / $_visualPageCount',
          style: const TextStyle(
            fontFamily: 'SourceSans3',
            fontSize: 14,
            color: Colors.black87,
          ),
        ),
      ),
    );
  }

  List<Widget> _buildNavStrips() {
    if (!isEink) {
      return [
        Positioned(
          left: 0, top: 0, bottom: 0, width: _navStripWidth,
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: () => _goToPage(_currentPage - 1),
          ),
        ),
        Positioned(
          right: 0, top: 0, bottom: 0, width: _navStripWidth,
          child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: () => _goToPage(_currentPage + 1),
          ),
        ),
      ];
    }

    // E-ink: each strip split top/bottom — top = next, bottom = prev.
    // Chevrons point right/left (reading direction) rather than up/down.
    // A hairline at the midpoint makes the split zone visible.
    Positioned einkSide(bool isLeft) => Positioned(
          left: isLeft ? 0 : null,
          right: isLeft ? null : 0,
          top: 0,
          bottom: 0,
          width: _einkStripWidth,
          child: Column(
            children: [
              Expanded(
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => _goToPage(_currentPage + 1),
                  child: Center(
                    child: Icon(Icons.chevron_right, size: 40, color: Colors.black26),
                  ),
                ),
              ),
              Container(height: 1, color: Colors.black12),
              Expanded(
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => _goToPage(_currentPage - 1),
                  child: Center(
                    child: Icon(Icons.chevron_left, size: 40, color: Colors.black26),
                  ),
                ),
              ),
            ],
          ),
        );

    return [
      if (widget.einkNavSide != 'right') einkSide(true),
      if (widget.einkNavSide != 'left') einkSide(false),
    ];
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Listener(
            onPointerSignal: (event) {
              if (_showJumpUI) return;
              if (event is! PointerScrollEvent) return;
              if (event.scrollDelta.dy.abs() <= 4.0) return;
              final now = DateTime.now();
              if (now.difference(_lastWheelEvent).inMilliseconds < 400) return;
              _lastWheelEvent = now;
              _goToPage(_currentPage + (event.scrollDelta.dy > 0 ? 1 : -1));
            },
            onPointerDown: isEink ? _onEinkPointerDown : null,
            onPointerMove: isEink ? _onEinkPointerMove : null,
            onPointerUp: isEink ? _onEinkPointerUp : null,
            onPointerCancel: isEink ? _onEinkPointerCancel : null,
            child: Stack(
              children: [
                Column(
                  children: [
                SizedBox(height: _padding),
                Expanded(
                  child: LayoutBuilder(builder: (context, pageConstraints) {
                    final textAreaHeight = pageConstraints.maxHeight;
                    final einkLeft = isEink && widget.einkNavSide != 'right' ? _einkStripWidth : 0.0;
                    final einkRight = isEink && widget.einkNavSide != 'left' ? _einkStripWidth : 0.0;
                    final availableWidth = pageConstraints.maxWidth - _padding * 2 - einkLeft - einkRight;
                    final colWidth = _colWidth(availableWidth);

                    final actuallyTwoCol = widget.twoColumn && colWidth < availableWidth;
                    if (_actuallyTwoCol != actuallyTwoCol) {
                      WidgetsBinding.instance.addPostFrameCallback((_) {
                        if (!mounted) return;
                        setState(() => _actuallyTwoCol = actuallyTwoCol);
                      });
                    }

                    if (_textAreaHeight != textAreaHeight || _lastTwoColumn != widget.twoColumn || _lastAvailableWidth != availableWidth) {
                      WidgetsBinding.instance.addPostFrameCallback((_) {
                        if (!mounted) return;
                        setState(() {
                          _textAreaHeight = textAreaHeight;
                          _lastTwoColumn = widget.twoColumn;
                          _lastAvailableWidth = availableWidth;
                          _pages = _paginate(
                            widget.content,
                            availableWidth,
                            widget.twoColumn ? textAreaHeight : textAreaHeight - _padding * 2,
                          );
                          if (_currentPage >= _pages.length) {
                            _currentPage = _pages.length - 1;
                          }
                        });
                      });
                    }

                    return Stack(
                      children: [
                        PageView.builder(
                          controller: _pageController,
                          physics: isEink ? const NeverScrollableScrollPhysics() : null,
                          onPageChanged: _onPageChanged,
                          itemCount: _actuallyTwoCol
                              ? (_pages.length / 2).ceil()
                              : _pages.length,
                          itemBuilder: (context, index) {

                            if (widget.twoColumn) {
                              final leftIdx = index * 2;
                              final rightIdx = leftIdx + 1;
                              final leftPage = _pages[leftIdx];
                              final rightPage = rightIdx < _pages.length ? _pages[rightIdx] : null;
                              if (!actuallyTwoCol) {
                                final page = _pages[index * 2];
                                return SizedBox.expand(
                                  child: Padding(
                                    padding: EdgeInsets.fromLTRB(_padding + einkLeft, _padding, _padding + einkRight, _padding),
                                    child: _buildPageContent(page, pageIndex: index * 2, textWidth: availableWidth, maxHeight: textAreaHeight - _padding * 2),
                                  ),
                                );
                              }
                              assert(_paginatedColWidth * 2 + 24 + _padding * 2 <= pageConstraints.maxWidth,
                                'Row overflow: ${_paginatedColWidth * 2 + 24 + _padding * 2} > ${pageConstraints.maxWidth}');
                              return SizedBox.expand(
                                child: Padding(
                                  padding: EdgeInsets.only(left: _padding + einkLeft, right: _padding + einkRight),
                                  child: Row(
                                    crossAxisAlignment: CrossAxisAlignment.start,
                                    children: [
                                      _buildPageContent(
                                        leftPage,
                                        pageIndex: leftIdx,
                                        textWidth: _paginatedColWidth,
                                        maxHeight: textAreaHeight,
                                      ),
                                      const SizedBox(width: 24),
                                      rightPage != null
                                          ? _buildPageContent(
                                              rightPage,
                                              pageIndex: rightIdx,
                                              textWidth: _paginatedColWidth,
                                              maxHeight: textAreaHeight,
                                            )
                                          : SizedBox(width: _paginatedColWidth, height: textAreaHeight),
                                    ],
                                  ),
                                ),
                              );
                            }
                            final page = _pages[index];
                            return SizedBox.expand(
                              child: Padding(
                                padding: EdgeInsets.fromLTRB(_padding + einkLeft, _padding, _padding + einkRight, _padding),
                                child: _buildPageContent(
                                  page,
                                  pageIndex: index,
                                  textWidth: availableWidth,
                                  maxHeight: textAreaHeight - _padding * 2,
                                ),
                              ),
                            );
                          },
                        ),

                        ..._buildNavStrips(),
                      ],
                    );
                  }),
                ),
                SizedBox(
                  height: _counterZone,
                  child: Stack(
                    children: [
                      Center(child: _buildPageCounter()),
                      if (widget.bottomTrailing != null)
                        Positioned(
                          right: isEink ? 16 : 8,
                          top: 0,
                          bottom: 0,
                          child: Center(child: widget.bottomTrailing!),
                        ),
                    ],
                  ),
                ),
                  ],
                ),
                if (_showJumpUI) ...[
                  GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTap: () => setState(() => _showJumpUI = false),
                    child: const SizedBox.expand(),
                  ),
                  Positioned(
                    left: 0,
                    right: 0,
                    bottom: 0,
                    child: _buildJumpOverlay(),
                  ),
                ],
              ],
            ),
      ),
    );
  }
}
