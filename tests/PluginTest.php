<?php

/**
 * The plugin is native code the PHP suite can't run; these tests hold its
 * manifest and sources to the contract nativephp/mobile core's
 * Native\Mobile\PendingScanner calls and the events it expects back.
 */
function scannerPlugin(string $path = ''): string
{
    return dirname(__DIR__).($path === '' ? '' : '/'.$path);
}

/** @return array<string, mixed> */
function scannerManifest(): array
{
    return json_decode((string) file_get_contents(scannerPlugin('nativephp.json')), true, flags: JSON_THROW_ON_ERROR);
}

/** nativephp/mobile core: this repo's own vendor, or the app's when installed there. */
function scannerCore(string $path): ?string
{
    foreach (['vendor/nativephp/mobile/', '../../nativephp/mobile/'] as $base) {
        if (is_file(scannerPlugin($base.$path))) {
            return scannerPlugin($base.$path);
        }
    }

    return null;
}

/** @return array<string, string> */
function scannerSources(): array
{
    return [
        'android' => (string) file_get_contents(scannerPlugin('resources/android/ScannerFunctions.kt')),
        'ios' => (string) file_get_contents(scannerPlugin('resources/ios/ScannerFunctions.swift')),
    ];
}

describe('Manifest', function () {
    it('names the plugin and its namespace', function () {
        $manifest = scannerManifest();

        expect($manifest['name'])->toBe('smronju/nativephp-scanner')
            ->and($manifest['version'])->toBe('1.0.0')
            ->and($manifest['namespace'])->toBe('Scanner')
            ->and($manifest['platforms'])->toBe(['android', 'ios'])
            ->and($manifest['service_provider'])->toBe('Smronju\\NativephpScanner\\ScannerServiceProvider');
    });

    it('declares the one bridge function core PendingScanner calls, on both platforms', function () {
        $functions = scannerManifest()['bridge_functions'];

        expect(array_column($functions, 'name'))->toBe(['Scanner.Scan'])
            ->and($functions[0]['android'])->toBe('com.smronju.plugins.scanner.ScannerFunctions.Scan')
            ->and($functions[0]['ios'])->toBe('ScannerFunctions.Scan');
    });

    it('matches the name and parameters core sends', function () {
        $core = (string) file_get_contents((string) scannerCore('src/PendingScanner.php'));

        expect($core)->toContain("'Scanner.Scan'");

        foreach (['prompt', 'continuous', 'formats', 'id', 'event'] as $parameter) {
            expect($core)->toContain("'{$parameter}' =>");
        }

        foreach (['qr', 'ean13', 'ean8', 'code128', 'code39', 'upca', 'upce', 'all'] as $format) {
            expect($core)->toContain("'{$format}'");
        }
    })->skip(fn () => scannerCore('src/PendingScanner.php') === null, 'nativephp/mobile is not installed');

    it('declares the camera permission, a non-required camera feature and the bundled ML Kit model', function () {
        $android = scannerManifest()['android'];

        expect($android['permissions'])->toContain('android.permission.CAMERA')
            ->and($android['features'])->toBe([['name' => 'android.hardware.camera', 'required' => false]])
            ->and($android['dependencies']['implementation'])->toContain('com.google.mlkit:barcode-scanning:17.3.0')
            ->and($android['dependencies']['implementation'])->toContain('androidx.camera:camera-view:1.4.1')
            ->and($android['min_version'])->toBe(26);

        // The Play-services "thin" client needs Google Play services on the phone.
        expect(implode(' ', $android['dependencies']['implementation']))->not->toContain('play-services-mlkit-barcode-scanning');
    });

    it('explains to iOS users why the app wants the camera', function () {
        $ios = scannerManifest()['ios'];

        expect($ios['info_plist']['NSCameraUsageDescription'])->toBe('Scans product barcodes and invoice QR codes.')
            ->and($ios['min_version'])->toBe('15.0');
    });

    it('lists the core events it dispatches, and they exist in core', function () {
        $events = scannerManifest()['events'];

        expect($events)->toBe([
            'Native\\Mobile\\Events\\Scanner\\CodeScanned',
            'Native\\Mobile\\Events\\Scanner\\ScannerCancelled',
        ]);

        expect(scannerCore('src/Events/Scanner/CodeScanned.php'))->not->toBeNull()
            ->and(scannerCore('src/Events/Scanner/ScannerCancelled.php'))->not->toBeNull();
    })->skip(fn () => scannerCore('src/PendingScanner.php') === null, 'nativephp/mobile is not installed');

    it('is a nativephp-plugin package pointing at its manifest', function () {
        $composer = json_decode((string) file_get_contents(scannerPlugin('composer.json')), true, flags: JSON_THROW_ON_ERROR);

        expect($composer['name'])->toBe('smronju/nativephp-scanner')
            ->and($composer['type'])->toBe('nativephp-plugin')
            ->and($composer['extra']['nativephp']['manifest'])->toBe('nativephp.json')
            ->and($composer['extra']['laravel']['providers'])->toBe(['Smronju\\NativephpScanner\\ScannerServiceProvider'])
            ->and($composer['autoload']['psr-4'])->toBe(['Smronju\\NativephpScanner\\' => 'src/']);
    });
});

describe('Native code', function () {
    it('has the bridge function class on each platform', function () {
        ['android' => $kotlin, 'ios' => $swift] = scannerSources();

        expect($kotlin)->toContain('package com.smronju.plugins.scanner')
            ->and($kotlin)->toContain('class Scan(private val activity: FragmentActivity) : BridgeFunction')
            ->and($swift)->toContain('class Scan: BridgeFunction');
    });

    it('reads every parameter core sends', function () {
        foreach (scannerSources() as $code) {
            foreach (['prompt', 'continuous', 'formats', 'id', 'event'] as $parameter) {
                expect($code)->toContain("parameters[\"{$parameter}\"]");
            }
        }
    });

    it('dispatches the events under their class names with the constructor field names', function () {
        foreach (scannerSources() as $code) {
            expect($code)->toContain('"Native\\\\Mobile\\\\Events\\\\Scanner\\\\CodeScanned"')
                ->and($code)->toContain('"Native\\\\Mobile\\\\Events\\\\Scanner\\\\ScannerCancelled"');

            foreach (['data', 'format', 'id', 'cancelled', 'reason'] as $field) {
                expect($code)->toContain("\"{$field}\"");
            }
        }
    });

    it('closes with the reason codes the app translates', function () {
        foreach (scannerSources() as $code) {
            foreach (['cancelled', 'done', 'permission_denied'] as $reason) {
                expect($code)->toContain("\"{$reason}\"");
            }
        }
    });

    it('maps every format name core accepts, and reports the same lowercase names', function () {
        ['android' => $kotlin, 'ios' => $swift] = scannerSources();

        foreach (['qr', 'ean13', 'ean8', 'code128', 'code39', 'upca', 'upce'] as $format) {
            expect($kotlin)->toContain("\"{$format}\" to Barcode.FORMAT_")
                ->and($kotlin)->toContain("-> \"{$format}\"")
                ->and($swift)->toContain("\"{$format}\": [")
                ->and($swift)->toContain("(\"{$format}\", ");
        }

        expect($kotlin)->toContain('Barcode.FORMAT_ALL_FORMATS')
            ->and($swift)->toContain('formats.contains("all")');
    });

    it('ignores the same code for 1.5 seconds in continuous mode', function () {
        ['android' => $kotlin, 'ios' => $swift] = scannerSources();

        expect($kotlin)->toContain('REPEAT_WINDOW_MS = 1_500L')
            ->and($swift)->toContain('scannerRepeatWindowSeconds: TimeInterval = 1.5');
    });

    it('beeps and vibrates on every accepted scan', function () {
        ['android' => $kotlin, 'ios' => $swift] = scannerSources();

        expect($kotlin)->toContain('ToneGenerator.TONE_PROP_BEEP')->toContain('VibrationEffect.createOneShot')
            ->and($swift)->toContain('AudioServicesPlaySystemSound')->toContain('notificationOccurred(.success)');
    });

    it('asks for the camera before opening it', function () {
        ['android' => $kotlin, 'ios' => $swift] = scannerSources();

        expect($kotlin)->toContain('override fun onCreate')
            ->and($kotlin)->toContain('registerForActivityResult(ActivityResultContracts.RequestPermission())')
            ->and($kotlin)->toContain('Manifest.permission.CAMERA')
            ->and($swift)->toContain('AVCaptureDevice.requestAccess(for: .video)');
    });

    it('uses ML Kit on Android and AVFoundation metadata output on iOS', function () {
        ['android' => $kotlin, 'ios' => $swift] = scannerSources();

        expect($kotlin)->toContain('import com.google.mlkit.vision.barcode.BarcodeScanning')
            ->and($kotlin)->toContain('ProcessCameraProvider')
            ->and($swift)->toContain('AVCaptureMetadataOutput()');
    });
});

it('has a service provider that loads, and no PHP of its own beyond it', function () {
    expect(glob(scannerPlugin('src/*')))->toBe([scannerPlugin('src/ScannerServiceProvider.php')]);

    require_once scannerPlugin('src/ScannerServiceProvider.php');

    expect(is_subclass_of('Smronju\\NativephpScanner\\ScannerServiceProvider', 'Illuminate\\Support\\ServiceProvider'))->toBeTrue();
})->skip(fn () => ! class_exists('Illuminate\\Support\\ServiceProvider'), 'illuminate/support is not installed');
