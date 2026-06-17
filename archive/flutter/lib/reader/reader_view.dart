// A dart:ui Paragraph-based, custom-painted reading surface for e-ink.
//
// This is a Flutter port of the native Kotlin reader core
// (android_native/.../reader/{Paginator,ReaderView,HighlightPainter}.kt). It
// replaces the e-ink PageFlipReader's SelectableText-per-column slices — which
// cannot select across columns and clip lines at page boundaries — with a
// single whole-book [ui.Paragraph] laid out once at the column width and sliced
// into columns by line ranges. A column is drawn by clip + translate around one
// `drawParagraph` pass (the StaticLayout pattern), so nothing is ever clipped
// mid-line and cross-column selection falls out for free.
//
// dart:ui has none of Android StaticLayout's line methods (verified against
// Flutter 3.44.1 / Dart 3.12.1): getLineForOffset / getPrimaryHorizontal /
// getLineStart-End / getOffsetForHorizontal / getLineForVertical / getLineTop-
// Bottom-Baseline DO NOT EXIST, and LineMetrics carries no char offsets. Every
// one is reconstructed here from numberOfLines + computeLineMetrics() +
// getLineNumberAt() + getLineBoundary() + getBoxesForRange() +
// getPositionForOffset(). See [PageLayout].
//
// The widget's public constructor mirrors PageFlipReader exactly so
// reader_screen.dart can swap it in for the e-ink pageFlip mode unchanged.

import 'dart:async';
import 'dart:collection';
import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';

import '../models/annotation.dart';
import '../models/reading_position.dart';
import '../utils/annotation_utils.dart';
import '../utils/eink_pen.dart';
import '../utils/platform_utils.dart';

/// One whole-book [ui.Paragraph] sliced into pages by line ranges.
///
/// The book is laid out ONCE at [columnWidth]; a page is a set of contiguous
/// columns and a column is a run of lines that fits [columnHeight]. A char
/// offset into the paragraph IS an index into the canonical plain text (the
/// same string), so [pageForChar] / [charStartOfPage] convert freely between a
/// reading position and a page — matching the native [PageLayout].
///
/// Two-column "newspaper" flow falls out for free: with [columns] == 2 the text
/// flows down the left column then continues at the top of the right, so page p
/// owns columns [p*2, p*2+2).
///
/// dart:ui Paragraph is a native handle that cannot cross an isolate boundary,
/// so this is always built and queried on the main isolate. The only expensive
/// step is [ui.Paragraph.computeLineMetrics]; the column-packing that follows is
/// pure Dart over the cached metrics. Per-line char ranges (the lazy
/// [lineCharRange] cache) are resolved on demand for the lines actually drawn,
/// never for the whole book.
class PageLayout {
  PageLayout._({
    required this.paragraph,
    required this.columns,
    required this.columnWidth,
    required this.columnGap,
    required this.contentWidth,
    required this.columnHeight,
    required this.length,
    required List<ui.LineMetrics> metrics,
    required List<int> columnStartLines,
  })  : _metrics = metrics,
        // ignore: prefer_initializing_formals — private field, named param.
        _columnStartLines = columnStartLines,
        _lineRangeCache = List<ui.TextRange?>.filled(metrics.length, null);

  /// The single whole-book paragraph. Drawn per-column via clip + translate.
  final ui.Paragraph paragraph;

  /// 1 or 2.
  final int columns;

  /// Width each column was laid out at (the paragraph's layout width).
  final double columnWidth;

  /// Gutter between the two columns (0 for one column).
  final double columnGap;

  /// Total content box width (both columns + gutter).
  final double contentWidth;

  /// The vertical box a single column must fit within.
  final double columnHeight;

  /// Length of the plain text (== paragraph text length).
  final int length;

  final List<ui.LineMetrics> _metrics;

  /// First layout line of each column, plus a trailing sentinel == lineCount.
  final List<int> _columnStartLines;

  /// Lazy per-line char range cache (newline-inclusive), filled on demand.
  final List<ui.TextRange?> _lineRangeCache;

  int get lineCount => _metrics.length;

  int get columnCount => _columnStartLines.length - 1;

  int get pageCount =>
      columnCount == 0 ? 1 : (columnCount + columns - 1) ~/ columns;

  int firstColumnOfPage(int page) => page * columns;

  /// First layout line shown in [column].
  int lineStartOfColumn(int column) => _columnStartLines[column];

  /// One-past-the-last layout line of [column].
  int lineEndOfColumn(int column) => _columnStartLines[column + 1];

  // --- Per-line geometry (replaces StaticLayout.getLine{Top,Bottom,...}) -----

  /// Top edge of [line] in paragraph coordinates (`baseline - ascent`).
  double lineTop(int line) {
    final m = _metrics[line];
    return m.baseline - m.ascent;
  }

  /// Bottom edge of [line] in paragraph coordinates (`baseline + descent`).
  double lineBottom(int line) {
    final m = _metrics[line];
    return m.baseline + m.descent;
  }

  double lineBaseline(int line) => _metrics[line].baseline;

  /// Font-intrinsic ascent of [line], ignoring the height multiplier — the
  /// analog of Android `fontMetrics.ascent`, used for strikethrough placement
  /// (the height-scaled [LineMetrics.ascent] would sit the strike too high).
  double lineUnscaledAscent(int line) => _metrics[line].unscaledAscent;

  double lineLeft(int line) => _metrics[line].left;

  /// Right text edge of [line] (`left + width` — the glyph extent, not the box).
  double lineRight(int line) {
    final m = _metrics[line];
    return m.left + m.width;
  }

  /// The visual line containing [charOffset] (replaces getLineForOffset).
  int lineForChar(int charOffset) {
    if (lineCount == 0) return 0;
    final clamped = charOffset.clamp(0, length);
    final ln = paragraph.getLineNumberAt(clamped);
    if (ln != null) return ln.clamp(0, lineCount - 1);
    // Null at/after the last visible code unit — fall back to the last line.
    return lineCount - 1;
  }

  /// The visual line at paragraph-space [y] (replaces getLineForVertical).
  /// Binary search over the (monotonically increasing) line tops.
  int lineForVertical(double y) {
    if (lineCount == 0) return 0;
    if (y <= lineTop(0)) return 0;
    if (y >= lineTop(lineCount - 1)) return lineCount - 1;
    int lo = 0;
    int hi = lineCount - 1;
    while (lo < hi) {
      final mid = (lo + hi + 1) >> 1;
      if (lineTop(mid) <= y) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    return lo;
  }

  /// Char range of [line] (newline included), via a left-edge caret probe.
  /// Replaces getLineStart/getLineEnd; cached because getLineBoundary is native.
  ui.TextRange lineCharRange(int line) {
    final cached = _lineRangeCache[line];
    if (cached != null) return cached;
    final probe = paragraph.getPositionForOffset(
      Offset(lineLeft(line) + 0.5, lineBaseline(line)),
    );
    final range = paragraph.getLineBoundary(probe);
    _lineRangeCache[line] = range;
    return range;
  }

  int lineStartChar(int line) => lineCharRange(line).start;

  int lineEndChar(int line) => lineCharRange(line).end;

  /// Nearest char offset to paragraph-space ([localX], on [line]).
  /// Replaces getOffsetForHorizontal(line, x).
  int charForPointOnLine(int line, double localX) {
    final pos = paragraph.getPositionForOffset(
      Offset(localX, lineBaseline(line)),
    );
    final range = lineCharRange(line);
    return pos.offset.clamp(range.start, range.end);
  }

  /// Leading-edge x of [char] in paragraph coordinates (replaces
  /// getPrimaryHorizontal). getBoxesForRange(o, o+1) is EMPTY when [char] sits
  /// exactly on a line break, so fall back to the previous glyph's trailing
  /// edge, then to the line's left — the same fallbacks the native reader used
  /// when getPrimaryHorizontal collapsed to the line start.
  double caretX(int char) {
    final boxes = paragraph.getBoxesForRange(
      char,
      char + 1,
      boxHeightStyle: ui.BoxHeightStyle.strut,
    );
    if (boxes.isNotEmpty) return boxes.first.left;
    if (char > 0) {
      final prev = paragraph.getBoxesForRange(
        char - 1,
        char,
        boxHeightStyle: ui.BoxHeightStyle.strut,
      );
      if (prev.isNotEmpty) return prev.last.right;
    }
    return lineLeft(lineForChar(char));
  }

  // --- Position <-> page -----------------------------------------------------

  /// Char offset of the first character on [page].
  int charStartOfPage(int page) {
    if (columnCount == 0) return 0;
    final col = firstColumnOfPage(page).clamp(0, columnCount - 1);
    return lineStartChar(_columnStartLines[col]);
  }

  /// The page that contains [charOffset].
  int pageForChar(int charOffset) {
    if (columnCount == 0) return 0;
    final line = lineForChar(charOffset);
    // Find the column whose [start, end) line range covers this line.
    var col = _columnStartLines.length - 2;
    for (var c = 0; c < _columnStartLines.length - 1; c++) {
      if (line < _columnStartLines[c + 1]) {
        col = c;
        break;
      }
    }
    return (col ~/ columns).clamp(0, pageCount - 1);
  }

  // --- View-space coordinate mapping (page + insets -> pixels) ---------------
  // These mirror the native ReaderView coordinate helpers. They are pure
  // geometry over the layout, so both the painter (handles, margin icons) and
  // the widget (hit-testing) share them. [leftPad]/[contentTop] are the content
  // box origin within the reading surface.

  /// x of the left edge of on-page column [colInPage].
  double columnX(int colInPage, double leftPad) =>
      leftPad + colInPage * (columnWidth + columnGap);

  /// Which on-page column (0-based within [page]) [char] falls in, or null if
  /// [char] is not on this page.
  int? columnOfChar(int page, int char) {
    final firstCol = firstColumnOfPage(page);
    for (var c = 0; c < columns; c++) {
      final col = firstCol + c;
      if (col >= columnCount) break;
      final colStart = lineStartChar(lineStartOfColumn(col));
      final colEnd = lineEndChar(lineEndOfColumn(col) - 1);
      if (char >= colStart && char <= colEnd) return c;
    }
    return null;
  }

  /// View-space point at the top ([bottom] == false) or bottom of [char]'s glyph
  /// on [page], or null if [char] is not on this page.
  Offset? charPointInView(
    int page,
    int char, {
    required bool bottom,
    required double leftPad,
    required double contentTop,
  }) {
    final colInPage = columnOfChar(page, char);
    if (colInPage == null) return null;
    final column = firstColumnOfPage(page) + colInPage;
    if (column >= columnCount) return null;
    final startLine = lineStartOfColumn(column);
    final endLine = lineEndOfColumn(column);
    final lineTopOfCol = lineTop(startLine);
    final line = lineForChar(char).clamp(startLine, endLine - 1);

    var x = caretX(char);
    // At a line-end/newline offset caretX collapses to the line start; fall back
    // to the right text edge so an end handle/anchor reads correctly.
    if (char > lineStartChar(line) && x <= caretX(lineStartChar(line))) {
      x = lineRight(line);
    }
    final yLayout = bottom ? lineBottom(line) : lineTop(line);
    return Offset(
      columnX(colInPage, leftPad) + x,
      contentTop + (yLayout - lineTopOfCol),
    );
  }

  /// Nearest char offset to a view-space touch ([vx],[vy]) on [page], or null if
  /// the point is above the content or outside the laid-out columns.
  int? charAtPoint(
    int page,
    double vx,
    double vy, {
    required double leftPad,
    required double contentTop,
  }) {
    final cx = vx - leftPad;
    final cy = vy - contentTop;
    if (cy < 0) return null;
    // Reject touches outside the laid-out content box (nav strips, past the right
    // edge) and, in two-column mode, the inter-column gutter. Without these an
    // out-of-range x silently clamps to the line's first/last char (a gutter tap
    // computes a negative localX -> line start; a past-right tap -> line end).
    if (cx < 0 || cx > contentWidth) return null;
    if (columns == 2 && cx >= columnWidth && cx < columnWidth + columnGap) {
      return null;
    }

    final colInPage = columns == 1 ? 0 : (cx < columnWidth ? 0 : 1);
    final localX =
        (cx - colInPage * (columnWidth + columnGap)).clamp(0.0, columnWidth);

    final column = firstColumnOfPage(page) + colInPage;
    if (column >= columnCount) return null;
    final startLine = lineStartOfColumn(column);
    final endLine = lineEndOfColumn(column);
    if (endLine <= startLine) return null;

    final lineTopOfCol = lineTop(startLine);
    final line = lineForVertical(cy + lineTopOfCol).clamp(startLine, endLine - 1);
    return charForPointOnLine(line, localX);
  }

  void dispose() => paragraph.dispose();

  // --- Construction ----------------------------------------------------------

  /// Builds the whole-book paragraph and computes column line-breaks for [text]
  /// at the given content box. [columns] is 1 or 2; [columnGap] is the gutter
  /// between two columns (ignored for one column). [formatSpans] carry DOCX
  /// bold/italic/heading runs in full-document char coordinates; annotation
  /// decorations are NOT baked in here (the painter draws them), so an
  /// annotation edit never forces a re-paginate.
  static PageLayout paginate({
    required String text,
    required List<DocxFormatSpan> formatSpans,
    required double contentWidth,
    required double contentHeight,
    required int columns,
    required double columnGap,
  }) {
    final cols = columns.clamp(1, 2);
    final gap = cols == 2 ? columnGap : 0.0;
    final columnWidth =
        ((contentWidth - gap * (cols - 1)) / cols).clamp(1.0, double.infinity);

    final paragraph = _buildParagraph(text, formatSpans, columnWidth);
    final metrics = paragraph.computeLineMetrics();
    final columnStarts = _computeColumnStarts(metrics, contentHeight);

    return PageLayout._(
      paragraph: paragraph,
      columns: cols,
      columnWidth: columnWidth,
      columnGap: gap,
      contentWidth: contentWidth,
      columnHeight: contentHeight,
      length: text.length,
      metrics: metrics,
      columnStartLines: columnStarts,
    );
  }

  /// Lays out [text] into a single paragraph at [width], applying [formatSpans]
  /// as styled runs over the canonical plain text. The base run style is
  /// [kReaderTextStyle]; a uniform strut reproduces the native StaticLayout's
  /// single line-spacing multiplier (clean, uniform e-ink columns).
  static ui.Paragraph _buildParagraph(
    String text,
    List<DocxFormatSpan> formatSpans,
    double width,
  ) {
    final base = kReaderTextStyle;
    final fontFamily = base.fontFamily ?? 'Literata';
    final fontSize = base.fontSize ?? 16.0;
    final lineHeight = base.height ?? 1.6;

    final paraStyle = ui.ParagraphStyle(
      textAlign: ui.TextAlign.left,
      textDirection: ui.TextDirection.ltr,
      fontFamily: fontFamily,
      fontSize: fontSize,
      height: lineHeight,
      strutStyle: ui.StrutStyle(
        fontFamily: fontFamily,
        fontSize: fontSize,
        height: lineHeight,
        leading: 0,
        forceStrutHeight: true,
        leadingDistribution: ui.TextLeadingDistribution.even,
      ),
      // includePad=false analog: don't apply the height multiplier to the very
      // first line's ascent / last line's descent, so the column hugs the top.
      textHeightBehavior: const ui.TextHeightBehavior(
        applyHeightToFirstAscent: false,
        applyHeightToLastDescent: false,
        leadingDistribution: ui.TextLeadingDistribution.even,
      ),
    );

    final builder = ui.ParagraphBuilder(paraStyle);

    final len = text.length;
    if (formatSpans.isEmpty || len == 0) {
      builder.pushStyle(base.getTextStyle());
      builder.addText(text);
      builder.pop();
    } else {
      // Cut the text at every format-span boundary, then style each segment by
      // the (single) span that fully covers it — mirrors buildAnnotatedText.
      final boundaries = SplayTreeSet<int>()..add(0)..add(len);
      for (final fs in formatSpans) {
        final s = fs.start.clamp(0, len);
        final e = fs.end.clamp(0, len);
        if (e <= s) continue;
        boundaries.add(s);
        boundaries.add(e);
      }
      final sorted = boundaries.toList();
      for (var i = 0; i < sorted.length - 1; i++) {
        final segStart = sorted[i];
        final segEnd = sorted[i + 1];
        if (segStart >= segEnd) continue;
        var style = base;
        for (final fs in formatSpans) {
          if (fs.start <= segStart && fs.end >= segEnd && fs.end > fs.start) {
            style = base.merge(fs.style);
            break;
          }
        }
        builder.pushStyle(style.getTextStyle());
        builder.addText(text.substring(segStart, segEnd));
        builder.pop();
      }
    }

    final paragraph = builder.build();
    paragraph.layout(ui.ParagraphConstraints(width: width));
    return paragraph;
  }

  /// Packs lines into columns, preferring to break BETWEEN paragraphs so a
  /// paragraph (which carries a concept) isn't cut across a column/page break.
  ///
  /// Each column is line-greedy-filled up to [columnHeight] (a line is included
  /// only if its BOTTOM fits — no clip, no safe-height fudge). If that natural
  /// break would fall mid-paragraph, the cut paragraph is pushed whole to the
  /// next column — but ONLY if doing so leaves <= [_kKeepWholeMaxGap] of the
  /// column empty; otherwise the paragraph is split (conservative with space).
  /// A paragraph taller than a whole column is split regardless (cross-split
  /// selection still works). Paragraph boundaries come from [ui.LineMetrics]
  /// `hardBreak` (a line that hard-breaks ends a paragraph). Returns
  /// column-first-line indices with a trailing sentinel == lineCount.
  static List<int> _computeColumnStarts(
    List<ui.LineMetrics> metrics,
    double columnHeight,
  ) {
    final lineCount = metrics.length;
    if (lineCount == 0) return <int>[0, 0];

    double top(int line) => metrics[line].baseline - metrics[line].ascent;
    double bottom(int line) => metrics[line].baseline + metrics[line].descent;

    // The paragraph-start line for every line: a line begins a paragraph when
    // the previous line ended at a hard break.
    final paraStartOf = List<int>.filled(lineCount, 0);
    var cur = 0;
    for (var line = 0; line < lineCount; line++) {
      if (line > 0 && metrics[line - 1].hardBreak) cur = line;
      paraStartOf[line] = cur;
    }

    final maxGap = columnHeight * _kKeepWholeMaxGap;
    final starts = <int>[];
    var line = 0;
    while (line < lineCount) {
      starts.add(line);
      final colTop = top(line);

      // Line-greedy: last line whose bottom still fits the column.
      var naturalEnd = line;
      while (naturalEnd + 1 < lineCount &&
          bottom(naturalEnd + 1) - colTop <= columnHeight) {
        naturalEnd++;
      }

      var columnEnd = naturalEnd;
      final next = naturalEnd + 1;
      // Only adjust if the natural break cuts a paragraph (the next line
      // continues naturalEnd's paragraph rather than starting a new one).
      if (next < lineCount && paraStartOf[next] != next) {
        final paraStart = paraStartOf[naturalEnd];
        if (paraStart > line) {
          // Whole paragraphs precede the cut one in this column: push it whole
          // to the next column if that wastes little, else split it.
          final gap = columnHeight - (bottom(paraStart - 1) - colTop);
          if (gap <= maxGap) columnEnd = paraStart - 1;
        }
        // else: the cut paragraph is taller than the column (or this column
        // already started mid-paragraph) → keep the line-greedy split.
      }
      line = columnEnd + 1;
    }
    starts.add(lineCount); // sentinel
    return starts;
  }
}

// --- Reader geometry & theme constants (mirror native ReaderTheme) -----------

const Color _kPaper = Color(0xFFF5F0E8); // warm paper, matches the Flutter app
const double _kHPadding = 26; // horizontal text inset
const double _kVPadding = 20; // vertical text inset
const double _kColumnGap = 34; // gutter between two columns
const double _kNavStripWidth = 80; // edge nav strip width (matches e-ink reader)
const double _kBottomZone = 64; // bottom page-counter zone height (52 content + 12 bottom buffer)
const double _kMinColumnWidth = 200; // two-column degrades below this per column
// Paragraph-atomic pagination: push a whole paragraph to the next column only if
// keeping it whole leaves <= this fraction of the column empty; otherwise split
// it. Low = conservative with space (split rather than leave obvious gaps).
const double _kKeepWholeMaxGap = 0.10;

const double _kUnderlineOffset = 3; // decoration drop below baseline
const double _kUnderlineStroke = 1.4;
const double _kDoubleGap = _kUnderlineOffset * 0.9;
const double _kDashOn = 2.2;
const double _kDashOff = 2.2;

// Highlight fill drawn in the overlay (no BlendMode): 60% white wash over the
// text area — makes black glyphs appear as mid-grey (~0x99) while leaving the
// warm paper nearly unchanged (~0xFB). Tune alpha for lighter/darker highlight.
const Color _kHighlightFill = Color(0xFFFFFFFF);
const double _kHighlightAlpha = 0.60;

const double _kHandleRadius = 9;
const double _kHandleStem = 6;
const double _kHandleGrab = 32; // large e-ink/finger grab radius
const double _kChevronHalfW = 8;
const double _kChevronHalfH = 12;
const double _kBodySize = 16; // == kReaderTextStyle.fontSize

/// An annotation resolved to a half-open char range [start, end) in the reader
/// text — the Dart analogue of the native ResolvedAnnotation. Produced by
/// [findAnnotationOffset] (prefix/suffix scoring), never by the 0..1 position.
@immutable
class ResolvedSpan {
  const ResolvedSpan({
    required this.annotation,
    required this.start,
    required this.end,
  });

  final Annotation annotation;
  final int start;
  final int end;
}

/// Which half of the reader a [ReaderPainter] draws. The two are stacked with a
/// RepaintBoundary on [base] so a selection/annotation change repaints only the
/// cheap [overlay] and never re-runs the whole-book `drawParagraph`.
enum ReaderLayer {
  /// Paper, the text columns (the one expensive whole-book drawParagraph), and
  /// the edge nav strips. Repaints only on page / layout / nav change.
  base,

  /// Annotation decorations, the active selection band, margin glyphs, and grab
  /// handles — everything that updates without touching the text raster.
  overlay,
}

/// Paints one [ReaderLayer] of the current page. [base] draws one
/// `drawParagraph` per column (clip + translate) plus paper and nav strips;
/// [overlay] draws annotation decorations, the active selection, grab handles
/// and margin indicators. Geometry is a port of the native HighlightPainter /
/// ReaderView.onDraw. Dotted lines are stroked as short segments (a reliable
/// substitute for DashPathEffect, which a plain dashed drawLine can't do).
class ReaderPainter extends CustomPainter {
  ReaderPainter({
    required this.layer,
    required this.layout,
    required this.currentPage,
    required this.resolved,
    required this.selectionStart,
    required this.selectionEnd,
    required this.isSelecting,
    required this.contentLeft,
    required this.contentTop,
    required this.navSide,
    required this.navReversed,
    required this.emphasizedAnnotationId,
    super.repaint,
  });

  final ReaderLayer layer;

  final PageLayout? layout;
  final int currentPage;
  final List<ResolvedSpan> resolved;
  final int selectionStart;
  final int selectionEnd;
  final bool isSelecting;
  final double contentLeft;
  final double contentTop;
  final String navSide;
  final bool navReversed;
  final String? emphasizedAnnotationId;

  static final Paint _paperPaint = Paint()..color = _kPaper;
  // Compositing layer for highlights: all spans are drawn opaque into this layer,
  // which is then composited at _kHighlightAlpha. Overlapping highlights share
  // the same wash level rather than compounding to near-invisible.
  static final Paint _highlightLayerPaint = Paint()
    ..color = _kHighlightFill.withValues(alpha: _kHighlightAlpha);
  static final Paint _highlightFillPaint = Paint()..color = _kHighlightFill;
  static final Paint _solidPaint = Paint()
    ..color = Colors.black
    ..strokeWidth = _kUnderlineStroke
    ..style = PaintingStyle.stroke;
  static final Paint _dottedPaint = Paint()
    ..color = Colors.black
    ..strokeWidth = _kUnderlineStroke
    ..strokeCap = StrokeCap.round
    ..style = PaintingStyle.stroke;
  static final Paint _navHairlinePaint = Paint()
    ..color = Colors.black.withValues(alpha: 0.35)
    ..strokeWidth = 1.5
    ..style = PaintingStyle.stroke;
  static final Paint _navChevronPaint = Paint()
    ..color = Colors.black.withValues(alpha: 0.22)
    ..strokeWidth = 2.5
    ..strokeCap = StrokeCap.round
    ..strokeJoin = StrokeJoin.round
    ..style = PaintingStyle.stroke;
  static final Paint _handleFill = Paint()
    ..color = Colors.black
    ..style = PaintingStyle.fill;
  static final Paint _handleStemPaint = Paint()
    ..color = Colors.black
    ..strokeWidth = 2
    ..style = PaintingStyle.stroke;

  @override
  void paint(Canvas canvas, Size size) {
    if (layer == ReaderLayer.base) {
      _paintBase(canvas, size);
    } else {
      _paintOverlay(canvas, size);
    }
  }

  /// The cached layer: paper, the text columns (the expensive whole-book
  /// drawParagraph), and the nav strips. Repaints only on page/layout/nav change.
  void _paintBase(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, _paperPaint);

    final pl = layout;
    if (pl == null) {
      _paintNavStrips(canvas, size);
      return;
    }

    final firstCol = pl.firstColumnOfPage(currentPage);
    for (var colInPage = 0; colInPage < pl.columns; colInPage++) {
      final column = firstCol + colInPage;
      if (column >= pl.columnCount) break;
      final startLine = pl.lineStartOfColumn(column);
      final endLine = pl.lineEndOfColumn(column);
      if (endLine <= startLine) continue;

      final colX = pl.columnX(colInPage, contentLeft);
      final lineTopOfCol = pl.lineTop(startLine);
      final packedHeight = (pl.lineBottom(endLine - 1) - lineTopOfCol)
          .clamp(0.0, pl.columnHeight);

      canvas.save();
      canvas.clipRect(
        Rect.fromLTWH(colX, contentTop, pl.columnWidth, packedHeight),
      );
      canvas.translate(colX, contentTop - lineTopOfCol);
      canvas.drawParagraph(pl.paragraph, Offset.zero);
      canvas.restore();
    }

    _paintNavStrips(canvas, size);
  }

  /// The dynamic layer: annotation decorations, the active selection band,
  /// margin glyphs, and grab handles — everything that changes on a selection or
  /// annotation edit without touching the (cached) text raster. No drawParagraph.
  void _paintOverlay(Canvas canvas, Size size) {
    final pl = layout;
    if (pl == null) return;

    final firstCol = pl.firstColumnOfPage(currentPage);
    for (var colInPage = 0; colInPage < pl.columns; colInPage++) {
      final column = firstCol + colInPage;
      if (column >= pl.columnCount) break;
      final startLine = pl.lineStartOfColumn(column);
      final endLine = pl.lineEndOfColumn(column);
      if (endLine <= startLine) continue;

      final colX = pl.columnX(colInPage, contentLeft);
      final lineTopOfCol = pl.lineTop(startLine);
      final packedHeight = (pl.lineBottom(endLine - 1) - lineTopOfCol)
          .clamp(0.0, pl.columnHeight);

      canvas.save();
      canvas.clipRect(
        Rect.fromLTWH(colX, contentTop, pl.columnWidth, packedHeight),
      );
      canvas.translate(colX, contentTop - lineTopOfCol);
      _paintHighlightFill(canvas, pl, startLine, endLine);
      _paintDecorations(canvas, pl, startLine, endLine);
      _paintSelection(canvas, pl, startLine, endLine);
      canvas.restore();
    }

    _paintMarginIcons(canvas, pl);
    _paintHandles(canvas, pl);
  }

  /// Grey-wash fill over every highlight span in [startLine, endLine). Drawn in
  /// the overlay before other decorations: no BlendMode, so this repaint is
  /// cheap — the base text layer never reruns drawParagraph for a highlight change.
  void _paintHighlightFill(
    Canvas canvas,
    PageLayout pl,
    int startLine,
    int endLine,
  ) {
    if (resolved.isEmpty) return;
    final colStartChar = pl.lineStartChar(startLine);
    final colEndChar = pl.lineEndChar(endLine - 1);
    // Draw all highlight rects into a single offscreen layer so overlapping
    // annotations share the same wash level instead of compounding to invisible.
    canvas.saveLayer(null, _highlightLayerPaint);
    for (final r in resolved) {
      if (r.annotation.tool != AnnotationTool.highlight) continue;
      final s = math.max(r.start, colStartChar);
      final e = math.min(r.end, colEndChar);
      if (s >= e) continue;
      final firstLine = pl.lineForChar(s);
      final lastLine = pl.lineForChar(e - 1);
      for (var line = firstLine; line <= lastLine; line++) {
        final ls = math.max(s, pl.lineStartChar(line));
        final le = math.min(e, pl.lineEndChar(line));
        if (ls >= le) continue;
        var xStart = pl.caretX(ls);
        var xEnd = pl.caretX(le);
        if (xEnd <= xStart) xEnd = pl.lineRight(line);
        if (xEnd < xStart) {
          final t = xStart;
          xStart = xEnd;
          xEnd = t;
        }
        canvas.drawRect(
          Rect.fromLTRB(xStart, pl.lineTop(line), xEnd, pl.lineBottom(line)),
          _highlightFillPaint,
        );
      }
    }
    canvas.restore();
  }

  /// Decorate every resolved annotation span intersecting [startLine, endLine).
  /// Drawn inside the column's canvas translate, so all coordinates are
  /// paragraph-space (caretX / baseline). Port of HighlightPainter.drawColumn.
  void _paintDecorations(
    Canvas canvas,
    PageLayout pl,
    int startLine,
    int endLine,
  ) {
    if (resolved.isEmpty) return;
    final colStartChar = pl.lineStartChar(startLine);
    final colEndChar = pl.lineEndChar(endLine - 1);

    for (final r in resolved) {
      final s = math.max(r.start, colStartChar);
      final e = math.min(r.end, colEndChar);
      if (s >= e) continue;
      final firstLine = pl.lineForChar(s);
      final lastLine = pl.lineForChar(e - 1);
      for (var line = firstLine; line <= lastLine; line++) {
        final ls = math.max(s, pl.lineStartChar(line));
        final le = math.min(e, pl.lineEndChar(line));
        if (ls >= le) continue;
        _drawDecoration(canvas, pl, line, ls, le, r.annotation.tool);
      }
    }
  }

  void _drawDecoration(
    Canvas canvas,
    PageLayout pl,
    int line,
    int startChar,
    int endChar,
    AnnotationTool tool,
  ) {
    var xStart = pl.caretX(startChar);
    var xEnd = pl.caretX(endChar);
    // caretX at a line-end/newline offset can collapse to the line start; fall
    // back to the line's right text edge (mirrors the native fallback).
    if (xEnd <= xStart) xEnd = pl.lineRight(line);
    if (xEnd < xStart) {
      final t = xStart;
      xStart = xEnd;
      xEnd = t;
    }
    final baseline = pl.lineBaseline(line);
    switch (tool) {
      case AnnotationTool.strikethrough:
        // ~35% of the font ascent above the baseline (mid-glyph), matching
        // native. Use the unscaled (font) ascent, not the strut-inflated one.
        final mid = baseline - pl.lineUnscaledAscent(line) * 0.35;
        _solidLine(canvas, xStart, xEnd, mid);
      case AnnotationTool.doubleUnderline:
        _solidLine(canvas, xStart, xEnd, baseline + _kUnderlineOffset);
        _solidLine(canvas, xStart, xEnd, baseline + _kUnderlineOffset + _kDoubleGap);
      case AnnotationTool.underline:
        _solidLine(canvas, xStart, xEnd, baseline + _kUnderlineOffset);
      case AnnotationTool.wavyUnderline:
        _wavyLine(canvas, xStart, xEnd, baseline + _kUnderlineOffset);
      case AnnotationTool.highlight:
        break; // filled in the overlay by _paintHighlightFill — no line here
      case AnnotationTool.comment:
      case AnnotationTool.inkAnnotation:
        // Dotted underline for comment/ink marks — e-ink safe (no fill).
        _dottedLine(canvas, xStart, xEnd, baseline + _kUnderlineOffset);
      case AnnotationTool.bookmark:
        break; // margin icon only — no line decoration
    }
  }

  /// Dotted underline beneath the active selection within this column.
  /// Port of HighlightPainter.drawSelection.
  void _paintSelection(Canvas canvas, PageLayout pl, int startLine, int endLine) {
    if (selectionStart < 0 || selectionEnd <= selectionStart) return;
    final colStartChar = pl.lineStartChar(startLine);
    final colEndChar = pl.lineEndChar(endLine - 1);
    if (selectionStart >= colEndChar || selectionEnd <= colStartChar) return;

    final firstLine =
        pl.lineForChar(selectionStart).clamp(startLine, endLine - 1);
    final lastLine = pl
        .lineForChar((selectionEnd - 1).clamp(selectionStart, pl.length))
        .clamp(startLine, endLine - 1);
    for (var line = firstLine; line <= lastLine; line++) {
      final ls = math.max(selectionStart, pl.lineStartChar(line));
      final le = math.min(selectionEnd, pl.lineEndChar(line));
      if (ls >= le) continue;
      var xStart = pl.caretX(ls);
      var xEnd = pl.caretX(le);
      if (xEnd <= xStart) xEnd = pl.lineRight(line);
      if (xEnd < xStart) {
        final t = xStart;
        xStart = xEnd;
        xEnd = t;
      }
      _dottedLine(canvas, xStart, xEnd, pl.lineBaseline(line) + _kUnderlineOffset);
    }
  }

  /// A small margin glyph for each annotation with a note whose anchor is on the
  /// current page (port of buildMarginIndicators: '▶' emphasized, else the tag
  /// initial, else '●'). Drawn in view space, in the gutter left of its column.
  void _paintMarginIcons(Canvas canvas, PageLayout pl) {
    for (final r in resolved) {
      final ann = r.annotation;
      if (ann.note == null || ann.note!.isEmpty) continue;
      if (ann.tool == AnnotationTool.bookmark) continue;
      final colInPage = pl.columnOfChar(currentPage, r.start);
      if (colInPage == null) continue;
      final top = pl.charPointInView(currentPage, r.start,
          bottom: false, leftPad: contentLeft, contentTop: contentTop);
      final bottom = pl.charPointInView(currentPage, r.start,
          bottom: true, leftPad: contentLeft, contentTop: contentTop);
      if (top == null || bottom == null) continue;
      final cy = (top.dy + bottom.dy) / 2;
      final colLeft = pl.columnX(colInPage, contentLeft);
      final gutter = colInPage == 0 ? _kHPadding : pl.columnGap;
      final cx = colLeft - gutter / 2;
      final emphasized = ann.id == emphasizedAnnotationId;
      final label = emphasized
          ? '▶'
          : (ann.tag != null ? ann.tag!.name[0].toUpperCase() : '●');
      _drawMarginLabel(canvas, label, cx, cy, emphasized);
    }
  }

  void _drawMarginLabel(
    Canvas canvas,
    String label,
    double cx,
    double cy,
    bool emphasized,
  ) {
    // Emphasized indicator matches PageFlipReader: larger + darker (no bold).
    final style = emphasized
        ? marginIndicatorStyle.copyWith(
            color: const Color(0xCC000000), fontSize: 13)
        : marginIndicatorStyle;
    final tp = TextPainter(
      text: TextSpan(text: label, style: style),
      textDirection: TextDirection.ltr,
    )..layout();
    tp.paint(canvas, Offset(cx - tp.width / 2, cy - tp.height / 2));
  }

  /// Grab handles below each end of a committed selection (port of
  /// ReaderView.drawHandleAt). Drawn only once the selection is committed.
  void _paintHandles(Canvas canvas, PageLayout pl) {
    if (selectionStart < 0 || selectionEnd <= selectionStart || isSelecting) {
      return;
    }
    final s = pl.charPointInView(currentPage, selectionStart,
        bottom: true, leftPad: contentLeft, contentTop: contentTop);
    final e = pl.charPointInView(currentPage, selectionEnd,
        bottom: true, leftPad: contentLeft, contentTop: contentTop);
    if (s != null) _drawHandle(canvas, s);
    if (e != null) _drawHandle(canvas, e);
  }

  void _drawHandle(Canvas canvas, Offset stemTop) {
    canvas.drawLine(
      stemTop,
      Offset(stemTop.dx, stemTop.dy + _kHandleStem),
      _handleStemPaint,
    );
    canvas.drawCircle(
      Offset(stemTop.dx, stemTop.dy + _kHandleStem + _kHandleRadius),
      _kHandleRadius,
      _handleFill,
    );
  }

  // --- Edge nav strips -------------------------------------------------------

  void _paintNavStrips(Canvas canvas, Size size) {
    if (navSide != 'right') _paintNavStrip(canvas, size, 0, _kNavStripWidth);
    if (navSide != 'left') {
      _paintNavStrip(canvas, size, size.width - _kNavStripWidth, size.width);
    }
  }

  void _paintNavStrip(Canvas canvas, Size size, double left, double right) {
    final midY = size.height / 2;
    final cx = (left + right) / 2;
    canvas.drawLine(Offset(left, midY), Offset(right, midY), _navHairlinePaint);
    // top = next normally; top = prev when reversed (RTL)
    _drawChevron(canvas, cx, size.height / 4, pointRight: !navReversed);
    _drawChevron(canvas, cx, size.height * 3 / 4, pointRight: navReversed);
  }

  void _drawChevron(
    Canvas canvas,
    double cx,
    double cy, {
    required bool pointRight,
  }) {
    final path = Path();
    if (pointRight) {
      path.moveTo(cx - _kChevronHalfW, cy - _kChevronHalfH);
      path.lineTo(cx + _kChevronHalfW, cy);
      path.lineTo(cx - _kChevronHalfW, cy + _kChevronHalfH);
    } else {
      path.moveTo(cx + _kChevronHalfW, cy - _kChevronHalfH);
      path.lineTo(cx - _kChevronHalfW, cy);
      path.lineTo(cx + _kChevronHalfW, cy + _kChevronHalfH);
    }
    canvas.drawPath(path, _navChevronPaint);
  }

  // --- Line primitives -------------------------------------------------------

  void _solidLine(Canvas canvas, double x0, double x1, double y) =>
      canvas.drawLine(Offset(x0, y), Offset(x1, y), _solidPaint);

  /// Dotted line as short stroked segments (DashPathEffect substitute).
  void _dottedLine(Canvas canvas, double x0, double x1, double y) {
    const step = _kDashOn + _kDashOff;
    var x = x0;
    while (x < x1) {
      final xe = math.min(x + _kDashOn, x1);
      canvas.drawLine(Offset(x, y), Offset(xe, y), _dottedPaint);
      x += step;
    }
  }

  void _wavyLine(Canvas canvas, double x0, double x1, double y) {
    const amp = 1.5;
    const wl = 6.0;
    final path = Path()..moveTo(x0, y);
    var x = x0;
    var up = true;
    while (x < x1) {
      final nx = math.min(x + wl / 2, x1);
      path.quadraticBezierTo((x + nx) / 2, y + (up ? -amp : amp), nx, y);
      x = nx;
      up = !up;
    }
    canvas.drawPath(path, _solidPaint);
  }

  @override
  bool shouldRepaint(covariant ReaderPainter old) {
    if (layer != old.layer ||
        layout != old.layout ||
        currentPage != old.currentPage ||
        contentLeft != old.contentLeft ||
        contentTop != old.contentTop) {
      return true;
    }
    // The base (text) layer ignores selection and annotation changes — highlights
    // are filled in the overlay, so a new annotation never triggers drawParagraph.
    if (layer == ReaderLayer.base) {
      return navSide != old.navSide || navReversed != old.navReversed;
    }
    return resolved != old.resolved ||
        selectionStart != old.selectionStart ||
        selectionEnd != old.selectionEnd ||
        isSelecting != old.isSelecting ||
        emphasizedAnnotationId != old.emphasizedAnnotationId;
  }
}

enum _Handle { none, start, end }

/// dart:ui Paragraph-based, custom-painted reading surface. A drop-in for the
/// e-ink pageFlip mode: its constructor mirrors PageFlipReader exactly so
/// reader_screen.dart can swap it in unchanged.
///
/// Unlike PageFlipReader (one SelectableText per column), this lays out the
/// whole book once as a [PageLayout] and paints columns by clip + translate, so
/// nothing clips at a page boundary and a selection can span columns. Selection,
/// page turns and annotation taps are driven by a raw pointer state machine
/// ported from the native ReaderView.onTouchEvent (no swipe — e-ink rule).
class ReaderView extends StatefulWidget {
  const ReaderView({
    super.key,
    required this.content,
    this.formatSpans = const [],
    required this.annotations,
    required this.savedPosition,
    required this.onSelection,
    required this.onAnnotationTap,
    required this.onPositionChanged,
    required this.jumpNotifier,
    this.onSelectionStart,
    this.emphasizedAnnotationId,
    this.twoColumn = false,
    this.cancelSelectionNotifier,
    this.einkNavSide = 'both',
    this.einkNavReversed = false,
    this.bottomLeading,
    this.bottomTrailing,
  });

  final String content;
  final List<DocxFormatSpan> formatSpans;
  final List<Annotation> annotations;
  final ReadingPosition? savedPosition;
  final void Function(
    String selectedText,
    String prefix,
    String suffix,
    Offset anchor,
    double fraction,
  ) onSelection;
  final void Function(Annotation, Offset) onAnnotationTap;
  final void Function(ReadingPosition) onPositionChanged;
  final ValueNotifier<double?> jumpNotifier;

  /// Fired when a NEW selection drag begins (stylus scrub past slop, or a finger
  /// long-press). Lets the screen dismiss a toolbar that is still up from the
  /// previous selection so a new lasso can be drawn without first tapping it
  /// away — used by the "start selecting over the toolbar" option.
  final VoidCallback? onSelectionStart;

  final String? emphasizedAnnotationId;
  final bool twoColumn;
  final ValueNotifier<int>? cancelSelectionNotifier;
  final String einkNavSide;
  final bool einkNavReversed;
  final Widget? bottomLeading;
  final Widget? bottomTrailing;

  @override
  State<ReaderView> createState() => _ReaderViewState();
}

class _ReaderViewState extends State<ReaderView> {
  final GlobalKey _surfaceKey = GlobalKey();

  PageLayout? _layout;
  int _currentPage = 0;
  int _pageCount = 1;
  List<ResolvedSpan> _resolved = const [];

  // The content box origin within the reading surface (depends on nav side).
  double _leftPad = _kHPadding;
  final double _contentTop = _kVPadding;
  double _surfaceW = 0;
  double _surfaceH = 0;

  // Pagination inputs from the last run, to detect when to re-paginate.
  String? _lastContent;
  double? _lastContentWidth;
  double? _lastContentHeight;
  int _lastColumns = 1;
  String _lastNavSide = 'both';
  int _paginateGeneration = 0;

  // Canonical reading position is a char offset (resize-robust, like native).
  int _pendingChar = 0;

  // Selection — char offsets into content; -1 = none.
  int _selStart = -1;
  int _selEnd = -1;
  bool _isSelecting = false;

  // Pointer-drag state.
  bool _isStylus = false;
  double _ptrDownX = 0;
  double _ptrDownY = 0;
  bool _ptrMoved = false;
  int _ptrAnchorChar = -1;
  int _scrubMin = 1 << 30;
  int _scrubMax = -1;
  Timer? _longPressTimer;
  // Repaints the scrub-selection band when the pen pauses, not on every move.
  Timer? _scrubPaintTimer;
  _Handle _draggingHandle = _Handle.none;

  // Jump scrubber overlay.
  bool _showJumpUI = false;
  double _jumpFraction = 0;

  static const double _tapSlop = 8;
  // The scrub band repaints this long after the pen last moved (i.e. on a pause)
  // rather than on every move; with the split painter this repaint is cheap.
  static const Duration _kScrubPaint = Duration(milliseconds: 90);

  @override
  void initState() {
    super.initState();
    final len = widget.content.length;
    final frac = widget.savedPosition?.fraction ?? 0.0;
    _pendingChar = (frac * len).round().clamp(0, len);
    _resolved = _resolveAnnotations();
    widget.jumpNotifier.addListener(_onJumpRequested);
    widget.cancelSelectionNotifier?.addListener(_onCancelSelection);
    // Arm the hardware dotted-lasso pen (penType 4) so drawPath renders the
    // selection stroke at EPD speed during a stylus drag. Clears on commit /
    // cancel below. No-op off e-ink.
    EinkPen.configureLasso();
  }

  @override
  void didUpdateWidget(covariant ReaderView old) {
    super.didUpdateWidget(old);
    if (!identical(old.jumpNotifier, widget.jumpNotifier)) {
      old.jumpNotifier.removeListener(_onJumpRequested);
      widget.jumpNotifier.addListener(_onJumpRequested);
    }
    if (!identical(old.cancelSelectionNotifier, widget.cancelSelectionNotifier)) {
      old.cancelSelectionNotifier?.removeListener(_onCancelSelection);
      widget.cancelSelectionNotifier?.addListener(_onCancelSelection);
    }
    if (!identical(old.annotations, widget.annotations) ||
        old.content != widget.content) {
      _resolved = _resolveAnnotations();
    }
  }

  @override
  void dispose() {
    widget.jumpNotifier.removeListener(_onJumpRequested);
    widget.cancelSelectionNotifier?.removeListener(_onCancelSelection);
    _longPressTimer?.cancel();
    _scrubPaintTimer?.cancel();
    _layout?.dispose();
    super.dispose();
  }

  // --- Annotation resolution -------------------------------------------------

  /// Resolves each annotation to a char range via [findAnnotationOffset]
  /// (prefix/suffix scoring over the full document) — the 0..1 position is not
  /// used for placement, matching the rest of the reader stack.
  List<ResolvedSpan> _resolveAnnotations() {
    final content = widget.content;
    final out = <ResolvedSpan>[];
    for (final ann in widget.annotations) {
      final off = findAnnotationOffset(content, ann);
      if (off < 0) continue;
      final start = off.clamp(0, content.length);
      final end = (off + ann.selectedText.length).clamp(0, content.length);
      if (end <= start) continue;
      out.add(ResolvedSpan(annotation: ann, start: start, end: end));
    }
    return out;
  }

  // --- Pagination ------------------------------------------------------------

  /// Re-paginate if the content box, column count, content or nav side changed.
  /// The whole-book paragraph cannot cross an isolate, so this runs on the main
  /// thread, deferred to a post-frame callback (never setState-during-build).
  void _maybeRepaginate(double surfaceW, double surfaceH) {
    _surfaceW = surfaceW;
    _surfaceH = surfaceH;
    if (surfaceW <= 0 || surfaceH <= 0) return;

    final navSide = widget.einkNavSide;
    final leftStrip = navSide != 'right';
    final rightStrip = navSide != 'left';
    final leftPad = _kHPadding + (leftStrip ? _kNavStripWidth : 0.0);
    final rightPad = _kHPadding + (rightStrip ? _kNavStripWidth : 0.0);
    final contentWidth = surfaceW - leftPad - rightPad;
    final contentHeight = surfaceH - _kVPadding * 2;
    _leftPad = leftPad;

    final cols = (widget.twoColumn &&
            (contentWidth - _kColumnGap) / 2 >= _kMinColumnWidth)
        ? 2
        : 1;

    final changed = _layout == null ||
        _lastContent != widget.content ||
        _lastContentWidth != contentWidth ||
        _lastContentHeight != contentHeight ||
        _lastColumns != cols ||
        _lastNavSide != navSide;
    if (!changed) return;

    _lastContent = widget.content;
    _lastContentWidth = contentWidth;
    _lastContentHeight = contentHeight;
    _lastColumns = cols;
    _lastNavSide = navSide;

    final gen = ++_paginateGeneration;
    final targetChar = _layout?.charStartOfPage(_currentPage) ?? _pendingChar;

    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted || gen != _paginateGeneration) return;
      if (contentWidth <= 0 || contentHeight <= 0) return;
      final old = _layout;
      final pl = PageLayout.paginate(
        text: widget.content,
        formatSpans: widget.formatSpans,
        contentWidth: contentWidth,
        contentHeight: contentHeight,
        columns: cols,
        columnGap: _kColumnGap,
      );
      old?.dispose();
      if (!mounted) {
        pl.dispose();
        return;
      }
      setState(() {
        _layout = pl;
        _pageCount = pl.pageCount;
        _currentPage = pl.pageForChar(targetChar);
        _pendingChar = targetChar;
        _resolved = _resolveAnnotations();
      });
      // No onPositionChanged here: (re)pagination preserves the reading
      // position (targetChar), so it is not a page change. Only next/prev/jump
      // notify — matching PageFlipReader, which stays silent on open/resize.
    });
  }

  // --- Navigation ------------------------------------------------------------

  void _next() {
    final pl = _layout;
    if (pl == null || _currentPage >= pl.pageCount - 1) return;
    setState(() {
      _currentPage++;
      _pendingChar = pl.charStartOfPage(_currentPage);
    });
    _cancelSelection();
    _notifyPosition();
  }

  void _prev() {
    final pl = _layout;
    if (pl == null || _currentPage <= 0) return;
    setState(() {
      _currentPage--;
      _pendingChar = pl.charStartOfPage(_currentPage);
    });
    _cancelSelection();
    _notifyPosition();
  }

  void _jumpToChar(int targetChar) {
    final pl = _layout;
    if (pl == null) {
      _pendingChar = targetChar;
      return;
    }
    setState(() {
      _currentPage = pl.pageForChar(targetChar);
      _pendingChar = targetChar;
    });
    _cancelSelection();
    _notifyPosition();
  }

  void _onJumpRequested() {
    final f = widget.jumpNotifier.value;
    if (f == null) return;
    final len = widget.content.length;
    _jumpToChar((f * len).round().clamp(0, len));
  }

  void _notifyPosition() {
    final pl = _layout;
    if (pl == null) return;
    final len = widget.content.length;
    final startChar = pl.charStartOfPage(_currentPage);
    final fraction = len == 0 ? 0.0 : (startChar / len).clamp(0.0, 1.0);
    widget.onPositionChanged(ReadingPosition(
      mode: ReadingMode.pageFlip,
      page: _currentPage,
      scrollOffset: 0,
      fraction: fraction,
    ));
  }

  // --- Coordinate helpers (view space) ---------------------------------------

  int? _charAt(Offset p) {
    final pl = _layout;
    if (pl == null) return null;
    return pl.charAtPoint(_currentPage, p.dx, p.dy,
        leftPad: _leftPad, contentTop: _contentTop);
  }

  /// The narrowest annotation whose resolved span contains [char].
  ResolvedSpan? _annotationAt(int char) {
    ResolvedSpan? best;
    for (final r in _resolved) {
      if (char >= r.start && char < r.end) {
        if (best == null || (r.end - r.start) < (best.end - best.start)) {
          best = r;
        }
      }
    }
    return best;
  }

  /// Edge nav: only the active strip(s) navigate — top half = next, bottom =
  /// prev. Taps in the central reading area do nothing. Returns true if handled.
  bool _navTap(Offset p) {
    final navSide = widget.einkNavSide;
    final inLeft = navSide != 'right' && p.dx < _kNavStripWidth;
    final inRight = navSide != 'left' && p.dx > _surfaceW - _kNavStripWidth;
    if (!inLeft && !inRight) return false;
    final topIsNext = !widget.einkNavReversed;
    if (p.dy < _surfaceH / 2) {
      topIsNext ? _next() : _prev();
    } else {
      topIsNext ? _prev() : _next();
    }
    return true;
  }

  void _handleTap(Offset p, Offset globalP) {
    if (_navTap(p)) return;
    final c = _charAt(p);
    if (c == null) return;
    final hit = _annotationAt(c);
    if (hit != null) widget.onAnnotationTap(hit.annotation, globalP);
  }

  // --- Selection -------------------------------------------------------------

  /// Word-level bidirectional selection from the drag anchor to [p] (finger).
  void _extendSelectionTo(Offset p) {
    final content = widget.content;
    final cur = _charAt(p);
    if (cur == null) return;
    final lo = math.min(_ptrAnchorChar, cur);
    final hi = (math.max(_ptrAnchorChar, cur) + 1).clamp(0, content.length);
    final snapped = snapToWordBoundaries(content, lo, hi);
    if (snapped.start != _selStart || snapped.end != _selEnd) {
      setState(() {
        _selStart = snapped.start;
        _selEnd = snapped.end;
      });
    }
  }

  /// Scrub-select (stylus): the selection grows monotonically to cover every
  /// char the pen passes over, then word-snaps. Never shrinks during the drag.
  /// The range updates every move (cheap), but the painted band is repaint-
  /// throttled to the next pause so the per-move repaint never lags the pen.
  void _extendScrubTo(Offset p) {
    final content = widget.content;
    final cur = _charAt(p);
    if (cur == null) return;
    _scrubMin = math.min(_scrubMin, cur);
    _scrubMax = math.max(_scrubMax, cur);
    final hi = (_scrubMax + 1).clamp(0, content.length);
    final snapped = snapToWordBoundaries(content, _scrubMin, hi);
    if (snapped.start != _selStart || snapped.end != _selEnd) {
      // Update the range every move (cheap), but repaint the band only when the
      // pen PAUSES — not continuously mid-drag. On e-ink a per-move band repaint
      // can't keep up with the pen; deferring it to a stop makes the band land
      // cleanly. The final band always paints on lift (see [_finaliseSelection]).
      // INVARIANT: this writes _selStart/_selEnd deliberately OUTSIDE setState —
      // the throttled _scheduleScrubPaint owns the repaint. If an unrelated
      // rebuild lands before the timer fires it paints the updated range early;
      // acceptable because the scrub range is monotonic and lift always repaints.
      _selStart = snapped.start;
      _selEnd = snapped.end;
      _scheduleScrubPaint();
    }
  }

  void _scheduleScrubPaint() {
    _scrubPaintTimer?.cancel();
    _scrubPaintTimer = Timer(_kScrubPaint, () {
      if (mounted) setState(() {});
    });
  }

  void _finaliseSelection() {
    _scrubPaintTimer?.cancel();
    final content = widget.content;
    if (_selStart < 0 || _selEnd <= _selStart) {
      _cancelSelection();
      return;
    }
    final snapped = snapToWordBoundaries(content, _selStart, _selEnd);
    setState(() {
      _selStart = snapped.start;
      _selEnd = snapped.end;
      _isSelecting = false;
    });
    // Clear the hardware lasso so drawPath's retained buffer doesn't overdraw
    // the committed Flutter selection band.
    EinkPen.clearInk();
    _fireSelection();
  }

  /// Word-snapped text + 20-char prefix/suffix context + global anchor.
  void _fireSelection() {
    final content = widget.content;
    if (_selStart < 0 || _selEnd <= _selStart) return;
    final selectedText = content.substring(_selStart, _selEnd);
    if (selectedText.trim().isEmpty) {
      _cancelSelection();
      return;
    }
    final prefix = content.substring((_selStart - 20).clamp(0, _selStart), _selStart);
    final suffix =
        content.substring(_selEnd, (_selEnd + 20).clamp(_selEnd, content.length));
    final anchor = _selectionAnchorGlobal() ?? Offset.zero;
    final fraction =
        content.isEmpty ? 0.0 : (_selStart / content.length).clamp(0.0, 1.0);
    widget.onSelection(selectedText, prefix, suffix, anchor, fraction);
  }

  /// Global-screen point just above the selection start — the toolbar anchor.
  Offset? _selectionAnchorGlobal() {
    final pl = _layout;
    if (pl == null) return null;
    final local = pl.charPointInView(_currentPage, _selStart,
        bottom: false, leftPad: _leftPad, contentTop: _contentTop);
    if (local == null) return null;
    final box = _surfaceKey.currentContext?.findRenderObject() as RenderBox?;
    if (box == null) return null;
    return box.localToGlobal(local);
  }

  void _cancelSelection() {
    _scrubPaintTimer?.cancel();
    if (_selStart < 0 && _selEnd < 0 && !_isSelecting) return;
    if (_isSelecting) EinkPen.clearInk(); // clear hardware lasso if mid-drag
    setState(() {
      _selStart = -1;
      _selEnd = -1;
      _isSelecting = false;
      _draggingHandle = _Handle.none;
    });
  }

  void _onCancelSelection() => _cancelSelection();

  // --- Selection handles -----------------------------------------------------

  Offset? _handleCenter(int char) {
    final pl = _layout;
    if (pl == null) return null;
    final a = pl.charPointInView(_currentPage, char,
        bottom: true, leftPad: _leftPad, contentTop: _contentTop);
    if (a == null) return null;
    return Offset(a.dx, a.dy + _kHandleStem + _kHandleRadius);
  }

  _Handle _handleHitTest(Offset p) {
    const grab2 = _kHandleGrab * _kHandleGrab;
    final sc = _handleCenter(_selStart);
    final ec = _handleCenter(_selEnd);
    final ds = sc != null ? _sq(p.dx - sc.dx, p.dy - sc.dy) : double.infinity;
    final de = ec != null ? _sq(p.dx - ec.dx, p.dy - ec.dy) : double.infinity;
    if (ds <= grab2 && ds <= de) return _Handle.start;
    if (de <= grab2) return _Handle.end;
    return _Handle.none;
  }

  /// Move the dragged handle to the char under the touch (lifted back onto the
  /// glyph line, since the handle sits below it). No crossing the other end.
  void _adjustHandle(Offset p) {
    final pl = _layout;
    if (pl == null) return;
    const lift = _kHandleStem + _kHandleRadius + _kBodySize * 0.7;
    final char = pl.charAtPoint(_currentPage, p.dx, p.dy - lift,
        leftPad: _leftPad, contentTop: _contentTop);
    if (char == null) return;
    final len = widget.content.length;
    setState(() {
      if (_draggingHandle == _Handle.start) {
        _selStart = char.clamp(0, _selEnd - 1);
      } else if (_draggingHandle == _Handle.end) {
        _selEnd = char.clamp(_selStart + 1, len);
      }
    });
  }

  double _sq(double a, double b) => a * a + b * b;

  // --- Pointer state machine (port of native onTouchEvent) -------------------

  void _onPointerDown(PointerDownEvent e) {
    final p = e.localPosition;
    // A fresh pointer-down supersedes any pending long-press from a prior
    // pointer (Listener is multi-touch), so cancel it before anything else —
    // otherwise a second finger starting a handle drag could let an armed
    // timer fire mid-gesture and corrupt the selection.
    _longPressTimer?.cancel();
    // Adjusting a committed selection via its grab handles takes priority.
    if (_selStart >= 0 && !_isSelecting) {
      final hit = _handleHitTest(p);
      if (hit != _Handle.none) {
        _draggingHandle = hit;
        return;
      }
    }

    _isStylus = e.kind == PointerDeviceKind.stylus ||
        e.kind == PointerDeviceKind.invertedStylus;
    _ptrDownX = p.dx;
    _ptrDownY = p.dy;
    _ptrMoved = false;
    _ptrAnchorChar = _charAt(p) ?? -1;

    if (_isStylus) {
      if (_ptrAnchorChar >= 0) {
        _scrubMin = _ptrAnchorChar;
        _scrubMax = _ptrAnchorChar;
      } else {
        _scrubMin = 1 << 30;
        _scrubMax = -1;
      }
    } else {
      // Finger: long-press begins a selection (no swipe page-turn on e-ink).
      _longPressTimer?.cancel();
      _longPressTimer = Timer(kLongPressTimeout, () {
        if (!mounted || _ptrMoved || _ptrAnchorChar < 0) return;
        setState(() => _isSelecting = true);
        widget.onSelectionStart?.call();
        _extendSelectionTo(Offset(_ptrDownX, _ptrDownY));
      });
    }
  }

  void _onPointerMove(PointerMoveEvent e) {
    final p = e.localPosition;
    if (_draggingHandle != _Handle.none) {
      _adjustHandle(p);
      return;
    }
    final dx = p.dx - _ptrDownX;
    final dy = p.dy - _ptrDownY;
    final pastSlop = dx * dx + dy * dy > _tapSlop * _tapSlop;

    if (_isStylus) {
      if (_ptrAnchorChar < 0) return;
      if (!_ptrMoved && pastSlop) {
        _ptrMoved = true;
        setState(() => _isSelecting = true);
        widget.onSelectionStart?.call();
      }
      // Scrub-select extends continuously while the pen is down; the selection
      // commits on pen-up (delivered promptly now drawPath is gone), so there is
      // no dwell to interrupt the drag.
      if (_isSelecting) {
        _extendScrubTo(p);
        // Pre-commit on pressure drop: Wacom EMR pressure falls to ~0–0.02 across
        // several ACTION_MOVE events before ACTION_UP fires (~150–300ms later).
        // Single sub-threshold event fires the commit; direction-change dips are
        // brief and rarely reach 0.03, so false positives are unlikely in practice.
        if (e.pressure < 0.03) {
          _finaliseSelection();
        }
      }
    } else {
      if (pastSlop && !_ptrMoved) {
        _ptrMoved = true;
        _longPressTimer?.cancel(); // movement is a pan, not a long-press
      }
      if (_isSelecting) _extendSelectionTo(p);
    }
  }

  void _onPointerUp(PointerUpEvent e) {
    final p = e.localPosition;
    _longPressTimer?.cancel();
    _isStylus = false;

    if (_draggingHandle != _Handle.none) {
      _draggingHandle = _Handle.none;
      _fireSelection(); // re-anchor + re-show the tool popup
      return;
    }

    if (_isSelecting) {
      _finaliseSelection();
    } else if (_selStart >= 0 && !_ptrMoved) {
      _cancelSelection(); // tap dismisses a shown selection
    } else if (!_ptrMoved) {
      // A stationary tap (no drag) checks the nav strips first, then an
      // annotation hit. Both pen and finger can tap an annotation to open its
      // actions toolbar; a pen DRAG is still a text selection, so this only
      // fires when the pen went down and up without moving.
      _handleTap(p, e.position);
    }
    _ptrMoved = false;
  }

  void _onPointerCancel(PointerCancelEvent e) {
    _longPressTimer?.cancel();
    if (_draggingHandle != _Handle.none) {
      _draggingHandle = _Handle.none;
      _fireSelection();
    } else if (_isSelecting) {
      _cancelSelection();
    }
    _ptrMoved = false;
    _isStylus = false;
  }

  // --- Jump scrubber ---------------------------------------------------------

  void _openJumpUI() {
    if (_pageCount <= 1) return;
    setState(() {
      _showJumpUI = true;
      _jumpFraction = _pageCount > 1 ? _currentPage / (_pageCount - 1) : 0.0;
    });
  }

  void _commitJump() {
    final pl = _layout;
    final total = _pageCount;
    final target = (_jumpFraction * (total - 1)).round().clamp(0, total - 1);
    setState(() => _showJumpUI = false);
    if (pl == null) return;
    _jumpToChar(pl.charStartOfPage(target));
  }

  // --- Build -----------------------------------------------------------------

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Column(
        children: [
          Expanded(
            child: LayoutBuilder(
              builder: (context, constraints) {
                _maybeRepaginate(constraints.maxWidth, constraints.maxHeight);
                return Stack(
                  children: [
                    Listener(
                      key: _surfaceKey,
                      behavior: HitTestBehavior.opaque,
                      onPointerDown: _onPointerDown,
                      onPointerMove: _onPointerMove,
                      onPointerUp: _onPointerUp,
                      onPointerCancel: _onPointerCancel,
                      child: SizedBox(
                        width: constraints.maxWidth,
                        height: constraints.maxHeight,
                        child: Stack(
                          fit: StackFit.expand,
                          children: [
                            // Cached text/paper/nav layer in its own
                            // RepaintBoundary: drawParagraph runs only when the
                            // page/layout/nav changes, never on a selection or
                            // annotation edit (those repaint just the overlay).
                            RepaintBoundary(
                              child: CustomPaint(
                                painter: ReaderPainter(
                                  layer: ReaderLayer.base,
                                  layout: _layout,
                                  currentPage: _currentPage,
                                  resolved: _resolved,
                                  selectionStart: _selStart,
                                  selectionEnd: _selEnd,
                                  isSelecting: _isSelecting,
                                  contentLeft: _leftPad,
                                  contentTop: _contentTop,
                                  navSide: widget.einkNavSide,
                                  navReversed: widget.einkNavReversed,
                                  emphasizedAnnotationId:
                                      widget.emphasizedAnnotationId,
                                ),
                              ),
                            ),
                            // Dynamic decorations / selection / handles layer.
                            CustomPaint(
                              painter: ReaderPainter(
                                layer: ReaderLayer.overlay,
                                layout: _layout,
                                currentPage: _currentPage,
                                resolved: _resolved,
                                selectionStart: _selStart,
                                selectionEnd: _selEnd,
                                isSelecting: _isSelecting,
                                contentLeft: _leftPad,
                                contentTop: _contentTop,
                                navSide: widget.einkNavSide,
                                navReversed: widget.einkNavReversed,
                                emphasizedAnnotationId:
                                    widget.emphasizedAnnotationId,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    if (_layout == null) _buildHint(),
                    if (_showJumpUI) _buildJumpOverlay(),
                  ],
                );
              },
            ),
          ),
          SizedBox(height: _kBottomZone, child: _buildBottomZone()),
        ],
      ),
    );
  }

  Widget _buildHint() {
    final message = widget.content.isEmpty ? 'No content' : 'Laying out…';
    return Center(
      child: Text(
        message,
        style: kReaderTextStyle.copyWith(
          color: Colors.black.withValues(alpha: 0.5),
        ),
      ),
    );
  }

  Widget _buildBottomZone() {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Stack(
        children: [
          Center(child: _buildPageCounter()),
          if (widget.bottomLeading != null)
            Positioned(
              left: isEink ? 16 : 8,
              top: 0,
              bottom: 0,
              child: Center(child: widget.bottomLeading!),
            ),
          if (widget.bottomTrailing != null)
            Positioned(
              right: isEink ? 16 : 8,
              top: 0,
              bottom: 0,
              child: Center(child: widget.bottomTrailing!),
            ),
        ],
      ),
    );
  }

  Widget _buildPageCounter() {
    final label = '${_currentPage + 1} / $_pageCount';
    return GestureDetector(
      onTap: _openJumpUI,
      // Transparent hit region padded to the 48dp minimum tap target; the
      // visible pill stays compact. opaque so the padding around it is tappable.
      behavior: HitTestBehavior.opaque,
      child: Container(
        constraints: const BoxConstraints(minHeight: 48),
        alignment: Alignment.center,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
          decoration: BoxDecoration(
            color: _kPaper,
            borderRadius: BorderRadius.circular(16),
            border: Border.all(color: Colors.black.withValues(alpha: 0.2)),
          ),
          child: Text(
            label,
            style: const TextStyle(
              fontFamily: 'SourceSans3',
              fontSize: 13,
              color: Colors.black87,
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildJumpOverlay() {
    final total = _pageCount;
    final target = (_jumpFraction * (total - 1)).round().clamp(0, total - 1);
    return Positioned(
      left: 0,
      right: 0,
      bottom: 0,
      child: Container(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
        decoration: BoxDecoration(
          color: _kPaper,
          border: Border(top: BorderSide(color: Colors.black.withValues(alpha: 0.2))),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              'Page ${target + 1} / $total',
              style: const TextStyle(
                fontFamily: 'SourceSans3',
                fontSize: 15,
                color: Colors.black87,
              ),
            ),
            Row(
              children: [
                IconButton(
                  icon: const Icon(Icons.chevron_left),
                  onPressed: total > 1
                      ? () => setState(() => _jumpFraction =
                          ((target - 1) / (total - 1)).clamp(0.0, 1.0))
                      : null,
                ),
                Expanded(
                  // e-ink forbids animation: the Slider's thumb/ripple/value
                  // indicator animate on drag. On e-ink step with the chevrons
                  // only (target page is shown above); desktop keeps the slider.
                  child: isEink
                      ? const SizedBox.shrink()
                      : Slider(
                          value: _jumpFraction.clamp(0.0, 1.0),
                          onChanged: (v) => setState(() => _jumpFraction = v),
                        ),
                ),
                IconButton(
                  icon: const Icon(Icons.chevron_right),
                  onPressed: total > 1
                      ? () => setState(() => _jumpFraction =
                          ((target + 1) / (total - 1)).clamp(0.0, 1.0))
                      : null,
                ),
              ],
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceBetween,
              children: [
                TextButton(
                  onPressed: () => setState(() => _showJumpUI = false),
                  child: const Text('Cancel',
                      style: TextStyle(fontFamily: 'SourceSans3')),
                ),
                TextButton(
                  onPressed: _commitJump,
                  child: const Text('Go',
                      style: TextStyle(fontFamily: 'SourceSans3')),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
