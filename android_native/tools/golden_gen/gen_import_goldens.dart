// Goldens for the read-side FALLBACK importers (used when leamh/annotations.json
// is absent — a fresh or foreign DOCX):
//
//   golden/import/native_formatting.document.xml  + .expected.json
//   golden/import/comments.document.xml + comments.comments.xml + .expected.json
//
// The functions below are copied VERBATIM from lib/models/docx_store.dart
// (_buildPlainMap, _unesc, _importNativeFormatting, _annotationFromSegment,
// _parseComments, _extractFromCommentRange) — keep in sync. The only change is a
// FIXED clock in _annotationFromSegment (Dart uses DateTime.now()) so ids and
// timestamps are deterministic; the Kotlin port uses the same fixed instant.
//
// NOTE: native_formatting fixtures are PROSE only (no tabs/tables), where the
// clean PlainTextMapper is byte-identical to the legacy _buildPlainMap, so these
// goldens are valid targets for the clean Kotlin port.
//
// Run from the repo root:
//   dart run android_native/tools/golden_gen/gen_import_goldens.dart
//
import 'dart:convert';
import 'dart:io';
import 'package:layuv/models/annotation.dart';

// Fixed clock shared with the Kotlin test.
final _now = DateTime.utc(2026, 1, 1);
final _idBase = _now.microsecondsSinceEpoch;

String _unesc(String s) => s
    .replaceAll('&apos;', "'")
    .replaceAll('&quot;', '"')
    .replaceAll('&gt;', '>')
    .replaceAll('&lt;', '<')
    .replaceAll('&amp;', '&');

({String plain, List<int> xmlOffsets}) _buildPlainMap(String xml) {
  final buf = StringBuffer();
  final offsets = <int>[];
  final re = RegExp(r'<w:t(?:[^>]*)>(.*?)</w:t>|</w:p>', dotAll: true);
  for (final m in re.allMatches(xml)) {
    if (m.group(0) == '</w:p>') {
      buf.write('\n');
      offsets.add(m.start);
    } else {
      final text = _unesc(m.group(1)!);
      final base = m.start + m.group(0)!.indexOf('>') + 1;
      for (int i = 0; i < text.length; i++) {
        buf.write(text[i]);
        offsets.add(base + i);
      }
    }
  }
  return (plain: buf.toString(), xmlOffsets: offsets);
}

// ---- _importNativeFormatting (verbatim) ----
List<Annotation> _importNativeFormatting(String documentXml) {
  final map = _buildPlainMap(documentXml);
  if (map.plain.isEmpty) return [];

  final runRe = RegExp(r'<w:r(?:\s[^>]*)?>(?<!\/>).*?</w:r>', dotAll: true);
  final wtRe = RegExp(r'<w:t(?:[^>]*)>(.*?)</w:t>', dotAll: true);

  final segments = <({AnnotationTool tool, int plainStart, int plainEnd})>[];

  for (final runMatch in runRe.allMatches(documentXml)) {
    final runContent = runMatch.group(0)!;

    final rPrRe = RegExp(r'<w:rPr>(.*?)</w:rPr>', dotAll: true);
    final rPrMatch = rPrRe.firstMatch(runContent);
    if (rPrMatch == null) continue;
    var rPr = rPrMatch.group(1)!;
    final rPrChangeIdx = rPr.indexOf('<w:rPrChange');
    if (rPrChangeIdx >= 0) rPr = rPr.substring(0, rPrChangeIdx);

    AnnotationTool? tool;
    if (rPr.contains('<w:highlight')) {
      tool = AnnotationTool.highlight;
    } else if (rPr.contains('w:val="wave"')) {
      tool = AnnotationTool.wavyUnderline;
    } else if (rPr.contains('w:val="double"')) {
      tool = AnnotationTool.doubleUnderline;
    } else if (rPr.contains('<w:u ') || rPr.contains('<w:u/>')) {
      tool = AnnotationTool.underline;
    } else if (rPr.contains('<w:strike')) {
      tool = AnnotationTool.strikethrough;
    }
    if (tool == null) continue;

    final text =
        wtRe.allMatches(runContent).map((m) => _unesc(m.group(1)!)).join('');
    if (text.isEmpty) continue;

    final firstWt = wtRe.firstMatch(runContent);
    if (firstWt == null) continue;
    final wtContentStart =
        runMatch.start + firstWt.start + firstWt.group(0)!.indexOf('>') + 1;

    int lo = 0, hi = map.xmlOffsets.length - 1, plainStart = -1;
    while (lo <= hi) {
      final mid = (lo + hi) ~/ 2;
      if (map.xmlOffsets[mid] >= wtContentStart) {
        plainStart = mid;
        hi = mid - 1;
      } else {
        lo = mid + 1;
      }
    }
    if (plainStart < 0) continue;
    final plainEnd = (plainStart + text.length).clamp(0, map.plain.length);
    if (map.plain.substring(plainStart, plainEnd) != text) continue;

    segments.add((tool: tool, plainStart: plainStart, plainEnd: plainEnd));
  }

  if (segments.isEmpty) return [];

  final results = <Annotation>[];
  var curr = segments.first;
  for (int i = 1; i < segments.length; i++) {
    final next = segments[i];
    if (next.tool == curr.tool && next.plainStart <= curr.plainEnd) {
      curr = (
        tool: curr.tool,
        plainStart: curr.plainStart,
        plainEnd: next.plainEnd > curr.plainEnd ? next.plainEnd : curr.plainEnd,
      );
    } else {
      results.add(_annotationFromSegment(curr, map.plain, results.length));
      curr = next;
    }
  }
  results.add(_annotationFromSegment(curr, map.plain, results.length));

  return results;
}

Annotation _annotationFromSegment(
  ({AnnotationTool tool, int plainStart, int plainEnd}) seg,
  String plain,
  int index,
) {
  final text = plain.substring(seg.plainStart, seg.plainEnd);
  final pos =
      plain.isNotEmpty ? (seg.plainStart / plain.length).clamp(0.0, 1.0) : 0.0;
  final prefix = plain.substring(
      (seg.plainStart - 20).clamp(0, seg.plainStart), seg.plainStart);
  final suffix = plain.substring(
      seg.plainEnd, (seg.plainEnd + 20).clamp(seg.plainEnd, plain.length));
  return Annotation(
    id: '${_idBase + index}', // Dart: DateTime.now().microsecondsSinceEpoch + index
    selectedText: text,
    prefix: prefix,
    suffix: suffix,
    tool: seg.tool,
    timestamp: _now, // Dart: DateTime.now()
    position: pos,
  );
}

// ---- _parseComments + _extractFromCommentRange (verbatim) ----
List<Annotation> _parseComments(String xml, String documentXml) {
  final results = <Annotation>[];
  final map = _buildPlainMap(documentXml);

  final commentRe =
      RegExp(r'<w:comment\s([^>]*)>(.*?)</w:comment>', dotAll: true);
  final idAttr = RegExp(r'w:id="([^"]*)"');
  final authorAttr = RegExp(r'w:author="([^"]*)"');
  final dateAttr = RegExp(r'w:date="([^"]*)"');
  final wtRe = RegExp(r'<w:t[^>]*>(.*?)</w:t>', dotAll: true);
  final legacyRe = RegExp(
      r'\[tool:(\w+)\](?:\s\[tag:(\w+)\])?\s(\d+)%\s—\s"(.*)"',
      dotAll: true);

  for (final cm in commentRe.allMatches(xml)) {
    try {
      final attrs = cm.group(1)!;
      final body = cm.group(2)!;
      final commentId = idAttr.firstMatch(attrs)?.group(1) ?? '';
      final authorRaw = _unesc(authorAttr.firstMatch(attrs)?.group(1) ?? '');
      final dateStr = dateAttr.firstMatch(attrs)?.group(1);
      if (dateStr == null) continue;
      final timestamp = DateTime.parse(dateStr);
      final texts =
          wtRe.allMatches(body).map((m) => _unesc(m.group(1)!)).toList();
      if (texts.isEmpty) continue;

      final legacyMatch =
          texts.map((t) => legacyRe.firstMatch(t)).nonNulls.firstOrNull;
      if (legacyMatch != null && authorRaw.isNotEmpty) {
        AnnotationTool tool;
        try {
          tool = AnnotationTool.values.byName(legacyMatch.group(1)!);
        } catch (_) {
          tool = AnnotationTool.highlight;
        }
        AnnotationTag? tag;
        if (legacyMatch.group(2) != null) {
          try {
            tag = AnnotationTag.values.byName(legacyMatch.group(2)!);
          } catch (_) {}
        }
        final headerIdx = texts.indexWhere((t) => legacyRe.hasMatch(t));
        final noteTexts = texts.sublist(headerIdx + 1);
        final note = noteTexts.isNotEmpty ? noteTexts.join('\n') : null;
        results.add(Annotation(
          id: authorRaw,
          selectedText: legacyMatch.group(4)!,
          prefix: '',
          suffix: '',
          tool: tool,
          tag: tag,
          note: note?.isEmpty == true ? null : note,
          timestamp: timestamp,
          position: int.parse(legacyMatch.group(3)!) / 100.0,
        ));
        continue;
      }

      if (commentId.isEmpty) continue;
      final extracted = _extractFromCommentRange(documentXml, commentId, map);
      if (extracted.text.isEmpty) continue;
      final note = texts.where((t) => t.trim().isNotEmpty).join(' ').trim();
      results.add(Annotation(
        id: 'word_$commentId',
        selectedText: extracted.text,
        prefix: extracted.prefix,
        suffix: extracted.suffix,
        tool: AnnotationTool.comment,
        note: note.isEmpty ? null : note,
        timestamp: timestamp,
        position: extracted.position,
      ));
    } catch (_) {}
  }

  results.sort((a, b) => a.position.compareTo(b.position));
  return results;
}

({String text, String prefix, String suffix, double position})
    _extractFromCommentRange(
  String documentXml,
  String commentId,
  ({String plain, List<int> xmlOffsets}) map,
) {
  final startMarker = '<w:commentRangeStart w:id="$commentId"/>';
  final endMarker = '<w:commentRangeEnd w:id="$commentId"/>';
  final si = documentXml.indexOf(startMarker);
  final ei = documentXml.indexOf(endMarker);
  if (si < 0 || ei < 0 || ei <= si) {
    return (text: '', prefix: '', suffix: '', position: 0.0);
  }
  final segment = documentXml.substring(si + startMarker.length, ei);
  final wtRe = RegExp(r'<w:t[^>]*>(.*?)</w:t>', dotAll: true);
  final text =
      wtRe.allMatches(segment).map((m) => _unesc(m.group(1)!)).join('');
  if (text.isEmpty) return (text: '', prefix: '', suffix: '', position: 0.0);

  final rangeStart = si + startMarker.length;
  int plainIdx = 0;
  for (int k = 0; k < map.xmlOffsets.length; k++) {
    if (map.xmlOffsets[k] >= rangeStart) {
      plainIdx = k;
      break;
    }
  }
  final position =
      map.plain.isNotEmpty ? (plainIdx / map.plain.length).clamp(0.0, 1.0) : 0.0;
  final plainEnd = (plainIdx + text.length).clamp(0, map.plain.length);
  return (
    text: text,
    prefix: map.plain.substring((plainIdx - 20).clamp(0, plainIdx), plainIdx),
    suffix: map.plain
        .substring(plainEnd, (plainEnd + 20).clamp(plainEnd, map.plain.length)),
    position: position,
  );
}

// ---------------------------------------------------------------------------

const _wns =
    'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"';
String _doc(String body) =>
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<w:document $_wns><w:body>$body</w:body></w:document>';
String _plain(String t) => '<w:r><w:t>$t</w:t></w:r>';
String _fmt(String rPr, String t) => '<w:r><w:rPr>$rPr</w:rPr><w:t>$t</w:t></w:r>';

void main() {
  final dir = Directory('android_native/docx/src/test/resources/golden/import')
    ..createSync(recursive: true);

  // ---- native formatting fixture (prose only) ----
  final nativeDoc = _doc(
    '<w:p>${_plain('This is ')}${_fmt('<w:highlight w:val="yellow"/>', 'highlighted')}'
    '${_plain(' and ')}${_fmt('<w:u w:val="single"/>', 'underlined')}${_plain(' text.')}</w:p>'
    '<w:p>${_fmt('<w:strike/>', 'struck')}${_plain(' and ')}'
    '${_fmt('<w:u w:val="wave"/>', 'wavy')}${_plain(' words')}</w:p>'
    '<w:p>${_fmt('<w:u w:val="double"/>', 'double-underlined')}</w:p>'
    '<w:p>${_fmt('<w:highlight w:val="yellow"/>', 'foo')}'
    '${_fmt('<w:highlight w:val="yellow"/>', 'bar')}</w:p>',
  );
  File('${dir.path}/native_formatting.document.xml').writeAsStringSync(nativeDoc);
  final nativeAnns = _importNativeFormatting(nativeDoc);
  File('${dir.path}/native_formatting.expected.json').writeAsStringSync(
      const JsonEncoder.withIndent('  ')
          .convert(nativeAnns.map((a) => a.toJson()).toList()));

  // ---- comments fixture (legacy Léamh + native Word) ----
  final commentsDoc = _doc(
    '<w:p>${_plain('Intro text. ')}'
    '<w:commentRangeStart w:id="1"/>${_plain('annotated span')}<w:commentRangeEnd w:id="1"/>'
    '${_plain(' after.')}</w:p>',
  );
  final commentsXml =
      '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
      '<w:comments $_wns>'
      '<w:comment w:id="0" w:author="uuid-abc-123" w:date="2026-06-08T10:00:00.000Z">'
      '<w:p>${_plain('[tool:underline] [tag:pacing] 25% — "the quote"')}</w:p>'
      '<w:p>${_plain('a note line')}</w:p>'
      '</w:comment>'
      '<w:comment w:id="1" w:author="Jane Reviewer" w:date="2026-06-08T11:00:00.000Z">'
      '<w:p>${_plain('Nice point here')}</w:p>'
      '</w:comment>'
      '</w:comments>';
  File('${dir.path}/comments.document.xml').writeAsStringSync(commentsDoc);
  File('${dir.path}/comments.comments.xml').writeAsStringSync(commentsXml);
  final commentAnns = _parseComments(commentsXml, commentsDoc);
  File('${dir.path}/comments.expected.json').writeAsStringSync(
      const JsonEncoder.withIndent('  ')
          .convert(commentAnns.map((a) => a.toJson()).toList()));

  stdout.writeln('native formatting annotations:');
  for (final a in nativeAnns) {
    stdout.writeln('  ${a.tool.name}: "${a.selectedText}" @ ${a.position.toStringAsFixed(3)} (id ${a.id})');
  }
  stdout.writeln('comment annotations:');
  for (final a in commentAnns) {
    stdout.writeln('  ${a.tool.name}: "${a.selectedText}" tag=${a.tag?.name} note=${a.note} @ ${a.position.toStringAsFixed(3)} (id ${a.id})');
  }
  stdout.writeln('\nWrote import goldens to ${dir.path}');
}
