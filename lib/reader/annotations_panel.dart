import 'package:flutter/material.dart';
import '../models/annotation.dart';
import '../models/annotation_store.dart';
import 'appbar_pill.dart';

class AnnotationsPanel extends StatefulWidget {
  final AnnotationStore store;
  final void Function(double position) onJumpTo;
  final VoidCallback onClose;

  const AnnotationsPanel({
    super.key,
    required this.store,
    required this.onJumpTo,
    required this.onClose,
  });

  @override
  State<AnnotationsPanel> createState() => _AnnotationsPanelState();
}

class _AnnotationsPanelState extends State<AnnotationsPanel>
    with SingleTickerProviderStateMixin {
  static const _warmPaper = Color(0xFFF5F0E8);

  List<Annotation> _annotations = [];
  Set<AnnotationTool> _activeFilters = {};

  static const _filterableTools = [
    AnnotationTool.highlight,
    AnnotationTool.underline,
    AnnotationTool.doubleUnderline,
    AnnotationTool.strikethrough,
    AnnotationTool.comment,
    AnnotationTool.inkAnnotation,
  ];

  @override
  void initState() {
    super.initState();
    _loadAnnotations();
  }

  Future<void> _loadAnnotations() async {
    final list = await widget.store.loadAnnotations();
    list.sort((a, b) => a.position.compareTo(b.position));
    if (mounted) setState(() => _annotations = list);
  }

  List<Annotation> get _filtered {
    if (_activeFilters.isEmpty) return _annotations;
    return _annotations.where((a) => _activeFilters.contains(a.tool)).toList();
  }

  String _toolLabel(AnnotationTool tool) => switch (tool) {
        AnnotationTool.highlight => 'Highlight',
        AnnotationTool.underline => 'Underline',
        AnnotationTool.doubleUnderline => 'Double',
        AnnotationTool.strikethrough => 'Strikethrough',
        AnnotationTool.comment => 'Comment',
        AnnotationTool.inkAnnotation => 'Ink',
        _ => tool.name,
      };

  Future<void> _confirmDelete(Annotation annotation) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: _warmPaper,
        title: const Text('Delete annotation?',
            style: TextStyle(fontFamily: 'Literata')),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Cancel',
                style: TextStyle(fontFamily: 'Literata', color: Colors.black54)),
          ),
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Delete',
                style: TextStyle(fontFamily: 'Literata', color: Colors.black87)),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      await widget.store.deleteAnnotation(annotation.id);
      await _loadAnnotations();
    }
  }

  @override
  Widget build(BuildContext context) {
    return DefaultTabController(
      length: 2,
      child: Container(
        color: _warmPaper,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            _Header(onClose: widget.onClose),
            const TabBar(
              labelStyle: TextStyle(
                fontFamily: 'Literata',
                fontWeight: FontWeight.w600,
                fontSize: 14,
              ),
              unselectedLabelStyle: TextStyle(
                fontFamily: 'Literata',
                fontSize: 14,
              ),
              labelColor: Colors.black87,
              unselectedLabelColor: Colors.black45,
              indicatorColor: Colors.black87,
              indicatorSize: TabBarIndicatorSize.label,
              dividerHeight: 0,
              tabs: [
                Tab(text: 'Annotations'),
                Tab(text: 'Bookmarks'),
              ],
            ),
            Expanded(
              child: TabBarView(
                children: [
                  _AnnotationsTab(
                    annotations: _filtered,
                    allAnnotations: _annotations,
                    activeFilters: _activeFilters,
                    filterableTools: _filterableTools,
                    toolLabel: _toolLabel,
                    onFilterChanged: (tool, selected) {
                      setState(() {
                        if (selected) {
                          _activeFilters.add(tool);
                        } else {
                          _activeFilters.remove(tool);
                        }
                      });
                    },
                    onClearFilters: () => setState(() => _activeFilters.clear()),
                    onTap: (a) {
                      widget.onJumpTo(a.position);
                      widget.onClose();
                    },
                    onLongPress: _confirmDelete,
                  ),
                  const Center(child: Text('Coming soon')),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ─── Annotations tab ─────────────────────────────────────────────────────────

class _AnnotationsTab extends StatelessWidget {
  final List<Annotation> annotations;
  final List<Annotation> allAnnotations;
  final Set<AnnotationTool> activeFilters;
  final List<AnnotationTool> filterableTools;
  final String Function(AnnotationTool) toolLabel;
  final void Function(AnnotationTool, bool) onFilterChanged;
  final VoidCallback onClearFilters;
  final void Function(Annotation) onTap;
  final void Function(Annotation) onLongPress;

  const _AnnotationsTab({
    required this.annotations,
    required this.allAnnotations,
    required this.activeFilters,
    required this.filterableTools,
    required this.toolLabel,
    required this.onFilterChanged,
    required this.onClearFilters,
    required this.onTap,
    required this.onLongPress,
  });

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _FilterRow(
          activeFilters: activeFilters,
          filterableTools: filterableTools,
          toolLabel: toolLabel,
          onFilterChanged: onFilterChanged,
          onClearFilters: onClearFilters,
        ),
        Expanded(
          child: allAnnotations.isEmpty
              ? const Center(
                  child: Text(
                    'No annotations yet',
                    style: TextStyle(
                      fontFamily: 'Literata',
                      fontSize: 14,
                      color: Colors.black38,
                    ),
                  ),
                )
              : ListView.builder(
                  itemCount: annotations.length,
                  itemBuilder: (_, i) => _AnnotationTile(
                    annotation: annotations[i],
                    onTap: onTap,
                    onLongPress: onLongPress,
                  ),
                ),
        ),
      ],
    );
  }
}

// ─── Filter chips row ─────────────────────────────────────────────────────────

class _FilterRow extends StatelessWidget {
  final Set<AnnotationTool> activeFilters;
  final List<AnnotationTool> filterableTools;
  final String Function(AnnotationTool) toolLabel;
  final void Function(AnnotationTool, bool) onFilterChanged;
  final VoidCallback onClearFilters;

  const _FilterRow({
    required this.activeFilters,
    required this.filterableTools,
    required this.toolLabel,
    required this.onFilterChanged,
    required this.onClearFilters,
  });

  @override
  Widget build(BuildContext context) {
    final allSelected = activeFilters.isEmpty;
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: Row(
        children: [
          FilterChip(
            label: const Text('All'),
            selected: allSelected,
            onSelected: (_) => onClearFilters(),
            labelStyle: TextStyle(
              fontFamily: 'Literata',
              fontSize: 12,
              color: allSelected ? Colors.black87 : Colors.black54,
            ),
            visualDensity: VisualDensity.compact,
            showCheckmark: false,
            selectedColor: Colors.black12,
            backgroundColor: Colors.transparent,
            side: BorderSide(color: allSelected ? Colors.black38 : Colors.black12),
          ),
          const SizedBox(width: 6),
          ...filterableTools.map((tool) {
            final selected = activeFilters.contains(tool);
            return Padding(
              padding: const EdgeInsets.only(right: 6),
              child: FilterChip(
                label: Text(toolLabel(tool)),
                selected: selected,
                onSelected: (v) => onFilterChanged(tool, v),
                labelStyle: TextStyle(
                  fontFamily: 'Literata',
                  fontSize: 12,
                  color: selected ? Colors.black87 : Colors.black54,
                ),
                visualDensity: VisualDensity.compact,
                showCheckmark: false,
                selectedColor: Colors.black12,
                backgroundColor: Colors.transparent,
                side: BorderSide(color: selected ? Colors.black38 : Colors.black12),
              ),
            );
          }),
        ],
      ),
    );
  }
}

// ─── Annotation tile ──────────────────────────────────────────────────────────

class _AnnotationTile extends StatelessWidget {
  final Annotation annotation;
  final void Function(Annotation) onTap;
  final void Function(Annotation) onLongPress;

  const _AnnotationTile({
    required this.annotation,
    required this.onTap,
    required this.onLongPress,
  });

  @override
  Widget build(BuildContext context) {
    final tag = annotation.tag;
    final note = annotation.note;
    final pct = '${(annotation.position * 100).round()}%';

    return InkWell(
      onTap: () => onTap(annotation),
      onLongPress: () => onLongPress(annotation),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Leading: tool icon + optional tag chip
            Column(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                ToolIcon(tool: annotation.tool, size: 16),
                if (tag != null) ...[
                  const SizedBox(height: 4),
                  Chip(
                    label: Text(tag.name),
                    labelStyle: const TextStyle(fontSize: 10),
                    labelPadding: EdgeInsets.zero,
                    visualDensity: VisualDensity.compact,
                    padding: EdgeInsets.zero,
                    side: BorderSide.none,
                    backgroundColor: Colors.black.withValues(alpha: 0.08),
                  ),
                ],
              ],
            ),
            const SizedBox(width: 12),
            // Body
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    annotation.selectedText,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(
                      fontFamily: 'Literata',
                      fontSize: 13,
                      color: Colors.black87,
                    ),
                  ),
                  if (note != null) ...[
                    const SizedBox(height: 2),
                    Text(
                      note,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontFamily: 'Source Sans 3',
                        fontSize: 12,
                        color: Colors.black54,
                      ),
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(width: 8),
            // Trailing: position
            Text(
              pct,
              style: const TextStyle(
                fontFamily: 'Source Sans 3',
                fontSize: 11,
                color: Colors.black38,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ─── Header ───────────────────────────────────────────────────────────────────

class _Header extends StatelessWidget {
  final VoidCallback onClose;

  const _Header({required this.onClose});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 8, 8),
      child: Row(
        children: [
          const Expanded(
            child: Text(
              'Annotations',
              style: TextStyle(
                fontFamily: 'Source Sans 3',
                fontWeight: FontWeight.bold,
                fontSize: 18,
                color: Colors.black87,
              ),
            ),
          ),
          IconButton(
            icon: const Icon(Icons.close, color: Colors.black87),
            onPressed: onClose,
          ),
        ],
      ),
    );
  }
}
