import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import 'annotation.dart';
import 'reading_position.dart';

// TODO: Replace with Security-Scoped Bookmarks (Swift method channel) so the
// sidecar saves next to the source file rather than in app support.

typedef _Sidecar = ({List<Annotation> annotations, ReadingPosition? position});

class AnnotationStore {
  final String filePath;

  AnnotationStore({required this.filePath});

  Future<File> get _sidecarFile async {
    final appSupport = await getApplicationSupportDirectory();
    final dir = Directory('${appSupport.path}/leamh_annotations');
    if (!await dir.exists()) await dir.create(recursive: true);
    final sanitized =
        filePath.replaceAll('/', '_').replaceAll(RegExp(r'^_+'), '');
    return File('${dir.path}/${sanitized}_leamh.json');
  }

  Future<_Sidecar> _read() async {
    try {
      final file = await _sidecarFile;
      if (!await file.exists()) return (annotations: <Annotation>[], position: null);
      final decoded = jsonDecode(await file.readAsString());

      // Legacy format: bare JSON array of annotations.
      if (decoded is List) {
        return (
          annotations: decoded
              .map((e) => Annotation.fromJson(e as Map<String, dynamic>))
              .toList(),
          position: null,
        );
      }

      final map = decoded as Map<String, dynamic>;
      final annotations = (map['annotations'] as List? ?? [])
          .map((e) => Annotation.fromJson(e as Map<String, dynamic>))
          .toList();
      final posJson = map['lastPosition'] as Map<String, dynamic>?;
      return (
        annotations: annotations,
        position: posJson == null ? null : ReadingPosition.fromJson(posJson),
      );
    } catch (e) {
      debugPrint('AnnotationStore read error: $e');
      return (annotations: <Annotation>[], position: null);
    }
  }

  Future<void> _write(_Sidecar sidecar) async {
    try {
      final file = await _sidecarFile;
      await file.writeAsString(jsonEncode({
        'annotations':
            sidecar.annotations.map((a) => a.toJson()).toList(),
        'lastPosition': sidecar.position?.toJson(),
      }));
    } catch (e) {
      debugPrint('AnnotationStore write error: $e');
    }
  }

  Future<List<Annotation>> loadAnnotations() async =>
      (await _read()).annotations;

  Future<ReadingPosition?> loadPosition() async =>
      (await _read()).position;

  Future<void> saveAnnotation(Annotation annotation) async {
    final sidecar = await _read();
    final idx = sidecar.annotations.indexWhere((a) => a.id == annotation.id);
    final updated = List<Annotation>.from(sidecar.annotations);
    if (idx >= 0) {
      updated[idx] = annotation;
    } else {
      updated.add(annotation);
    }
    await _write((annotations: updated, position: sidecar.position));
  }

  Future<void> deleteAnnotation(String id) async {
    final sidecar = await _read();
    final updated =
        sidecar.annotations.where((a) => a.id != id).toList();
    await _write((annotations: updated, position: sidecar.position));
  }

  Future<void> savePosition(ReadingPosition position) async {
    final sidecar = await _read();
    await _write((annotations: sidecar.annotations, position: position));
  }
}
