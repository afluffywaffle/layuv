import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
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
  final ValueNotifier<double?> jumpNotifier;
  final String? emphasizedAnnotationId;
  final ValueNotifier<int>? cancelSelectionNotifier;

  const ScreenFlipReader({
    super.key,
    required this.content,
    required this.annotations,
    required this.savedPosition,
    required this.onSelection,
    required this.onAnnotationTap,
    required this.onPositionChanged,
    required this.jumpNotifier,
    this.emphasizedAnnotationId,
    this.cancelSelectionNotifier,
  });

  @override
  State<ScreenFlipReader> createState() => _ScreenFlipReaderState();
}

class _ScreenFlipReaderState extends State<ScreenFlipReader> {
  late ScrollController _scrollController;
  double _screenHeight = 0;
  Timer? _selectionDebounce;
  final Offset _lastAnchor = Offset.zero;
  final _selectableTextKey = GlobalKey();

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
      mode: ReadingMode.screenFlip,
      page: 0,
      scrollOffset: _scrollController.offset,
      fraction: max > 0
          ? (_scrollController.offset / max).clamp(0.0, 1.0)
          : 0.0,
    ));
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
            emphasizedAnnotationId: widget.emphasizedAnnotationId,
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
              ),
            ),
          );
        },
      ),
    );
  }
}
