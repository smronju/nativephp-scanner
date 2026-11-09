<?php

namespace Smronju\NativephpScanner;

use Illuminate\Support\ServiceProvider;

/**
 * This plugin contributes no PHP runtime behaviour of its own: it supplies the
 * Android (Kotlin) and iOS (Swift) Scanner.Scan bridge function that
 * Native\Mobile\Facades\Scanner (nativephp/mobile core) already calls. The
 * provider exists only because nativephp.json requires one.
 */
class ScannerServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        //
    }
}
