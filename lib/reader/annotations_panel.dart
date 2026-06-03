import 'package:flutter/material.dart';
import '../models/annotation.dart';
import '../models/annotation_store_interface.dart';
import '../utils/platform_utils.dart';
import 'appbar_pill.dart';

class AnnotationsPanel extends StatefulWidget {
  final AnnotationStoreInterface store;
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

class _AnnotationsPanelState extends State<AnnotationsPanel> {
  static const _warmPaper = Color(0xFFF5F0E8);

  List<Annotation> _annotations = [];
  final Set<AnnotationTool> _activeFilters = {};
  String? _highlightedAnnotationId;
  bool _filterCommentsOnly = false;
  bool _editMode = false;
  final Set<String> _selected = {};

  static const _filterableTools = [
    AnnotationTool.highlight,
    AnnotationTool.underline,
    AnnotationTool.doubleUnderline,
    AnnotationTool.strikethrough,
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

  List<Annotation> get _bookmarks =>
      _annotations.where((a) => a.tool == AnnotationTool.bookmark).toList();

  List<Annotation> get _filtered {
    var list = _annotations
        .where((a) => a.tool != AnnotationTool.bookmark)
        .toList();
    if (_activeFilters.isNotEmpty) {
      list = list.where((a) => _activeFilters.contains(a.tool)).toList();
    }
    if (_filterCommentsOnly) {
      list = list.where((a) => a.note != null && a.note!.isNotEmpty).toList();
    }
    return list;
  }

  String _toolLabel(AnnotationTool tool) => switch (tool) {
        AnnotationTool.highlight => 'Highlight',
        AnnotationTool.underline => 'Underline',
        AnnotationTool.doubleUnderline => 'Double Underline',
        AnnotationTool.strikethrough => 'Strikethrough',
        AnnotationTool.comment => 'Comment',
        AnnotationTool.inkAnnotation => 'Ink',
        _ => tool.name,
      };

  Future<void> _deleteSelected() async {
    final ids = _selected.toList();
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFFF5F0E8),
        title: Text('Delete ${ids.length} annotation${ids.length == 1 ? '' : 's'}?',
            style: const TextStyle(fontFamily: 'Literata')),
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
      await widget.store.deleteAll(ids);
      setState(() {
        _selected.clear();
        _editMode = false;
      });
      await _loadAnnotations();
    }
  }

  Future<void> _deleteAllAnnotations() async {
    final all = _annotations
        .where((a) => a.tool != AnnotationTool.bookmark)
        .map((a) => a.id)
        .toList();
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFFF5F0E8),
        title: const Text('Delete all annotations?',
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
      await widget.store.deleteAll(all);
      setState(() {
        _selected.clear();
        _editMode = false;
      });
      await _loadAnnotations();
    }
  }

  Future<bool?> _confirmSingleDelete(Annotation annotation) {
    return showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: const Color(0xFFF5F0E8),
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
            _Header(
              onClose: widget.onClose,
              editMode: _editMode,
              onToggleEdit: () => setState(() {
                _editMode = !_editMode;
                _selected.clear();
              }),
            ),
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
                    allAnnotations: _annotations
                        .where((a) => a.tool != AnnotationTool.bookmark)
                        .toList(),
                    activeFilters: _activeFilters,
                    filterableTools: _filterableTools,
                    toolLabel: _toolLabel,
                    onFilterToggle: (tool) {
                      setState(() {
                        if (_activeFilters.contains(tool)) {
                          _activeFilters.remove(tool);
                        } else {
                          _activeFilters.add(tool);
                        }
                      });
                    },
                    onClearFilters: () => setState(() {
                      _activeFilters.clear();
                      _filterCommentsOnly = false;
                    }),
                    filterCommentsOnly: _filterCommentsOnly,
                    onToggleCommentsOnly: () =>
                        setState(() => _filterCommentsOnly = !_filterCommentsOnly),
                    highlightedAnnotationId: _highlightedAnnotationId,
                    onTap: (a) {
                      widget.onJumpTo(a.position);
                      if (a.note != null && a.note!.isNotEmpty) {
                        setState(() => _highlightedAnnotationId = a.id);
                      } else {
                        widget.onClose();
                      }
                    },
                    onLongPress: (a) async {
                      final confirmed = await _confirmSingleDelete(a);
                      if (confirmed == true) {
                        await widget.store.deleteAnnotation(a.id);
                        await _loadAnnotations();
                      }
                    },
                    onDelete: (a) async {
                      await widget.store.deleteAnnotation(a.id);
                      await _loadAnnotations();
                    },
                    confirmDelete: _confirmSingleDelete,
                    editMode: _editMode,
                    selected: _selected,
                    onToggleSelect: (id) => setState(() {
                      if (_selected.contains(id)) {
                        _selected.remove(id);
                      } else {
                        _selected.add(id);
                      }
                    }),
                  ),
                  _AnnotationsTab(
                    annotations: _bookmarks,
                    allAnnotations: _bookmarks,
                    activeFilters: const {},
                    filterableTools: const [],
                    toolLabel: _toolLabel,
                    onFilterToggle: (_) {},
                    onClearFilters: () {},
                    showFilters: false,
                    showSections: false,
                    emptyText: 'No bookmarks yet',
                    onTap: (a) {
                      widget.onJumpTo(a.position);
                      if (a.note == null || a.note!.isEmpty) {
                        widget.onClose();
                      }
                    },
                    onLongPress: (a) async {
                      final confirmed = await _confirmSingleDelete(a);
                      if (confirmed == true) {
                        await widget.store.deleteAnnotation(a.id);
                        await _loadAnnotations();
                      }
                    },
                    onDelete: (a) async {
                      await widget.store.deleteAnnotation(a.id);
                      await _loadAnnotations();
                    },
                    confirmDelete: _confirmSingleDelete,
                  ),
                ],
              ),
            ),
            if (_editMode)
              SafeArea(
                child: Padding(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 16),
                  child: Row(
                    children: [
                      Expanded(
                        child: OutlinedButton(
                          onPressed: _selected.isEmpty ? null : _deleteSelected,
                          style: OutlinedButton.styleFrom(
                            foregroundColor: Colors.black87,
                            side: const BorderSide(color: Colors.black26),
                            padding: const EdgeInsets.symmetric(vertical: 12),
                          ),
                          child: Text(
                            _selected.isEmpty
                                ? 'Delete Selected'
                                : 'Delete Selected (${_selected.length})',
                            style: const TextStyle(
                                fontFamily: 'Literata', fontSize: 13),
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: FilledButton(
                          onPressed: _deleteAllAnnotations,
                          style: FilledButton.styleFrom(
                            backgroundColor: Colors.red.shade700,
                            foregroundColor: Colors.white,
                            padding: const EdgeInsets.symmetric(vertical: 12),
                          ),
                          child: const Text('Delete All',
                              style: TextStyle(
                                  fontFamily: 'Literata', fontSize: 13)),
                        ),
                      ),
                    ],
                  ),
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
  final void Function(AnnotationTool) onFilterToggle;
  final VoidCallback onClearFilters;
  final void Function(Annotation) onTap;
  final void Function(Annotation) onLongPress;
  final Future<void> Function(Annotation) onDelete;
  final Future<bool?> Function(Annotation) confirmDelete;
  final bool showFilters;
  final bool showSections;
  final String emptyText;
  final String? highlightedAnnotationId;
  final bool filterCommentsOnly;
  final VoidCallback onToggleCommentsOnly;
  final bool editMode;
  final Set<String> selected;
  final void Function(String id) onToggleSelect;

  const _AnnotationsTab({
    required this.annotations,
    required this.allAnnotations,
    required this.activeFilters,
    required this.filterableTools,
    required this.toolLabel,
    required this.onFilterToggle,
    required this.onClearFilters,
    required this.onTap,
    required this.onLongPress,
    required this.onDelete,
    required this.confirmDelete,
    this.showFilters = true,
    this.showSections = true,
    this.emptyText = 'No annotations yet',
    this.highlightedAnnotationId,
    this.filterCommentsOnly = false,
    this.onToggleCommentsOnly = _noOp,
    this.editMode = false,
    this.selected = const {},
    this.onToggleSelect = _noOp2,
  });

  static void _noOp() {}
  static void _noOp2(String _) {}

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (showFilters && !editMode)
          _IconToggleRow(
            activeFilters: activeFilters,
            filterableTools: filterableTools,
            onFilterToggle: onFilterToggle,
            onClearFilters: onClearFilters,
            filterCommentsOnly: filterCommentsOnly,
            onToggleCommentsOnly: onToggleCommentsOnly,
          ),
        if (showFilters && editMode)
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            child: Text(
              '${selected.length} selected',
              style: const TextStyle(
                fontFamily: 'Literata',
                fontSize: 13,
                color: Colors.black54,
              ),
            ),
          ),
        Expanded(
          child: allAnnotations.isEmpty
              ? Center(
                  child: Text(
                    emptyText,
                    style: const TextStyle(
                      fontFamily: 'Literata',
                      fontSize: 14,
                      color: Colors.black38,
                    ),
                  ),
                )
              : showSections
                  ? _SectionedList(
                      annotations: annotations,
                      filterableTools: filterableTools,
                      toolLabel: toolLabel,
                      onTap: onTap,
                      onLongPress: onLongPress,
                      onDelete: onDelete,
                      confirmDelete: confirmDelete,
                      highlightedAnnotationId: highlightedAnnotationId,
                      editMode: editMode,
                      selected: selected,
                      onToggleSelect: onToggleSelect,
                    )
                  : ListView.builder(
                      itemCount: annotations.length,
                      itemBuilder: (_, i) => _AnnotationTile(
                        annotation: annotations[i],
                        onTap: onTap,
                        onLongPress: onLongPress,
                        onDelete: onDelete,
                        confirmDelete: confirmDelete,
                        isHighlighted: annotations[i].id == highlightedAnnotationId,
                        editMode: editMode,
                        isSelected: selected.contains(annotations[i].id),
                        onToggleSelect: onToggleSelect,
                      ),
                    ),
        ),
      ],
    );
  }
}

// ─── Icon toggle row ──────────────────────────────────────────────────────────

class _IconToggleRow extends StatelessWidget {
  final Set<AnnotationTool> activeFilters;
  final List<AnnotationTool> filterableTools;
  final void Function(AnnotationTool) onFilterToggle;
  final VoidCallback onClearFilters;
  final bool filterCommentsOnly;
  final VoidCallback onToggleCommentsOnly;

  const _IconToggleRow({
    required this.activeFilters,
    required this.filterableTools,
    required this.onFilterToggle,
    required this.onClearFilters,
    required this.filterCommentsOnly,
    required this.onToggleCommentsOnly,
  });

  @override
  Widget build(BuildContext context) {
    final allActive = activeFilters.isEmpty;
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: Row(
        children: [
          // "All" toggle
          GestureDetector(
            onTap: onClearFilters,
            child: Container(
              width: 32,
              height: 32,
              decoration: BoxDecoration(
                color: allActive ? Colors.black12 : Colors.transparent,
                borderRadius: BorderRadius.circular(6),
              ),
              child: const Center(
                child: Text(
                  'All',
                  style: TextStyle(
                    fontFamily: 'Literata',
                    fontSize: 10,
                    color: Colors.black87,
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(width: 4),
          // Tool toggles
          ...filterableTools.map((tool) {
            final active = activeFilters.contains(tool);
            return Padding(
              padding: const EdgeInsets.only(right: 4),
              child: GestureDetector(
                onTap: () => onFilterToggle(tool),
                child: Container(
                  width: 32,
                  height: 32,
                  decoration: BoxDecoration(
                    color: active ? Colors.black12 : Colors.transparent,
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Center(child: ToolIcon(tool: tool, size: 16)),
                ),
              ),
            );
          }),
          // Comments-only toggle
          GestureDetector(
            onTap: onToggleCommentsOnly,
            child: Container(
              width: 32,
              height: 32,
              decoration: BoxDecoration(
                color: filterCommentsOnly ? Colors.black12 : Colors.transparent,
                borderRadius: BorderRadius.circular(6),
              ),
              child: const Center(
                child: Icon(Icons.chat_bubble_outline,
                    size: 16, color: Colors.black87),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

// ─── Sectioned list ───────────────────────────────────────────────────────────

class _SectionedList extends StatelessWidget {
  final List<Annotation> annotations;
  final List<AnnotationTool> filterableTools;
  final String Function(AnnotationTool) toolLabel;
  final void Function(Annotation) onTap;
  final void Function(Annotation) onLongPress;
  final Future<void> Function(Annotation) onDelete;
  final Future<bool?> Function(Annotation) confirmDelete;
  final String? highlightedAnnotationId;
  final bool editMode;
  final Set<String> selected;
  final void Function(String id) onToggleSelect;

  const _SectionedList({
    required this.annotations,
    required this.filterableTools,
    required this.toolLabel,
    required this.onTap,
    required this.onLongPress,
    required this.onDelete,
    required this.confirmDelete,
    this.highlightedAnnotationId,
    this.editMode = false,
    this.selected = const {},
    this.onToggleSelect = _noOp,
  });

  static void _noOp(String _) {}

  static const _sectionTitleStyle = TextStyle(
    fontFamily: 'Source Sans 3',
    fontSize: 13,
    fontWeight: FontWeight.bold,
    color: Colors.black87,
  );

  static const _sectionCountStyle = TextStyle(
    fontFamily: 'Source Sans 3',
    fontSize: 11,
    color: Colors.black45,
  );

  @override
  Widget build(BuildContext context) {
    // Group by tool (excluding comment tool), preserving document order
    final grouped = <AnnotationTool, List<Annotation>>{};
    for (final a in annotations) {
      if (a.tool != AnnotationTool.comment) {
        (grouped[a.tool] ??= []).add(a);
      }
    }

    // Tool sections in filterableTools order, skipping comment and empty groups
    final toolSections = filterableTools
        .where((t) => t != AnnotationTool.comment && grouped.containsKey(t))
        .toList();

    // Comments section: all annotations with a non-empty note, sorted by position
    final commented = annotations
        .where((a) => a.note != null && a.note!.isNotEmpty)
        .toList();

    final totalSections = toolSections.length + (commented.isNotEmpty ? 1 : 0);

    if (totalSections == 0) {
      return const Center(
        child: Text(
          'No annotations match filter',
          style: TextStyle(
            fontFamily: 'Literata',
            fontSize: 14,
            color: Colors.black38,
          ),
        ),
      );
    }

    return ListView.builder(
      itemCount: totalSections,
      itemBuilder: (_, i) {
        // Comments section is always last
        if (i == toolSections.length) {
          return Material(
            color: const Color(0xFFF5F0E8),
            child: ExpansionTile(
              leading: const Icon(Icons.chat_bubble_outline,
                  size: 16, color: Colors.black87),
              title: const Text('Comments', style: _sectionTitleStyle),
              trailing: Text('${commented.length}', style: _sectionCountStyle),
              initiallyExpanded: true,
              tilePadding: const EdgeInsets.symmetric(horizontal: 12),
              children: commented
                  .map((a) => _AnnotationTile(
                        annotation: a,
                        onTap: onTap,
                        onLongPress: onLongPress,
                        onDelete: onDelete,
                        confirmDelete: confirmDelete,
                        showToolIcon: true,
                        isHighlighted: a.id == highlightedAnnotationId,
                        editMode: editMode,
                        isSelected: selected.contains(a.id),
                        onToggleSelect: onToggleSelect,
                      ))
                  .toList(),
            ),
          );
        }

        final tool = toolSections[i];
        final items = grouped[tool]!;
        return Material(
          color: const Color(0xFFF5F0E8),
          child: ExpansionTile(
            leading: ToolIcon(tool: tool, size: 16),
            title: Text(toolLabel(tool), style: _sectionTitleStyle),
            trailing: Text('${items.length}', style: _sectionCountStyle),
            initiallyExpanded: true,
            tilePadding: const EdgeInsets.symmetric(horizontal: 12),
            children: items
                .map((a) => _AnnotationTile(
                      annotation: a,
                      onTap: onTap,
                      onLongPress: onLongPress,
                      onDelete: onDelete,
                      confirmDelete: confirmDelete,
                      isHighlighted: a.id == highlightedAnnotationId,
                      editMode: editMode,
                      isSelected: selected.contains(a.id),
                      onToggleSelect: onToggleSelect,
                    ))
                .toList(),
          ),
        );
      },
    );
  }
}

// ─── Annotation tile ──────────────────────────────────────────────────────────

class _AnnotationTile extends StatefulWidget {
  final Annotation annotation;
  final void Function(Annotation) onTap;
  final void Function(Annotation) onLongPress;
  final Future<void> Function(Annotation) onDelete;
  final Future<bool?> Function(Annotation) confirmDelete;
  final bool showToolIcon;
  final bool isHighlighted;
  final bool editMode;
  final bool isSelected;
  final void Function(String id) onToggleSelect;

  const _AnnotationTile({
    required this.annotation,
    required this.onTap,
    required this.onLongPress,
    required this.onDelete,
    required this.confirmDelete,
    this.showToolIcon = false,
    this.isHighlighted = false,
    this.editMode = false,
    this.isSelected = false,
    this.onToggleSelect = _noOp,
  });

  static void _noOp(String _) {}

  @override
  State<_AnnotationTile> createState() => _AnnotationTileState();
}

class _AnnotationTileState extends State<_AnnotationTile> {
  bool _noteExpanded = false;

  @override
  Widget build(BuildContext context) {
    final annotation = widget.annotation;
    final onTap = widget.onTap;
    final onLongPress = widget.onLongPress;
    final onDelete = widget.onDelete;
    final confirmDelete = widget.confirmDelete;
    final showToolIcon = widget.showToolIcon;
    final isHighlighted = widget.isHighlighted;
    final editMode = widget.editMode;
    final isSelected = widget.isSelected;
    final onToggleSelect = widget.onToggleSelect;
    final tag = annotation.tag;
    final note = annotation.note;
    final pct = '${(annotation.position * 100).round()}%';

    Widget tile = Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: editMode
            ? () => onToggleSelect(annotation.id)
            : () => onTap(annotation),
        onLongPress: editMode ? null : () => onLongPress(annotation),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (editMode) ...[
                Checkbox(
                  value: isSelected,
                  onChanged: (_) => onToggleSelect(annotation.id),
                  activeColor: Colors.black87,
                  materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  visualDensity: VisualDensity.compact,
                ),
                const SizedBox(width: 4),
              ],
              // Tool icon (Comments section only)
              if (showToolIcon) ...[
                ToolIcon(tool: annotation.tool, size: 12),
                const SizedBox(width: 6),
              ],
              // Tag chip (optional)
              if (tag != null) ...[
                Chip(
                  label: Text(tag.name),
                  labelStyle: const TextStyle(fontSize: 10),
                  labelPadding: EdgeInsets.zero,
                  visualDensity: VisualDensity.compact,
                  padding: EdgeInsets.zero,
                  side: BorderSide.none,
                  backgroundColor: Colors.black.withValues(alpha: 0.08),
                ),
                const SizedBox(width: 8),
              ],
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
                    if (note != null && note.isNotEmpty) ...[
                      const SizedBox(height: 2),
                      if (isHighlighted || _noteExpanded)
                        Container(
                          margin: const EdgeInsets.only(top: 6, bottom: 4),
                          padding: const EdgeInsets.all(8),
                          decoration: BoxDecoration(
                            color: Colors.black.withValues(alpha: 0.06),
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: Text(
                            note,
                            style: const TextStyle(
                              fontFamily: 'Source Sans 3',
                              fontSize: 12,
                              color: Colors.black87,
                            ),
                          ),
                        )
                      else
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
              // Trailing: position % + optional note indicator
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    pct,
                    style: const TextStyle(
                      fontFamily: 'Source Sans 3',
                      fontSize: 11,
                      color: Colors.black38,
                    ),
                  ),
                  if (note != null && note.isNotEmpty) ...[
                    const SizedBox(width: 4),
                    GestureDetector(
                      onTap: () => setState(() => _noteExpanded = !_noteExpanded),
                      child: Icon(
                        _noteExpanded || isHighlighted
                            ? Icons.chat_bubble
                            : Icons.chat_bubble_outline,
                        size: 11,
                        color: _noteExpanded || isHighlighted
                            ? Colors.black54
                            : Colors.black38,
                      ),
                    ),
                  ],
                ],
              ),
            ],
          ),
        ),
      ),
    );

    if (!editMode && !isEink) {
      tile = Dismissible(
        key: ValueKey(annotation.id),
        direction: DismissDirection.endToStart,
        confirmDismiss: (_) => confirmDelete(annotation),
        onDismissed: (_) => onDelete(annotation),
        background: Container(
          color: Colors.red.shade400,
          alignment: Alignment.centerRight,
          padding: const EdgeInsets.only(right: 16),
          child: const Icon(Icons.delete_outline, color: Colors.white),
        ),
        child: tile,
      );
    }

    return tile;
  }
}

// ─── Header ───────────────────────────────────────────────────────────────────

class _Header extends StatelessWidget {
  final VoidCallback onClose;
  final bool editMode;
  final VoidCallback onToggleEdit;

  const _Header({
    required this.onClose,
    required this.editMode,
    required this.onToggleEdit,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 8, 8),
      child: Row(
        children: [
          const Expanded(
            child: Text(
              'Annotations / Bookmarks',
              style: TextStyle(
                fontFamily: 'Source Sans 3',
                fontWeight: FontWeight.bold,
                fontSize: 18,
                color: Colors.black87,
              ),
            ),
          ),
          TextButton(
            onPressed: onToggleEdit,
            child: Text(
              editMode ? 'Done' : 'Edit',
              style: const TextStyle(
                fontFamily: 'Literata',
                color: Colors.black54,
                fontSize: 14,
              ),
            ),
          ),
          if (!editMode)
            IconButton(
              icon: const Icon(Icons.close, color: Colors.black87),
              onPressed: onClose,
            ),
        ],
      ),
    );
  }
}
