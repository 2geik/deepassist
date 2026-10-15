package com.deepassist.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Short, crisp haptics that match the earcons; plain buzzes where the phone lacks primitives. */
object Haptics {

    fun listenStart(context: Context) = play(context, listOf(VibrationEffect.Composition.PRIMITIVE_CLICK to 0.7f), fallbackMs = 50)

    fun success(context: Context) = play(context, listOf(VibrationEffect.Composition.PRIMITIVE_TICK to 0.6f), fallbackMs = 25)

    fun error(context: Context) = play(
        context,
        listOf(VibrationEffect.Composition.PRIMITIVE_CLICK to 0.8f, VibrationEffect.Composition.PRIMITIVE_CLICK to 0.8f),
        fallbackMs = 90
    )

    private fun play(context: Context, primitives: List<Pair<Int, Float>>, fallbackMs: Long) {
        runCatching {
            val vibrator = vibrator(context) ?: return
            val ids = primitives.map { it.first }.toIntArray()
            if (Build.VERSION.SDK_INT >= 30 && vibrator.areAllPrimitivesSupported(*ids)) {
                val composition = VibrationEffect.startComposition()
                primitives.forEachIndexed { i, (id, scale) ->
                    composition.addPrimitive(id, scale, if (i == 0) 0 else 70)
                }
                vibrator.vibrate(composition.compose())
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(fallbackMs, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
