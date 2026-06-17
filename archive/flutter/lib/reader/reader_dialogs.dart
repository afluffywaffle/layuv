import 'package:flutter/material.dart';
import '../utils/platform_utils.dart';

/// Confirmation dialog for deleting a single annotation, with a per-document
/// "don't ask again" checkbox.
///
/// Presentation only — the caller persists the "don't ask again" preference and
/// performs the actual delete. Pops `null` if cancelled or dismissed, or the
/// chosen `dontAskAgain` bool if the user confirmed the delete.
class DeleteAnnotationDialog extends StatefulWidget {
  const DeleteAnnotationDialog({super.key});

  @override
  State<DeleteAnnotationDialog> createState() => _DeleteAnnotationDialogState();
}

class _DeleteAnnotationDialogState extends State<DeleteAnnotationDialog> {
  bool _dontAskAgain = false;

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      backgroundColor: const Color(0xFFF5F0E8),
      title: const Text('Delete annotation?',
          style: TextStyle(fontFamily: 'Literata')),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text('This can’t be undone.',
              style: TextStyle(fontFamily: 'Literata', color: Colors.black54)),
          const SizedBox(height: 16),
          InkWell(
            onTap: () => setState(() => _dontAskAgain = !_dontAskAgain),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Row(
                children: [
                  Icon(
                    _dontAskAgain
                        ? Icons.check_box
                        : Icons.check_box_outline_blank,
                    size: 22,
                    color: Colors.black87,
                  ),
                  const SizedBox(width: 10),
                  const Expanded(
                    child: Text("Don't ask again for this document",
                        style: TextStyle(
                            fontFamily: 'Literata', color: Colors.black87)),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(null),
          child: const Text('Cancel',
              style: TextStyle(fontFamily: 'Literata', color: Colors.black54)),
        ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(_dontAskAgain),
          child: const Text('Delete',
              style: TextStyle(fontFamily: 'Literata', color: Colors.black87)),
        ),
      ],
    );
  }
}

/// Confirmation dialog shown before closing the document. Pops `true` to close,
/// `false`/`null` to stay. Presentation only — the caller handles the flush.
class CloseDocumentDialog extends StatelessWidget {
  const CloseDocumentDialog({super.key});

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      backgroundColor: const Color(0xFFF5F0E8),
      title: const Text('Close document?',
          style: TextStyle(fontFamily: 'Literata')),
      content: const Text('Your annotations are saved automatically.',
          style: TextStyle(fontFamily: 'Literata', color: Colors.black54)),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(false),
          child: const Text('Stay',
              style: TextStyle(fontFamily: 'Literata', color: Colors.black54)),
        ),
        TextButton(
          onPressed: () => Navigator.of(context).pop(true),
          child: const Text('Close',
              style: TextStyle(fontFamily: 'Literata', color: Colors.black87)),
        ),
      ],
    );
  }
}

/// Non-blocking "Saving…" indicator shown while a coalesced annotation save
/// flushes on close. The encode runs on a background isolate (DocxStore), so the
/// UI isolate stays live and this renders (a spinner on desktop; static text on
/// e-ink, which does not animate).
class SavingDialog extends StatelessWidget {
  const SavingDialog({super.key});

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      backgroundColor: const Color(0xFFF5F0E8),
      content: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (!isEink) ...[
            const SizedBox(
              width: 20,
              height: 20,
              child: CircularProgressIndicator(
                  strokeWidth: 2, color: Colors.black54),
            ),
            const SizedBox(width: 16),
          ],
          const Text('Saving…',
              style: TextStyle(fontFamily: 'Literata', color: Colors.black87)),
        ],
      ),
    );
  }
}
