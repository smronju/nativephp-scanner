# smronju/nativephp-scanner

Implements NativePHP Mobile's `Scanner` on Android and iOS. nativephp/mobile v4
ships the PHP side (`Native\Mobile\Facades\Scanner`, `PendingScanner`, the
`Scanner\*` events) but registers the `Scanner.Scan` bridge function on neither
platform. This plugin registers it; it adds no PHP API of its own.

| | Android | iOS |
| --- | --- | --- |
| Camera | CameraX 1.4.1 | AVFoundation |
| Decoder | ML Kit `barcode-scanning` 17.3.0 (bundled model, no Google Play services needed) | `AVCaptureMetadataOutput` |
| Minimum | API 26 | iOS 15 |

## Use

```php
use Native\Mobile\Attributes\On;
use Native\Mobile\Events\Scanner\CodeScanned;
use Native\Mobile\Events\Scanner\ScannerCancelled;
use Native\Mobile\Facades\Scanner;

$scan = Scanner::scan()
    ->prompt(__('Scan the product barcode'))
    ->formats(['ean13', 'code128', 'qr'])
    ->continuous();

$this->scanId = $scan->getId();
$scan->scan();

#[On(CodeScanned::class)]
public function scanned(string $data, string $format, ?string $id = null): void
{
    if ($id !== $this->scanId) {
        return;
    }
    // ...
}

#[On(ScannerCancelled::class)]
public function closed(bool $cancelled = true, ?string $reason = null, ?string $id = null): void
{
    // $reason: cancelled, done, permission_denied or unavailable
}
```

Check that the plugin is in the build with `nativephp_can('Scanner.Scan')`.

## Contract

`Scanner.Scan` receives `{prompt, continuous, formats, id, event}` from
`PendingScanner::scan()`, returns at once and opens a full-screen scanner.

- **Formats:** `qr`, `ean13`, `ean8`, `code128`, `code39`, `upca`, `upce`, `all`.
  `CodeScanned.format` uses the same lowercase names (`all` can also report
  `code93`, `codabar`, `itf`, `datamatrix`, `pdf417`, `aztec`).
- **Single mode:** closes on the first code and dispatches
  `CodeScanned {data, format, id}`.
- **Continuous mode:** stays open and dispatches `CodeScanned` for every code.
  The same value is ignored while it stays in view and for 1.5 seconds after it
  was last seen, so a box held in front of the camera counts once, but showing
  it again counts again. Each accepted scan beeps, vibrates and shows the value
  (with a running count) on screen.
- **Closing without a code** (back, Cancel, Done) dispatches
  `ScannerCancelled {cancelled: true, reason, id}`; `reason` is `done` when a
  continuous scan accepted at least one code, otherwise `cancelled`.
- **Permission:** asks for the camera the first time; if refused, dispatches
  `ScannerCancelled` with `reason: permission_denied`. No camera, or one that
  fails to start, gives `reason: unavailable`.
- One scanner at a time: a second call while one is open is answered with
  `ScannerCancelled` (`cancelled`) for the new id.
- The screen shows the caller's prompt (pass it translated); the button labels
  (`Cancel`, `Done`, `Torch`) are English. A torch toggle appears when the
  camera has a flash.
- iOS reads UPC-A as a 13-digit EAN starting with 0; when `upca` or `all` was
  asked for, it is reported as `upca` with 12 digits, as on Android.

## Manifest

- Android: `CAMERA`, `VIBRATE`; `android.hardware.camera` (not required);
  CameraX 1.4.1 and `com.google.mlkit:barcode-scanning:17.3.0`; API 26+. The
  scanner is a full-screen `DialogFragment` over the app's activity, so no
  manifest activity is added.
- iOS: `NSCameraUsageDescription` ("Scans product barcodes and invoice QR
  codes."); iOS 15+.

## Install

Add it to `composer.json` and list `ScannerServiceProvider` in the app's
`NativeServiceProvider::plugins()` (`php artisan native:plugin:register`).
`php artisan native:plugin:validate` checks the manifest.

## Tests

`tests/PluginTest.php` checks the manifest and sources against the contract
core calls. Run with `composer install && composer test`.
