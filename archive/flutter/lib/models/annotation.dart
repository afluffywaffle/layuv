enum ReadingMode { scroll, screenFlip, pageFlip }

enum AnnotationTool {
  highlight,
  underline,
  doubleUnderline,
  strikethrough,
  wavyUnderline,
  bookmark,
  inkAnnotation,
  comment, // opens panel with no pre-selected tool decoration
}

enum AnnotationTag { voice, pacing, continuity, query }

String newId() => DateTime.now().microsecondsSinceEpoch.toString();

class Annotation {
  final String id;
  final String selectedText;
  final String prefix;
  final String suffix;
  final AnnotationTool tool;
  final String? note;
  final AnnotationTag? tag;
  final DateTime timestamp;
  final double position;
  final bool hasInk;

  Annotation({
    required this.id,
    required this.selectedText,
    required this.prefix,
    required this.suffix,
    required this.tool,
    this.note,
    this.tag,
    required this.timestamp,
    this.position = 0.0,
    this.hasInk = false,
  });

  Annotation copyWith({
    String? note,
    AnnotationTag? tag,
    AnnotationTool? tool,
    double? position,
    bool? hasInk,
  }) =>
      Annotation(
        id: id,
        selectedText: selectedText,
        prefix: prefix,
        suffix: suffix,
        tool: tool ?? this.tool,
        note: note ?? this.note,
        tag: tag ?? this.tag,
        timestamp: timestamp,
        position: position ?? this.position,
        hasInk: hasInk ?? this.hasInk,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'selectedText': selectedText,
        'prefix': prefix,
        'suffix': suffix,
        'tool': tool.name,
        'note': note,
        'tag': tag?.name,
        'timestamp': timestamp.toIso8601String(),
        'position': position,
        'hasInk': hasInk,
      };

  factory Annotation.fromJson(Map<String, dynamic> json) {
    final toolName = json['tool'] as String? ??
        json['toolType'] as String? ?? // backwards compat with old records
        AnnotationTool.highlight.name;
    return Annotation(
      id: json['id'] as String? ?? json['timestamp'] as String, // fallback for old records without id
      selectedText: json['selectedText'] as String,
      prefix: json['prefix'] as String,
      suffix: json['suffix'] as String,
      tool: AnnotationTool.values.byName(toolName),
      note: json['note'] as String?,
      tag: json['tag'] == null
          ? null
          : AnnotationTag.values
              .where((t) => t.name == json['tag'])
              .firstOrNull,
      timestamp: DateTime.parse(json['timestamp'] as String),
      position: (json['position'] as num?)?.toDouble() ?? 0.0,
      hasInk: json['hasInk'] as bool? ?? false,
    );
  }
}
