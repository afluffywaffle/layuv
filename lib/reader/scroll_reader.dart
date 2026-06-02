import 'dart:async';
import 'package:flutter/material.dart';
import '../models/annotation.dart';
import '../models/reading_position.dart';
import '../utils/annotation_utils.dart';

class ScrollReader extends StatefulWidget {
  final String content;
  final List<Annotation> annotations;
  final ReadingPosition? savedPosition;
  final void Function(String selectedText, String prefix, String suffix, Offset anchor) onSelection;
  final void Function(Annotation) onAnnotationTap;
  final void Function(ReadingPosition) onPositionChanged;

  const ScrollReader({
    super.key,
    required this.content,
    required this.annotations,
    required this.savedPosition,
    required this.onSelection,
    required this.onAnnotationTap,
    required this.onPositionChanged,
  });

  @override
  State<ScrollReader> createState() => _ScrollReaderState();
}

class _ScrollReaderState extends State<ScrollReader> {
  late ScrollController _scrollController;

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
    widget.onPositionChanged(ReadingPosition(
      mode: ReadingMode.scroll,
      page: 0,
      scrollOffset: _scrollController.offset,
    ));
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) {
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
          return SingleChildScrollView(
            controller: _scrollController,
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
          );
        },
      ),
    );
  }
}
