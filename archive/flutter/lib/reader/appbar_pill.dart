import 'package:flutter/material.dart';
import '../models/annotation.dart';
import '../utils/pen_tappable.dart';
import '../utils/platform_utils.dart';

class AppBarPill extends StatelessWidget {
  final ReadingMode readingMode;
  final AnnotationTool? lockedTool;
  final VoidCallback onClose;
  final void Function(ReadingMode) onModeSelected;
  final VoidCallback onUnlock;
  final VoidCallback onAnnotations;
  final VoidCallback onUndo;
  final bool canUndo;
  final bool hasAnnotations;
  final bool twoColumnEnabled;
  final VoidCallback onToggleTwoColumn;
  final VoidCallback? onEinkSettings;

  const AppBarPill({
    super.key,
    required this.readingMode,
    required this.lockedTool,
    required this.onClose,
    required this.onModeSelected,
    required this.onUnlock,
    required this.onAnnotations,
    required this.onUndo,
    this.canUndo = false,
    this.hasAnnotations = true,
    this.twoColumnEnabled = false,
    required this.onToggleTwoColumn,
    this.onEinkSettings,
  });

  Widget get _divider => SizedBox(
        width: 1,
        height: isEink ? 40.0 : 32.0,
        child: const ColoredBox(color: Colors.black12),
      );

  Widget _pillButton({
    required Widget child,
    required VoidCallback onTap,
  }) {
    final padded = Padding(
      padding: isEink
          ? const EdgeInsets.symmetric(horizontal: 16, vertical: 12)
          : const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      child: child,
    );
    // On e-ink the OS holds the stylus pen-up over Flutter UI, so an InkWell tap
    // never resolves with the pen. PenTappable commits on pen-down dwell.
    if (isEink) {
      return PenTappable(onTap: onTap, child: padded);
    }
    return InkWell(
      borderRadius: BorderRadius.circular(16),
      onTap: onTap,
      child: padded,
    );
  }

  Widget _modeIcon(ReadingMode mode) {
    final icon = switch (mode) {
      ReadingMode.scroll => Icons.unfold_more,
      ReadingMode.screenFlip => Icons.arrow_upward,
      ReadingMode.pageFlip => Icons.arrow_forward,
    };
    return Icon(icon, size: isEink ? 28.0 : 20.0, color: Colors.black87);
  }

  Widget _lockSlot(AnnotationTool tool) {
    final sz = isEink ? 28.0 : 20.0;
    return Stack(
      clipBehavior: Clip.none,
      children: [
        SizedBox(
          width: sz,
          height: sz,
          child: Align(
            alignment: Alignment.center,
            child: ToolIcon(tool: tool, size: sz),
          ),
        ),
        Positioned(
          right: -4,
          bottom: -4,
          child: Container(
            width: 12,
            height: 12,
            decoration: const BoxDecoration(
              color: Color(0xFFF5F0E8),
              shape: BoxShape.circle,
            ),
            child: const Icon(Icons.lock, size: 8, color: Colors.black87),
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final locked = lockedTool;

    final pill = Container(
      decoration: BoxDecoration(
        color: Colors.black.withValues(alpha: 0.06),
        borderRadius: BorderRadius.circular(20),
      ),
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          _pillButton(
            onTap: onAnnotations,
            child: Icon(Icons.list_alt, size: isEink ? 28.0 : 20.0,
                color: hasAnnotations ? Colors.black87 : Colors.black26),
          ),

          _divider,

          // Persistent undo — removes the most recent annotation. Dimmed (but
          // still laid out, so the pill width doesn't jump) when there's nothing
          // to undo; the tap is a no-op in that state.
          _pillButton(
            onTap: onUndo,
            child: Icon(
              Icons.undo,
              size: isEink ? 28.0 : 20.0,
              color: canUndo ? Colors.black87 : Colors.black26,
            ),
          ),

          _divider,

          _pillButton(
            onTap: onClose,
            child: Icon(Icons.close, size: isEink ? 28.0 : 20.0, color: Colors.black87),
          ),

          _divider,

          if (!isEink)
            PopupMenuButton<ReadingMode>(
              initialValue: readingMode,
              onSelected: onModeSelected,
              offset: const Offset(0, 40),
              color: const Color(0xFFF5F0E8),
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
              itemBuilder: (_) => [
                _modeMenuItem(ReadingMode.scroll, Icons.unfold_more, 'Scroll'),
                _modeMenuItem(ReadingMode.screenFlip, Icons.arrow_upward, 'Screen Flip'),
                _modeMenuItem(ReadingMode.pageFlip, Icons.arrow_forward, 'Page Flip'),
              ],
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                child: _modeIcon(readingMode),
              ),
            ),

          if (isEink) _divider,

          // Lock slot — visible whenever a tool is locked
          if (locked != null) ...[
            _divider,
            _pillButton(
              onTap: onUnlock,
              child: _lockSlot(locked),
            ),
          ],

          _divider,

          PopupMenuButton<String>(
            offset: const Offset(0, 40),
            color: const Color(0xFFF5F0E8),
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
            onSelected: (value) {
              if (value == 'two_column') onToggleTwoColumn();
              if (value == 'eink_settings') onEinkSettings?.call();
            },
            itemBuilder: (_) => [
              if (isEink)
                PopupMenuItem<String>(
                  value: 'eink_settings',
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: const [
                      Icon(Icons.settings_outlined, size: 20, color: Colors.black87),
                      SizedBox(width: 12),
                      Text('E-ink settings', style: TextStyle(fontFamily: 'Literata')),
                    ],
                  ),
                ),
              if (readingMode == ReadingMode.pageFlip)
                PopupMenuItem<String>(
                  value: 'two_column',
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(
                        twoColumnEnabled
                            ? Icons.view_column
                            : Icons.view_agenda_outlined,
                        size: 20,
                        color: Colors.black87,
                      ),
                      const SizedBox(width: 12),
                      Text(
                        twoColumnEnabled ? '2 columns: on' : '2 columns: off',
                        style: const TextStyle(fontFamily: 'Literata'),
                      ),
                    ],
                  ),
                ),
              const PopupMenuItem<String>(
                enabled: false,
                child: Text(
                  'Export (coming soon)',
                  style: TextStyle(fontFamily: 'Literata', color: Colors.black38),
                ),
              ),
            ],
            child: Padding(
              padding: isEink
                  ? const EdgeInsets.symmetric(horizontal: 16, vertical: 12)
                  : const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              child: Icon(Icons.more_horiz, size: isEink ? 28.0 : 20.0, color: Colors.black87),
            ),
          ),
        ],
      ),
    );

    // Subtle cross-fade when lock slot appears/disappears on non-e-ink.
    if (isEink) return IntrinsicWidth(child: pill);

    return IntrinsicWidth(
      child: AnimatedSwitcher(
        duration: const Duration(milliseconds: 150),
        child: KeyedSubtree(
          key: ValueKey(locked),
          child: pill,
        ),
      ),
    );
  }

  PopupMenuItem<ReadingMode> _modeMenuItem(
    ReadingMode mode,
    IconData icon,
    String label,
  ) =>
      PopupMenuItem<ReadingMode>(
        value: mode,
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          Icon(icon, size: 20),
          const SizedBox(width: 12),
          Text(label, style: const TextStyle(fontFamily: 'Literata')),
        ]),
      );
}

// ─── Tool icon (shared by pill lock slot, toolbar, and lock picker) ───────────

class ToolIcon extends StatelessWidget {
  final AnnotationTool tool;
  final double size;

  const ToolIcon({super.key, required this.tool, required this.size});

  @override
  Widget build(BuildContext context) {
    final fontSize = size * 0.875;
    final base = TextStyle(
      fontFamily: 'Literata',
      fontSize: fontSize,
      fontWeight: FontWeight.bold,
      color: Colors.black87,
    );
    switch (tool) {
      case AnnotationTool.highlight:
        return Container(
          width: size,
          height: size * 0.7,
          decoration: BoxDecoration(
            color: isEink ? const Color(0x26000000) : const Color(0xFFF5D76E),
            border: Border.all(color: Colors.black38, width: 0.5),
          ),
        );
      case AnnotationTool.underline:
        return SizedBox(
          width: size,
          height: size,
          child: Stack(
            clipBehavior: Clip.hardEdge,
            children: [
              Center(
                child: FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text('U', style: base.copyWith(decoration: TextDecoration.none)),
                ),
              ),
              Positioned(bottom: 1, left: 2, right: 2, child: Container(height: 1, color: Colors.black87)),
            ],
          ),
        );
      case AnnotationTool.doubleUnderline:
        return SizedBox(
          width: size,
          height: size,
          child: Stack(
            clipBehavior: Clip.hardEdge,
            children: [
              Center(
                child: FittedBox(
                  fit: BoxFit.scaleDown,
                  child: Text('U', style: base.copyWith(decoration: TextDecoration.none)),
                ),
              ),
              Positioned(bottom: 3, left: 2, right: 2, child: Container(height: 1, color: Colors.black87)),
              Positioned(bottom: 1, left: 2, right: 2, child: Container(height: 1, color: Colors.black87)),
            ],
          ),
        );
      case AnnotationTool.strikethrough:
        return SizedBox(
          width: size,
          height: size,
          child: FittedBox(
            fit: BoxFit.scaleDown,
            child: Text('S',
                style: base.copyWith(
                  decoration: TextDecoration.lineThrough,
                  decorationColor: Colors.black87,
                )),
          ),
        );
      case AnnotationTool.wavyUnderline:
        return SizedBox(
          width: size,
          height: size,
          child: FittedBox(
            fit: BoxFit.scaleDown,
            child: Text('W',
                style: base.copyWith(
                  decoration: TextDecoration.underline,
                  decorationStyle: TextDecorationStyle.wavy,
                  decorationColor: Colors.black87,
                )),
          ),
        );
      case AnnotationTool.bookmark:
        return Icon(Icons.bookmark_border, size: size, color: Colors.black87);
      case AnnotationTool.comment:
        return Icon(Icons.chat_bubble_outline, size: size, color: Colors.black87);
      case AnnotationTool.inkAnnotation:
        return Icon(Icons.edit_outlined, size: size, color: Colors.black87);
    }
  }
}
