import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'utils/pen_tappable.dart';

class EinkSettingsScreen extends StatefulWidget {
  const EinkSettingsScreen({super.key});

  @override
  State<EinkSettingsScreen> createState() => _EinkSettingsScreenState();
}

class _EinkSettingsScreenState extends State<EinkSettingsScreen> {
  String _navSide = 'both';
  bool _navReversed = false;
  String _inkRuleLines = 'none';

  @override
  void initState() {
    super.initState();
    _loadPrefs();
  }

  Future<void> _loadPrefs() async {
    final prefs = await SharedPreferences.getInstance();
    if (!mounted) return;
    setState(() {
      _navSide = prefs.getString('eink_nav_side') ?? 'both';
      _navReversed = prefs.getBool('eink_nav_reversed') ?? false;
      _inkRuleLines = prefs.getString('ink_rule_lines') ?? 'none';
    });
  }

  Future<void> _setNavSide(String value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('eink_nav_side', value);
    if (!mounted) return;
    setState(() => _navSide = value);
  }

  Future<void> _setNavReversed(bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('eink_nav_reversed', value);
    if (!mounted) return;
    setState(() => _navReversed = value);
  }

  Future<void> _setInkRuleLines(String value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('ink_rule_lines', value);
    if (!mounted) return;
    setState(() => _inkRuleLines = value);
  }

  static const _sectionStyle = TextStyle(
    fontFamily: 'SourceSans3',
    fontSize: 12,
    fontWeight: FontWeight.w600,
    color: Colors.black54,
    letterSpacing: 0.8,
  );

  static const _labelStyle = TextStyle(
    fontFamily: 'Literata',
    fontSize: 15,
    color: Colors.black87,
  );

  Widget _optionTile({
    required String label,
    required bool selected,
    required VoidCallback onTap,
  }) {
    return PenTappable(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        decoration: BoxDecoration(
          border: Border(bottom: BorderSide(color: Colors.black.withValues(alpha: 0.08))),
        ),
        child: Row(
          children: [
            Expanded(child: Text(label, style: _labelStyle)),
            if (selected)
              const Icon(Icons.check, size: 20, color: Colors.black87),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: const Color(0xFFF5F0E8),
      appBar: AppBar(
        backgroundColor: const Color(0xFFF5F0E8),
        elevation: 0,
        leading: IconButton(
          icon: const Icon(Icons.arrow_back, color: Colors.black87),
          onPressed: () => Navigator.pop(context),
        ),
        title: const Text(
          'E-ink settings',
          style: TextStyle(fontFamily: 'Literata', color: Colors.black87, fontSize: 17),
        ),
      ),
      body: ListView(
        children: [
          const Padding(
            padding: EdgeInsets.fromLTRB(16, 20, 16, 8),
            child: Text('NAVIGATION BUTTONS', style: _sectionStyle),
          ),
          Container(
            margin: const EdgeInsets.symmetric(horizontal: 16),
            decoration: BoxDecoration(
              border: Border.all(color: Colors.black.withValues(alpha: 0.12)),
              borderRadius: BorderRadius.circular(8),
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: Column(
                children: [
                  _optionTile(
                    label: 'Both sides',
                    selected: _navSide == 'both',
                    onTap: () => _setNavSide('both'),
                  ),
                  _optionTile(
                    label: 'Left side only',
                    selected: _navSide == 'left',
                    onTap: () => _setNavSide('left'),
                  ),
                  _optionTile(
                    label: 'Right side only',
                    selected: _navSide == 'right',
                    onTap: () => _setNavSide('right'),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 16),
          Container(
            margin: const EdgeInsets.symmetric(horizontal: 16),
            decoration: BoxDecoration(
              border: Border.all(color: Colors.black.withValues(alpha: 0.12)),
              borderRadius: BorderRadius.circular(8),
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: PenTappable(
                onTap: () => _setNavReversed(!_navReversed),
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                  child: Row(
                    children: [
                      Icon(
                        _navReversed ? Icons.check_box : Icons.check_box_outline_blank,
                        size: 22,
                        color: Colors.black87,
                      ),
                      const SizedBox(width: 12),
                      const Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text('Reverse nav direction (RTL)', style: _labelStyle),
                            SizedBox(height: 2),
                            Text(
                              'Right = previous, Left = next',
                              style: TextStyle(
                                fontFamily: 'SourceSans3',
                                fontSize: 12,
                                color: Colors.black54,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
          const SizedBox(height: 32),
          const Padding(
            padding: EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: Text('INK CANVAS', style: _sectionStyle),
          ),
          Container(
            margin: const EdgeInsets.symmetric(horizontal: 16),
            decoration: BoxDecoration(
              border: Border.all(color: Colors.black.withValues(alpha: 0.12)),
              borderRadius: BorderRadius.circular(8),
            ),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(8),
              child: Column(
                children: [
                  _optionTile(
                    label: 'No rule lines',
                    selected: _inkRuleLines == 'none',
                    onTap: () => _setInkRuleLines('none'),
                  ),
                  _optionTile(
                    label: 'Wide ruled',
                    selected: _inkRuleLines == 'wide',
                    onTap: () => _setInkRuleLines('wide'),
                  ),
                  _optionTile(
                    label: 'College ruled',
                    selected: _inkRuleLines == 'college',
                    onTap: () => _setInkRuleLines('college'),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 32),
        ],
      ),
    );
  }
}
