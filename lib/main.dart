import 'package:flutter/material.dart';
import 'package:file_picker/file_picker.dart';
import 'reader_screen.dart';

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

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  Future<void> _pickFile() async {
    try {
      FilePickerResult? result = await FilePicker.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['txt', 'md', 'rtf', 'docx'],
      );
      if (result != null && result.files.isNotEmpty) {
        final filePath = result.files.single.path;
        if (filePath != null) {
          Navigator.of(context).push(
            MaterialPageRoute(
              builder: (context) => ReaderScreen(filePath: filePath),
            ),
          );
        }
      }
    } catch (e) {
      print('Error: $e');
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
