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
  final ValueNotifier<double?> jumpNotifier;
  final String? emphasizedAnnotationId;
  final ValueNotifier<int>? cancelSelectionNotifier;

  const ScrollReader({
    super.key,
    required this.content,
    required this.annotations,
    required this.savedPosition,
    required this.onSelection,
    required this.onAnnotationTap,
    required this.onPositionChanged,
    required this.jumpNotifier,
    this.onDismiss,
    this.emphasizedAnnotationId,
    this.cancelSelectionNotifier,
  });

  @override
  State<ScrollReader> createState() => _ScrollReaderState();
}

class _ScrollReaderState extends State<ScrollReader> {
  late ScrollController _scrollController;
  final Offset _lastAnchor = Offset.zero;
  final _selectableTextKey = GlobalKey();
  Timer? _selectionDebounce;

  @override
  void initState() {
    super.initState();
    final initialOffset = widget.savedPosition?.scrollOffset ?? 0.0;
    _scrollController = ScrollController(initialScrollOffset: initialOffset);
    _scrollController.addListener(_onScroll);
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
    _scrollController.removeListener(_onScroll);
    _scrollController.dispose();
    widget.jumpNotifier.removeListener(_onJumpRequested);
    widget.cancelSelectionNotifier?.removeListener(_onCancelSelection);
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

  void _onJumpRequested() {
    final fraction = widget.jumpNotifier.value;
    if (fraction == null) return;
    final max = _scrollController.position.maxScrollExtent;
    _scrollController.animateTo(
      max * fraction,
      duration: const Duration(milliseconds: 400),
      curve: Curves.easeInOut,
    );
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
            emphasizedAnnotationId: widget.emphasizedAnnotationId,
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
                        if (!selection.isValid || selection.isCollapsed) {
                          _selectionDebounce?.cancel();
                          if (mounted) widget.onDismiss?.call();
                          return;
                        }
                        _selectionDebounce?.cancel();
                        _selectionDebounce = Timer(const Duration(milliseconds: 350), () {
                          if (!mounted) return;
                          final text = widget.content;
                          final snapped = snapToWordBoundaries(text, selection.start, selection.end);
                          final selectedText = text.substring(snapped.start, snapped.end);
                          if (selectedText.trim().isEmpty) return;
                          final prefix = text.substring((snapped.start - 20).clamp(0, snapped.start), snapped.start);
                          final suffix = text.substring(snapped.end, (snapped.end + 20).clamp(snapped.end, text.length));
                          final anchor = _anchorForSelection(selection);
                          widget.onSelection(selectedText, prefix, suffix, anchor);
                        });
                      },
                      contextMenuBuilder: (context, editableTextState) {
                        return const SizedBox.shrink();
                      },
                    ),
                  ),
                ),
                ...marginIndicators.map((m) => Positioned(
                  left: 4,
                  top: padding + m.topOffset,
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
        },
      ),
    );
  }
}
