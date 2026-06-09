// Golden generator for the anchoring + model layers of the native engine.
//
//   golden/anchoring/locate.json    — _locateInPlain cases (Kotlin Anchoring)
//   golden/anchoring/wordsnap.json   — snapToWordBoundaries cases
//   golden/model/annotations.json    — REAL Dart Annotation.toJson output
//   golden/model/position.json       — REAL Dart ReadingPosition.toJson output
//
// The anchoring functions below are copied VERBATIM from
// lib/models/docx_store.dart (_locateInPlain, _findClosest, _normaliseQuotes)
// and lib/utils/annotation_utils.dart (snapToWordBoundaries, _isWordBoundary) —
// keep in sync. The model goldens use the REAL pure-Dart models, so they are
// authoritative.
//
// Run from the repo root:
//   dart run android_native/tools/golden_gen/gen_engine_goldens.dart
//
import 'dart:convert';
import 'dart:io';
import 'package:archive/archive.dart';
import 'package:layuv/models/annotation.dart';
import 'package:layuv/models/reading_position.dart';

// ---------------------------------------------------------------------------
// VERBATIM from lib/models/docx_store.dart
// ---------------------------------------------------------------------------

String _normaliseQuotes(String s) => s
    .replaceAll('“', '"')
    .replaceAll('”', '"')
    .replaceAll('‘', "'")
    .replaceAll('’', "'")
    .replaceAll('–', '-')
    .replaceAll('—', '-');

({int start, int end})? _locateInPlain(
  String plain,
  String selectedText,
  String prefix,
  String suffix, {
  double positionHint = 0.0,
}) {
  if (selectedText.isEmpty) return null;

  if (prefix.isNotEmpty || suffix.isNotEmpty) {
    final needle = prefix + selectedText + suffix;
    final idx = plain.indexOf(needle);
    if (idx >= 0) {
      final start = idx + prefix.length;
      return (start: start, end: start + selectedText.length);
    }
  }

  final hintPos = (positionHint * plain.length).round().clamp(0, plain.length);
  final best = _findClosest(plain, selectedText, hintPos);
  if (best >= 0) return (start: best, end: best + selectedText.length);

  final plainN = _normaliseQuotes(plain);
  final selectedN = _normaliseQuotes(selectedText);
  final prefixN = _normaliseQuotes(prefix);
  final suffixN = _normaliseQuotes(suffix);

  if (prefixN.isNotEmpty || suffixN.isNotEmpty) {
    final needle = prefixN + selectedN + suffixN;
    final idx = plainN.indexOf(needle);
    if (idx >= 0) {
      final start = idx + prefixN.length;
      return (start: start, end: start + selectedN.length);
    }
  }
  final bestN = _findClosest(plainN, selectedN, hintPos);
  if (bestN >= 0) return (start: bestN, end: bestN + selectedN.length);

  return null;
}

int _findClosest(String hay, String needle, int hintPos) {
  int best = -1;
  int bestDist = 0x7fffffff;
  int from = 0;
  while (true) {
    final idx = hay.indexOf(needle, from);
    if (idx < 0) break;
    final dist = (idx - hintPos).abs();
    if (dist < bestDist) {
      bestDist = dist;
      best = idx;
    }
    from = idx + 1;
  }
  return best;
}

// VERBATIM from lib/utils/annotation_utils.dart (TextRange -> record).
({int start, int end}) _snapToWordBoundaries(String text, int start, int end) {
  int s = start;
  while (s > 0 && !_isWordBoundary(text[s - 1])) {
    s--;
  }
  int e = end;
  while (e < text.length && !_isWordBoundary(text[e])) {
    e++;
  }
  return (start: s, end: e);
}

bool _isWordBoundary(String char) {
  return char == ' ' ||
      char == '\n' ||
      char == '\r' ||
      char == '\t' ||
      char == '.' ||
      char == ',' ||
      char == '!' ||
      char == '?' ||
      char == ';' ||
      char == ':' ||
      char == '"' ||
      char == "'" ||
      char == '(' ||
      char == ')' ||
      char == '[' ||
      char == ']' ||
      char == '—' ||
      char == '–';
}

// ---------------------------------------------------------------------------
// Cases
// ---------------------------------------------------------------------------

final _locateCases = <Map<String, dynamic>>[
  {
    'name': 'context_match',
    'plain': 'The quick brown fox jumps',
    'selectedText': 'brown',
    'prefix': 'quick ',
    'suffix': ' fox',
    'positionHint': 0.0,
  },
  {
    'name': 'position_hint_picks_last',
    'plain': 'cat dog cat dog cat',
    'selectedText': 'cat',
    'prefix': '',
    'suffix': '',
    'positionHint': 0.9,
  },
  {
    'name': 'context_beats_hint',
    'plain': 'foo bar X foo bar',
    'selectedText': 'bar',
    'prefix': 'foo ',
    'suffix': '',
    'positionHint': 1.0,
  },
  {
    'name': 'quote_normalised_fallback',
    'plain': 'say “hello” now',
    'selectedText': '"hello"',
    'prefix': 'say ',
    'suffix': ' now',
    'positionHint': 0.0,
  },
  {
    'name': 'not_found',
    'plain': 'abc def',
    'selectedText': 'xyz',
    'prefix': '',
    'suffix': '',
    'positionHint': 0.0,
  },
  {
    'name': 'emoji_selected',
    'plain': 'react with 😀 here',
    'selectedText': '😀',
    'prefix': 'with ',
    'suffix': ' here',
    'positionHint': 0.0,
  },
];

final _wordsnapCases = <Map<String, dynamic>>[
  {'name': 'mid_word', 'text': 'the quick brown fox', 'start': 5, 'end': 7},
  {'name': 'already_boundary', 'text': 'a b c', 'start': 0, 'end': 1},
  {'name': 'punctuation', 'text': 'Hello, world!', 'start': 8, 'end': 10},
  {'name': 'em_dash', 'text': 'word—word', 'start': 6, 'end': 7},
  {'name': 'no_expand_full', 'text': 'single', 'start': 0, 'end': 6},
];

// ---------------------------------------------------------------------------

void main() {
  final base = Directory('android_native/docx/src/test/resources/golden');
  final anchorDir = Directory('${base.path}/anchoring')..createSync(recursive: true);
  final modelDir = Directory('${base.path}/model')..createSync(recursive: true);

  // ---- anchoring: locate ----
  final locateOut = _locateCases.map((c) {
    final r = _locateInPlain(
      c['plain'] as String,
      c['selectedText'] as String,
      c['prefix'] as String,
      c['suffix'] as String,
      positionHint: c['positionHint'] as double,
    );
    return {
      ...c,
      'expected': r == null ? null : {'start': r.start, 'end': r.end},
    };
  }).toList();
  File('${anchorDir.path}/locate.json')
      .writeAsStringSync(const JsonEncoder.withIndent('  ').convert(locateOut));

  // ---- anchoring: wordsnap ----
  final snapOut = _wordsnapCases.map((c) {
    final r = _snapToWordBoundaries(
        c['text'] as String, c['start'] as int, c['end'] as int);
    return {
      ...c,
      'expected': {'start': r.start, 'end': r.end},
    };
  }).toList();
  File('${anchorDir.path}/wordsnap.json')
      .writeAsStringSync(const JsonEncoder.withIndent('  ').convert(snapOut));

  // ---- model: annotations (REAL Dart Annotation.toJson) ----
  final annotations = <Annotation>[
    Annotation(
      id: '1000',
      selectedText: 'Hello',
      prefix: '',
      suffix: ' world',
      tool: AnnotationTool.highlight,
      timestamp: DateTime.utc(2026, 1, 2, 3, 4, 5, 0, 6),
      position: 0.0,
    ),
    Annotation(
      id: '1001',
      selectedText: 'naïve café 😀',
      prefix: 'a “smart” ',
      suffix: ' end\nline',
      tool: AnnotationTool.comment,
      note: 'a note with "quotes"\nand a newline',
      tag: AnnotationTag.query,
      timestamp: DateTime.utc(2026, 6, 8, 12, 0, 0),
      position: 0.5,
    ),
    Annotation(
      id: '1002',
      selectedText: 'underlined twice',
      prefix: 'see ',
      suffix: '.',
      tool: AnnotationTool.doubleUnderline,
      timestamp: DateTime.utc(2026, 6, 8, 12, 30, 0, 123),
      position: 0.123456,
    ),
    Annotation(
      id: '1003',
      selectedText: 'ink here',
      prefix: '',
      suffix: '',
      tool: AnnotationTool.inkAnnotation,
      tag: AnnotationTag.voice,
      timestamp: DateTime.utc(2026, 6, 8, 13, 0, 0),
      position: 1.0,
      hasInk: true,
    ),
    Annotation(
      id: '1004',
      selectedText: 'marker',
      prefix: '',
      suffix: '',
      tool: AnnotationTool.bookmark,
      timestamp: DateTime.utc(2026, 6, 8, 14, 0, 0),
      position: 0.75,
    ),
  ];
  File('${modelDir.path}/annotations.json').writeAsStringSync(
      const JsonEncoder.withIndent('  ')
          .convert(annotations.map((a) => a.toJson()).toList()));

  // ---- model: reading position (REAL Dart ReadingPosition.toJson) ----
  const pos = ReadingPosition(
    mode: ReadingMode.pageFlip,
    page: 42,
    scrollOffset: 0.0,
    fraction: 0.333,
  );
  File('${modelDir.path}/position.json').writeAsStringSync(
      const JsonEncoder.withIndent('  ').convert(pos.toJson()));

  // ---- docx fixture: a full .docx the Kotlin DocxStore.load reads end-to-end ----
  const para1 = 'The quick brown fox jumps over the lazy dog.';
  const para2 = 'Second paragraph here.';
  final documentXml = _docXml('${_para(para1)}${_para(para2)}');
  // P matches the CLEAN extraction for prose: each paragraph + a trailing \n.
  const plainText = '$para1\n$para2\n';

  final docAnnotations = <Annotation>[
    Annotation(
      id: 'annA',
      selectedText: 'brown fox',
      prefix: 'quick ',
      suffix: ' jumps',
      tool: AnnotationTool.highlight,
      timestamp: DateTime.utc(2026, 6, 8, 9, 0, 0),
      position: plainText.indexOf('brown fox') / plainText.length,
    ),
    Annotation(
      id: 'annB',
      selectedText: 'Second',
      prefix: '',
      suffix: ' paragraph',
      tool: AnnotationTool.underline,
      timestamp: DateTime.utc(2026, 6, 8, 9, 1, 0),
      position: plainText.indexOf('Second') / plainText.length,
    ),
    Annotation(
      id: 'annC',
      selectedText: 'nonexistent phrase',
      prefix: '',
      suffix: '',
      tool: AnnotationTool.comment,
      timestamp: DateTime.utc(2026, 6, 8, 9, 2, 0),
      position: 0.5,
    ),
    // hasInk:false in JSON, but the ink PNG is present -> load must flip to true.
    Annotation(
      id: 'ink1',
      selectedText: 'lazy dog',
      prefix: 'the ',
      suffix: '.',
      tool: AnnotationTool.inkAnnotation,
      timestamp: DateTime.utc(2026, 6, 8, 9, 3, 0),
      position: plainText.indexOf('lazy dog') / plainText.length,
    ),
    // hasInk:true in JSON, but NO PNG -> load must flip to false.
    Annotation(
      id: 'flagonly',
      selectedText: 'quick',
      prefix: 'The ',
      suffix: ' brown',
      tool: AnnotationTool.highlight,
      timestamp: DateTime.utc(2026, 6, 8, 9, 4, 0),
      position: plainText.indexOf('quick') / plainText.length,
      hasInk: true,
    ),
  ];

  const docPos = ReadingPosition(
      mode: ReadingMode.pageFlip, page: 1, scrollOffset: 0.0, fraction: 0.5);

  final archive = Archive();
  void put(String name, List<int> b) =>
      archive.addFile(ArchiveFile(name, b.length, b));
  put('word/document.xml', utf8.encode(documentXml));
  put('leamh/annotations.json',
      utf8.encode(jsonEncode(docAnnotations.map((a) => a.toJson()).toList())));
  put('leamh/position.json', utf8.encode(jsonEncode(docPos.toJson())));
  // PNG presence is the source of truth for hasInk: only ink1 gets one.
  put('word/media/ink_ink1.png',
      <int>[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0]);
  final docxBytes = ZipEncoder().encode(archive)!;

  final docxDir = Directory('${base.path}/docx')..createSync(recursive: true);
  File('${docxDir.path}/sample.docx').writeAsBytesSync(docxBytes);

  Map<String, dynamic>? spanOf(Annotation a) {
    final r = _locateInPlain(plainText, a.selectedText, a.prefix, a.suffix,
        positionHint: a.position);
    return r == null ? null : {'start': r.start, 'end': r.end};
  }

  final expectedDoc = {
    'plainText': plainText,
    'position': docPos.toJson(),
    'annotations': docAnnotations
        .map((a) => {
              'id': a.id,
              'hasInk': a.id == 'ink1', // only ink1 has a PNG
              'span': spanOf(a),
            })
        .toList(),
  };
  File('${docxDir.path}/expected.json')
      .writeAsStringSync(const JsonEncoder.withIndent('  ').convert(expectedDoc));

  // Console summary.
  stdout.writeln('locate cases:');
  for (final c in locateOut) {
    stdout.writeln('  ${c['name']}: ${c['expected']}');
  }
  stdout.writeln('wordsnap cases:');
  for (final c in snapOut) {
    stdout.writeln('  ${c['name']}: ${c['expected']}');
  }
  stdout.writeln('\nWrote anchoring + model goldens to ${base.path}');
}

const _wns =
    'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"';
String _docXml(String body) =>
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<w:document $_wns><w:body>$body</w:body></w:document>';
String _para(String text) => '<w:p><w:r><w:t>$text</w:t></w:r></w:p>';
