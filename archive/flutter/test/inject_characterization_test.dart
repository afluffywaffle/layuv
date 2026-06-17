// Characterization harness for _injectNativeFormatting (via the real
// DocxStore write path). NOT a committed golden — it dumps the written entries
// to /tmp so the injection algorithm can be optimized and verified
// byte-identical (capture baseline on the OLD code, diff after the rewrite).
//
//   flutter test test/inject_characterization_test.dart
//
// Exercises the paths the optimization touches: mid-run start/end splits,
// OVERLAPPING annotations (highlight+underline on the same words), every tool,
// notes/tags (comments), bookmarks, and a high annotation count.
import 'dart:convert';
import 'dart:io';
import 'package:archive/archive.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:layuv/models/annotation.dart';
import 'package:layuv/models/docx_store.dart';

const _wns =
    'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"';

// Ten distinct single-run paragraphs of prose.
const _paras = <String>[
  'The quick brown fox jumps over the lazy dog near the river bank today.',
  'She sells sea shells by the sea shore every single morning without fail.',
  'A journey of a thousand miles begins with a single deliberate step forward.',
  'To be or not to be that is the honest question worth asking ourselves now.',
  'All that glitters is not gold but silver shines brightly in the moonlight.',
  'The early bird catches the worm while the late sleeper misses the train.',
  'Better late than never but never late is far better than slightly tardy.',
  'When in Rome you should try to do exactly as the local Romans do daily.',
  'Actions always speak much louder than words in nearly every situation here.',
  'A picture is genuinely worth a thousand carefully chosen ordinary words.',
];

void main() {
  test('characterize injection output', () async {
    TestWidgetsFlutterBinding.ensureInitialized();
    final outDir = Directory('/tmp/inject_char')..createSync(recursive: true);

    // Build input DOCX: one run per paragraph.
    final body = _paras
        .map((t) => '<w:p><w:r><w:t xml:space="preserve">$t</w:t></w:r></w:p>')
        .join();
    final documentXml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<w:document $_wns><w:body>$body</w:body></w:document>';
    const contentTypes =
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
        '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
        '</Types>';
    const relsDotRels =
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
        '</Relationships>';
    const docRels =
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"></Relationships>';

    final input = Archive();
    void put(String n, String s) =>
        input.addFile(ArchiveFile(n, utf8.encode(s).length, utf8.encode(s)));
    put('[Content_Types].xml', contentTypes);
    put('_rels/.rels', relsDotRels);
    put('word/document.xml', documentXml);
    put('word/_rels/document.xml.rels', docRels);
    final inputBytes = ZipEncoder().encode(input)!;

    // Plain text the same way the reader sees it: paragraphs + newlines.
    final plain = '${_paras.join('\n')}\n';
    double pos(String s) => plain.indexOf(s) / plain.length;

    int idc = 0;
    DateTime ts() => DateTime.utc(2026, 1, 1, 0, 0, idc);
    Annotation mk(String sel, String pre, String suf, AnnotationTool tool,
        {String? note, AnnotationTag? tag}) {
      idc++;
      return Annotation(
        id: 'a$idc',
        selectedText: sel,
        prefix: pre,
        suffix: suf,
        tool: tool,
        note: note,
        tag: tag,
        timestamp: ts(),
        position: pos(sel),
      );
    }

    final anns = <Annotation>[
      // Para 1 — overlapping highlight + underline on "brown", mid-run splits.
      mk('quick brown', 'The ', ' fox', AnnotationTool.highlight),
      mk('brown fox jumps', 'quick ', ' over', AnnotationTool.underline),
      mk('lazy dog', 'the ', ' near', AnnotationTool.strikethrough),
      mk('river bank', 'the ', ' today', AnnotationTool.bookmark),
      // Para 2 — double underline, overlapping highlight.
      mk('sea shells', 'sells ', ' by', AnnotationTool.doubleUnderline),
      mk('shells by the sea', 'sea ', ' shore', AnnotationTool.highlight),
      mk('single morning', 'every ', ' without', AnnotationTool.underline,
          note: 'morning note', tag: AnnotationTag.query),
      // Para 3 — bookmark + highlight + underline same words (full overlap).
      mk('thousand miles', 'a ', ' begins', AnnotationTool.bookmark),
      mk('single deliberate', 'a ', ' step', AnnotationTool.highlight),
      mk('single deliberate', 'a ', ' step', AnnotationTool.underline),
      mk('step forward', 'deliberate ', '.', AnnotationTool.strikethrough),
      // Para 4 — comment with note, plus highlight.
      mk('honest question', 'the ', ' worth', AnnotationTool.highlight,
          note: 'is it though?', tag: AnnotationTag.pacing),
      mk('worth asking', 'question ', ' ourselves', AnnotationTool.underline),
      // Para 5.
      mk('glitters is not', 'that ', ' gold', AnnotationTool.highlight),
      mk('silver shines', 'but ', ' brightly', AnnotationTool.doubleUnderline),
      mk('moonlight', 'the ', '.', AnnotationTool.strikethrough),
      // Para 6.
      mk('early bird', 'The ', ' catches', AnnotationTool.highlight),
      mk('late sleeper', 'the ', ' misses', AnnotationTool.underline),
      mk('the train', 'misses ', '.', AnnotationTool.bookmark),
      // Para 7.
      mk('Better late', '', ' than', AnnotationTool.highlight),
      mk('never late', 'than ', ' is', AnnotationTool.strikethrough),
      mk('slightly tardy', 'than ', '.', AnnotationTool.underline,
          note: 'tardy', tag: AnnotationTag.query),
      // Para 8.
      mk('in Rome', 'When ', ' you', AnnotationTool.highlight),
      mk('local Romans', 'the ', ' do', AnnotationTool.underline),
      mk('do daily', 'Romans ', '.', AnnotationTool.doubleUnderline),
      // Para 9.
      mk('Actions always', '', ' speak', AnnotationTool.highlight),
      mk('louder than words', 'much ', ' in', AnnotationTool.underline),
      mk('every situation', 'nearly ', ' here', AnnotationTool.strikethrough),
      // Para 10.
      mk('picture is genuinely', 'A ', ' worth', AnnotationTool.highlight),
      mk('thousand carefully', 'a ', ' chosen', AnnotationTool.underline),
      mk('ordinary words', 'chosen ', '.', AnnotationTool.bookmark),
    ];

    final tmp = File('${Directory.systemTemp.path}/leamh_inject_char.docx');
    tmp.writeAsBytesSync(inputBytes);
    final store = DocxStore(filePath: tmp.path);
    await store.saveAll(anns);

    final out = ZipDecoder().decodeBytes(tmp.readAsBytesSync());
    String? entry(String n) {
      final f = out.findFile(n);
      return f == null ? null : utf8.decode(f.content as List<int>);
    }

    for (final e in {
      'word/document.xml': 'document.xml',
      'word/comments.xml': 'comments.xml',
      'leamh/annotations.json': 'annotations.json',
    }.entries) {
      final c = entry(e.key);
      if (c != null) File('${outDir.path}/${e.value}').writeAsStringSync(c);
    }

    // Sanity: every rPr tool annotation should have produced markup.
    final doc = entry('word/document.xml')!;
    expect(doc.contains('<w:highlight'), isTrue);
    expect(doc.contains('<w:u w:val="single"'), isTrue);
    expect(doc.contains('<w:u w:val="double"'), isTrue);
    expect(doc.contains('<w:strike/>'), isTrue);
    expect(doc.contains('<w:bookmarkStart'), isTrue);
    expect(entry('word/comments.xml'), isNotNull);
    // ignore: avoid_print
    print('CHAR: wrote ${outDir.path} (doc ${doc.length} chars)');
  });
}
