import 'dart:convert';
import 'dart:io';
import 'package:archive/archive_io.dart';
import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import 'package:path/path.dart' as p;
import 'package:shared_preferences/shared_preferences.dart';
import 'reader_screen.dart';
import 'services/bookmark_service.dart';

void main() {
  runApp(const LeamhApp());
}

class LeamhApp extends StatelessWidget {
  const LeamhApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Léamh',
      debugShowCheckedModeBanner: false,
      home: const HomeScreen(),
    );
  }
}

sealed class _ConvertResult {}

class _ConvertSuccess extends _ConvertResult {
  final String path;
  _ConvertSuccess(this.path);
}

class _ConvertFailed extends _ConvertResult {}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final _bookmarks = BookmarkService();

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _tryReopenLast());
  }

  Future<void> _tryReopenLast() async {
    String? lastPath;
    try {
      final prefs = await SharedPreferences.getInstance();
      lastPath = prefs.getString('bookmark_last_path');
      final path = await _bookmarks.resolveLastFile();
      if (path == null || !mounted) return;
      if (!File(path).existsSync()) {
        if (lastPath != null) await _bookmarks.clearBookmark(lastPath);
        return;
      }
      Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => ReaderScreen(filePath: path)),
      );
    } catch (e) {
      debugPrint('_tryReopenLast: bookmark stale or unresolvable, clearing ($e)');
      if (lastPath != null) await _bookmarks.clearBookmark(lastPath);
    }
  }

  Future<_ConvertResult> _convertToDocx(
    String sourcePath,
    String destPath,
  ) async {
    try {
      final sourceFile = File(sourcePath);
      final text = await sourceFile.readAsString();

      String esc(String s) => s
          .replaceAll('&', '&amp;')
          .replaceAll('<', '&lt;')
          .replaceAll('>', '&gt;');

      final paragraphs = text.split('\n').map((line) {
        final escaped = esc(line);
        if (escaped.isEmpty) {
          return '<w:p/>';
        }
        return '<w:p><w:r><w:t xml:space="preserve">$escaped</w:t></w:r></w:p>';
      }).join('\n');

      final documentXml = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
$paragraphs
    <w:sectPr/>
  </w:body>
</w:document>''';

      const contentTypes = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels"
    ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml"
    ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>''';

      const relsMain = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1"
    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
    Target="word/document.xml"/>
</Relationships>''';

      const relsDocument = '''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
</Relationships>''';

      final archive = Archive();
      void add(String name, String content) {
        final bytes = utf8.encode(content);
        archive.addFile(ArchiveFile(name, bytes.length, bytes));
      }

      add('[Content_Types].xml', contentTypes);
      add('_rels/.rels', relsMain);
      add('word/document.xml', documentXml);
      add('word/_rels/document.xml.rels', relsDocument);

      final zipBytes = ZipEncoder().encode(archive)!;
      await File(destPath).writeAsBytes(zipBytes, flush: true);
      return _ConvertSuccess(destPath);
    } catch (e) {
      debugPrint('_convertToDocx error: $e');
      return _ConvertFailed();
    }
  }

  Future<void> _pickFile() async {
    try {
      final result = await FilePicker.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['txt', 'md', 'rtf', 'docx'],
      );
      if (result == null || result.files.isEmpty) return;
      final picked = result.files.single.path;
      if (picked == null) return;

      String filePath = picked;
      final ext = p.extension(picked).toLowerCase();

      if (ext != '.docx') {
        if (!mounted) return;
        await showDialog<void>(
          context: context,
          barrierDismissible: false,
          builder: (ctx) => AlertDialog(
            backgroundColor: const Color(0xFFF5F0E8),
            title: const Text(
              'Léamh works in DOCX format',
              style: TextStyle(fontFamily: 'Literata', fontSize: 16),
            ),
            content: const Text(
              'Your file will be converted to a DOCX copy for '
              'annotation. The original will not be changed.',
              style: TextStyle(
                fontFamily: 'Literata',
                fontSize: 14,
                color: Colors.black54,
              ),
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(ctx).pop(),
                child: const Text(
                  'Continue',
                  style: TextStyle(
                    fontFamily: 'Literata',
                    color: Colors.black87,
                  ),
                ),
              ),
            ],
          ),
        );

        if (!mounted) return;

        final suggestedName =
            '${p.basenameWithoutExtension(picked)}.docx';
        final savePath = await FilePicker.saveFile(
          dialogTitle: 'Save annotated DOCX copy',
          fileName: suggestedName,
          type: FileType.custom,
          allowedExtensions: ['docx'],
        );

        if (savePath == null) return;

        if (!mounted) return;
        final convertResult = await _convertToDocx(picked, savePath);

        switch (convertResult) {
          case _ConvertSuccess(:final path):
            filePath = path;
          case _ConvertFailed():
            if (!mounted) return;
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(
                content: Text(
                  'Could not convert file. Please try a different file.',
                  style: TextStyle(fontFamily: 'Literata'),
                ),
              ),
            );
            return;
        }
      }

      await _bookmarks.saveBookmark(filePath);

      if (!mounted) return;
      Navigator.of(context).push(
        MaterialPageRoute(
          builder: (context) => ReaderScreen(filePath: filePath),
        ),
      );
    } catch (e) {
      debugPrint('_pickFile error: $e');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF5F0E8),
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 32.0),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                Text(
                  'Léamh',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    fontFamily: 'Literata',
                    fontSize: 64,
                    fontWeight: FontWeight.w300,
                    color: Colors.black87,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  '(LAY-uv)',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    fontFamily: 'Literata',
                    fontSize: 14,
                    color: Color(0xFF8C8070),
                  ),
                ),
                const SizedBox(height: 16),
                Text(
                  'Open something worth noting.',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    fontFamily: 'Literata',
                    fontSize: 18,
                    color: Colors.black87,
                  ),
                ),
                const SizedBox(height: 32),
                OutlinedButton(
                  onPressed: _pickFile,
                  style: OutlinedButton.styleFrom(
                    foregroundColor: Colors.black87,
                    side: const BorderSide(color: Colors.black54),
                    padding: const EdgeInsets.symmetric(
                      horizontal: 28,
                      vertical: 16,
                    ),
                  ),
                  child: Text(
                    'Open file',
                    style: const TextStyle(fontFamily: 'Literata'),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
