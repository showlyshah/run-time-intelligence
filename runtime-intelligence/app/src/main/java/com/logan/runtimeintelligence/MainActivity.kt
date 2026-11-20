package com.logan.runtimeintelligence

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var memorySummary: TextView
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 40)
        }

        val heading = TextView(this).apply {
            text = "Runtime Intelligence"
            textSize = 26f
        }
        status = TextView(this).apply {
            text = "Device memory dashboard"
            textSize = 15f
            setPadding(0, 16, 0, 24)
        }
        memorySummary = TextView(this).apply {
            textSize = 18f
            setPadding(0, 0, 0, 24)
            text = "Device-level statistics collection is disabled."
        }
        val refresh = Button(this).apply {
            text = "Refresh status"
            setOnClickListener { refreshStatus() }
        }

        root.addView(heading)
        root.addView(status)
        root.addView(memorySummary)
        root.addView(refresh)
        setContentView(root)
    }

    /**
     * Keep the original dashboard layout and refresh control, but do not read
     * or generate statistics about this device.
     */
    private fun refreshStatus() {
        status.text = "Device statistics collection remains disabled."
        memorySummary.text = "No device-level RAM statistics are collected."
    }
}
