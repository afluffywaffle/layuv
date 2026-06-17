import 'package:flutter/material.dart';

/// Truncated filename/title shown on the LEFT of the bottom bar (and in the
/// `bottomLeading` slot of the page-flip readers).
class ReaderTitleText extends StatelessWidget {
  final String title;
  const ReaderTitleText(this.title, {super.key});

  @override
  Widget build(BuildContext context) {
    return ConstrainedBox(
      constraints: const BoxConstraints(maxWidth: 160),
      child: Text(
        title,
        style: const TextStyle(
          fontFamily: 'SourceSans3',
          fontSize: 13,
          color: Colors.black45,
        ),
        overflow: TextOverflow.ellipsis,
        maxLines: 1,
      ),
    );
  }
}

/// Bottom bar for scroll / screen-flip modes: [leading] (title) on the left,
/// [trailing] (the AppBarPill cluster) on the right. The pageFlip readers use
/// their own counter zone via bottomLeading/bottomTrailing instead of this bar.
class ReaderBottomBar extends StatelessWidget {
  final Widget leading;
  final Widget trailing;
  const ReaderBottomBar({
    super.key,
    required this.leading,
    required this.trailing,
  });

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 64,
      child: Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Stack(
          children: [
            Positioned(
              left: 16,
              top: 0,
              bottom: 0,
              child: Center(child: leading),
            ),
            Positioned(
              right: 8,
              top: 0,
              bottom: 0,
              child: Center(child: trailing),
            ),
          ],
        ),
      ),
    );
  }
}
