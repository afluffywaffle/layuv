import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:docx_to_text/docx_to_text.dart';
import 'dart:io';

class ReaderScreen extends StatefulWidget {
  final String filePath;

  const ReaderScreen({
    super.key,
    required this.filePath,
  });

  @override
  State<ReaderScreen> createState() => _ReaderScreenState();
}

class _ReaderScreenState extends State<ReaderScreen> {
  late Future<String> _fileContentFuture;

  @override
  void initState() {
    super.initState();
    _fileContentFuture = _readFile();
  }

  Future<String> _readFile() async {
    try {
      final file = File(widget.filePath);
      final fileExtension = widget.filePath.toLowerCase().split('.').last;

      if (fileExtension == 'docx') {
        final bytes = await file.readAsBytes();
        final text = docxToText(bytes);
        return text.isNotEmpty ? text : 'No text found in DOCX file.';
      } else {
        return await file.readAsString();
      }
    } catch (e) {
      return 'Error reading file: $e';
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF5F0E8),
      body: SafeArea(
        child: FutureBuilder<String>(
          future: _fileContentFuture,
          builder: (context, snapshot) {
            if (snapshot.connectionState == ConnectionState.waiting) {
              return const Center(
                child: CircularProgressIndicator(),
              );
            }

            if (snapshot.hasError) {
              return Center(
                child: Text('Error: ${snapshot.error}'),
              );
            }

            final content = snapshot.data ?? '';

            return Padding(
              padding: const EdgeInsets.all(32.0),
              child: SingleChildScrollView(
                child: Text(
                  content,
                  style: GoogleFonts.literata(
                    fontSize: 16,
                    color: Colors.black87,
                    height: 1.6,
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}
