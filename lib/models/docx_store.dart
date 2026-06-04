import 'dart:convert';
import 'dart:io';
import 'package:archive/archive_io.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'annotation.dart';
import 'annotation_store_interface.dart';
import 'reading_position.dart';

class DocxStore implements AnnotationStoreInterface {
  final String filePath;

  Future<void> _writeLock = Future.value();

  static const _bookmarkChannel =
      MethodChannel('com.afluffywaffle.layuv/bookmarks');

  DocxStore({required this.filePath});

  Future<void> _resolveAccess() async {
    if (!Platform.isMacOS) return;
    try {
      await _bookmarkChannel.invokeMethod('resolveBookmark', filePath);
    } catch (_) {
      // Best-effort — proceed even if bookmark resolution fails
    }
  }

  Future<T> _serialized<T>(Future<T> Function() fn) {
    final next = _writeLock.then((_) => fn());
    _writeLock = next.then((_) {}, onError: (_) {});
    return next;
  }

  // ---------------------------------------------------------------------------
  // XML helpers
  // ---------------------------------------------------------------------------

  static String _esc(String s) => s
      .replaceAll('&', '&amp;')
      .replaceAll('<', '&lt;')
      .replaceAll('>', '&gt;')
      .replaceAll('"', '&quot;')
      .replaceAll("'", '&apos;');

  static String _unesc(String s) => s
      .replaceAll('&apos;', "'")
      .replaceAll('&quot;', '"')
      .replaceAll('&gt;', '>')
      .replaceAll('&lt;', '<')
      .replaceAll('&amp;', '&');

  static String _buildComment({
    required int xmlId,
    required Annotation a,
  }) {
    final pct = '${(a.position * 100).round()}%';
    final header = '[tool:${a.tool.name}]'
        '${a.tag != null ? ' [tag:${a.tag!.name}]' : ''}'
        ' $pct — &quot;${_esc(a.selectedText)}&quot;';
    final noteXml = (a.note != null && a.note!.isNotEmpty)
        ? '<w:p><w:r><w:t xml:space="preserve">${_esc(a.note!)}</w:t></w:r></w:p>'
        : '';
    return '''
<w:comment w:id="$xmlId" w:author="${_esc(a.id)}" w:date="${a.timestamp.toUtc().toIso8601String()}">
  <w:p>
    <w:pPr><w:pStyle w:val="CommentText"/></w:pPr>
    <w:r><w:rPr><w:rStyle w:val="CommentReference"/></w:rPr><w:annotationRef/></w:r>
    <w:r><w:t xml:space="preserve">$header</w:t></w:r>
  </w:p>
  $noteXml
</w:comment>''';
  }

  // ---------------------------------------------------------------------------
  // ZIP helpers
  // ---------------------------------------------------------------------------

  Archive _decodeZip(List<int> bytes) => ZipDecoder().decodeBytes(bytes);

  String? _entryString(Archive archive, String name) {
    final entry = archive.findFile(name);
    if (entry == null) return null;
    return utf8.decode(entry.content as List<int>);
  }

  // ---------------------------------------------------------------------------
  // Reading position  (stored in leamh/position.json)
  // ---------------------------------------------------------------------------

  @override
  Future<ReadingPosition?> loadPosition() async {
    try {
      final bytes = await File(filePath).readAsBytes();
      final archive = _decodeZip(bytes);
      final raw = _entryString(archive, 'leamh/position.json');
      if (raw == null) return null;
      final json = jsonDecode(raw) as Map<String, dynamic>;
      return ReadingPosition.fromJson(json);
    } catch (e) {
      debugPrint('DocxStore.loadPosition error: $e');
      return null;
    }
  }

  @override
  Future<void> savePosition(ReadingPosition position) =>
      _serialized(() => _savePositionInner(position));

  Future<void> _savePositionInner(ReadingPosition position) async {
    try {
      await _resolveAccess();
      final bytes = await File(filePath).readAsBytes();
      var archive = _decodeZip(bytes);

      final posJson = utf8.encode(jsonEncode(position.toJson()));
      archive = _replaceOrAddEntry(archive, 'leamh/position.json', posJson);

      await _writeArchive(archive);
    } catch (e) {
      debugPrint('DocxStore.savePosition error: $e');
    }
  }

  // ---------------------------------------------------------------------------
  // Load annotations
  // ---------------------------------------------------------------------------

  @override
  Future<List<Annotation>> loadAnnotations() async {
    try {
      final bytes = await File(filePath).readAsBytes();
      final archive = _decodeZip(bytes);
      final xml = _entryString(archive, 'word/comments.xml');
      if (xml == null) return [];
      return _parseComments(xml);
    } catch (e) {
      debugPrint('DocxStore.loadAnnotations error: $e');
      return [];
    }
  }

  static List<Annotation> _parseComments(String xml) {
    final results = <Annotation>[];

    // Match each <w:comment ...>...</w:comment> block
    final commentRe = RegExp(
      r'<w:comment\s([^>]*)>(.*?)</w:comment>',
      dotAll: true,
    );

    final authorAttr = RegExp(r'w:author="([^"]*)"');
    final dateAttr = RegExp(r'w:date="([^"]*)"');

    // Header: [tool:X] [tag:Y] Z% — "selected text"
    final headerRe = RegExp(
      r'\[tool:(\w+)\](?:\s\[tag:(\w+)\])?\s(\d+)%\s—\s"(.*)"',
      dotAll: true,
    );

    // Extract all <w:t> contents in order
    final wtRe = RegExp(r'<w:t[^>]*>(.*?)</w:t>', dotAll: true);

    for (final cm in commentRe.allMatches(xml)) {
      try {
        final attrs = cm.group(1)!;
        final body = cm.group(2)!;

        final annotationId = _unesc(authorAttr.firstMatch(attrs)?.group(1) ?? '');
        final dateStr = dateAttr.firstMatch(attrs)?.group(1);
        if (annotationId.isEmpty || dateStr == null) continue;

        final timestamp = DateTime.parse(dateStr);

        // Collect all <w:t> texts
        final texts = wtRe
            .allMatches(body)
            .map((m) => _unesc(m.group(1)!))
            .toList();

        if (texts.isEmpty) continue;

        // First w:t that matches the header pattern is the header
        String? headerText;
        int headerIdx = -1;
        for (int i = 0; i < texts.length; i++) {
          if (headerRe.hasMatch(texts[i])) {
            headerText = texts[i];
            headerIdx = i;
            break;
          }
        }
        if (headerText == null) continue;

        final hm = headerRe.firstMatch(headerText)!;
        final toolName = hm.group(1)!;
        final tagName = hm.group(2);
        final pctStr = hm.group(3)!;
        final selectedText = hm.group(4)!;

        AnnotationTool tool;
        try {
          tool = AnnotationTool.values.byName(toolName);
        } catch (_) {
          tool = AnnotationTool.highlight;
        }

        AnnotationTag? tag;
        if (tagName != null) {
          try {
            tag = AnnotationTag.values.byName(tagName);
          } catch (_) {
            tag = null;
          }
        }

        // Remaining texts after the header paragraph form the note
        final noteTexts = texts.sublist(headerIdx + 1);
        final note = noteTexts.isNotEmpty ? noteTexts.join('\n') : null;

        results.add(Annotation(
          id: annotationId,
          selectedText: selectedText,
          prefix: '',
          suffix: '',
          tool: tool,
          tag: tag,
          note: note?.isEmpty == true ? null : note,
          timestamp: timestamp,
          position: int.parse(pctStr) / 100.0,
        ));
      } catch (e) {
        debugPrint('DocxStore: skipping malformed comment: $e');
      }
    }

    results.sort((a, b) => a.position.compareTo(b.position));
    return results;
  }

  // ---------------------------------------------------------------------------
  // Save / delete
  // ---------------------------------------------------------------------------

  @override
  Future<void> saveAnnotation(Annotation annotation) =>
      _serialized(() async {
        final list = await loadAnnotations();
        final idx = list.indexWhere((a) => a.id == annotation.id);
        if (idx >= 0) {
          list[idx] = annotation;
        } else {
          list.add(annotation);
        }
        await _writeAllAnnotations(list);
      });

  @override
  Future<void> deleteAnnotation(String id) =>
      _serialized(() async {
        final list = await loadAnnotations();
        list.removeWhere((a) => a.id == id);
        await _writeAllAnnotations(list);
      });

  @override
  Future<void> deleteAll(List<String> ids) =>
      _serialized(() async {
        final set = ids.toSet();
        final list = await loadAnnotations();
        list.removeWhere((a) => set.contains(a.id));
        await _writeAllAnnotations(list);
      });

  // ---------------------------------------------------------------------------
  // _writeAllAnnotations
  // ---------------------------------------------------------------------------

  Future<void> _writeAllAnnotations(List<Annotation> annotations) async {
    await _resolveAccess();
    final bytes = await File(filePath).readAsBytes();
    var archive = _decodeZip(bytes);

    // Build word/comments.xml
    final commentBlocks = annotations
        .asMap()
        .entries
        .map((e) => _buildComment(xmlId: e.key, a: e.value))
        .join('\n');

    final commentsXml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<w:comments'
        ' xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
        ' xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
        ' xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml"'
        '>\n'
        '$commentBlocks\n'
        '</w:comments>';

    archive = _replaceOrAddEntry(archive, 'word/comments.xml', utf8.encode(commentsXml));
    archive = _ensureContentType(archive);
    archive = _ensureRelsEntry(archive);

    await _writeArchive(archive);
  }

  Archive _replaceOrAddEntry(Archive archive, String name, List<int> data) {
    final newArchive = Archive();
    for (final file in archive.files) {
      if (file.name != name) newArchive.addFile(file);
    }
    newArchive.addFile(ArchiveFile(name, data.length, data));
    return newArchive;
  }

  Archive _ensureContentType(Archive archive) {
    const entryName = '[Content_Types].xml';
    final raw = _entryString(archive, entryName);
    if (raw == null) return archive;
    const override = 'PartName="/word/comments.xml"';
    if (raw.contains(override)) return archive;

    const insertion =
        '<Override PartName="/word/comments.xml"'
        ' ContentType="application/vnd.openxmlformats-officedocument'
        '.wordprocessingml.comments+xml"/>';

    final updated = raw.replaceFirst('</Types>', '$insertion\n</Types>');
    return _replaceOrAddEntry(archive, entryName, utf8.encode(updated));
  }

  Archive _ensureRelsEntry(Archive archive) {
    const entryName = 'word/_rels/document.xml.rels';
    final raw = _entryString(archive, entryName);
    if (raw == null) return archive;
    if (raw.contains('comments.xml')) return archive;

    const rel =
        '<Relationship Id="rId_leamh_comments"'
        ' Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments"'
        ' Target="comments.xml"/>';

    final updated = raw.replaceFirst('</Relationships>', '$rel\n</Relationships>');
    return _replaceOrAddEntry(archive, entryName, utf8.encode(updated));
  }

  Future<void> _writeArchive(Archive archive) async {
    final outBytes = ZipEncoder().encode(archive)!;
    await File(filePath).writeAsBytes(outBytes, flush: true);
  }
}
