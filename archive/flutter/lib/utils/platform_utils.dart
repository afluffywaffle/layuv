import 'dart:io';

/// True on e-ink Android targets — disables animations and colour fills.
bool get isEink => Platform.isAndroid;

// TODO: extend to macOS touch when Apple adds trackpad-tap selection support.
bool get supportsInk => Platform.isIOS || Platform.isAndroid;

/// Write [bytes] to [path] safely.
///
/// On Android (no sandbox): atomic temp+rename — `flush: true` fsyncs the temp
/// data before the rename, so a mid-write process kill leaves the original file
/// fully intact. (Residual gap: the directory entry of the rename is not itself
/// fsynced — dart:io exposes no dir-fsync — so a power loss in a one-syscall
/// window can revert to the prior valid file; it never corrupts.)
///
/// On macOS/iOS: direct write — DELIBERATELY non-atomic. The sandbox/security-
/// scoped bookmark grants access to the specific file, not its containing
/// directory, so creating a temp sibling would be rejected. The trade-off is
/// that a crash mid-write can truncate the file; mitigating that would require a
/// directory-scoped bookmark + same-dir temp+rename (see audit L2).
Future<void> safeWriteBytes(String path, List<int> bytes) async {
  if (Platform.isAndroid) {
    final tmp = File('$path.tmp');
    await tmp.writeAsBytes(bytes, flush: true);
    await tmp.rename(path);
  } else {
    await File(path).writeAsBytes(bytes, flush: true);
  }
}
