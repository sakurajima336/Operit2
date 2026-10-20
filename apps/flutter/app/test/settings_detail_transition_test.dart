import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:operit2/data/preferences/UserPreferencesManager.dart';
import 'package:operit2/ui/features/settings/components/SettingsCategoryList.dart';
import 'package:operit2/ui/features/settings/components/SettingsDetailView.dart';
import 'package:operit2/ui/features/settings/models/SettingsModels.dart';
import 'package:operit2/ui/features/settings/screens/SettingsScreen.dart';
import 'package:operit2/ui/main/TopBarController.dart';
import 'package:operit2/ui/theme/OperitGlassSurface.dart';
import 'package:operit2/ui/theme/OperitTheme.dart';

/// Verifies settings cards blur the actual background throughout navigation.
void main() {
  testWidgets('settings detail stays frosted during its entrance animation', (
    tester,
  ) async {
    tester.view.physicalSize = const Size(1100, 700);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    final captureKey = GlobalKey();
    final topBar = TopBarController();
    addTearDown(topBar.dispose);
    await tester.pumpWidget(
      OperitTheme(
        initialThemePreferenceSnapshot: ThemePreferenceSnapshot.fromJson({
          ...UserPreferencesManager.defaultThemePreferenceSnapshot.toJson(),
          'transparentSurfaceEnabled': true,
        }),
        initialThemeIsReady: false,
        unconfiguredChildEnabled: true,
        hostInteractionHostsEnabled: false,
        child: TopBarScope(
          controller: topBar,
          child: RepaintBoundary(
            key: captureKey,
            child: Stack(
              fit: StackFit.expand,
              children: const [
                CustomPaint(painter: _StripePainter()),
                SettingsScreen(initialCategory: SettingsCategory.about),
              ],
            ),
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    tester
        .widget<SettingsCategoryList>(find.byType(SettingsCategoryList))
        .onCategorySelected(SettingsCategory.globalBehavior);
    await tester.pump();
    final detail = find.byKey(
      const ValueKey<SettingsCategory>(SettingsCategory.globalBehavior),
    );
    expect(
      tester.widget<SettingsDetailView>(detail).category,
      SettingsCategory.globalBehavior,
    );
    final initialX = tester.getTopLeft(detail).dx;
    for (final elapsed in <int>[16, 64, 80]) {
      await tester.pump(Duration(milliseconds: elapsed));
      final card = find
          .descendant(of: detail, matching: find.byType(OperitGlassSurface))
          .first;
      final rect = tester.getRect(card);
      // The left padding has no text and the outgoing page has no card here.
      final position = Offset(rect.left + 8, rect.center.dy);
      await _expectBlurredPixel(tester, captureKey, position);
    }
    expect(tester.getTopLeft(detail).dx, lessThan(initialX));
    await tester.pumpAndSettle();
    final card = find
        .descendant(of: detail, matching: find.byType(OperitGlassSurface))
        .first;
    final rect = tester.getRect(card);
    await _expectBlurredPixel(
      tester,
      captureKey,
      Offset(rect.left + 8, rect.center.dy),
    );
    await tester.pumpWidget(const SizedBox.shrink());
    expect(tester.takeException(), isNull);
  });
}

/// Checks raster output so a filter reading an empty opacity layer fails.
Future<void> _expectBlurredPixel(
  WidgetTester tester,
  GlobalKey captureKey,
  Offset position,
) async {
  final boundary = tester.renderObject<RenderRepaintBoundary>(
    find.byKey(captureKey),
  );
  final localPosition = boundary.globalToLocal(position);
  final red = await tester.runAsync(() async {
    final image = await boundary.toImage();
    try {
      final data = (await image.toByteData(
        format: ui.ImageByteFormat.rawRgba,
      ))!;
      final offset =
          (localPosition.dy.toInt() * image.width + localPosition.dx.toInt()) *
          4;
      return data.getUint8(offset);
    } finally {
      image.dispose();
    }
  });
  expect(red, inInclusiveRange(60, 195));
}

class _StripePainter extends CustomPainter {
  /// Creates a high-contrast background that exposes missing backdrop blur.
  const _StripePainter();

  /// Draws stripes that blend to gray when sampled through a frosted card.
  @override
  void paint(Canvas canvas, Size size) {
    for (var x = 0.0; x < size.width; x += 4) {
      canvas.drawRect(
        Rect.fromLTWH(x, 0, 4, size.height),
        Paint()..color = (x / 4).toInt().isEven ? Colors.black : Colors.white,
      );
    }
  }

  /// Keeps the static background identical between animation frames.
  @override
  bool shouldRepaint(covariant _StripePainter oldDelegate) => false;
}
