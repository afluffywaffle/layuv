import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:docx_to_text/docx_to_text.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:io';

enum ReadingMode { scroll, screenFlip, pageFlip }

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
  late ReadingMode _readingMode;
  // Prevents _loadReadingModePreference from overwriting a user-initiated
  // selection if both async operations are in flight simultaneously.
  bool _modeSetByUser = false;

  @override
  void initState() {
    super.initState();
    _fileContentFuture = _readFile();
    _readingMode = _getDefaultReadingMode();
    _loadReadingModePreference();
  }

  ReadingMode _getDefaultReadingMode() {
    if (Platform.isMacOS || Platform.isIOS) {
      return ReadingMode.screenFlip;
    } else {
      return ReadingMode.pageFlip;
    }
  }

  Future<void> _loadReadingModePreference() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      if (!mounted || _modeSetByUser) return;
      final savedMode = prefs.getString('reading_mode');
      if (savedMode != null) {
        setState(() {
          _readingMode = ReadingMode.values.byName(savedMode);
        });
      } else {
        await prefs.setString('reading_mode', _readingMode.name);
      }
    } catch (e) {
      debugPrint('Error loading reading mode preference: $e');
    }
  }

  void _setReadingMode(ReadingMode mode) {
    // Update UI immediately — no await before setState.
    setState(() {
      _readingMode = mode;
      _modeSetByUser = true;
    });
    // Persist in the background; errors are non-fatal.
    SharedPreferences.getInstance()
        .then((prefs) => prefs.setString('reading_mode', mode.name));
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
      appBar: AppBar(
        backgroundColor: const Color(0xFFF5F0E8),
        elevation: 0,
        actions: [
          Padding(
            padding: const EdgeInsets.all(16.0),
            child: PopupMenuButton<ReadingMode>(
              initialValue: _readingMode,
              onSelected: _setReadingMode,
              itemBuilder: (BuildContext context) => [
                PopupMenuItem<ReadingMode>(
                  value: ReadingMode.scroll,
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.unfold_more, size: 20),
                      const SizedBox(width: 12),
                      Text(
                        'Scroll',
                        style: GoogleFonts.sourceSans3(),
                      ),
                    ],
                  ),
                ),
                PopupMenuItem<ReadingMode>(
                  value: ReadingMode.screenFlip,
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.arrow_upward, size: 20),
                      const SizedBox(width: 12),
                      Text(
                        'Screen Flip',
                        style: GoogleFonts.sourceSans3(),
                      ),
                    ],
                  ),
                ),
                PopupMenuItem<ReadingMode>(
                  value: ReadingMode.pageFlip,
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      const Icon(Icons.arrow_forward, size: 20),
                      const SizedBox(width: 12),
                      Text(
                        'Page Flip',
                        style: GoogleFonts.sourceSans3(),
                      ),
                    ],
                  ),
                ),
              ],
              child: _getModeIcon(_readingMode),
            ),
          ),
        ],
      ),
      body: FutureBuilder<String>(
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

          switch (_readingMode) {
            case ReadingMode.scroll:
              return _buildScrollMode(content);
            case ReadingMode.screenFlip:
              return _buildScreenFlipMode(content);
            case ReadingMode.pageFlip:
              return _buildPageFlipMode(content);
          }
        },
      ),
    );
  }

  Widget _getModeIcon(ReadingMode mode) {
    final iconData = switch (mode) {
      ReadingMode.scroll => Icons.unfold_more,
      ReadingMode.screenFlip => Icons.arrow_upward,
      ReadingMode.pageFlip => Icons.arrow_forward,
    };
    return Icon(iconData, color: Colors.black87);
  }

  Widget _buildScrollMode(String content) {
    return SafeArea(
      child: Padding(
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
      ),
    );
  }

  Widget _buildScreenFlipMode(String content) {
    return _ScreenFlipReader(
      content: content,
    );
  }

  Widget _buildPageFlipMode(String content) {
    return _PageFlipReader(
      content: content,
    );
  }
}

class _ScreenFlipReader extends StatefulWidget {
  final String content;

  const _ScreenFlipReader({
    required this.content,
  });

  @override
  State<_ScreenFlipReader> createState() => _ScreenFlipReaderState();
}

class _ScreenFlipReaderState extends State<_ScreenFlipReader> {
  late ScrollController _scrollController;
  double _screenHeight = 0;

  @override
  void initState() {
    super.initState();
    _scrollController = ScrollController();
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  void _scrollByScreen(bool down) {
    final newOffset = _scrollController.offset +
        (down ? _screenHeight : -_screenHeight);
    _scrollController.animateTo(
      newOffset.clamp(0, _scrollController.position.maxScrollExtent),
      duration: const Duration(milliseconds: 500),
      curve: Curves.easeInOut,
    );
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) {
          _screenHeight = constraints.maxHeight;
          return GestureDetector(
            behavior: HitTestBehavior.opaque,
            onVerticalDragEnd: (details) {
              final velocity = details.primaryVelocity ?? 0;
              if (velocity > 300) {
                // Swiping down -> go back one screen
                _scrollByScreen(false);
              } else if (velocity < -300) {
                // Swiping up -> advance one screen
                _scrollByScreen(true);
              }
            },
            // Disable the scroll view's own physics so a swipe can't
            // free-scroll. Each swipe instead jumps exactly one screen
            // height via the ScrollController's animateTo.
            child: SingleChildScrollView(
              controller: _scrollController,
              physics: const NeverScrollableScrollPhysics(),
              padding: const EdgeInsets.all(32.0),
              child: Text(
                widget.content,
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
    );
  }
}

class _PageFlipReader extends StatefulWidget {
  final String content;

  const _PageFlipReader({
    required this.content,
  });

  @override
  State<_PageFlipReader> createState() => _PageFlipReaderState();
}

class _PageFlipReaderState extends State<_PageFlipReader> {
  final PageController _pageController = PageController();
  int _currentPage = 0;
  List<String> _pages = const [''];
  Size? _lastSize;

  static const double _padding = 32.0;
  // Positioned indicator is bottom: 24 with ~32px pill height.
  static const double _indicatorReservedHeight = 56.0;
  // Extra breathing room so the last line is never clipped.
  static const double _safetyMargin = 24.0;

  TextStyle get _textStyle => GoogleFonts.literata(
        fontSize: 16,
        color: Colors.black87,
        height: 1.6,
      );

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  /// Splits [text] into pages that each fit within [maxWidth] x [maxHeight].
  /// Breaks only on complete line boundaries so no line is ever clipped.
  List<String> _paginate(String text, double maxWidth, double maxHeight) {
    if (text.isEmpty || maxWidth <= 0 || maxHeight <= 0) return const [''];

    final pages = <String>[];
    int start = 0;

    while (start < text.length) {
      final remaining = text.substring(start);
      final painter = TextPainter(
        text: TextSpan(text: remaining, style: _textStyle),
        textDirection: TextDirection.ltr,
      )..layout(maxWidth: maxWidth);

      if (painter.height <= maxHeight) {
        pages.add(remaining);
        break;
      }

      // Find the character at the bottom edge of the page, then snap back to
      // the start of that line so we never display a partially clipped line.
      final pos = painter.getPositionForOffset(Offset(maxWidth, maxHeight));
      final lineBoundary = painter.getLineBoundary(pos);
      int end = lineBoundary.start;

      // If we landed on the very first line, include at least that line so
      // we always make forward progress.
      if (end <= 0) {
        end = lineBoundary.end > 0 ? lineBoundary.end : 1;
      }

      pages.add(remaining.substring(0, end).trimRight());
      start += end;

      // Skip any leading whitespace/newlines before the next page.
      while (start < text.length && text[start] == '\n') {
        start++;
      }
    }

    return pages.isEmpty ? const [''] : pages;
  }

  void _goToPage(int page) {
    _pageController.animateToPage(
      page.clamp(0, _pages.length - 1),
      duration: const Duration(milliseconds: 400),
      curve: Curves.easeInOut,
    );
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: LayoutBuilder(
        builder: (context, constraints) {
          final size = Size(constraints.maxWidth, constraints.maxHeight);

          // Recompute pages only when the available size changes.
          if (_lastSize != size) {
            _lastSize = size;
            _pages = _paginate(
              widget.content,
              constraints.maxWidth - _padding * 2,
              (constraints.maxHeight - _padding * 2 - _indicatorReservedHeight - _safetyMargin) * 0.85,
            );
            if (_currentPage > _pages.length - 1) {
              _currentPage = _pages.length - 1;
            }
          }

          return Stack(
            children: [
              PageView.builder(
                controller: _pageController,
                onPageChanged: (page) {
                  setState(() {
                    _currentPage = page;
                  });
                },
                itemCount: _pages.length,
                itemBuilder: (context, index) {
                  return GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTapUp: (details) {
                      final width = MediaQuery.of(context).size.width;
                      if (details.globalPosition.dx < width / 3) {
                        _goToPage(_currentPage - 1);
                      } else if (details.globalPosition.dx > 2 * width / 3) {
                        _goToPage(_currentPage + 1);
                      }
                    },
                    child: Padding(
                      padding: const EdgeInsets.all(_padding),
                      child: Text(
                        _pages[index],
                        style: _textStyle,
                      ),
                    ),
                  );
                },
              ),
              Positioned(
            bottom: 24,
            left: 0,
            right: 0,
            child: Center(
              child: Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 16.0,
                  vertical: 8.0,
                ),
                decoration: BoxDecoration(
                  color: Colors.black12,
                  borderRadius: BorderRadius.circular(20),
                ),
                child: Text(
                  '${_currentPage + 1} / ${_pages.length}',
                  style: GoogleFonts.sourceSans3(
                    fontSize: 14,
                    color: Colors.black87,
                  ),
                ),
              ),
            ),
          ),
            ],
          );
        },
      ),
    );
  }
}

