import 'package:flutter/material.dart';
import '../models/annotation.dart';
import '../utils/platform_utils.dart';
import 'appbar_pill.dart';

class AnnotationToolbar extends StatefulWidget {
  final TextSelectionToolbarAnchors anchors;
  final void Function(AnnotationTool) onToolSelected;
  final void Function(AnnotationTool) onLockTool;
  final VoidCallback onDismiss;
  final bool dismissOnTapOutside;

  const AnnotationToolbar({
    super.key,
    required this.anchors,
    required this.onToolSelected,
    required this.onLockTool,
    required this.onDismiss,
    this.dismissOnTapOutside = true,
  });

  @override
  State<AnnotationToolbar> createState() => _AnnotationToolbarState();
}

class _AnnotationToolbarState extends State<AnnotationToolbar> {
  static const double _height = 48;
  static const double _buttonWidth = 44;
  static const double _hPadding = 4;
  static const int _toolCount = 6;
  static const double _totalWidth = _buttonWidth * _toolCount + _hPadding * 2;

  static const _tools = [
    AnnotationTool.highlight,
    AnnotationTool.underline,
    AnnotationTool.doubleUnderline,
    AnnotationTool.strikethrough,
    AnnotationTool.bookmark,
    AnnotationTool.comment,
  ];

  @override
  Widget build(BuildContext context) {
    final anchor = widget.anchors.primaryAnchor;
    final screen = MediaQuery.of(context).size;

    final left = (anchor.dx - _totalWidth / 2)
        .clamp(8.0, screen.width - _totalWidth - 8);
    final top = (anchor.dy - _height - 8)
        .clamp(8.0, screen.height - _height - 8);

    final toolbar = Container(
      height: _height,
      padding: const EdgeInsets.symmetric(horizontal: _hPadding, vertical: 4),
      decoration: BoxDecoration(
        color: const Color(0xFFF5F0E8),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: Colors.black12),
        boxShadow: isEink
            ? null
            : [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.12),
                  blurRadius: 8,
                  offset: const Offset(0, 2),
                ),
              ],
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: _tools
            .map((tool) => ToolButton(
                  tool: tool,
                  onTap: () => widget.onToolSelected(tool),
                  onLock: tool != AnnotationTool.comment
                      ? () => widget.onLockTool(tool)
                      : null,
                ))
            .toList(),
      ),
    );

    final positioned = isEink
        ? toolbar
        : TweenAnimationBuilder<double>(
            tween: Tween(begin: 0.0, end: 1.0),
            duration: const Duration(milliseconds: 120),
            builder: (_, v, child) => Opacity(opacity: v, child: child),
            child: toolbar,
          );

    return Material(
      type: MaterialType.transparency,
      child: Stack(
        children: [
          Positioned.fill(
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () {
                if (widget.dismissOnTapOutside) widget.onDismiss();
              },
            ),
          ),
          Positioned(
            left: left,
            top: top,
            child: Material(
              color: Colors.transparent,
              child: positioned,
            ),
          ),
        ],
      ),
    );
  }
}

class ToolButton extends StatefulWidget {
  final AnnotationTool tool;
  final VoidCallback onTap;
  final VoidCallback? onLock; // null = not lockable (comment, inkAnnotation)

  const ToolButton({
    super.key,
    required this.tool,
    required this.onTap,
    this.onLock,
  });

  @override
  State<ToolButton> createState() => _ToolButtonState();
}

class _ToolButtonState extends State<ToolButton> {
  OverlayEntry? _picker;

  @override
  void dispose() {
    _picker?.remove();
    super.dispose();
  }

  void _showPicker(Offset globalPosition) {
    _picker?.remove();
    _picker = OverlayEntry(
      builder: (_) => LockPickerOverlay(
        position: globalPosition,
        tool: widget.tool,
        onApplyOnce: () {
          _picker?.remove();
          _picker = null;
          widget.onTap();
        },
        onLock: () {
          _picker?.remove();
          _picker = null;
          widget.onLock!();
        },
        onDismiss: () {
          _picker?.remove();
          _picker = null;
        },
      ),
    );
    Overlay.of(context).insert(_picker!);
  }

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: widget.onTap,
      onLongPressStart: widget.onLock != null
          ? (d) => _showPicker(d.globalPosition)
          : null,
      child: SizedBox(
        width: 44,
        height: 40,
        child: Center(child: ToolIcon(tool: widget.tool, size: 20)),
      ),
    );
  }
}

class LockPickerOverlay extends StatelessWidget {
  final Offset position;
  final AnnotationTool tool;
  final VoidCallback onApplyOnce;
  final VoidCallback onLock;
  final VoidCallback onDismiss;

  const LockPickerOverlay({
    super.key,
    required this.position,
    required this.tool,
    required this.onApplyOnce,
    required this.onLock,
    required this.onDismiss,
  });

  static const double _width = 148;
  static const double _rowHeight = 44;
  static const double _totalHeight = _rowHeight * 2 + 1;

  @override
  Widget build(BuildContext context) {
    final screen = MediaQuery.of(context).size;
    final left = (position.dx - _width / 2)
        .clamp(8.0, screen.width - _width - 8);
    final top = (position.dy - _totalHeight - 12)
        .clamp(8.0, screen.height - _totalHeight - 8);

    final card = Container(
      width: _width,
      decoration: BoxDecoration(
        color: const Color(0xFFF5F0E8),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: Colors.black12),
        boxShadow: isEink
            ? null
            : [
                BoxShadow(
                  color: Colors.black.withValues(alpha: 0.14),
                  blurRadius: 10,
                  offset: const Offset(0, 3),
                ),
              ],
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(10),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            _PickerRow(
              tool: tool,
              showLock: false,
              label: 'Apply once',
              onTap: onApplyOnce,
              topRadius: true,
            ),
            const Divider(height: 1, thickness: 1, color: Colors.black12),
            _PickerRow(
              tool: tool,
              showLock: true,
              label: 'Lock tool',
              onTap: onLock,
              topRadius: false,
            ),
          ],
        ),
      ),
    );

    final animated = isEink
        ? card
        : TweenAnimationBuilder<double>(
            tween: Tween(begin: 0.0, end: 1.0),
            duration: const Duration(milliseconds: 100),
            builder: (_, v, child) => Opacity(opacity: v, child: child),
            child: card,
          );

    return Material(
      type: MaterialType.transparency,
      child: Stack(
        children: [
          Positioned.fill(
            child: GestureDetector(
              behavior: HitTestBehavior.translucent,
              onTap: onDismiss,
            ),
          ),
          Positioned(left: left, top: top, child: animated),
        ],
      ),
    );
  }
}

class _PickerRow extends StatelessWidget {
  final AnnotationTool tool;
  final bool showLock;
  final String label;
  final VoidCallback onTap;
  final bool topRadius;

  const _PickerRow({
    required this.tool,
    required this.showLock,
    required this.label,
    required this.onTap,
    required this.topRadius,
  });

  static const double _rowHeight = 44;

  @override
  Widget build(BuildContext context) {
    final radius = topRadius
        ? const BorderRadius.vertical(top: Radius.circular(10))
        : const BorderRadius.vertical(bottom: Radius.circular(10));

    Widget iconWidget = ToolIcon(tool: tool, size: 18);
    if (showLock) {
      iconWidget = Stack(
        clipBehavior: Clip.none,
        children: [
          iconWidget,
          Positioned(
            right: -5,
            bottom: -5,
            child: Container(
              width: 11,
              height: 11,
              decoration: const BoxDecoration(
                color: Color(0xFFF5F0E8),
                shape: BoxShape.circle,
              ),
              child: const Icon(Icons.lock, size: 7, color: Colors.black87),
            ),
          ),
        ],
      );
    }

    return InkWell(
      borderRadius: radius,
      onTap: onTap,
      child: SizedBox(
        height: _rowHeight,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14),
          child: Row(
            children: [
              SizedBox(width: 24, child: Center(child: iconWidget)),
              const SizedBox(width: 10),
              Text(
                label,
                style: const TextStyle(
                  fontFamily: 'Literata',
                  fontSize: 13,
                  color: Colors.black87,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
