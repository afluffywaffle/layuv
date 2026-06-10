import 'annotation.dart';
import 'reading_position.dart';

abstract interface class AnnotationStoreInterface {
  Future<List<Annotation>> loadAnnotations();
  Future<ReadingPosition?> loadPosition();
  Future<void> saveAnnotation(Annotation annotation);
  Future<void> deleteAnnotation(String id);
  Future<void> deleteAll(List<String> ids);
  Future<void> savePosition(ReadingPosition position);
  Future<void> saveInkPng(String annotationId, List<int> pngBytes);
}
