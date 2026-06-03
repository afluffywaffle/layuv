import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/gestures.dart';
import '../models/annotation.dart';
import '../models/reading_position.dart';
import '../utils/annotation_utils.dart';

class ScreenFlipReader extends StatefulWidget {
  final String content;
  final List<Annotation> annotations;
  final ReadingPosition? savedPosition;
  final void Function(String selectedText, String prefix, String suffix, Offset anchor) onSelection;
  final void Function(Annotation) onAnnotationTap;
  final void Function(ReadingPosition) onPositionChanged;

  const ScreenFlipReader({
    super.key,
    required this.content,
    required this.annotations,
    required this.savedPosition,
    required this.onSelection,
    required this.onAnnotationTap,
    required this.onPositionChanged,
  });

  @override
  State<ScreenFlipReader> createState() => _ScreenFlipReaderState();
}

class _ScreenFlipReaderState extends State<ScreenFlipReader> {
  late ScrollController _scrollController;
  double _screenHeight = 0;

  @override
  void initState() {
    super.initState();
    final initialOffset = widget.savedPosition?.scrollOffset ?? 0.0;
    _scrollController = ScrollController(initialScrollOffset: initialOffset);
    _scrollController.addListener(_onScroll);
  }

  @override
  void dispose() {
    _scrollController.removeListener(_onScroll);
    _scrollController.dispose();
    super.dispose();
  }

  void _onScroll() {
    final max = _scrollController.position.maxScrollExtent;
    widget.onPositionChanged(ReadingPosition(
      mode: ReadingMode.screenFlip,
      page: 0,
      scrollOffset: _scrollController.offset,
      fraction: max > 0
          ? (_scrollController.offset / max).clamp(0.0, 1.0)
          : 0.0,
    ));
  }

  void _scrollByScreen(bool down) {
    final delta = _screenHeight * 0.9;
    _scrollController.animateTo(
      (_scrollController.offset + (down ? delta : -delta))
          .clamp(0, _scrollController.position.maxScrollExtent),
      duration: const Duration(milliseconds: 500),
      curve: Curves.easeInOut,
    );
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) {
          _screenHeight = constraints.maxHeight;
          const padding = 32.0;
          final textWidth = constraints.maxWidth - padding * 2;
          final marginIndicators = buildMarginIndicators(
            sliceContent: widget.content,
            fullContent: widget.content,
            annotations: widget.annotations,
            sliceOffset: 0,
            lineHeight: kReaderTextStyle.height! * kReaderTextStyle.fontSize!,
            maxWidth: textWidth,
          );

          return Listener(
            onPointerSignal: (event) {
              if (event is PointerScrollEvent && event.scrollDelta.dy.abs() > 4.0) {
                _scrollByScreen(event.scrollDelta.dy > 0);
              }
            },
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onVerticalDragEnd: (details) {
                final v = details.primaryVelocity ?? 0;
                if (v > 300) {
                  _scrollByScreen(false);
                } else if (v < -300) {
                  _scrollByScreen(true);
                }
              },
              child: SingleChildScrollView(
                controller: _scrollController,
                physics: const NeverScrollableScrollPhysics(),
                child: Stack(
                  children: [
                    Padding(
                      padding: const EdgeInsets.all(padding),
                      child: DefaultSelectionStyle(
                        selectionColor: const Color(0xFFF5D76E),
                        child: SelectableText.rich(
                          buildAnnotatedText(
                            sliceContent: widget.content,
                            fullContent: widget.content,
                            annotations: widget.annotations,
                            sliceOffset: 0,
                            baseStyle: kReaderTextStyle,
                            onAnnotationTap: widget.onAnnotationTap,
                          ),
                          contextMenuBuilder: (context, editableTextState) {
                            final sel = editableTextState.textEditingValue.selection;
                            if (sel.isValid && !sel.isCollapsed) {
                              final text = editableTextState.textEditingValue.text;
                              final selectedText = text.substring(sel.start, sel.end);
                              final prefix = text.substring(
                                  (sel.start - 20).clamp(0, sel.start), sel.start);
                              final suffix = text.substring(
                                  sel.end, (sel.end + 20).clamp(sel.end, text.length));
                              final anchor =
                                  editableTextState.contextMenuAnchors.primaryAnchor;
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
                      top: padding + m.topOffset,
                      child: Text(m.label, style: marginIndicatorStyle),
                    )),
                  ],
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}
