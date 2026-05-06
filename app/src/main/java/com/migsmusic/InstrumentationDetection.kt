package com.migsmusic

/**
 * Detects whether the process is hosting androidx instrumentation tests. The androidx.test
 * runtime only ends up on the classpath when the test APK is loaded; in production builds
 * the lookup throws ClassNotFoundException. Cheap, no setup ceremony, no build-config flag.
 *
 * Currently used to:
 *  - Skip the auto-rescan ContentObserver during tests (a stray MediaStore notification
 *    mid-test can break waitUntil timing).
 *  - Skip the player-route restore on cold start (tests are recreated repeatedly without
 *    going through the user's navigation, and would otherwise land on Player from a stale
 *    pref every time the suite starts).
 */
internal fun isInstrumentationRunning(): Boolean =
    try {
        Class.forName("androidx.test.platform.app.InstrumentationRegistry")
        true
    } catch (e: ClassNotFoundException) {
        false
    }
