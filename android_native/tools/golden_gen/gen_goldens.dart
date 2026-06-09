// Golden generator for the native Android DOCX engine.
//
// Emits TWO golden sets the Kotlin `PlainTextMapper` is tested against:
//
//   golden/legacy/  — VERBATIM output of docx_store._buildPlainMap. A
//                     compatibility REFERENCE: documents the exact existing
//                     Flutter behaviour (bugs included). The clean extraction
//                     must equal this on ordinary prose.
//   golden/clean/   — the CLEAN extraction the native port actually uses for
//                     both rendering and anchoring. The Kotlin TARGET.
//
// `_buildPlainMap` / `_unesc` below are copied VERBATIM from
// lib/models/docx_store.dart — keep in sync. `buildCleanMap` is the spec for
// the native engine: it fixes the <w:tab/>/<w:tbl>/<w:tr>/<w:tc> over-match bug
// and decodes numeric entities, while remaining byte-identical to
// _buildPlainMap (incl. xmlOffsets) on prose.
//
// Run from the repo root:
//   dart run android_native/tools/golden_gen/gen_goldens.dart
//
import 'dart:convert';
import 'dart:io';

// ---------------------------------------------------------------------------
// VERBATIM from lib/models/docx_store.dart — the legacy reference.
// ---------------------------------------------------------------------------

String _unesc(String s) => s
    .replaceAll('&apos;', "'")
    .replaceAll('&quot;', '"')
    .replaceAll('&gt;', '>')
    .replaceAll('&lt;', '<')
    .replaceAll('&amp;', '&');

({String plain, List<int> xmlOffsets}) buildPlainMapLegacy(String xml) {
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

// ---------------------------------------------------------------------------
// CLEAN extraction — the native engine spec. A small, dependency-free scanner
// that parses real element names (so <w:tab/> != <w:t>), decodes named AND
// numeric entities, and maps tab/break/paragraph to real characters.
//
//   <w:t>…</w:t>      decoded text, one xmlOffset per UTF-16 code unit (base+i,
//                     matching _buildPlainMap on entity-free content)
//   <w:tab/>          '\t'      offset = tag start
//   <w:br/> <w:cr/>   '\n'      offset = tag start
//   </w:p>            '\n'      offset = tag start   (trailing newline kept)
//   everything else   ignored
// ---------------------------------------------------------------------------

String _decodeEntities(String s) {
  final re = RegExp(r'&(#x[0-9A-Fa-f]+|#[0-9]+|amp|lt|gt|quot|apos);');
  return s.replaceAllMapped(re, (m) {
    final e = m.group(1)!;
    switch (e) {
      case 'amp':
        return '&';
      case 'lt':
        return '<';
      case 'gt':
        return '>';
      case 'quot':
        return '"';
      case 'apos':
        return "'";
    }
    final code = e.startsWith('#x')
        ? int.parse(e.substring(2), radix: 16)
        : int.parse(e.substring(1));
    return String.fromCharCode(code);
  });
}

({String plain, List<int> xmlOffsets}) buildCleanMap(String xml) {
  final buf = StringBuffer();
  final offsets = <int>[];
  final n = xml.length;
  int i = 0;
  while (i < n) {
    final lt = xml.indexOf('<', i);
    if (lt < 0) break;
    final gt = xml.indexOf('>', lt);
    if (gt < 0) break;
    final tag = xml.substring(lt, gt + 1); // includes '<' and '>'
    final isEnd = tag.startsWith('</');
    final isSelfClose = tag.endsWith('/>');

    // Element name = chars after '<' (or '</') up to space/'/'/'>'.
    final nameStart = isEnd ? 2 : 1;
    int k = nameStart;
    while (k < tag.length) {
      final c = tag[k];
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '/' || c == '>') {
        break;
      }
      k++;
    }
    final name = tag.substring(nameStart, k);

    if (!isEnd && !isSelfClose && name == 'w:t') {
      final contentStart = gt + 1;
      final close = xml.indexOf('</w:t>', contentStart);
      final end = close < 0 ? n : close;
      final decoded = _decodeEntities(xml.substring(contentStart, end));
      for (int j = 0; j < decoded.length; j++) {
        buf.write(decoded[j]);
        offsets.add(contentStart + j);
      }
      i = close < 0 ? n : close + '</w:t>'.length;
      continue;
    }
    if (!isEnd && name == 'w:tab') {
      buf.write('\t');
      offsets.add(lt);
    } else if (!isEnd && (name == 'w:br' || name == 'w:cr')) {
      buf.write('\n');
      offsets.add(lt);
    } else if (isEnd && name == 'w:p') {
      buf.write('\n');
      offsets.add(lt);
    }
    i = gt + 1;
  }
  return (plain: buf.toString(), xmlOffsets: offsets);
}

// ---------------------------------------------------------------------------
// Fixtures — each a full word/document.xml exercising one edge case.
// ---------------------------------------------------------------------------

const _w =
    'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"';
String _doc(String body) =>
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<w:document $_w><w:body>$body</w:body></w:document>';
String _p(String runs) => '<w:p>$runs</w:p>';
String _r(String t, {String? attrs}) =>
    '<w:r><w:t${attrs == null ? '' : ' $attrs'}>$t</w:t></w:r>';

// Prose fixtures: clean MUST equal legacy byte-for-byte (plain + offsets).
const _proseNames = {
  'simple',
  'preserve',
  'multi_wt_run',
  'empty_para',
  'self_closing_run',
  'unicode',
};

final fixtures = <String, String>{
  'simple': _doc(_p(_r('Hello world.')) + _p(_r('Second paragraph.'))),
  'preserve':
      _doc(_p(_r('  leading and trailing  ', attrs: 'xml:space="preserve"'))),
  'multi_wt_run':
      _doc(_p('<w:r><w:t>first part </w:t><w:t>second part</w:t></w:r>')),
  'empty_para': _doc('${_p(_r('above'))}<w:p></w:p>${_p(_r('below'))}'),
  'self_closing_run': _doc(_p('<w:r><w:t>keep</w:t></w:r>'
      '<w:r w:rsidR="00AB12CD"/>'
      '<w:r><w:t> this</w:t></w:r>')),
  'unicode': _doc(_p(_r('café — naïve 😀 end'))),

  // Buggy-in-legacy fixtures: clean diverges (correctly) from legacy.
  'entities': _doc(_p(_r('Tom &amp; Jerry &lt;tag&gt; &quot;q&quot; &apos;a&apos;')) +
      _p(_r('caf&#233; numeric'))),
  'tabs_breaks': _doc(_p('<w:r><w:t>before</w:t></w:r>'
      '<w:r><w:tab/></w:r>'
      '<w:r><w:t>after</w:t></w:r>'
      '<w:r><w:br/></w:r>'
      '<w:r><w:t>nextline-still-same-para</w:t></w:r>')),
  'table': _doc('${_p(_r('intro'))}'
      '<w:tbl><w:tr>'
      '<w:tc>${_p(_r('cell A'))}</w:tc>'
      '<w:tc>${_p(_r('cell B'))}</w:tc>'
      '</w:tr></w:tbl>'
      '${_p(_r('outro'))}'),
};

// ---------------------------------------------------------------------------

void main() {
  final base = Directory('android_native/docx/src/test/resources/golden');
  final legacyDir = Directory('${base.path}/legacy')..createSync(recursive: true);
  final cleanDir = Directory('${base.path}/clean')..createSync(recursive: true);
  final fixtureDir = Directory('${base.path}/fixtures')..createSync(recursive: true);

  final report = StringBuffer()
    ..writeln('# Golden generation report\n')
    ..writeln('`legacy` = docx_store._buildPlainMap (reference). '
        '`clean` = native engine target.\n')
    ..writeln('| fixture | prose? | clean==legacy? | len(legacy) | len(clean) | clean P |')
    ..writeln('|---|---|---|---|---|---|');

  bool allProseMatch = true;
  final names = fixtures.keys.toList()..sort();
  for (final name in names) {
    final xml = fixtures[name]!;
    final legacy = buildPlainMapLegacy(xml);
    final clean = buildCleanMap(xml);

    File('${fixtureDir.path}/$name.document.xml').writeAsStringSync(xml);
    File('${legacyDir.path}/$name.plain.txt').writeAsStringSync(legacy.plain);
    File('${legacyDir.path}/$name.offsets.json')
        .writeAsStringSync(jsonEncode(legacy.xmlOffsets));
    File('${cleanDir.path}/$name.plain.txt').writeAsStringSync(clean.plain);
    File('${cleanDir.path}/$name.offsets.json')
        .writeAsStringSync(jsonEncode(clean.xmlOffsets));

    final isProse = _proseNames.contains(name);
    final match = legacy.plain == clean.plain &&
        jsonEncode(legacy.xmlOffsets) == jsonEncode(clean.xmlOffsets);
    if (isProse && !match) allProseMatch = false;

    report.writeln('| $name | ${isProse ? 'yes' : 'no'} | '
        '${match ? '✅' : '➖ (fixes bug)'} | ${legacy.plain.length} | '
        '${clean.plain.length} | `${_escape(clean.plain)}` |');
  }

  File('${base.path}/_REPORT.md').writeAsStringSync(report.toString());
  stdout.write(report.toString());
  stdout.writeln('\nWrote ${names.length} fixtures + legacy/clean goldens '
      'to ${base.path}');
  if (!allProseMatch) {
    stderr.writeln('\n❌ INVARIANT VIOLATED: a prose fixture has '
        'clean != legacy. The clean extraction must match _buildPlainMap on '
        'prose.');
    exitCode = 1;
  } else {
    stdout.writeln('✅ Invariant holds: clean == legacy on all prose fixtures.');
  }
}

String _escape(String s) =>
    s.replaceAll('\n', '\\n').replaceAll('\t', '\\t');
