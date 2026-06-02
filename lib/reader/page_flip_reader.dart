import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/gestures.dart';
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

  const PageFlipReader({
    super.key,
    required this.content,
    required this.annotations,
    required this.savedPosition,
    required this.onSelection,
    required this.onAnnotationTap,
    required this.onPositionChanged,
  });

  @override
  State<PageFlipReader> createState() => _PageFlipReaderState();
}

class _PageFlipReaderState extends State<PageFlipReader> {
  late PageController _pageController;
  int _currentPage = 0;
  List<_Page> _pages = const [(text: '', offset: 0)];
  Size? _lastSize;
  DateTime _lastWheelEvent = DateTime.fromMillisecondsSinceEpoch(0);

  static const double _padding = 32.0;
  static const double _indicatorHeight = 56.0;
  static const double _safetyMargin = 24.0;
  static const double _navStripWidth = 64.0;

  @override
  void initState() {
    super.initState();
    final initialPage = widget.savedPosition?.page ?? 0;
    _currentPage = initialPage;
    _pageController = PageController(initialPage: initialPage);
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  List<_Page> _paginate(String text, double maxWidth, double maxHeight) {
    if (text.isEmpty || maxWidth <= 0 || maxHeight <= 0) {
      return const [(text: '', offset: 0)];
    }

    final pages = <_Page>[];
    int start = 0;

    while (start < text.length) {
      final remaining = text.substring(start);
      final painter = TextPainter(
        text: TextSpan(text: remaining, style: kReaderTextStyle),
        textDirection: TextDirection.ltr,
      )..layout(maxWidth: maxWidth);

      if (painter.height <= maxHeight) {
        pages.add((text: remaining, offset: start));
        break;
      }

      final pos = painter.getPositionForOffset(Offset(maxWidth, maxHeight));
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

  void _goToPage(int page) {
    _pageController.animateToPage(
      page.clamp(0, _pages.length - 1),
      duration: const Duration(milliseconds: 400),
      curve: Curves.easeInOut,
    );
  }

  void _onPageChanged(int page) {
    setState(() => _currentPage = page);
    widget.onPositionChanged(ReadingPosition(
      mode: ReadingMode.pageFlip,
      page: page,
      scrollOffset: 0,
    ));
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) {
          final size = Size(constraints.maxWidth, constraints.maxHeight);
          if (_lastSize != size) {
            _lastSize = size;
            _pages = _paginate(
              widget.content,
              constraints.maxWidth - _padding * 2,
              (constraints.maxHeight - _padding * 2 - _indicatorHeight - _safetyMargin) * 0.85,
            );
            if (_currentPage >= _pages.length) {
              _currentPage = _pages.length - 1;
            }
          }

          return Listener(
            onPointerSignal: (event) {
              if (event is! PointerScrollEvent) return;
              if (event.scrollDelta.dy.abs() <= 4.0) return;
              final now = DateTime.now();
              if (now.difference(_lastWheelEvent).inMilliseconds < 400) return;
              _lastWheelEvent = now;
              _goToPage(_currentPage + (event.scrollDelta.dy > 0 ? 1 : -1));
            },
            child: Stack(
              children: [
                PageView.builder(
                  controller: _pageController,
                  onPageChanged: _onPageChanged,
                  itemCount: _pages.length,
                  itemBuilder: (context, index) {
                    final page = _pages[index];
                    final textWidth = constraints.maxWidth - _padding * 2;
                    final marginIndicators = buildMarginIndicators(
                      sliceContent: page.text,
                      fullContent: widget.content,
                      annotations: widget.annotations,
                      sliceOffset: page.offset,
                      lineHeight: kReaderTextStyle.height! * kReaderTextStyle.fontSize!,
                      maxWidth: textWidth,
                    );
                    return Stack(
                      children: [
                        Padding(
                          padding: const EdgeInsets.all(_padding),
                          child: DefaultSelectionStyle(
                            selectionColor: const Color(0xFFF5D76E),
                            child: SelectableText.rich(
                              buildAnnotatedText(
                                sliceContent: page.text,
                                fullContent: widget.content,
                                annotations: widget.annotations,
                                sliceOffset: page.offset,
                                baseStyle: kReaderTextStyle,
                                onAnnotationTap: widget.onAnnotationTap,
                              ),
                              contextMenuBuilder: (context, editableTextState) {
                                final sel =
                                    editableTextState.textEditingValue.selection;
                                if (sel.isValid && !sel.isCollapsed) {
                                  final text =
                                      editableTextState.textEditingValue.text;
                                  final selectedText =
                                      text.substring(sel.start, sel.end);
                                  final prefix = text.substring(
                                      (sel.start - 20).clamp(0, sel.start),
                                      sel.start);
                                  final suffix = text.substring(sel.end,
                                      (sel.end + 20).clamp(sel.end, text.length));
                                  final anchor = editableTextState
                                      .contextMenuAnchors.primaryAnchor;
                                  scheduleMicrotask(() {
                                    if (mounted) widget.onSelection(selectedText, prefix, suffix, anchor);
                                  });
                                }
                                return const SizedBox.shrink();
                              },
                            ),
                          ),
                        ),
                        ...marginIndicators.map((m) => Positioned(
                          left: 4,
                          top: _padding + m.topOffset,
                          child: Text(m.label, style: marginIndicatorStyle),
                        )),
                      ],
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

                Positioned(
                  bottom: 24, left: 0, right: 0,
                  child: Center(
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
                      decoration: BoxDecoration(
                        color: Colors.black12,
                        borderRadius: BorderRadius.circular(20),
                      ),
                      child: Text(
                        '${_currentPage + 1} / ${_pages.length}',
                        style: const TextStyle(
                          fontFamily: 'SourceSans3',
                          fontSize: 14,
                          color: Colors.black87,
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          );
        },
      ),
    );
  }
}
