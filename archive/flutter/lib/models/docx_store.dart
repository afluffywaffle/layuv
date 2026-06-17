import 'dart:convert';
import 'dart:io';
import 'dart:isolate';
import 'package:archive/archive_io.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'annotation.dart';
import 'annotation_store_interface.dart';
import 'reading_position.dart';
import '../utils/annotation_utils.dart' show DocxFormatSpan;
import '../utils/platform_utils.dart' show safeWriteBytes;

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
    } catch (_) {}
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

  // OOXML run property for a given tool (inner content only, no outer tags).
  static String _rPrForTool(AnnotationTool tool) => switch (tool) {
        AnnotationTool.highlight ||
        AnnotationTool.comment ||
        AnnotationTool.inkAnnotation =>
          '<w:highlight w:val="yellow"/>',
        AnnotationTool.underline => '<w:u w:val="single"/>',
        AnnotationTool.doubleUnderline => '<w:u w:val="double"/>',
        AnnotationTool.strikethrough => '<w:strike/>',
        AnnotationTool.wavyUnderline => '<w:u w:val="wave"/>',
        AnnotationTool.bookmark => '',
      };

  // ---------------------------------------------------------------------------
  // ZIP helpers
  // ---------------------------------------------------------------------------

  static Archive _decodeZip(List<int> bytes) => ZipDecoder().decodeBytes(bytes);

  static String? _entryString(Archive archive, String name) {
    final entry = archive.findFile(name);
    if (entry == null) return null;
    return utf8.decode(entry.content as List<int>);
  }

  // ---------------------------------------------------------------------------
  // Reading position (stored in leamh/position.json)
  // ---------------------------------------------------------------------------

  @override
  Future<ReadingPosition?> loadPosition() async {
    try {
      final bytes = await File(filePath).readAsBytes();
      final archive = _decodeZip(bytes);
      final raw = _entryString(archive, 'leamh/position.json');
      if (raw == null) return null;
      return ReadingPosition.fromJson(jsonDecode(raw) as Map<String, dynamic>);
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
      archive = _replaceOrAddEntry(
          archive, 'leamh/position.json', utf8.encode(jsonEncode(position.toJson())));
      archive = _ensureContentType(archive);
      await _writeArchive(archive);
    } catch (e) {
      debugPrint('DocxStore.savePosition error: $e');
    }
  }

  // ---------------------------------------------------------------------------
  // Load annotations
  //
  // Primary:  leamh/annotations.json  (written by Léamh — complete, reliable)
  // Fallback: word/comments.xml       (legacy Léamh format + native Word comments)
  // ---------------------------------------------------------------------------

  @override
  Future<List<Annotation>> loadAnnotations() async {
    try {
      final bytes = await File(filePath).readAsBytes();
      final archive = _decodeZip(bytes);

      final jsonRaw = _entryString(archive, 'leamh/annotations.json');
      if (jsonRaw != null) {
        final list = jsonDecode(jsonRaw) as List<dynamic>;
        // Detect hasInk from archive presence — PNG is the source of truth.
        return list.map((e) {
          final a = Annotation.fromJson(e as Map<String, dynamic>);
          final hasInk = archive.findFile('word/media/ink_${a.id}.png') != null;
          return hasInk == a.hasInk ? a : a.copyWith(hasInk: hasInk);
        }).toList();
      }

      // No Léamh JSON yet — import native DOCX formatting + legacy comments.
      final documentXml = _entryString(archive, 'word/document.xml') ?? '';
      final commentsXml = _entryString(archive, 'word/comments.xml');
      final native = documentXml.isNotEmpty
          ? _importNativeFormatting(documentXml)
          : <Annotation>[];
      final legacy = commentsXml != null
          ? _parseComments(commentsXml, documentXml)
          : <Annotation>[];
      return [...native, ...legacy];
    } catch (e) {
      debugPrint('DocxStore.loadAnnotations error: $e');
      return [];
    }
  }

  // ---------------------------------------------------------------------------
  // Native formatting importer
  //
  // Scans word/document.xml for existing <w:highlight>, <w:u>, <w:strike>
  // run properties and returns them as Léamh annotations.  Adjacent runs
  // with the same tool are merged into a single annotation.
  // Called only when leamh/annotations.json is absent (fresh or legacy file).
  // ---------------------------------------------------------------------------

  static List<Annotation> _importNativeFormatting(String documentXml) {
    final map = _buildPlainMap(documentXml);
    if (map.plain.isEmpty) return [];

    final runRe = RegExp(r'<w:r(?:\s[^>]*)?>(?<!\/>).*?</w:r>', dotAll: true);
    final wtRe = RegExp(r'<w:t(?:[^>]*)>(.*?)</w:t>', dotAll: true);

    // Collect (tool, plainStart, plainEnd) for each individually formatted run.
    final segments =
        <({AnnotationTool tool, int plainStart, int plainEnd})>[];

    for (final runMatch in runRe.allMatches(documentXml)) {
      final runContent = runMatch.group(0)!;

      // Only inspect the run's own <w:rPr>, stopping before <w:rPrChange>
      // (which carries the *pre-change* formatting for tracked edits).
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

      // Extract plain text from this run.
      final text =
          wtRe.allMatches(runContent).map((m) => _unesc(m.group(1)!)).join('');
      if (text.isEmpty) continue;

      // Locate this run's text in the plain map using the XML position of
      // its first <w:t> content character.
      final firstWt = wtRe.firstMatch(runContent);
      if (firstWt == null) continue;
      final wtContentStart = runMatch.start +
          firstWt.start +
          firstWt.group(0)!.indexOf('>') +
          1;

      // Binary search: first plain-map entry at or after wtContentStart.
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

    // Merge consecutive segments that share the same tool and abut in the
    // plain text (allowing a gap of 0 — i.e. directly touching).
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

  static Annotation _annotationFromSegment(
    ({AnnotationTool tool, int plainStart, int plainEnd}) seg,
    String plain,
    int index,
  ) {
    final text = plain.substring(seg.plainStart, seg.plainEnd);
    final pos = plain.isNotEmpty
        ? (seg.plainStart / plain.length).clamp(0.0, 1.0)
        : 0.0;
    final prefix =
        plain.substring((seg.plainStart - 20).clamp(0, seg.plainStart), seg.plainStart);
    final suffix = plain.substring(
        seg.plainEnd, (seg.plainEnd + 20).clamp(seg.plainEnd, plain.length));
    return Annotation(
      id: '${DateTime.now().microsecondsSinceEpoch + index}',
      selectedText: text,
      prefix: prefix,
      suffix: suffix,
      tool: seg.tool,
      timestamp: DateTime.now(),
      position: pos,
    );
  }

  // ---------------------------------------------------------------------------
  // DOCX format-span extractor
  //
  // Returns bold/italic/heading spans for the full document plain text so the
  // reader can layer DOCX formatting under annotation highlights.
  // Reads leamh/document_clean.xml when available (avoids picking up
  // Léamh-injected underlines/highlights as native formatting).
  // ---------------------------------------------------------------------------

  static List<DocxFormatSpan> extractFormatSpans(List<int> docxBytes) {
    try {
      final archive = ZipDecoder().decodeBytes(docxBytes);
      ArchiveFile? entry = archive.findFile('leamh/document_clean.xml');
      entry ??= archive.findFile('word/document.xml');
      if (entry == null) return const [];
      final documentXml = utf8.decode(entry.content as List<int>);
      final map = _buildPlainMap(documentXml);
      if (map.plain.isEmpty) return const [];
      return _extractFormatSpans(documentXml, map.plain, map.xmlOffsets);
    } catch (e) {
      debugPrint('DocxStore.extractFormatSpans error: $e');
      return const [];
    }
  }

  static String? extractTitle(List<int> docxBytes) {
    try {
      final archive = ZipDecoder().decodeBytes(docxBytes);
      final entry = archive.findFile('docProps/core.xml');
      if (entry == null) return null;
      final xml = utf8.decode(entry.content as List<int>);
      final match = RegExp(r'<dc:title[^>]*>(.*?)</dc:title>', dotAll: true).firstMatch(xml);
      final title = match?.group(1)?.trim();
      return (title == null || title.isEmpty) ? null : title;
    } catch (_) {
      return null;
    }
  }

  static List<DocxFormatSpan> _extractFormatSpans(
      String documentXml, String plain, List<int> xmlOffsets) {
    final spans = <DocxFormatSpan>[];

    final paraRe = RegExp(r'<w:p(?:\s[^>]*)?>.*?</w:p>', dotAll: true);
    final pStyleRe = RegExp(r'<w:pStyle\s+w:val="([^"]*)"');
    // Exclude self-closing runs.
    final runRe = RegExp(r'<w:r(?:\s[^>]*)?>(?<!\/>).*?</w:r>', dotAll: true);
    final wtRe = RegExp(r'<w:t(?:[^>]*)>(.*?)</w:t>', dotAll: true);
    final rPrRe = RegExp(r'<w:rPr>(.*?)</w:rPr>', dotAll: true);
    final boldOffRe = RegExp(r'<w:b(?:\s[^>]*)?w:val="(?:false|0)"');
    final italicOffRe = RegExp(r'<w:i(?:\s[^>]*)?w:val="(?:false|0)"');

    for (final paraMatch in paraRe.allMatches(documentXml)) {
      final paraContent = paraMatch.group(0)!;

      // Heading/title paragraph styles → font size + weight override.
      TextStyle? paraStyle;
      final pStyleMatch = pStyleRe.firstMatch(paraContent);
      if (pStyleMatch != null) {
        paraStyle = _headingTextStyle(pStyleMatch.group(1)!);
      }

      for (final runMatch in runRe.allMatches(paraContent)) {
        final runContent = runMatch.group(0)!;
        final absRunStart = paraMatch.start + runMatch.start;

        final text =
            wtRe.allMatches(runContent).map((m) => _unesc(m.group(1)!)).join('');
        if (text.isEmpty) continue;

        final firstWt = wtRe.firstMatch(runContent);
        if (firstWt == null) continue;
        final wtContentStart = absRunStart +
            firstWt.start +
            firstWt.group(0)!.indexOf('>') +
            1;

        // Binary search for the plain-text index of this run's first character.
        int lo = 0, hi = xmlOffsets.length - 1, plainStart = -1;
        while (lo <= hi) {
          final mid = (lo + hi) ~/ 2;
          if (xmlOffsets[mid] >= wtContentStart) {
            plainStart = mid;
            hi = mid - 1;
          } else {
            lo = mid + 1;
          }
        }
        if (plainStart < 0) continue;
        final plainEnd = (plainStart + text.length).clamp(0, plain.length);
        if (plainStart >= plainEnd) continue;

        // Run-level formatting — strip rPrChange to read current state only.
        bool bold = false;
        bool italic = false;
        final rPrMatch = rPrRe.firstMatch(runContent);
        if (rPrMatch != null) {
          var rPr = rPrMatch.group(1)!;
          final rPrChangeIdx = rPr.indexOf('<w:rPrChange');
          if (rPrChangeIdx >= 0) rPr = rPr.substring(0, rPrChangeIdx);

          // <w:b/> or <w:b ...> but not <w:bCs> and not val="false"/"0".
          if (RegExp(r'<w:b(?=[\s/> ])').hasMatch(rPr) &&
              !boldOffRe.hasMatch(rPr)) {
            bold = true;
          }
          if (RegExp(r'<w:i(?=[\s/> ])').hasMatch(rPr) &&
              !italicOffRe.hasMatch(rPr)) {
            italic = true;
          }
        }

        // Merge paragraph heading style with run bold/italic.
        TextStyle? runStyle;
        if (bold || italic) {
          runStyle = TextStyle(
            fontWeight: bold ? FontWeight.bold : null,
            fontStyle: italic ? FontStyle.italic : null,
          );
        }

        TextStyle? finalStyle = paraStyle != null
            ? (runStyle != null ? paraStyle.merge(runStyle) : paraStyle)
            : runStyle;

        if (finalStyle != null) {
          spans.add((start: plainStart, end: plainEnd, style: finalStyle));
        }
      }
    }

    return spans;
  }

  static TextStyle? _headingTextStyle(String styleName) {
    switch (styleName.toLowerCase()) {
      case 'heading1':
        return const TextStyle(fontSize: 22, fontWeight: FontWeight.bold);
      case 'heading2':
        return const TextStyle(fontSize: 18, fontWeight: FontWeight.bold);
      case 'heading3':
        return const TextStyle(fontSize: 16, fontWeight: FontWeight.bold);
      case 'title':
        return const TextStyle(fontSize: 26, fontWeight: FontWeight.bold);
      case 'subtitle':
        return const TextStyle(fontSize: 18, fontStyle: FontStyle.italic);
      default:
        return null;
    }
  }

  // ---------------------------------------------------------------------------
  // Legacy comment parser (used only when leamh/annotations.json is absent)
  // ---------------------------------------------------------------------------

  static List<Annotation> _parseComments(String xml, String documentXml) {
    final results = <Annotation>[];
    final map = _buildPlainMap(documentXml);

    final commentRe =
        RegExp(r'<w:comment\s([^>]*)>(.*?)</w:comment>', dotAll: true);
    final idAttr = RegExp(r'w:id="([^"]*)"');
    final authorAttr = RegExp(r'w:author="([^"]*)"');
    final dateAttr = RegExp(r'w:date="([^"]*)"');
    final wtRe = RegExp(r'<w:t[^>]*>(.*?)</w:t>', dotAll: true);
    // Legacy: [tool:X] [tag:Y] N% — "text"
    final legacyRe = RegExp(
        r'\[tool:(\w+)\](?:\s\[tag:(\w+)\])?\s(\d+)%\s—\s"(.*)"',
        dotAll: true);

    for (final cm in commentRe.allMatches(xml)) {
      try {
        final attrs = cm.group(1)!;
        final body = cm.group(2)!;
        final commentId = idAttr.firstMatch(attrs)?.group(1) ?? '';
        final authorRaw =
            _unesc(authorAttr.firstMatch(attrs)?.group(1) ?? '');
        final dateStr = dateAttr.firstMatch(attrs)?.group(1);
        if (dateStr == null) continue;
        final timestamp = DateTime.parse(dateStr);
        final texts = wtRe
            .allMatches(body)
            .map((m) => _unesc(m.group(1)!))
            .toList();
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
          final note =
              noteTexts.isNotEmpty ? noteTexts.join('\n') : null;
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

        // Native Word comment
        if (commentId.isEmpty) continue;
        final extracted =
            _extractFromCommentRange(documentXml, commentId, map);
        if (extracted.text.isEmpty) continue;
        final note =
            texts.where((t) => t.trim().isNotEmpty).join(' ').trim();
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
      } catch (e) {
        debugPrint('DocxStore: skipping malformed comment: $e');
      }
    }

    results.sort((a, b) => a.position.compareTo(b.position));
    return results;
  }

  static ({String text, String prefix, String suffix, double position})
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
    final position = map.plain.isNotEmpty
        ? (plainIdx / map.plain.length).clamp(0.0, 1.0)
        : 0.0;
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

  // Persists [annotations] as the authoritative set in a SINGLE write. The
  // reader holds the in-memory annotation list as the source of truth during a
  // session, so its debounced/coalesced flush calls this once after a burst —
  // one DOCX encode instead of one per annotation (which is the per-save cost
  // that froze the UI in rapid locked-tool annotation).
  Future<void> saveAll(List<Annotation> annotations) =>
      _serialized(() => _writeAllAnnotations(List.of(annotations)));

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

  // Embeds a PNG for an ink annotation and sets hasInk = true.
  // Called by the platform ink canvas layer after the user finishes drawing.
  @override
  Future<void> saveInkPng(String annotationId, List<int> pngBytes) =>
      _serialized(() async {
        await _resolveAccess();
        // Pre-write the PNG so _writeAllAnnotations sees it in the archive.
        final bytes = await File(filePath).readAsBytes();
        var archive = _decodeZip(bytes);
        archive = _replaceOrAddEntry(
            archive, 'word/media/ink_$annotationId.png', pngBytes);
        await _writeArchive(archive);
        // Update hasInk flag and rebuild comments/document XML.
        final list = await loadAnnotations();
        final idx = list.indexWhere((a) => a.id == annotationId);
        if (idx >= 0) {
          list[idx] = list[idx].copyWith(hasInk: true);
          await _writeAllAnnotations(list);
        }
      });

  // ---------------------------------------------------------------------------
  // _writeAllAnnotations
  //
  // Layout:
  //   leamh/annotations.json  — authoritative store for all Léamh annotations
  //   leamh/document_clean.xml — original document.xml snapshot (restore-before-inject)
  //   word/comments.xml        — ONLY for annotations that have a note or tag
  //   word/document.xml        — restored from clean, then formatting injected
  // ---------------------------------------------------------------------------

  Future<void> _writeAllAnnotations(List<Annotation> annotations) async {
    await _resolveAccess();
    final bytes = await File(filePath).readAsBytes();
    final annotationsJson = annotations.map((a) => a.toJson()).toList();
    // The heavy decode → inject → encode is pure CPU and grows with document
    // size and annotation count (O(N×M) injection + full zip encode). Run it on
    // a one-shot background isolate so a save never freezes the UI isolate, no
    // matter how large or heavily annotated the document is.
    final outBytes = await Isolate.run(
      () => _buildAnnotatedDocxBytes(bytes, annotationsJson),
    );
    await safeWriteBytes(filePath, outBytes);
  }

  // Pure, isolate-safe transform: decode the DOCX, rebuild
  // leamh/annotations.json + word/comments.xml + word/document.xml native
  // formatting from [annotationsJson], and re-encode. Touches no file handle or
  // instance state, so it runs unchanged inside [Isolate.run]. Byte-identical to
  // the previous inline write path (golden-verified). Returns the archive bytes.
  static List<int> _buildAnnotatedDocxBytes(
    List<int> inputBytes,
    List<Map<String, dynamic>> annotationsJson,
  ) {
    final annotations =
        annotationsJson.map((j) => Annotation.fromJson(j)).toList();
    var archive = _decodeZip(inputBytes);

    // Save the original document.xml as a clean snapshot on first write.
    if (_entryString(archive, 'leamh/document_clean.xml') == null) {
      final docXml = _entryString(archive, 'word/document.xml');
      if (docXml != null) {
        archive = _replaceOrAddEntry(
            archive, 'leamh/document_clean.xml', utf8.encode(docXml));
      }
    }

    // Always restore document.xml from the clean snapshot before injecting,
    // so every write starts from the original state with no leftover markup.
    final cleanDoc = _entryString(archive, 'leamh/document_clean.xml');
    if (cleanDoc != null) {
      archive = _replaceOrAddEntry(
          archive, 'word/document.xml', utf8.encode(cleanDoc));
    }

    // Write leamh/annotations.json — primary recovery store.
    archive = _replaceOrAddEntry(
      archive,
      'leamh/annotations.json',
      utf8.encode(jsonEncode(annotationsJson)),
    );

    // Write word/comments.xml — for annotations with a note, tag, or ink.
    // These appear as comment bubbles in Word/Pages/Docs; ink is embedded
    // as a <w:drawing><wp:inline> image referencing word/media/ink_[id].png.
    final commentAnnotations = annotations
        .where((a) => a.note != null || a.tag != null || a.hasInk)
        .toList();

    if (commentAnnotations.isNotEmpty) {
      final commentBlocks = commentAnnotations
          .asMap()
          .entries
          .map((e) => _buildNoteComment(
                xmlId: e.key,
                a: e.value,
                inkRelId: e.value.hasInk ? _inkRelId(e.value.id) : null,
              ))
          .join('\n');
      final commentsXml =
          '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
          '<w:comments'
          ' xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
          ' xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
          ' xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"'
          ' xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"'
          ' xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"'
          ' xmlns:w14="http://schemas.microsoft.com/office/word/2010/wordml"'
          '>\n'
          '$commentBlocks\n'
          '</w:comments>';
      archive = _replaceOrAddEntry(
          archive, 'word/comments.xml', utf8.encode(commentsXml));
      archive = _ensureRelsEntry(archive);
      final inkAnnotations =
          commentAnnotations.where((a) => a.hasInk).toList();
      if (inkAnnotations.isNotEmpty) {
        archive = _ensureCommentsRels(archive, inkAnnotations);
      }
    } else {
      // No comment annotations — write empty comments.xml if one already existed.
      final existing = _entryString(archive, 'word/comments.xml');
      if (existing != null) {
        const empty =
            '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
            '<w:comments'
            ' xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"/>';
        archive = _replaceOrAddEntry(
            archive, 'word/comments.xml', utf8.encode(empty));
      }
    }

    archive = _ensureContentType(archive);
    archive = _injectNativeFormatting(archive, annotations, commentAnnotations);
    return ZipEncoder().encode(archive)!;
  }

  // Builds a <w:comment> for an annotation that has a note, tag, or ink.
  // Author = annotation UUID. Body = note text, tag name, and/or ink drawing.
  // No [tool:X] metadata — tools are in leamh/annotations.json.
  static String _buildNoteComment({
    required int xmlId,
    required Annotation a,
    String? inkRelId,
  }) {
    final noteXml = (a.note != null && a.note!.isNotEmpty)
        ? '<w:p><w:r><w:t xml:space="preserve">${_esc(a.note!)}</w:t></w:r></w:p>'
        : '';
    final tagXml = a.tag != null
        ? '<w:p><w:r><w:t xml:space="preserve">[${a.tag!.name}]</w:t></w:r></w:p>'
        : '';
    final drawingXml = inkRelId != null ? _buildInkDrawing(inkRelId) : '';
    final bodyParts = [noteXml, tagXml, drawingXml].where((s) => s.isNotEmpty);
    final bodyXml = bodyParts.isEmpty ? '<w:p/>' : bodyParts.join('');

    return '<w:comment w:id="$xmlId" w:author="${_esc(a.id)}"'
        ' w:date="${a.timestamp.toUtc().toIso8601String()}">\n'
        '  <w:p>\n'
        '    <w:pPr><w:pStyle w:val="CommentText"/></w:pPr>\n'
        '    <w:r><w:rPr><w:rStyle w:val="CommentReference"/></w:rPr>'
        '<w:annotationRef/></w:r>\n'
        '  </w:p>\n'
        '  $bodyXml</w:comment>';
  }

  // 4 inches × 2 inches in EMU (914400 EMU per inch).
  static const _inkCx = 3657600;
  static const _inkCy = 1828800;

  static String _buildInkDrawing(String relId) =>
      '<w:p><w:r><w:drawing>'
      '<wp:inline distT="0" distB="0" distL="0" distR="0">'
      '<wp:extent cx="$_inkCx" cy="$_inkCy"/>'
      '<wp:docPr id="1" name="Ink"/>'
      '<wp:cNvGraphicFramePr>'
      '<a:graphicFrameLocks noChangeAspect="1"/>'
      '</wp:cNvGraphicFramePr>'
      '<a:graphic>'
      '<a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">'
      '<pic:pic>'
      '<pic:nvPicPr>'
      '<pic:cNvPr id="1" name="ink.png"/>'
      '<pic:cNvPicPr/>'
      '</pic:nvPicPr>'
      '<pic:blipFill>'
      '<a:blip r:embed="$relId"/>'
      '<a:stretch><a:fillRect/></a:stretch>'
      '</pic:blipFill>'
      '<pic:spPr>'
      '<a:xfrm><a:off x="0" y="0"/><a:ext cx="$_inkCx" cy="$_inkCy"/></a:xfrm>'
      '<a:prstGeom prst="rect"><a:avLst/></a:prstGeom>'
      '</pic:spPr>'
      '</pic:pic>'
      '</a:graphicData>'
      '</a:graphic>'
      '</wp:inline>'
      '</w:drawing></w:r></w:p>';

  static String _inkRelId(String annotationId) {
    final safe = annotationId.replaceAll(RegExp(r'[^a-zA-Z0-9_]'), '_');
    return 'rId_ink_$safe';
  }

  // ---------------------------------------------------------------------------
  // _injectNativeFormatting
  //
  // Injects run properties and (for note annotations) comment anchors into
  // document.xml. document.xml was just restored from the clean snapshot.
  //
  // Processes annotations one at a time, rebuilding the plain map and run
  // lists after each because run-splitting (see below) modifies the XML.
  //
  // Run splitting: <w:rPr> applies to an entire <w:r>, not a substring. If a
  // selection starts or ends mid-run, we split the run at that boundary so
  // that rPr is injected only into the exact covered portion.
  // ---------------------------------------------------------------------------

  static Archive _injectNativeFormatting(
    Archive archive,
    List<Annotation> annotations,
    List<Annotation> noteAnnotations,
  ) {
    if (annotations.isEmpty) return archive;
    final xmlRaw = _entryString(archive, 'word/document.xml');
    if (xmlRaw == null) return archive;
    var xml = xmlRaw;

    // Strip all comment range markers before injecting — the clean snapshot
    // may already contain Word's native comment anchors, and we always
    // re-inject them from the annotation list, so starting fresh prevents
    // duplicates.
    xml = xml.replaceAll(RegExp(r'<w:commentRangeStart\b[^>]*/>'), '');
    xml = xml.replaceAll(RegExp(r'<w:commentRangeEnd\b[^>]*/>'), '');
    xml = xml.replaceAll(
        RegExp(r'<w:r><w:rPr><w:rStyle w:val="CommentReference"/>'
            r'</w:rPr><w:commentReference\b[^>]*/></w:r>'),
        '');

    final noteCommentId = <String, int>{};
    for (int i = 0; i < noteAnnotations.length; i++) {
      noteCommentId[noteAnnotations[i].id] = i;
    }

    // Anchor/bookmark insertions are collected and applied at the very end
    // because they don't change run structure.
    // Identity fields are stored (not positions) so they can be resolved
    // against the final xml after all rPr insertions are complete.
    final anchorInsertions = <({
      String annotationId,
      int commentId,
      String selectedText,
      String prefix,
      String suffix,
      double position,
    })>[];
    final bookmarkInsertions = <({
      String annotationId,
      String selectedText,
      String prefix,
      String suffix,
      double position,
    })>[];

    // The plain text is INVARIANT across run-splits and rPr injection (markup
    // changes, visible text does not), so locate against it once. Only the
    // char→byte mapping shifts as xml grows; that is rebuilt per iteration as a
    // lightweight segment index (O(runs), not O(characters)).
    final plain = _buildPlainMap(xml).plain;
    for (final a in annotations) {
      if (plain.isEmpty) break;

      final loc = _locateInPlain(
          plain, a.selectedText, a.prefix, a.suffix,
          positionHint: a.position);
      if (loc == null) {
        debugPrint('DocxStore: could not locate '
            '"${a.selectedText.substring(0, a.selectedText.length.clamp(0, 40))}"');
        continue;
      }

      // Rebuild run lists + offset segments every iteration — xml grew from splits.
      var runOpens = RegExp(r'<w:r(?:\s[^>]*)?>(?<!\/>)').allMatches(xml).toList();
      var runCloses = RegExp(r'</w:r>').allMatches(xml).toList();
      final segs = _xmlOffsetSegments(xml);

      final startXmlPos = _byteForChar(segs, loc.start);
      final endXmlPos = _byteForChar(segs, loc.end - 1);
      if (startXmlPos < 0 || endXmlPos < 0) continue;

      var sIdx = _findRunIdxBS(runOpens, startXmlPos);
      var eIdx = _findRunIdxBS(runOpens, endXmlPos);
      if (sIdx < 0 || eIdx < 0) continue;

      final startRC = _findRunClose(runCloses, runOpens[sIdx]);
      final endRC = _findRunClose(runCloses, runOpens[eIdx]);
      if (startRC == null || endRC == null) continue;

      // Bookmark: use anchor-insertion approach, no rPr or splitting.
      if (a.tool == AnnotationTool.bookmark) {
        bookmarkInsertions.add((
          annotationId: a.id,
          selectedText: a.selectedText,
          prefix: a.prefix,
          suffix: a.suffix,
          position: a.position,
        ));
        continue;
      }

      final rPrContent = _rPrForTool(a.tool);
      if (rPrContent.isEmpty) continue;

      // ---- Run splitting ----
      // Determine how far into each boundary run the selection starts/ends.
      final startOffset =
          _approxCharOffsetInRun(xml, runOpens[sIdx], startRC, startXmlPos);
      final endOffset =
          _approxCharOffsetInRun(xml, runOpens[eIdx], endRC, endXmlPos);
      final startRunLen =
          _getRunPlainText(xml, runOpens[sIdx], startRC).length;
      final endRunLen =
          _getRunPlainText(xml, runOpens[eIdx], endRC).length;

      final needStartSplit = startOffset > 0 && startRunLen > 1;
      final needEndSplit =
          (endOffset + 1) < endRunLen && endRunLen > 1;

      if (sIdx == eIdx) {
        // Same run — split at end first (higher position), then at start.
        if (needEndSplit) {
          final newXml = _splitRunAt(
              xml, runOpens[sIdx], startRC, endOffset + 1);
          if (newXml != xml) {
            xml = newXml;
            runOpens = RegExp(r'<w:r(?:\s[^>]*)?>(?<!\/>)').allMatches(xml).toList();
            runCloses = RegExp(r'</w:r>').allMatches(xml).toList();
            // sIdx still points to the first part [0, endOffset+1)
          }
        }
        if (needStartSplit) {
          final ro = runOpens[sIdx];
          final rc = _findRunClose(runCloses, ro)!;
          final newXml = _splitRunAt(xml, ro, rc, startOffset);
          if (newXml != xml) {
            xml = newXml;
            runOpens = RegExp(r'<w:r(?:\s[^>]*)?>(?<!\/>)').allMatches(xml).toList();
            runCloses = RegExp(r'</w:r>').allMatches(xml).toList();
            sIdx += 1; // selected part is now the second fragment
          }
        }
        eIdx = sIdx; // same run case: start == end after splitting
      } else {
        // Different runs — split end first (higher XML position, no effect on sIdx).
        if (needEndSplit) {
          final newXml = _splitRunAt(xml, runOpens[eIdx], endRC, endOffset + 1);
          if (newXml != xml) {
            xml = newXml;
            runOpens = RegExp(r'<w:r(?:\s[^>]*)?>(?<!\/>)').allMatches(xml).toList();
            runCloses = RegExp(r'</w:r>').allMatches(xml).toList();
            // eIdx still points to the selected end fragment
          }
        }
        if (needStartSplit) {
          final ro = runOpens[sIdx];
          final rc = _findRunClose(runCloses, ro)!;
          final newXml = _splitRunAt(xml, ro, rc, startOffset);
          if (newXml != xml) {
            xml = newXml;
            runOpens = RegExp(r'<w:r(?:\s[^>]*)?>(?<!\/>)').allMatches(xml).toList();
            runCloses = RegExp(r'</w:r>').allMatches(xml).toList();
            sIdx += 1; // selected start is now the second fragment
            eIdx += 1; // all indices after sIdx shifted
          }
        }
      }

      // ---- rPr injection into fully-covered runs ----
      final rPrInsertions = <({int pos, String tag})>[];
      for (int idx = sIdx; idx <= eIdx; idx++) {
        final rO = runOpens[idx];
        final rC = _findRunClose(runCloses, rO);
        if (rC == null) continue;
        final runContent = xml.substring(rO.end, rC.start);
        final rPrEndIdx = runContent.indexOf('</w:rPr>');
        final hasRPr = rPrEndIdx >= 0 &&
            runContent.indexOf('<w:rPr') <
                (runContent.contains('<w:t')
                    ? runContent.indexOf('<w:t')
                    : runContent.length);
        if (hasRPr) {
          // Only inject if not already present — avoids doubling properties
          // that already exist in the clean snapshot (e.g. native Word
          // highlights/underlines that Léamh imported as annotations).
          final existingRPr = runContent.substring(0, rPrEndIdx);
          if (!existingRPr.contains(rPrContent)) {
            rPrInsertions.add((pos: rO.end + rPrEndIdx, tag: rPrContent));
          }
        } else {
          rPrInsertions
              .add((pos: rO.end, tag: '<w:rPr>$rPrContent</w:rPr>'));
        }
      }
      rPrInsertions.sort((a, b) => b.pos.compareTo(a.pos));
      for (final ins in rPrInsertions) {
        xml = xml.substring(0, ins.pos) + ins.tag + xml.substring(ins.pos);
      }

      // ---- Comment anchors (collected, applied at end) ----
      final commentId = noteCommentId[a.id];
      if (commentId != null) {
        anchorInsertions.add((
          annotationId: a.id,
          commentId: commentId,
          selectedText: a.selectedText,
          prefix: a.prefix,
          suffix: a.suffix,
          position: a.position,
        ));
      }
    }

    // Resolve anchor/bookmark positions from the final xml (after all rPr
    // insertions) so stored positions are never stale.
    final finalInsertions = <({int pos, String tag})>[];
    if (anchorInsertions.isNotEmpty || bookmarkInsertions.isNotEmpty) {
      final finalMap = _buildPlainMap(xml);
      final finalOpens =
          RegExp(r'<w:r(?:\s[^>]*)?>(?<!\/>)').allMatches(xml).toList();
      final finalCloses = RegExp(r'</w:r>').allMatches(xml).toList();

      for (final bk in bookmarkInsertions) {
        final loc = _locateInPlain(
            finalMap.plain, bk.selectedText, bk.prefix, bk.suffix,
            positionHint: bk.position);
        if (loc == null) continue;
        final startXmlPos = finalMap.xmlOffsets[loc.start];
        final endXmlPos = finalMap.xmlOffsets[loc.end - 1];
        final sIdx = _findRunIdxBS(finalOpens, startXmlPos);
        final eIdx = _findRunIdxBS(finalOpens, endXmlPos);
        if (sIdx < 0 || eIdx < 0) continue;
        final endRC = _findRunClose(finalCloses, finalOpens[eIdx]);
        if (endRC == null) continue;
        final safeId = bk.annotationId.replaceAll(RegExp(r'[^a-zA-Z0-9_]'), '_');
        final bkId = 10000 + sIdx;
        finalInsertions
          ..add((
            pos: finalOpens[sIdx].start,
            tag: '<w:bookmarkStart w:id="$bkId" w:name="leamh_$safeId"/>',
          ))
          ..add((pos: endRC.end, tag: '<w:bookmarkEnd w:id="$bkId"/>'));
      }

      for (final anc in anchorInsertions) {
        final loc = _locateInPlain(
            finalMap.plain, anc.selectedText, anc.prefix, anc.suffix,
            positionHint: anc.position);
        if (loc == null) continue;
        final startXmlPos = finalMap.xmlOffsets[loc.start];
        final endXmlPos = finalMap.xmlOffsets[loc.end - 1];
        final sIdx = _findRunIdxBS(finalOpens, startXmlPos);
        final eIdx = _findRunIdxBS(finalOpens, endXmlPos);
        if (sIdx < 0 || eIdx < 0) continue;
        final eClose = _findRunClose(finalCloses, finalOpens[eIdx]);
        if (eClose == null) continue;
        finalInsertions
          ..add((
            pos: finalOpens[sIdx].start,
            tag: '<w:commentRangeStart w:id="${anc.commentId}"/>',
          ))
          ..add((
            pos: eClose.end,
            tag: '<w:commentRangeEnd w:id="${anc.commentId}"/>'
                '<w:r><w:rPr><w:rStyle w:val="CommentReference"/></w:rPr>'
                '<w:commentReference w:id="${anc.commentId}"/></w:r>',
          ));
      }
    }

    // Apply bookmark/comment anchors in reverse order.
    finalInsertions.sort((a, b) => b.pos.compareTo(a.pos));
    for (final ins in finalInsertions) {
      xml = xml.substring(0, ins.pos) + ins.tag + xml.substring(ins.pos);
    }

    return _replaceOrAddEntry(archive, 'word/document.xml', utf8.encode(xml));
  }

  // ---------------------------------------------------------------------------
  // Run helpers used by _injectNativeFormatting
  // ---------------------------------------------------------------------------

  static int _findRunIdxBS(List<RegExpMatch> runOpens, int xmlPos) {
    int lo = 0, hi = runOpens.length - 1, found = -1;
    while (lo <= hi) {
      final mid = (lo + hi) ~/ 2;
      if (runOpens[mid].start <= xmlPos) {
        found = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    return found;
  }

  static RegExpMatch? _findRunClose(
      List<RegExpMatch> runCloses, RegExpMatch runOpen) {
    for (final m in runCloses) {
      if (m.start > runOpen.start) return m;
    }
    return null;
  }

  static String _getRunPlainText(
      String xml, RegExpMatch runOpen, RegExpMatch runClose) {
    final runContent = xml.substring(runOpen.end, runClose.start);
    final wtRe = RegExp(r'<w:t(?:[^>]*)>(.*?)</w:t>', dotAll: true);
    return wtRe.allMatches(runContent).map((m) => _unesc(m.group(1)!)).join('');
  }

  // Returns the approximate char offset of xmlCharPos within the run's plain
  // text. Exact for ASCII/entity-free text; approximate when XML entities
  // (e.g. &amp;) appear earlier in the same <w:t> block.
  static int _approxCharOffsetInRun(
      String xml, RegExpMatch runOpen, RegExpMatch runClose, int xmlCharPos) {
    final runContent = xml.substring(runOpen.end, runClose.start);
    final wtRe = RegExp(r'<w:t(?:[^>]*)>(.*?)</w:t>', dotAll: true);
    int charsBefore = 0;
    for (final wt in wtRe.allMatches(runContent)) {
      final wtContentStart =
          runOpen.end + wt.start + wt.group(0)!.indexOf('>') + 1;
      final wtRawLen = wt.group(1)!.length;
      if (xmlCharPos >= wtContentStart &&
          xmlCharPos < wtContentStart + wtRawLen) {
        return charsBefore + (xmlCharPos - wtContentStart);
      }
      charsBefore += _unesc(wt.group(1)!).length;
    }
    return charsBefore;
  }

  // Splits a run at charPos (0-indexed in the run's plain text).
  // Only handles single-<w:t> runs; returns xml unchanged for complex runs.
  static String _splitRunAt(
      String xml, RegExpMatch runOpen, RegExpMatch runClose, int charPos) {
    final runContent = xml.substring(runOpen.end, runClose.start);
    final wtRe = RegExp(r'<w:t(?:[^>]*)>(.*?)</w:t>', dotAll: true);
    final wtList = wtRe.allMatches(runContent).toList();
    if (wtList.length != 1) return xml;

    final fullText = _unesc(wtList.first.group(1)!);
    if (charPos <= 0 || charPos >= fullText.length) return xml;

    // Strip <w:rPrChange> before matching so the non-greedy regex doesn't
    // stop at the inner </w:rPr> nested inside a tracked-change block.
    final rPrChangeStripped = runContent.replaceAll(
        RegExp(r'<w:rPrChange\b[^>]*>.*?</w:rPrChange>', dotAll: true), '');
    final rPrMatch =
        RegExp(r'<w:rPr>.*?</w:rPr>', dotAll: true).firstMatch(rPrChangeStripped);
    final rPrXml = rPrMatch?.group(0) ?? '';

    final openTag = xml.substring(runOpen.start, runOpen.end);
    final t1 = _esc(fullText.substring(0, charPos));
    final t2 = _esc(fullText.substring(charPos));

    final run1 =
        '$openTag$rPrXml<w:t xml:space="preserve">$t1</w:t></w:r>';
    final run2 =
        '$openTag$rPrXml<w:t xml:space="preserve">$t2</w:t></w:r>';

    return xml.substring(0, runOpen.start) +
        run1 +
        run2 +
        xml.substring(runClose.end);
  }

  // ---------------------------------------------------------------------------
  // Plain-text map (XML offset ↔ plain-text index)
  // ---------------------------------------------------------------------------

  static ({String plain, List<int> xmlOffsets}) _buildPlainMap(String xml) {
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

  // Lightweight char→byte index for the injection hot loop: one segment per
  // <w:t> run (and per </w:p> newline) instead of one entry per character.
  // [_byteForChar] over this yields EXACTLY _buildPlainMap(xml).xmlOffsets[k]
  // (same base + per-char arithmetic, same escaped-text approximation), at
  // O(runs) build cost instead of O(characters) — the per-character offset list
  // was 64% of injection time, rebuilt once per annotation.
  static List<({int charStart, int byteBase, int len})> _xmlOffsetSegments(
      String xml) {
    final segs = <({int charStart, int byteBase, int len})>[];
    final re = RegExp(r'<w:t(?:[^>]*)>(.*?)</w:t>|</w:p>', dotAll: true);
    int chars = 0;
    for (final m in re.allMatches(xml)) {
      if (m.group(0) == '</w:p>') {
        segs.add((charStart: chars, byteBase: m.start, len: 1));
        chars += 1;
      } else {
        final text = _unesc(m.group(1)!);
        final base = m.start + m.group(0)!.indexOf('>') + 1;
        segs.add((charStart: chars, byteBase: base, len: text.length));
        chars += text.length;
      }
    }
    return segs;
  }

  // Byte offset of plain-char [charIndex]; identical to
  // _buildPlainMap(xml).xmlOffsets[charIndex]. -1 if out of range.
  static int _byteForChar(
      List<({int charStart, int byteBase, int len})> segs, int charIndex) {
    if (charIndex < 0) return -1;
    int lo = 0, hi = segs.length - 1, found = -1;
    while (lo <= hi) {
      final mid = (lo + hi) >> 1;
      if (segs[mid].charStart <= charIndex) {
        found = mid;
        lo = mid + 1;
      } else {
        hi = mid - 1;
      }
    }
    if (found < 0) return -1;
    final s = segs[found];
    if (charIndex >= s.charStart + s.len) return -1;
    return s.byteBase + (charIndex - s.charStart);
  }

  static String _normaliseQuotes(String s) => s
      .replaceAll('“', '"')
      .replaceAll('”', '"')
      .replaceAll('‘', "'")
      .replaceAll('’', "'")
      .replaceAll('–', '-')
      .replaceAll('—', '-');

  // Locates selectedText within plain using prefix/suffix context, with a
  // position-fraction hint to disambiguate when the text appears more than once.
  static ({int start, int end})? _locateInPlain(
    String plain,
    String selectedText,
    String prefix,
    String suffix, {
    double positionHint = 0.0,
  }) {
    if (selectedText.isEmpty) return null;

    // Context match (most reliable — won't have false positives).
    if (prefix.isNotEmpty || suffix.isNotEmpty) {
      final needle = prefix + selectedText + suffix;
      final idx = plain.indexOf(needle);
      if (idx >= 0) {
        final start = idx + prefix.length;
        return (start: start, end: start + selectedText.length);
      }
    }

    // Find all occurrences and return the one closest to positionHint.
    final hintPos = (positionHint * plain.length).round().clamp(0, plain.length);
    final best = _findClosest(plain, selectedText, hintPos);
    if (best >= 0) return (start: best, end: best + selectedText.length);

    // Quote-normalised fallback.
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

  // Returns the index of the occurrence of [needle] in [hay] that is closest
  // to [hintPos]. Returns -1 if [needle] is not found.
  static int _findClosest(String hay, String needle, int hintPos) {
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

  // ---------------------------------------------------------------------------
  // Archive helpers
  // ---------------------------------------------------------------------------

  static Archive _replaceOrAddEntry(Archive archive, String name, List<int> data) {
    final newArchive = Archive();
    for (final file in archive.files) {
      if (file.name != name) newArchive.addFile(file);
    }
    newArchive.addFile(ArchiveFile(name, data.length, data));
    return newArchive;
  }

  static Archive _ensureContentType(Archive archive) {
    const entryName = '[Content_Types].xml';
    var raw = _entryString(archive, entryName);
    if (raw == null) return archive;

    // Ensure all custom part extensions have a content type so Word doesn't
    // reject the file as invalid.
    bool changed = false;

    const commentsOverride = 'PartName="/word/comments.xml"';
    if (!raw.contains(commentsOverride)) {
      raw = raw.replaceFirst('</Types>',
          '<Override PartName="/word/comments.xml"'
          ' ContentType="application/vnd.openxmlformats-officedocument'
          '.wordprocessingml.comments+xml"/>\n</Types>');
      changed = true;
    }

    const pngDefault = 'Extension="png"';
    if (!raw.contains(pngDefault)) {
      raw = raw.replaceFirst('</Types>',
          '<Default Extension="png" ContentType="image/png"/>\n</Types>');
      changed = true;
    }

    const jsonDefault = 'Extension="json"';
    if (!raw.contains(jsonDefault)) {
      raw = raw.replaceFirst('</Types>',
          '<Default Extension="json" ContentType="application/json"/>\n</Types>');
      changed = true;
    }

    // Ensures leamh/document_clean.xml gets the generic XML content type
    // so Word doesn't flag it as an unrecognised part.
    const xmlDefault = 'Extension="xml"';
    if (!raw.contains(xmlDefault)) {
      raw = raw.replaceFirst('</Types>',
          '<Default Extension="xml" ContentType="application/xml"/>\n</Types>');
      changed = true;
    }

    if (!changed) return archive;
    return _replaceOrAddEntry(archive, entryName, utf8.encode(raw));
  }

  static Archive _ensureCommentsRels(Archive archive, List<Annotation> inkAnnotations) {
    const relsPath = 'word/_rels/comments.xml.rels';
    final relEntries = inkAnnotations.map((a) {
      final relId = _inkRelId(a.id);
      return '<Relationship Id="$relId"'
          ' Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"'
          ' Target="media/ink_${a.id}.png"/>';
    }).join('\n');
    final xml = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<Relationships'
        ' xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\n'
        '$relEntries\n'
        '</Relationships>';
    return _replaceOrAddEntry(archive, relsPath, utf8.encode(xml));
  }

  static Archive _ensureRelsEntry(Archive archive) {
    const entryName = 'word/_rels/document.xml.rels';
    final raw = _entryString(archive, entryName);
    if (raw == null) return archive;
    if (raw.contains('comments.xml')) return archive;

    const rel = '<Relationship Id="rId_leamh_comments"'
        ' Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments"'
        ' Target="comments.xml"/>';
    return _replaceOrAddEntry(archive, entryName,
        utf8.encode(raw.replaceFirst('</Relationships>', '$rel\n</Relationships>')));
  }

  Future<void> _writeArchive(Archive archive) async {
    final outBytes = ZipEncoder().encode(archive)!;
    await safeWriteBytes(filePath, outBytes);
  }

}
