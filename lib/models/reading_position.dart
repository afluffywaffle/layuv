import 'annotation.dart';

class ReadingPosition {
  final ReadingMode mode;
  final int page;
  final double scrollOffset;

  const ReadingPosition({
    required this.mode,
    required this.page,
    required this.scrollOffset,
  });

  Map<String, dynamic> toJson() => {
        'mode': mode.name,
        'page': page,
        'scrollOffset': scrollOffset,
      };

  factory ReadingPosition.fromJson(Map<String, dynamic> json) =>
      ReadingPosition(
        mode: ReadingMode.values.byName(json['mode'] as String),
        page: json['page'] as int,
        scrollOffset: (json['scrollOffset'] as num).toDouble(),
      );
}
