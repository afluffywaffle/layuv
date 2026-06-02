import 'dart:io';

/// True on e-ink Android targets — disables animations and colour fills.
bool get isEink => Platform.isAndroid;

// TODO: extend to macOS touch when Apple adds trackpad-tap selection support.
bool get supportsInk => Platform.isIOS || Platform.isAndroid;
