# Changelog

All notable changes to this project will be documented in this file.

## [1.0.0] - 2026-11-09

### Added

- Android and iOS implementations of the `Scanner.Scan` bridge function that
  `Native\Mobile\Scanner` (nativephp/mobile core) calls but core registers on neither platform.
- Full-screen scanner with the caller's prompt, a torch toggle, the last value scanned and a
  Done/Cancel button.
- Single mode closes on the first code; continuous mode stays open and reports every code,
  ignoring the same value while it stays in view and for 1.5 seconds after.
- Beep and vibration on each accepted scan.
- `CodeScanned {data, format, id}` with the same lowercase format names the caller asks with;
  `ScannerCancelled {cancelled, reason, id}` with reason `cancelled`, `done`,
  `permission_denied` or `unavailable`.
- Android: CameraX 1.4.1 with the bundled ML Kit barcode model (`barcode-scanning` 17.3.0), so
  it works on phones without Google Play services. Camera permission asked through the
  scanner fragment's `RequestPermission` launcher.
- iOS: `AVCaptureMetadataOutput`; camera permission via `AVCaptureDevice.requestAccess`.
- Manifest and source contract tests (`tests/PluginTest.php`).
