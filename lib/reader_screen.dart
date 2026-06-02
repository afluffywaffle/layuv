import 'package:flutter/material.dart';
import 'package:flutter/gestures.dart';
import 'package:docx_to_text/docx_to_text.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:io';

enum ReadingMode { scroll, screenFlip, pageFlip }

enum _AnnotationTag { voice, pacing, continuity, query }

// ─── ReaderScreen ─────────────────────────────────────────────────────────────

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
    setState(() {
      _readingMode = mode;
      _modeSetByUser = true;
    });
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

  void _onTextSelected(String selectedText) {
    if (selectedText.trim().isEmpty) return;
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      isDismissible: false,
      backgroundColor: Colors.transparent,
      builder: (_) => _AnnotationPanel(selectedText: selectedText),
    );
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
                      Text('Scroll', style: const TextStyle(fontFamily: 'Literata')),
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
                      Text('Screen Flip', style: const TextStyle(fontFamily: 'Literata')),
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
                      Text('Page Flip', style: const TextStyle(fontFamily: 'Literata')),
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
            return const Center(child: CircularProgressIndicator());
          }
          if (snapshot.hasError) {
            return Center(child: Text('Error: ${snapshot.error}'));
          }
          final content = snapshot.data ?? '';
          switch (_readingMode) {
            case ReadingMode.scroll:
              return _buildScrollMode(content);
            case ReadingMode.screenFlip:
              return _ScreenFlipReader(
                content: content,
                onTextSelected: _onTextSelected,
              );
            case ReadingMode.pageFlip:
              return _PageFlipReader(
                content: content,
                onTextSelected: _onTextSelected,
              );
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
          child: SelectableText(
            content,
            style: TextStyle(fontFamily: 'Literata',
              fontSize: 16,
              color: Colors.black87,
              height: 1.6,
            ),
            contextMenuBuilder: (ctx, editableTextState) {
              _interceptSelection(editableTextState, _onTextSelected);
              return const SizedBox.shrink();
            },
          ),
        ),
      ),
    );
  }
}

/// Extracts the selected span from [editableTextState] and fires [onSelected].
/// Returns immediately — the bottom sheet is shown in a post-frame callback so
/// the overlay context has already been cleaned up before we push the sheet.
void _interceptSelection(
  EditableTextState editableTextState,
  void Function(String) onSelected,
) {
  final sel = editableTextState.textEditingValue.selection;
  if (!sel.isValid || sel.isCollapsed) return;
  final selected = editableTextState.textEditingValue.text
      .substring(sel.start, sel.end);
  WidgetsBinding.instance.addPostFrameCallback((_) => onSelected(selected));
}

// ─── Screen-flip mode ─────────────────────────────────────────────────────────

class _ScreenFlipReader extends StatefulWidget {
  final String content;
  final void Function(String) onTextSelected;

  const _ScreenFlipReader({
    required this.content,
    required this.onTextSelected,
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
    final newOffset =
        _scrollController.offset + (down ? _screenHeight * 0.9 : -_screenHeight * 0.9);
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
          return Listener(
            onPointerSignal: (event) {
              if (event is PointerScrollEvent &&
                  event.scrollDelta.dy.abs() > 4.0) {
                _scrollByScreen(event.scrollDelta.dy > 0);
              }
            },
            child: GestureDetector(
            behavior: HitTestBehavior.opaque,
            onVerticalDragEnd: (details) {
              final velocity = details.primaryVelocity ?? 0;
              if (velocity > 300) {
                _scrollByScreen(false);
              } else if (velocity < -300) {
                _scrollByScreen(true);
              }
            },
            child: SingleChildScrollView(
              controller: _scrollController,
              physics: const NeverScrollableScrollPhysics(),
              padding: const EdgeInsets.all(32.0),
              child: SelectableText(
                widget.content,
                style: TextStyle(fontFamily: 'Literata',
                  fontSize: 16,
                  color: Colors.black87,
                  height: 1.6,
                ),
                contextMenuBuilder: (ctx, editableTextState) {
                  _interceptSelection(
                      editableTextState, widget.onTextSelected);
                  return const SizedBox.shrink();
                },
              ),
            ),
          ),
          );
        },
      ),
    );
  }
}

// ─── Page-flip mode ───────────────────────────────────────────────────────────

class _PageFlipReader extends StatefulWidget {
  final String content;
  final void Function(String) onTextSelected;

  const _PageFlipReader({
    required this.content,
    required this.onTextSelected,
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
  static const double _indicatorReservedHeight = 56.0;
  static const double _safetyMargin = 24.0;
  // Narrow transparent strips on each edge handle prev/next taps so
  // SelectableText owns the central area for long-press selection.
  static const double _navStripWidth = 64.0;

  // Prevents a single wheel flick from firing multiple page changes.
  DateTime _lastWheelEvent = DateTime.fromMillisecondsSinceEpoch(0);

  TextStyle get _textStyle => TextStyle(fontFamily: 'Literata',
        fontSize: 16,
        color: Colors.black87,
        height: 1.6,
      );

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

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

      final pos = painter.getPositionForOffset(Offset(maxWidth, maxHeight));
      final lineBoundary = painter.getLineBoundary(pos);
      int end = lineBoundary.start;

      if (end <= 0) {
        end = lineBoundary.end > 0 ? lineBoundary.end : 1;
      }

      pages.add(remaining.substring(0, end).trimRight());
      start += end;

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

          if (_lastSize != size) {
            _lastSize = size;
            _pages = _paginate(
              widget.content,
              constraints.maxWidth - _padding * 2,
              (constraints.maxHeight -
                      _padding * 2 -
                      _indicatorReservedHeight -
                      _safetyMargin) *
                  0.85,
            );
            if (_currentPage > _pages.length - 1) {
              _currentPage = _pages.length - 1;
            }
          }

          return Listener(
            onPointerSignal: (event) {
              if (event is! PointerScrollEvent) return;
              if (event.scrollDelta.dy.abs() <= 4.0) return;
              final now = DateTime.now();
              if (now.difference(_lastWheelEvent).inMilliseconds < 400) return;
              _lastWheelEvent = now;
              _goToPage(
                _currentPage + (event.scrollDelta.dy > 0 ? 1 : -1),
              );
            },
            child: Stack(
            children: [
              PageView.builder(
                controller: _pageController,
                onPageChanged: (page) => setState(() => _currentPage = page),
                itemCount: _pages.length,
                itemBuilder: (context, index) {
                  return Padding(
                    padding: const EdgeInsets.all(_padding),
                    child: SelectableText(
                      _pages[index],
                      style: _textStyle,
                      contextMenuBuilder: (ctx, editableTextState) {
                        _interceptSelection(
                            editableTextState, widget.onTextSelected);
                        return const SizedBox.shrink();
                      },
                    ),
                  );
                },
              ),

              // Previous-page tap zone (left edge).
              Positioned(
                left: 0,
                top: 0,
                bottom: 0,
                width: _navStripWidth,
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => _goToPage(_currentPage - 1),
                ),
              ),

              // Next-page tap zone (right edge).
              Positioned(
                right: 0,
                top: 0,
                bottom: 0,
                width: _navStripWidth,
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => _goToPage(_currentPage + 1),
                ),
              ),

              // Page indicator pill.
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
                      style: TextStyle(fontFamily: 'SourceSans3',
                        fontSize: 14,
                        color: Colors.black87,
                      ),
                    ),
                  ),
                ),
              ),
            ],
          ),
          );
        },
      ),
    );
  }
}

// ─── Annotation panel ─────────────────────────────────────────────────────────

class _AnnotationPanel extends StatefulWidget {
  final String selectedText;

  const _AnnotationPanel({required this.selectedText});

  @override
  State<_AnnotationPanel> createState() => _AnnotationPanelState();
}

class _AnnotationPanelState extends State<_AnnotationPanel> {
  final _controller = TextEditingController();
  _AnnotationTag? _activeTag;

  static const _tagLabels = {
    _AnnotationTag.voice: 'Voice',
    _AnnotationTag.pacing: 'Pacing',
    _AnnotationTag.continuity: 'Continuity',
    _AnnotationTag.query: 'Query',
  };

  static const _tagPrompts = {
    _AnnotationTag.voice:
        'The voice feels [too formal / too casual / inconsistent] because ',
    _AnnotationTag.pacing:
        'The pacing feels [rushed / slow / uneven] because ',
    _AnnotationTag.continuity: 'Possible continuity issue: ',
    _AnnotationTag.query: 'Question: ',
  };

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _selectTag(_AnnotationTag tag) {
    setState(() {
      if (_activeTag == tag) {
        _activeTag = null;
        _controller.clear();
      } else {
        _activeTag = tag;
        _controller.text = _tagPrompts[tag]!;
        _controller.selection =
            TextSelection.collapsed(offset: _controller.text.length);
      }
    });
  }

  void _save() {
    final note = _controller.text.trim();
    if (note.isEmpty) return;
    debugPrint('--- ANNOTATION ---');
    debugPrint('Selected: "${widget.selectedText}"');
    debugPrint('Tag: ${_activeTag?.name ?? 'none'}');
    debugPrint('Note: "$note"');
    debugPrint('------------------');
    Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: Color(0xFFF5F0E8),
        borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
      ),
      padding: EdgeInsets.fromLTRB(
        24,
        20,
        24,
        MediaQuery.of(context).viewInsets.bottom + 24,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          // Header row
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'Add Note',
                style: TextStyle(fontFamily: 'Literata',
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                  color: Colors.black87,
                ),
              ),
              GestureDetector(
                onTap: () => Navigator.of(context).pop(),
                child:
                    const Icon(Icons.close, size: 22, color: Colors.black54),
              ),
            ],
          ),
          const SizedBox(height: 16),

          // Selected-text quote box
          Container(
            width: double.infinity,
            padding:
                const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
            decoration: BoxDecoration(
              color: Colors.black.withValues(alpha: 0.06),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Text(
              '“${widget.selectedText}”',
              style: TextStyle(fontFamily: 'Literata',
                fontSize: 14,
                fontStyle: FontStyle.italic,
                color: Colors.black54,
                height: 1.5,
              ),
              maxLines: 4,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          const SizedBox(height: 14),

          // Note input
          TextField(
            controller: _controller,
            autofocus: true,
            maxLines: null,
            keyboardType: TextInputType.multiline,
            style:
                TextStyle(fontFamily: 'Literata',fontSize: 15, color: Colors.black87),
            decoration: InputDecoration(
              hintText: 'Write your note...',
              hintStyle: TextStyle(fontFamily: 'Literata',
                fontSize: 15,
                color: Colors.black38,
              ),
              filled: true,
              fillColor: Colors.black.withValues(alpha: 0.04),
              contentPadding: const EdgeInsets.all(12),
              enabledBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(8),
                borderSide: BorderSide.none,
              ),
              focusedBorder: OutlineInputBorder(
                borderRadius: BorderRadius.circular(8),
                borderSide:
                    const BorderSide(color: Colors.black26, width: 1),
              ),
            ),
          ),
          const SizedBox(height: 12),

          // Tag chips
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: _AnnotationTag.values.map((tag) {
              final isActive = _activeTag == tag;
              return GestureDetector(
                onTap: () => _selectTag(tag),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 150),
                  padding: const EdgeInsets.symmetric(
                      horizontal: 14, vertical: 7),
                  decoration: BoxDecoration(
                    color: isActive
                        ? Colors.black87
                        : Colors.black.withValues(alpha: 0.08),
                    borderRadius: BorderRadius.circular(20),
                  ),
                  child: Text(
                    _tagLabels[tag]!,
                    style: TextStyle(fontFamily: 'Literata',
                      fontSize: 13,
                      color: isActive
                          ? const Color(0xFFF5F0E8)
                          : Colors.black54,
                    ),
                  ),
                ),
              );
            }).toList(),
          ),
          const SizedBox(height: 20),

          // Save button
          SizedBox(
            width: double.infinity,
            child: ElevatedButton(
              onPressed: _save,
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.black87,
                foregroundColor: const Color(0xFFF5F0E8),
                elevation: 0,
                padding: const EdgeInsets.symmetric(vertical: 14),
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(8),
                ),
              ),
              child: Text(
                'Save',
                style: TextStyle(fontFamily: 'Literata',
                  fontSize: 15,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
