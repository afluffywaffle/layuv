// Generates write-back goldens by driving the REAL Dart DocxStore
// (_writeAllAnnotations via saveAnnotation) — authoritative, not a copy. Run:
//
//   flutter test android_native/tools/golden_gen/writeback_golden_test.dart
//
// Inputs are PROSE (no tabs/tables), where the clean Kotlin PlainTextMapper is
// byte-identical to the legacy _buildPlainMap, so the Kotlin DocxStore.write
// output must match these XML entries byte-for-byte.
import 'dart:convert';
import 'dart:io';
import 'package:archive/archive.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:layuv/models/annotation.dart';
import 'package:layuv/models/docx_store.dart';

const _wns =
    'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"';

const _contentTypes =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
    '</Types>';

const _relsDotRels =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
    '</Relationships>';

const _documentXml =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<w:document $_wns><w:body>'
    '<w:p><w:r><w:t>The quick brown fox jumps over the lazy dog.</w:t></w:r></w:p>'
    '<w:p><w:r><w:t>Second paragraph here.</w:t></w:r></w:p>'
    '</w:body></w:document>';

const _docRels =
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '</Relationships>';

void main() {
  test('generate writeback goldens', () async {
    TestWidgetsFlutterBinding.ensureInitialized();
    final goldenDir =
        Directory('android_native/docx/src/test/resources/golden/writeback')
          ..createSync(recursive: true);

    // Build the input DOCX.
    final input = Archive();
    void put(String name, List<int> bytes) =>
        input.addFile(ArchiveFile(name, bytes.length, bytes));
    put('[Content_Types].xml', utf8.encode(_contentTypes));
    put('_rels/.rels', utf8.encode(_relsDotRels));
    put('word/document.xml', utf8.encode(_documentXml));
    put('word/_rels/document.xml.rels', utf8.encode(_docRels));
    // Ink PNG present (as saveInkPng would have written it).
    put('word/media/ink_ink4.png',
        <int>[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
    final inputBytes = ZipEncoder().encode(input)!;
    File('${goldenDir.path}/input.docx').writeAsBytesSync(inputBytes);

    // Annotations covering: rPr-only (highlight, run-split), note+tag comment,
    // bookmark, and ink (drawing + rels). Fixed UTC timestamps for determinism.
    const p =
        'The quick brown fox jumps over the lazy dog.\nSecond paragraph here.\n';
    double pos(String s) => p.indexOf(s) / p.length;
    final anns = <Annotation>[
      Annotation(
          id: 'h1',
          selectedText: 'quick brown',
          prefix: 'The ',
          suffix: ' fox',
          tool: AnnotationTool.highlight,
          timestamp: DateTime.utc(2026, 6, 8, 10, 0, 0),
          position: pos('quick brown')),
      Annotation(
          id: 'u2',
          selectedText: 'lazy dog',
          prefix: 'the ',
          suffix: '.',
          tool: AnnotationTool.underline,
          note: 'a note',
          tag: AnnotationTag.query,
          timestamp: DateTime.utc(2026, 6, 8, 10, 1, 0),
          position: pos('lazy dog')),
      Annotation(
          id: 'b3',
          selectedText: 'Second',
          prefix: '',
          suffix: ' paragraph',
          tool: AnnotationTool.bookmark,
          timestamp: DateTime.utc(2026, 6, 8, 10, 2, 0),
          position: pos('Second')),
      Annotation(
          id: 'ink4',
          selectedText: 'paragraph',
          prefix: 'Second ',
          suffix: ' here',
          tool: AnnotationTool.inkAnnotation,
          note: 'ink note',
          hasInk: true,
          timestamp: DateTime.utc(2026, 6, 8, 10, 3, 0),
          position: pos('paragraph')),
    ];

    final tmp = File('${Directory.systemTemp.path}/leamh_wb_golden.docx');
    tmp.writeAsBytesSync(inputBytes);
    final store = DocxStore(filePath: tmp.path);
    for (final a in anns) {
      await store.saveAnnotation(a);
    }

    // Dump the written entries as goldens.
    final out = ZipDecoder().decodeBytes(tmp.readAsBytesSync());
    String? entry(String n) {
      final f = out.findFile(n);
      return f == null ? null : utf8.decode(f.content as List<int>);
    }

    final mapping = {
      'word/document.xml': 'document.xml',
      'word/comments.xml': 'comments.xml',
      '[Content_Types].xml': 'content_types.xml',
      'word/_rels/document.xml.rels': 'document.xml.rels',
      'word/_rels/comments.xml.rels': 'comments.xml.rels',
      'leamh/annotations.json': 'annotations.json',
      'leamh/document_clean.xml': 'document_clean.xml',
    };
    for (final e in mapping.entries) {
      final content = entry(e.key);
      if (content != null) {
        File('${goldenDir.path}/${e.value}').writeAsStringSync(content);
      }
    }

    expect(entry('word/document.xml'), isNotNull);
    expect(entry('word/comments.xml'), isNotNull);
  });
}
