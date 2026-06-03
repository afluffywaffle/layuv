import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
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
  final VoidCallback? onDismiss;

  const ScrollReader({
    super.key,
    required this.content,
    required this.annotations,
    required this.savedPosition,
    required this.onSelection,
    required this.onAnnotationTap,
    required this.onPositionChanged,
    this.onDismiss,
  });

  @override
  State<ScrollReader> createState() => _ScrollReaderState();
}

class _ScrollReaderState extends State<ScrollReader> {
  late ScrollController _scrollController;
  Offset _lastAnchor = Offset.zero;
  final _selectableTextKey = GlobalKey();

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

  Offset _anchorForSelection(TextSelection selection) {
    final renderBox = _selectableTextKey.currentContext?.findRenderObject() as RenderBox?;
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

  void _onScroll() {
    final max = _scrollController.position.maxScrollExtent;
    widget.onPositionChanged(ReadingPosition(
      mode: ReadingMode.scroll,
      page: 0,
      scrollOffset: _scrollController.offset,
      fraction: max > 0
          ? (_scrollController.offset / max).clamp(0.0, 1.0)
          : 0.0,
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
                      key: _selectableTextKey,
                      buildAnnotatedText(
                        sliceContent: widget.content,
                        fullContent: widget.content,
                        annotations: widget.annotations,
                        sliceOffset: 0,
                        baseStyle: kReaderTextStyle,
                        onAnnotationTap: widget.onAnnotationTap,
                      ),
                      onSelectionChanged: (selection, _) {
                        if (selection.isValid && !selection.isCollapsed) {
                          final text = widget.content;
                          final selectedText = text.substring(selection.start, selection.end);
                          final prefix = text.substring(
                            (selection.start - 20).clamp(0, selection.start),
                            selection.start,
                          );
                          final suffix = text.substring(
                            selection.end,
                            (selection.end + 20).clamp(selection.end, text.length),
                          );
                          final anchor = _anchorForSelection(selection);
                          scheduleMicrotask(() {
                            if (mounted) widget.onSelection(selectedText, prefix, suffix, anchor);
                          });
                        } else {
                          if (mounted) widget.onDismiss?.call();
                        }
                      },
                      contextMenuBuilder: (context, editableTextState) {
                        final sel = editableTextState.textEditingValue.selection;
                        if (sel.isValid && !sel.isCollapsed) {
                          final text = editableTextState.textEditingValue.text;
                          final selectedText = text.substring(sel.start, sel.end);
                          final prefix = text.substring(
                              (sel.start - 20).clamp(0, sel.start), sel.start);
                          final suffix = text.substring(
                              sel.end, (sel.end + 20).clamp(sel.end, text.length));
                          _lastAnchor =
                              editableTextState.contextMenuAnchors.primaryAnchor;
                          scheduleMicrotask(() {
                            if (mounted) widget.onSelection(selectedText, prefix, suffix, _lastAnchor);
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
