package com.logan.runtimeintelligence

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Lightweight landing screen.
 *
 * Device-wide RAM statistics and refresh polling were deliberately removed.
 * This activity does not query ActivityManager, collect telemetry, or start
 * background monitoring. Runtime diagnostics can be added later as explicit,
 * user-triggered features with clear privacy controls.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(32), dp(28), dp(32))
        }

        val heading = TextView(this).apply {
            text = "Runtime Intelligence"
            textSize = 25f
            gravity = Gravity.CENTER
        }
        val status = TextView(this).apply {
            text = "Device statistics are disabled. No device RAM telemetry is being collected."
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }

        root.addView(
            heading,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(
            status,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(root)
    }
}
