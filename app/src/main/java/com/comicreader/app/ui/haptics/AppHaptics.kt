package com.comicreader.app.ui.haptics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Shared tactile language for ComicReader.
 *
 * This version intentionally does NOT depend on amplitude differences.
 *
 * The current test device reports:
 *     hasVibrator=true
 *     amplitudeControl=false
 *
 * That means values such as 80, 180, or 255 do not provide reliable
 * "strength" control on that hardware. Instead, each interaction gets its
 * character from pulse duration and timing.
 *
 * AndroidManifest.xml must contain:
 * <uses-permission android:name="android.permission.VIBRATE" />
 */
internal object AppHaptics {
    private const val TAG = "AppHaptics"

    /**
     * Very short single tap while crossing into the next focused card/page.
     * Used repeatedly, so this must remain the lightest effect.
     */
    fun scrollTick(view: View) {
        vibratePattern(
            view = view,
            timingsMillis = longArrayOf(
                0L,
                20L
            ),
            fallbackConstant =
                HapticFeedbackConstants.CLOCK_TICK,
            label = "scrollTick"
        )
    }

    /**
     * Slightly more deliberate single tap when the soft magnet catches.
     */
    fun magnetTick(view: View) {
        vibratePattern(
            view = view,
            timingsMillis = longArrayOf(
                0L,
                25L
            ),
            fallbackConstant =
                HapticFeedbackConstants.CLOCK_TICK,
            label = "magnetTick"
        )
    }

    /**
     * Two tiny pulses: page flips, then lightly "lands".
     *
     * This makes the page-turn feel different from ordinary carousel ticks
     * without turning it into a long buzz.
     */
    fun pageTurn(view: View) {
        vibratePattern(
            view = view,
            timingsMillis = longArrayOf(
                0L,
                22L,
                28L,
                14L
            ),
            fallbackConstant =
                HapticFeedbackConstants.VIRTUAL_KEY,
            label = "pageTurn"
        )
    }

    /**
     * A clean two-stage confirmation for entering the reader.
     *
     * It is still shorter and less aggressive than the v11 diagnostic pulse,
     * but remains intentionally more noticeable than scrolling.
     */
    fun comicOpen(view: View) {
        vibratePattern(
            view = view,
            timingsMillis = longArrayOf(
                0L,
                30L,
                34L,
                16L
            ),
            fallbackConstant =
                HapticFeedbackConstants.VIRTUAL_KEY,
            label = "comicOpen"
        )
    }

    private fun vibratePattern(
        view: View,
        timingsMillis: LongArray,
        fallbackConstant: Int,
        label: String
    ) {
        val vibrator =
            vibratorFor(
                view.context
            )

        if (
            vibrator == null ||
            !vibrator.hasVibrator()
        ) {
            val fallbackResult =
                view.performHapticFeedback(
                    fallbackConstant
                )

            Log.d(
                TAG,
                "$label fallback-only result=$fallbackResult vibratorAvailable=false"
            )
            return
        }

        try {
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        timingsMillis,
                        -1
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    timingsMillis,
                    -1
                )
            }

            val amplitudeControl =
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.O
                ) {
                    vibrator.hasAmplitudeControl()
                } else {
                    false
                }

            Log.d(
                TAG,
                "$label pattern=${timingsMillis.joinToString(prefix = "[", postfix = "]")} " +
                        "hasVibrator=${vibrator.hasVibrator()} " +
                        "amplitudeControl=$amplitudeControl"
            )
        } catch (
            securityError: SecurityException
        ) {
            val fallbackResult =
                view.performHapticFeedback(
                    fallbackConstant
                )

            Log.w(
                TAG,
                "$label direct vibration blocked. Add android.permission.VIBRATE. " +
                        "fallbackResult=$fallbackResult",
                securityError
            )
        } catch (
            error: Throwable
        ) {
            val fallbackResult =
                view.performHapticFeedback(
                    fallbackConstant
                )

            Log.w(
                TAG,
                "$label direct vibration failed; fallbackResult=$fallbackResult",
                error
            )
        }
    }

    private fun vibratorFor(
        context: Context
    ): Vibrator? {
        return if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.S
        ) {
            context
                .getSystemService(
                    VibratorManager::class.java
                )
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(
                Context.VIBRATOR_SERVICE
            ) as? Vibrator
        }
    }
}

/**
 * Prevent a fast fling from producing a machine-gun stream of ticks.
 */
internal class HapticThrottle(
    private val minimumIntervalMillis: Long = 60L
) {
    private var lastHapticAtMillis =
        -minimumIntervalMillis

    fun tryAcquire(): Boolean {
        val now =
            SystemClock.elapsedRealtime()

        if (
            now - lastHapticAtMillis <
            minimumIntervalMillis
        ) {
            return false
        }

        lastHapticAtMillis =
            now
        return true
    }
}
