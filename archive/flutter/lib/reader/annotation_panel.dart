import 'dart:io';
import 'package:flutter/material.dart';
import '../models/annotation.dart';
import '../models/annotation_store_interface.dart';
import '../utils/ink_channel.dart';
import '../utils/platform_utils.dart';
import 'annotation_toolbar.dart';
import 'ink_canvas_screen.dart';

class AnnotationPanel extends StatefulWidget {
  final String selectedText;
  final String prefix;
  final String suffix;
  final AnnotationStoreInterface store;
  final AnnotationTool initialTool;
  final Annotation? existing;
  final double fraction;
  final Future<void> Function() onSaved;

  const AnnotationPanel({
    super.key,
    required this.selectedText,
    required this.prefix,
    required this.suffix,
    required this.store,
    required this.initialTool,
    required this.existing,
    required this.fraction,
    required this.onSaved,
  });

  @override
  State<AnnotationPanel> createState() => _AnnotationPanelState();
}

class _AnnotationPanelState extends State<AnnotationPanel> {
  late AnnotationTool _tool;
  late TextEditingController _noteController;
  AnnotationTag? _tag;
  bool _inkCaptured = false;
  // Pre-generated ID used for both saveInkPng and saveAnnotation so the PNG
  // and the annotation share the same key. Set on first ink capture.
  String? _reservedId;

  bool get _isEditing => widget.existing != null;

  static const _tagLabels = {
    AnnotationTag.voice: 'Voice',
    AnnotationTag.pacing: 'Pacing',
    AnnotationTag.continuity: 'Continuity',
    AnnotationTag.query: 'Query',
  };

  static const _tagPrompts = {
    AnnotationTag.voice: "This doesn't sound like [character] because...",
    AnnotationTag.pacing: 'This section feels too [fast/slow] because...',
    AnnotationTag.continuity: 'Possible inconsistency with...',
    AnnotationTag.query: 'Question: ...',
  };

  static const _selectorTools = [
    AnnotationTool.highlight,
    AnnotationTool.underline,
    AnnotationTool.doubleUnderline,
    AnnotationTool.strikethrough,
    AnnotationTool.bookmark,
  ];

  @override
  void initState() {
    super.initState();
    _tool = widget.initialTool == AnnotationTool.comment
        ? AnnotationTool.highlight
        : widget.initialTool;
    _noteController = TextEditingController(text: widget.existing?.note ?? '');
    _tag = widget.existing?.tag;
  }

  @override
  void dispose() {
    _noteController.dispose();
    super.dispose();
  }

  void _selectTag(AnnotationTag tag) {
    setState(() {
      if (_tag == tag) {
        _tag = null;
      } else {
        _tag = tag;
        if (_noteController.text.isEmpty) {
          _noteController.text = _tagPrompts[tag]!;
          _noteController.selection =
              TextSelection.collapsed(offset: _noteController.text.length);
        }
      }
    });
  }

  Future<void> _save() async {
    final note = _noteController.text.trim();
    final position = widget.existing?.position ?? widget.fraction;
    final annotation = Annotation(
      id: _reservedId ?? widget.existing?.id ?? newId(),
      selectedText: widget.selectedText,
      prefix: widget.prefix,
      suffix: widget.suffix,
      tool: _tool,
      note: note.isEmpty ? null : note,
      tag: _tag,
      timestamp: widget.existing?.timestamp ?? DateTime.now(),
      position: position,
      hasInk: _inkCaptured || (widget.existing?.hasInk ?? false),
    );
    await widget.store.saveAnnotation(annotation);
    await widget.onSaved();
    if (mounted) Navigator.of(context).pop();
  }

  Future<void> _delete() async {
    final id = widget.existing?.id;
    if (id == null) return;
    final confirm = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFFF5F0E8),
        title: const Text('Delete annotation?',
            style: TextStyle(fontFamily: 'Literata')),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Cancel',
                style: TextStyle(fontFamily: 'Literata', color: Colors.black54)),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Delete',
                style: TextStyle(fontFamily: 'Literata', color: Colors.black87)),
          ),
        ],
      ),
    );
    if (confirm != true) return;
    await widget.store.deleteAnnotation(id);
    await widget.onSaved();
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: Color(0xFFF5F0E8),
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      padding: EdgeInsets.fromLTRB(
          24, 20, 24, MediaQuery.of(context).viewInsets.bottom + 24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header row
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                _isEditing ? 'Edit Note' : 'Add Note',
                style: const TextStyle(
                  fontFamily: 'Literata',
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                  color: Colors.black87,
                ),
              ),
              Row(
                children: [
                  if (_isEditing)
                    GestureDetector(
                      onTap: _delete,
                      child: const Padding(
                        padding: EdgeInsets.only(right: 12),
                        child: Icon(Icons.delete_outline,
                            size: 22, color: Colors.black38),
                      ),
                    ),
                  GestureDetector(
                    onTap: () => Navigator.of(context).pop(),
                    child: const Icon(Icons.close, size: 22, color: Colors.black54),
                  ),
                ],
              ),
            ],
          ),
          const SizedBox(height: 14),

          // Tool selector
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            child: Row(
              children: _selectorTools.map((tool) {
                final selected = _tool == tool;
                return GestureDetector(
                  onTap: () => setState(() => _tool = tool),
                  child: Container(
                    margin: const EdgeInsets.only(right: 8),
                    width: 40,
                    height: 40,
                    decoration: BoxDecoration(
                      color: isEink
                          ? (selected ? Colors.transparent : Colors.black.withValues(alpha: 0.06))
                          : (selected ? Colors.black87 : Colors.transparent),
                      borderRadius: BorderRadius.circular(8),
                      border: Border.all(
                        color: selected ? Colors.black87 : Colors.black26,
                        width: selected && isEink ? 2 : 1,
                      ),
                    ),
                    child: Center(
                      child: ToolButton(
                        tool: tool,
                        onTap: () => setState(() => _tool = tool),
                      ),
                    ),
                  ),
                );
              }).toList(),
            ),
          ),
          const SizedBox(height: 14),

          // Quote box
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
            decoration: BoxDecoration(
              color: Colors.black.withValues(alpha: 0.06),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Text(
              '"${widget.selectedText}"',
              style: const TextStyle(
                fontFamily: 'Literata',
                fontSize: 14,
                fontStyle: FontStyle.italic,
                color: Colors.black54,
                height: 1.5,
              ),
              maxLines: 4,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          const SizedBox(height: 12),

          // Ink capture — iOS PencilKit or Supernote drawPath pen.
          if (Platform.isIOS || isEink) ...[
            GestureDetector(
              onTap: () async {
                // Generate the ID once here so both saveInkPng and
                // saveAnnotation use the same key.
                final id = widget.existing?.id ?? newId();
                if (isEink) {
                  final bytes = await Navigator.push<List<int>>(
                    context,
                    PageRouteBuilder(
                      pageBuilder: (_, _, _) => InkCanvasScreen(selectedText: widget.selectedText),
                      transitionDuration: Duration.zero,
                      reverseTransitionDuration: Duration.zero,
                    ),
                  );
                  if (bytes == null || bytes.isEmpty || !mounted) return;
                  await widget.store.saveInkPng(id, bytes);
                  setState(() {
                    _reservedId = id;
                    _inkCaptured = true;
                  });
                } else {
                  final saved = await captureInk(
                    annotationId: id,
                    store: widget.store,
                  );
                  if (saved && mounted) {
                    setState(() {
                      _reservedId = id;
                      _inkCaptured = true;
                    });
                  }
                }
              },
              child: Container(
                width: double.infinity,
                height: 48,
                decoration: BoxDecoration(
                  color: _inkCaptured
                      ? Colors.black87
                      : Colors.black.withValues(alpha: 0.04),
                  borderRadius: BorderRadius.circular(8),
                  border: Border.all(
                    color: _inkCaptured ? Colors.black87 : Colors.black12,
                    width: 1,
                  ),
                ),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    Icon(
                      _inkCaptured ? Icons.draw : Icons.draw_outlined,
                      size: 18,
                      color: _inkCaptured
                          ? const Color(0xFFF5F0E8)
                          : Colors.black54,
                    ),
                    const SizedBox(width: 8),
                    Text(
                      _inkCaptured ? 'Ink saved' : 'Add ink',
                      style: TextStyle(
                        fontFamily: 'Literata',
                        fontSize: 14,
                        color: _inkCaptured
                            ? const Color(0xFFF5F0E8)
                            : Colors.black54,
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
          ],

          // Note field
          TextField(
            controller: _noteController,
            autofocus: !isEink,
            maxLines: null,
            keyboardType: TextInputType.multiline,
            style: const TextStyle(
                fontFamily: 'Literata', fontSize: 15, color: Colors.black87),
            decoration: InputDecoration(
              hintText: 'Write your note... (optional)',
              hintStyle: const TextStyle(
                  fontFamily: 'Literata', fontSize: 15, color: Colors.black38),
              filled: true,
              fillColor: Colors.black.withValues(alpha: 0.04),
              contentPadding: const EdgeInsets.all(12),
              enabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(8),
                borderSide: BorderSide.none,
              ),
              focusedBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(8),
                borderSide: const BorderSide(color: Colors.black26, width: 1),
              ),
            ),
          ),
          const SizedBox(height: 12),

          // Tag chips
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: AnnotationTag.values.map((tag) {
              final active = _tag == tag;
              final decoration = isEink
                  ? BoxDecoration(
                      color: active
                          ? Colors.transparent
                          : Colors.black.withValues(alpha: 0.08),
                      borderRadius: BorderRadius.circular(20),
                      border: active
                          ? Border.all(color: Colors.black87, width: 2)
                          : null,
                    )
                  : BoxDecoration(
                      color: active
                          ? Colors.black87
                          : Colors.black.withValues(alpha: 0.08),
                      borderRadius: BorderRadius.circular(20),
                    );
              final label = Text(
                _tagLabels[tag]!,
                style: TextStyle(
                  fontFamily: 'Literata',
                  fontSize: 13,
                  color: active
                      ? (isEink ? Colors.black87 : const Color(0xFFF5F0E8))
                      : Colors.black54,
                ),
              );
              final content = Padding(
                padding:
                    const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
                child: label,
              );
              return GestureDetector(
                onTap: () => _selectTag(tag),
                child: isEink
                    ? Container(decoration: decoration, child: content)
                    : AnimatedContainer(
                        duration: const Duration(milliseconds: 150),
                        decoration: decoration,
                        child: content,
                      ),
              );
            }).toList(),
          ),
          const SizedBox(height: 20),

          // Save button
          SizedBox(
            width: double.infinity,
            child: ElevatedButton(
              onPressed: _save,
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.black87,
                foregroundColor: const Color(0xFFF5F0E8),
                elevation: 0,
                padding: const EdgeInsets.symmetric(vertical: 14),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(8),
                ),
              ),
              child: Text(
                _isEditing ? 'Update' : 'Save',
                style: const TextStyle(
                  fontFamily: 'Literata',
                  fontSize: 15,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
