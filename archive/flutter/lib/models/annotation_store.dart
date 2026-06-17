import 'dart:convert';
import 'dart:io';
import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import 'annotation.dart';
import 'annotation_store_interface.dart';
import 'reading_position.dart';

// TODO: Replace with Security-Scoped Bookmarks (Swift method channel) so the
// sidecar saves next to the source file rather than in app support.

typedef _Sidecar = ({List<Annotation> annotations, ReadingPosition? position});

class AnnotationStore implements AnnotationStoreInterface {
  final String filePath;

  // Serializes all read-modify-write operations so they never interleave.
  Future<void> _writeLock = Future.value();

  AnnotationStore({required this.filePath});

  Future<T> _serialized<T>(Future<T> Function() fn) {
    final next = _writeLock.then((_) => fn());
    // Chain on the void future so a failure in fn() doesn't break the lock.
    _writeLock = next.then((_) {}, onError: (_) {});
    return next;
  }

  Future<File> get _sidecarFile async {
    final appSupport = await getApplicationSupportDirectory();
    final dir = Directory('${appSupport.path}/leamh_annotations');
    if (!await dir.exists()) await dir.create(recursive: true);
    final sanitized =
        filePath.replaceAll('/', '_').replaceAll(RegExp(r'^_+'), '');
    return File('${dir.path}/${sanitized}_leamh.json');
  }

  Future<_Sidecar> _read() async {
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
  }

  Future<void> _write(_Sidecar sidecar) async {
    final file = await _sidecarFile;
    final tmp = File('${file.path}.tmp');
    try {
      await tmp.writeAsString(jsonEncode({
        'annotations': sidecar.annotations.map((a) => a.toJson()).toList(),
        'lastPosition': sidecar.position?.toJson(),
      }));
      await tmp.rename(file.path);
    } catch (e) {
      await tmp.delete().catchError((_) => File(''));
      rethrow;
    }
  }

  @override
  Future<List<Annotation>> loadAnnotations() async {
    try {
      return (await _read()).annotations;
    } catch (e) {
      debugPrint('AnnotationStore read error: $e');
      return [];
    }
  }

  @override
  Future<ReadingPosition?> loadPosition() async {
    try {
      return (await _read()).position;
    } catch (e) {
      debugPrint('AnnotationStore read error: $e');
      return null;
    }
  }

  @override
  Future<void> saveAnnotation(Annotation annotation) =>
      _serialized(() async {
        final sidecar = await _read();
        final idx = sidecar.annotations.indexWhere((a) => a.id == annotation.id);
        final updated = List<Annotation>.from(sidecar.annotations);
        if (idx >= 0) {
          updated[idx] = annotation;
        } else {
          updated.add(annotation);
        }
        await _write((annotations: updated, position: sidecar.position));
      }).catchError((e) {
        debugPrint('AnnotationStore saveAnnotation error: $e');
      });

  @override
  Future<void> deleteAnnotation(String id) =>
      _serialized(() async {
        final sidecar = await _read();
        final updated = sidecar.annotations.where((a) => a.id != id).toList();
        await _write((annotations: updated, position: sidecar.position));
      }).catchError((e) {
        debugPrint('AnnotationStore deleteAnnotation error: $e');
      });

  @override
  Future<void> deleteAll(List<String> ids) =>
      _serialized(() async {
        final sidecar = await _read();
        final idSet = ids.toSet();
        final updated =
            sidecar.annotations.where((a) => !idSet.contains(a.id)).toList();
        await _write((annotations: updated, position: sidecar.position));
      }).catchError((e) {
        debugPrint('AnnotationStore deleteAll error: $e');
      });

  @override
  Future<void> savePosition(ReadingPosition position) =>
      _serialized(() async {
        final sidecar = await _read();
        await _write((annotations: sidecar.annotations, position: position));
      }).catchError((e) {
        debugPrint('AnnotationStore savePosition error: $e');
      });

  @override
  Future<void> saveInkPng(String annotationId, List<int> pngBytes) async {
    // JSON sidecar store does not support ink — no-op.
    debugPrint('AnnotationStore.saveInkPng: ink not supported on this store');
  }
}
