// Perf reproduction for _injectNativeFormatting at scale (throwaway, not a
// committed golden). Builds a large document with many annotations and times
// the full saveAll, and dumps document.xml to /tmp/inject_char/perf_document.xml
// so the rewrite can be checked byte-identical at scale too.
//
//   flutter test test/inject_perf_test.dart
import 'dart:convert';
import 'dart:io';
import 'package:archive/archive.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:layuv/models/annotation.dart';
import 'package:layuv/models/docx_store.dart';

const _wns =
    'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"';

void main() {
  test('perf: many annotations on a large document', () async {
    TestWidgetsFlutterBinding.ensureInitialized();
    final outDir = Directory('/tmp/inject_char')..createSync(recursive: true);

    const nParas = 260;
    const filler =
        'lorem ipsum dolor sit amet consectetur adipiscing elit sed do '
        'eiusmod tempor incididunt ut labore et dolore magna aliqua enim '
        'ad minim veniam quis nostrud exercitation ullamco laboris nisi ut '
        'aliquip ex ea commodo consequat duis aute irure dolor in voluptate '
        'velit esse cillum dolore eu fugiat nulla pariatur excepteur sint';

    // Each paragraph embeds its index so annotation phrases are unique and
    // locate deterministically via context match.
    final paras = <String>[
      for (int i = 0; i < nParas; i++)
        'Section$i intro mark${i}a quick brown fox mid mark${i}b lazy dog tail '
            '$filler done$i.',
    ];

    final body = paras
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

    final plain = '${paras.join('\n')}\n';
    double pos(String s) => plain.indexOf(s) / plain.length;

    int idc = 0;
    final anns = <Annotation>[];
    for (int i = 0; i < nParas; i++) {
      idc++;
      anns.add(Annotation(
        id: 'h$idc',
        selectedText: 'quick brown fox',
        prefix: 'mark${i}a ',
        suffix: ' mid',
        tool: AnnotationTool.highlight,
        timestamp: DateTime.utc(2026, 1, 1, 0, 0, idc % 60),
        position: pos('mark${i}a quick brown fox'),
      ));
      idc++;
      anns.add(Annotation(
        id: 'u$idc',
        selectedText: 'lazy dog',
        prefix: 'mark${i}b ',
        suffix: ' tail',
        tool: AnnotationTool.underline,
        timestamp: DateTime.utc(2026, 1, 1, 0, 0, idc % 60),
        position: pos('mark${i}b lazy dog'),
      ));
    }

    final tmp = File('${Directory.systemTemp.path}/leamh_inject_perf.docx');
    tmp.writeAsBytesSync(inputBytes);
    final store = DocxStore(filePath: tmp.path);

    final sw = Stopwatch()..start();
    await store.saveAll(anns);
    sw.stop();

    final out = ZipDecoder().decodeBytes(tmp.readAsBytesSync());
    final doc =
        utf8.decode(out.findFile('word/document.xml')!.content as List<int>);
    File('${outDir.path}/perf_document.xml').writeAsStringSync(doc);

    final hl = RegExp('<w:highlight').allMatches(doc).length;
    final ul = RegExp('<w:u w:val="single"').allMatches(doc).length;
    // ignore: avoid_print
    print('PERF: ${anns.length} anns, document.xml ${doc.length} chars, '
        'saveAll ${sw.elapsedMilliseconds}ms — highlights=$hl underlines=$ul');
    expect(hl, nParas);
    expect(ul, nParas);
  });
}
