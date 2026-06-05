import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
import '../models/annotation.dart';
import '../models/reading_position.dart';
import '../utils/annotation_utils.dart';

typedef _Page = ({String text, int offset});

class PageFlipReader extends StatefulWidget {
  final String content;
  final List<Annotation> annotations;
  final ReadingPosition? savedPosition;
  final void Function(String selectedText, String prefix, String suffix, Offset anchor) onSelection;
  final void Function(Annotation) onAnnotationTap;
  final void Function(ReadingPosition) onPositionChanged;
  final ValueNotifier<double?> jumpNotifier;
  final String? emphasizedAnnotationId;
  final bool twoColumn;
  final ValueNotifier<int>? cancelSelectionNotifier;

  const PageFlipReader({
    super.key,
    required this.content,
    required this.annotations,
    required this.savedPosition,
    required this.onSelection,
    required this.onAnnotationTap,
    required this.onPositionChanged,
    required this.jumpNotifier,
    this.emphasizedAnnotationId,
    this.twoColumn = false,
    this.cancelSelectionNotifier,
  });

  @override
  State<PageFlipReader> createState() => _PageFlipReaderState();
}

class _PageFlipReaderState extends State<PageFlipReader> {
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

  static const double _padding = 32.0;
  static const double _counterZone = 52.0;
  static const double _navStripWidth = 64.0;

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
    super.dispose();
  }

  void _onJumpRequested() {
    final fraction = widget.jumpNotifier.value;
    if (fraction == null) return;
    final page = _actuallyTwoCol
        ? (fraction * (_pages.length - 1) / 2).round().clamp(0, ((_pages.length / 2).ceil()) - 1)
        : ((fraction * (_pages.length - 1)).round()).clamp(0, _pages.length - 1);
    _pageController.animateToPage(
      page,
      duration: const Duration(milliseconds: 400),
      curve: Curves.easeInOut,
    );
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
      final safeHeight = maxHeight - (lineHeight * 2);
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
    _pageController.animateToPage(
      page.clamp(0, _pages.length - 1),
      duration: const Duration(milliseconds: 400),
      curve: Curves.easeInOut,
    );
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
              ),
              onSelectionChanged: (selection, _) {
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
                  widget.onSelection(selectedText, prefix, suffix, anchor);
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
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      decoration: BoxDecoration(
        color: Colors.black12,
        borderRadius: BorderRadius.circular(20),
      ),
      child: Text(
        () {
          final displayCurrent = _actuallyTwoCol ? _currentPage + 1 : _currentPage + 1;
          final displayTotal = _actuallyTwoCol
              ? (_pages.length / 2).ceil()
              : _pages.length;
          return '$displayCurrent / $displayTotal';
        }(),
        style: const TextStyle(
          fontFamily: 'SourceSans3',
          fontSize: 14,
          color: Colors.black87,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Listener(
            onPointerSignal: (event) {
              if (event is! PointerScrollEvent) return;
              if (event.scrollDelta.dy.abs() <= 4.0) return;
              final now = DateTime.now();
              if (now.difference(_lastWheelEvent).inMilliseconds < 400) return;
              _lastWheelEvent = now;
              _goToPage(_currentPage + (event.scrollDelta.dy > 0 ? 1 : -1));
            },
            child: Column(
              children: [
                SizedBox(height: _padding),
                Expanded(
                  child: LayoutBuilder(builder: (context, pageConstraints) {
                    final textAreaHeight = pageConstraints.maxHeight;
                    final availableWidth = pageConstraints.maxWidth - _padding * 2;
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
                            pageConstraints.maxWidth - _padding * 2,
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
                                    padding: const EdgeInsets.symmetric(horizontal: _padding, vertical: _padding),
                                    child: _buildPageContent(page, pageIndex: index * 2, textWidth: availableWidth, maxHeight: textAreaHeight - _padding * 2),
                                  ),
                                );
                              }
                              assert(_paginatedColWidth * 2 + 24 + _padding * 2 <= pageConstraints.maxWidth,
                                'Row overflow: ${_paginatedColWidth * 2 + 24 + _padding * 2} > ${pageConstraints.maxWidth}');
                              return SizedBox.expand(
                                child: Padding(
                                  padding: const EdgeInsets.symmetric(horizontal: _padding),
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
                                padding: const EdgeInsets.symmetric(horizontal: _padding, vertical: _padding),
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
                      ],
                    );
                  }),
                ),
                SizedBox(
                  height: _counterZone,
                  child: Center(child: _buildPageCounter()),
                ),
              ],
            ),
      ),
    );
  }
}
