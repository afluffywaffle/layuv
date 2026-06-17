import 'package:flutter/material.dart';
import '../models/annotation.dart';
import '../utils/circle_tappable.dart';
import '../utils/platform_utils.dart';
import 'appbar_pill.dart';

class AnnotationToolbar extends StatefulWidget {
  final TextSelectionToolbarAnchors anchors;
  final void Function(AnnotationTool) onToolSelected;
  final void Function(AnnotationTool) onLockTool;
  final VoidCallback onDismiss;
  final bool dismissOnTapOutside;

  /// When true the full-screen tap-outside barrier is translucent rather than
  /// opaque, so a drag passes through to the reader beneath and immediately
  /// starts a new selection (the "start selecting over the toolbar" option). A
  /// tap on the barrier still dismisses (subject to [dismissOnTapOutside]).
  final bool passThrough;

  const AnnotationToolbar({
    super.key,
    required this.anchors,
    required this.onToolSelected,
    required this.onLockTool,
    required this.onDismiss,
    this.dismissOnTapOutside = true,
    this.passThrough = false,
  });

  @override
  State<AnnotationToolbar> createState() => _AnnotationToolbarState();
}

class _AnnotationToolbarState extends State<AnnotationToolbar> {
  // E-ink: taller + wider buttons so circles around icons stay within bounds.
  static double get _height => isEink ? 80.0 : 48.0;
  static double get _buttonWidth => isEink ? 64.0 : 44.0;
  static double get _hPadding => isEink ? 8.0 : 4.0;
  static const int _toolCount = 6;
  static double get _totalWidth => _buttonWidth * _toolCount + _hPadding * 2;

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
      padding: EdgeInsets.symmetric(horizontal: _hPadding, vertical: 4),
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
                  // comment isn't lockable; everything else can be locked.
                  onLock: tool == AnnotationTool.comment
                      ? null
                      : () => widget.onLockTool(tool),
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
          // Tap-outside barrier (dismiss / pass-through drags). Opaque by
          // default; translucent when pass-through is on so a drag reaches the
          // reader beneath and starts a new selection.
          Positioned.fill(
            child: GestureDetector(
              behavior: widget.passThrough
                  ? HitTestBehavior.translucent
                  : HitTestBehavior.opaque,
              onTap: () {
                if (widget.dismissOnTapOutside) widget.onDismiss();
              },
            ),
          ),
          // The floating tool row (ToolButton: tap = apply once, long-press =
          // apply-once / lock picker). Identical for pen and finger — with
          // drawPath gone the pen-up is delivered promptly, so the pen needs no
          // dwell workaround and pen long-press locks just like finger.
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

/// A single tappable tool icon with an optional long-press apply-once/lock
/// picker. Used both by the floating [AnnotationToolbar] and as the tool
/// selector in the annotation panel — a plain `GestureDetector(onTap:,
/// onLongPressStart:)`, which works for pen and finger alike (the Supernote
/// pen-up is delivered promptly once drawPath is out of the picture).
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
    final iconSize = isEink ? 28.0 : 20.0;
    final buttonW = isEink ? 64.0 : 44.0;
    final buttonH = isEink ? 64.0 : 40.0;
    final inner = GestureDetector(
      // Long-press only — tap/circle handled by CircleTappable wrapper on e-ink,
      // or by onTap here on non-e-ink.
      onTap: isEink ? null : widget.onTap,
      onLongPressStart: widget.onLock != null
          ? (d) => _showPicker(d.globalPosition)
          : null,
      child: SizedBox(
        width: buttonW,
        height: buttonH,
        child: Stack(
          children: [
            Center(child: ToolIcon(tool: widget.tool, size: iconSize)),
            // Photoshop-style corner triangle: signals that long-press reveals
            // more options. E-ink only; only on lockable buttons.
            if (isEink && widget.onLock != null)
              const Positioned(
                right: 4,
                bottom: 4,
                child: CustomPaint(
                  size: Size(6, 6),
                  painter: _CornerHintPainter(),
                ),
              ),
          ],
        ),
      ),
    );
    if (isEink) {
      // CircleTappable: fires onTap for both straight taps and pen circles.
      // Pointer-capture means the pen-up is delivered here even when the stroke
      // briefly exits the button's bounds mid-circle.
      return CircleTappable(onTap: widget.onTap, child: inner);
    }
    return inner;
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

/// A small floating "Undo" affordance shown immediately AFTER an annotation is
/// applied — the same overlay lifecycle as [AnnotationToolbar], but it pops up
/// on annotation-made instead of on text-selected. Lets an accidental
/// annotation (especially in locked-tool rapid mode) be removed without opening
/// the annotations panel. Tapping the pill calls [onUndo].
///
/// Deliberately has NO full-screen barrier: in locked-tool mode the user goes
/// straight from one circle to the next with no tool-tap in between, so a
/// barrier here would sit in the gesture arena for every new pen stroke and lag
/// the next selection. Only the pill itself is hit-testable; everything else
/// falls through to the reader. Dismissal is driven entirely by the caller
/// (a new selection start, the next selection, or navigation).
class UndoToolbar extends StatelessWidget {
  final Offset anchor;
  final VoidCallback onUndo;

  const UndoToolbar({
    super.key,
    required this.anchor,
    required this.onUndo,
  });

  @override
  Widget build(BuildContext context) {
    final height = isEink ? 80.0 : 48.0;
    final width = isEink ? 80.0 : 48.0;
    final iconSize = isEink ? 28.0 : 20.0;
    final screen = MediaQuery.of(context).size;

    final left = (anchor.dx - width / 2).clamp(8.0, screen.width - width - 8);
    final top = (anchor.dy - height - 8).clamp(8.0, screen.height - height - 8);

    final inner = SizedBox(
      width: width,
      height: height,
      child: Center(child: Icon(Icons.undo, size: iconSize, color: Colors.black87)),
    );

    final pill = Container(
      height: height,
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
      // CircleTappable on e-ink so a pen tap/circle over the pill fires onUndo,
      // matching the toolbar tool buttons. Plain tap on non-e-ink.
      child: isEink
          ? CircleTappable(onTap: onUndo, child: inner)
          : GestureDetector(onTap: onUndo, child: inner),
    );

    // The Stack only hit-tests the Positioned pill, so taps/strokes anywhere
    // else pass straight through to the reader beneath.
    return Material(
      type: MaterialType.transparency,
      child: Stack(
        children: [
          Positioned(
            left: left,
            top: top,
            child: Material(color: Colors.transparent, child: pill),
          ),
        ],
      ),
    );
  }
}

/// Floating actions shown when an existing annotation is tapped: Comment (open
/// the edit panel) and Delete (with confirmation). Anchored above the tapped
/// annotation, mirroring [AnnotationToolbar]. Annotation taps are finger-driven
/// (the pen is the selection tool), so a tap-outside barrier is safe here — it
/// is translucent so a pen drag still falls through to start a selection.
class AnnotationActionToolbar extends StatelessWidget {
  final Offset anchor;
  final VoidCallback onComment;
  final VoidCallback onDelete;
  final VoidCallback onDismiss;

  const AnnotationActionToolbar({
    super.key,
    required this.anchor,
    required this.onComment,
    required this.onDelete,
    required this.onDismiss,
  });

  @override
  Widget build(BuildContext context) {
    final height = isEink ? 80.0 : 48.0;
    final hPadding = isEink ? 8.0 : 4.0;
    // Comment + Delete, each a labelled button.
    final buttonWidth = isEink ? 120.0 : 92.0;
    final totalWidth = buttonWidth * 2 + hPadding * 2;
    final screen = MediaQuery.of(context).size;

    final left =
        (anchor.dx - totalWidth / 2).clamp(8.0, screen.width - totalWidth - 8);
    final top =
        (anchor.dy - height - 8).clamp(8.0, screen.height - height - 8);

    final pill = Container(
      height: height,
      padding: EdgeInsets.symmetric(horizontal: hPadding, vertical: 4),
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
        children: [
          _ActionButton(
            icon: Icons.chat_bubble_outline,
            label: 'Comment',
            width: buttonWidth,
            onTap: onComment,
          ),
          SizedBox(
            width: 1,
            height: isEink ? 48.0 : 32.0,
            child: const ColoredBox(color: Colors.black12),
          ),
          _ActionButton(
            icon: Icons.delete_outline,
            label: 'Delete',
            width: buttonWidth,
            onTap: onDelete,
          ),
        ],
      ),
    );

    return Material(
      type: MaterialType.transparency,
      child: Stack(
        children: [
          // Translucent tap-outside barrier: a tap dismisses, a pen drag falls
          // through to start a selection (which also dismisses upstream).
          Positioned.fill(
            child: GestureDetector(
              behavior: HitTestBehavior.translucent,
              onTap: onDismiss,
            ),
          ),
          Positioned(
            left: left,
            top: top,
            child: Material(color: Colors.transparent, child: pill),
          ),
        ],
      ),
    );
  }
}

class _ActionButton extends StatelessWidget {
  final IconData icon;
  final String label;
  final double width;
  final VoidCallback onTap;

  const _ActionButton({
    required this.icon,
    required this.label,
    required this.width,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final iconSize = isEink ? 26.0 : 18.0;
    final fontSize = isEink ? 16.0 : 13.0;
    final inner = SizedBox(
      width: width,
      height: isEink ? 72.0 : 40.0,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(icon, size: iconSize, color: Colors.black87),
          const SizedBox(width: 8),
          Text(
            label,
            style: TextStyle(
              fontFamily: 'Literata',
              fontSize: fontSize,
              color: Colors.black87,
            ),
          ),
        ],
      ),
    );
    // CircleTappable on e-ink so a pen or finger tap (or small circle) fires
    // reliably despite the stylus pen-up delay; plain tap elsewhere.
    if (isEink) return CircleTappable(onTap: onTap, child: inner);
    return GestureDetector(onTap: onTap, child: inner);
  }
}

/// Small right-triangle painted in the bottom-right corner of a [ToolButton]
/// to signal that long-press reveals more options (Photoshop-style).
class _CornerHintPainter extends CustomPainter {
  const _CornerHintPainter();

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = Colors.black54
      ..style = PaintingStyle.fill;
    final path = Path()
      ..moveTo(size.width, 0)
      ..lineTo(size.width, size.height)
      ..lineTo(0, size.height)
      ..close();
    canvas.drawPath(path, paint);
  }

  @override
  bool shouldRepaint(_CornerHintPainter _) => false;
}
