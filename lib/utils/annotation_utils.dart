import 'package:flutter/material.dart';
import 'package:flutter/gestures.dart';
import '../models/annotation.dart';
import 'platform_utils.dart';

/// A DOCX-sourced formatting range: [start, end) in full-document plain-text
/// coordinates, paired with the style to apply (bold, italic, heading size…).
typedef DocxFormatSpan = ({int start, int end, TextStyle style});

const _kMarginIndicatorStyle = TextStyle(
  fontFamily: 'SourceSans3',
  fontSize: 11,
  color: Color(0x66000000),
  height: 1,
);

/// Page text paired with its start offset within the full document.
typedef ReaderPage = ({String text, int offset});

TextStyle get kReaderTextStyle => TextStyle(
  fontFamily: 'Literata',
  fontSize: 16,
  color: Colors.black87,
  height: isEink ? 1.85 : 1.6,
);

TextStyle styleForTool(TextStyle base, AnnotationTool tool) {
  switch (tool) {
    case AnnotationTool.highlight:
    case AnnotationTool.inkAnnotation:
    case AnnotationTool.comment:
      return isEink
          ? base.copyWith(
              decoration: TextDecoration.underline,
              decorationStyle: TextDecorationStyle.dotted,
            )
          : base.copyWith(backgroundColor: const Color(0xFFF5D76E));
    case AnnotationTool.underline:
      return base.copyWith(decoration: TextDecoration.underline);
    case AnnotationTool.doubleUnderline:
      return base.copyWith(
        decoration: TextDecoration.underline,
        decorationStyle: TextDecorationStyle.double,
      );
    case AnnotationTool.strikethrough:
      return base.copyWith(decoration: TextDecoration.lineThrough);
    case AnnotationTool.wavyUnderline:
      return base.copyWith(
        decoration: TextDecoration.underline,
        decorationStyle: TextDecorationStyle.wavy,
      );
    case AnnotationTool.bookmark:
      return base;
  }
}

TextStyle _combineStyles(TextStyle base, List<Annotation> annotations) {
  var style = base;
  final decorations = <TextDecoration>[];
  bool hasBackground = false;
  bool hasEinkHighlight = false;

  for (final ann in annotations) {
    switch (ann.tool) {
      case AnnotationTool.highlight:
      case AnnotationTool.comment:
      case AnnotationTool.inkAnnotation:
        if (!hasBackground) {
          if (isEink) {
            decorations.add(TextDecoration.underline);
            hasEinkHighlight = true;
          } else {
            style = style.copyWith(backgroundColor: const Color(0xFFF5D76E));
          }
          hasBackground = true;
        }
      case AnnotationTool.underline:
        decorations.add(TextDecoration.underline);
      case AnnotationTool.doubleUnderline:
        decorations.add(TextDecoration.underline);
      case AnnotationTool.strikethrough:
        decorations.add(TextDecoration.lineThrough);
      case AnnotationTool.wavyUnderline:
        decorations.add(TextDecoration.underline);
      case AnnotationTool.bookmark:
        break;
    }
  }

  if (decorations.isNotEmpty) {
    style = style.copyWith(decoration: TextDecoration.combine(decorations));
  }

  final hasDouble = annotations.any((a) => a.tool == AnnotationTool.doubleUnderline);
  if (hasDouble) {
    style = style.copyWith(decorationStyle: TextDecorationStyle.double);
  }

  final hasWavy = annotations.any((a) => a.tool == AnnotationTool.wavyUnderline);
  if (hasWavy && !hasDouble) {
    style = style.copyWith(decorationStyle: TextDecorationStyle.wavy);
  }

  if (hasEinkHighlight && !hasDouble && !hasWavy) {
    style = style.copyWith(decorationStyle: TextDecorationStyle.dotted);
  }

  return style;
}

/// Builds a [TextSpan] tree with annotated passages styled by their tool type.
/// Overlapping annotations are combined per-segment so multiple styles coexist.
// NOTE: TapGestureRecognizer instances are not disposed here — acceptable for a
// stable reading-app widget tree; revisit if memory pressure arises.
TextSpan buildAnnotatedText({
  required String sliceContent,
  required String fullContent,
  required List<Annotation> annotations,
  required int sliceOffset,
  required TextStyle baseStyle,
  required void Function(Annotation) onAnnotationTap,
  List<DocxFormatSpan> formatSpans = const [],
}) {
  final ranges = <({int start, int end, Annotation annotation})>[];

  for (final ann in annotations) {
    final fullOffset = findAnnotationOffset(fullContent, ann);
    if (fullOffset < 0) continue;
    final localStart = fullOffset - sliceOffset;
    final localEnd = localStart + ann.selectedText.length;
    if (localEnd <= 0 || localStart >= sliceContent.length) continue;
    ranges.add((
      start: localStart.clamp(0, sliceContent.length),
      end: localEnd.clamp(0, sliceContent.length),
      annotation: ann,
    ));
  }

  // Clip format spans to this slice.
  final localFormatSpans = <({int start, int end, TextStyle style})>[];
  for (final fs in formatSpans) {
    final localStart = fs.start - sliceOffset;
    final localEnd = fs.end - sliceOffset;
    if (localEnd <= 0 || localStart >= sliceContent.length) continue;
    localFormatSpans.add((
      start: localStart.clamp(0, sliceContent.length),
      end: localEnd.clamp(0, sliceContent.length),
      style: fs.style,
    ));
  }

  final hasAnyContent = ranges.isNotEmpty || localFormatSpans.isNotEmpty;
  if (!hasAnyContent) return TextSpan(text: sliceContent, style: baseStyle);

  // Build segment boundaries from annotation and format span endpoints.
  final boundaries = <int>{0, sliceContent.length};
  for (final r in ranges) {
    boundaries.add(r.start);
    boundaries.add(r.end);
  }
  for (final fs in localFormatSpans) {
    boundaries.add(fs.start);
    boundaries.add(fs.end);
  }
  final sortedBoundaries = boundaries.toList()..sort();

  final children = <InlineSpan>[];

  for (int i = 0; i < sortedBoundaries.length - 1; i++) {
    final segStart = sortedBoundaries[i];
    final segEnd = sortedBoundaries[i + 1];
    if (segStart >= segEnd) continue;

    // Base style: merge DOCX formatting (bold/italic/size) under reader baseStyle.
    TextStyle segBaseStyle = baseStyle;
    for (final fs in localFormatSpans) {
      if (fs.start <= segStart && fs.end >= segEnd) {
        segBaseStyle = baseStyle.merge(fs.style);
        break;
      }
    }

    final covering = ranges
        .where((r) => r.start <= segStart && r.end >= segEnd)
        .toList();

    final segText = sliceContent.substring(segStart, segEnd);

    if (covering.isEmpty) {
      children.add(TextSpan(text: segText, style: segBaseStyle));
      continue;
    }

    // First annotation is the tap target; bookmarks prepend their glyph once.
    final primary = covering.first.annotation;
    final recognizer = TapGestureRecognizer()..onTap = () => onAnnotationTap(primary);

    final nonBookmarks = covering.where((r) => r.annotation.tool != AnnotationTool.bookmark).toList();
    final hasBookmark = covering.any((r) => r.annotation.tool == AnnotationTool.bookmark);

    // Prepend bookmark glyph only at the bookmark annotation's start segment.
    if (hasBookmark) {
      final bookmarkR = covering.firstWhere((r) => r.annotation.tool == AnnotationTool.bookmark);
      if (bookmarkR.start == segStart) {
        children.add(TextSpan(
          text: '▶ ',
          style: baseStyle.copyWith(fontSize: 10, color: Colors.black45),
          recognizer: recognizer,
        ));
      }
    }

    final segStyle = nonBookmarks.isEmpty
        ? segBaseStyle
        : _combineStyles(segBaseStyle, nonBookmarks.map((r) => r.annotation).toList());

    // Span-start • dot: prepend before the first segment of each annotation that has a note.
    for (final r in covering) {
      final ann = r.annotation;
      if (ann.note == null || ann.note!.isEmpty) continue;
      if (r.start == segStart) {
        children.add(TextSpan(
          text: '•',
          style: baseStyle.copyWith(
            fontSize: 13,
            color: const Color(0x66000000),
            backgroundColor: Colors.transparent,
            decoration: TextDecoration.none,
          ),
        ));
        break;
      }
    }

    children.add(TextSpan(text: segText, style: segStyle, recognizer: recognizer));
  }

  return TextSpan(children: children);
}

/// Returns the character offset of [ann.selectedText] in [fullContent],
/// using prefix/suffix scoring to disambiguate repeated occurrences.
/// Returns -1 if not found.
int findAnnotationOffset(String fullContent, Annotation ann) {
  final text = ann.selectedText;
  int searchFrom = 0;
  int firstOccurrence = -1;

  while (true) {
    final idx = fullContent.indexOf(text, searchFrom);
    if (idx < 0) break;
    if (firstOccurrence < 0) firstOccurrence = idx;

    final prefixStart = (idx - ann.prefix.length).clamp(0, idx);
    final actualPrefix = fullContent.substring(prefixStart, idx);
    final prefixScore = overlapScore(actualPrefix, ann.prefix);

    final afterIdx = idx + text.length;
    final suffixEnd =
        (afterIdx + ann.suffix.length).clamp(0, fullContent.length);
    final actualSuffix = fullContent.substring(afterIdx, suffixEnd);
    final suffixScore = overlapScore(actualSuffix, ann.suffix);

    if (prefixScore > 0.5 || suffixScore > 0.5) return idx;
    searchFrom = idx + 1;
  }

  return firstOccurrence;
}

double overlapScore(String a, String b) {
  if (a.isEmpty || b.isEmpty) return 0;
  final len = a.length < b.length ? a.length : b.length;
  int matches = 0;
  for (int i = 0; i < len; i++) {
    if (a[i] == b[i]) matches++;
  }
  return matches / len;
}

/// Snaps [start] and [end] outward to full word boundaries within [text].
/// Only expands — never shrinks a selection.
TextRange snapToWordBoundaries(String text, int start, int end) {
  int s = start;
  while (s > 0 && !_isWordBoundary(text[s - 1])) { s--; }
  int e = end;
  while (e < text.length && !_isWordBoundary(text[e])) { e++; }
  return TextRange(start: s, end: e);
}

bool _isWordBoundary(String char) {
  return char == ' ' || char == '\n' || char == '\r' || char == '\t'
      || char == '.' || char == ',' || char == '!' || char == '?'
      || char == ';' || char == ':' || char == '"' || char == "'"
      || char == '(' || char == ')' || char == '[' || char == ']'
      || char == '—' || char == '–';
}

/// Returns the margin indicator style (exported for use in reader Stacks).
const marginIndicatorStyle = _kMarginIndicatorStyle;

/// Computes the vertical position of each annotated note within a text slice,
/// using TextPainter to measure caret offsets at annotation start positions.
List<({double topOffset, String label, bool emphasized})> buildMarginIndicators({
  required String sliceContent,
  required String fullContent,
  required List<Annotation> annotations,
  required int sliceOffset,
  required double lineHeight,
  required double maxWidth,
  String? emphasizedAnnotationId,
}) {
  if (maxWidth <= 0 || sliceContent.isEmpty) return const [];

  final painter = TextPainter(
    text: TextSpan(text: sliceContent, style: kReaderTextStyle),
    textDirection: TextDirection.ltr,
  )..layout(maxWidth: maxWidth);

  final result = <({double topOffset, String label, bool emphasized})>[];

  for (final ann in annotations) {
    if (ann.note == null || ann.note!.isEmpty) continue;
    if (ann.tool == AnnotationTool.bookmark) continue;

    final fullOffset = findAnnotationOffset(fullContent, ann);
    if (fullOffset < 0) continue;
    final localStart = fullOffset - sliceOffset;
    if (localStart < 0 || localStart >= sliceContent.length) continue;

    final caretOffset = painter.getOffsetForCaret(
      TextPosition(offset: localStart),
      Rect.zero,
    );

    final emphasized = ann.id == emphasizedAnnotationId;
    final label = emphasized
        ? '▶'
        : (ann.tag != null ? ann.tag!.name[0].toUpperCase() : '●');
    result.add((topOffset: caretOffset.dy, label: label, emphasized: emphasized));
  }

  return result;
}
