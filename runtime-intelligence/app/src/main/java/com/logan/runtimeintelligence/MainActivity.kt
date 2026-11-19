package com.logan.runtimeintelligence

import android.app.Activity
import android.app.ActivityManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

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
        }
        val refresh = Button(this).apply {
            text = "Refresh memory statistics"
            setOnClickListener { refreshMemoryStats() }
        }

        root.addView(heading)
        root.addView(status)
        root.addView(memorySummary)
        root.addView(refresh)
        setContentView(root)

        refreshMemoryStats()
    }

    private fun refreshMemoryStats() {
        val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)

        val totalMb = info.totalMem / (1024.0 * 1024.0)
        val availableMb = info.availMem / (1024.0 * 1024.0)
        val usedMb = totalMb - availableMb
        val usedPercent = if (totalMb > 0) (usedMb / totalMb) * 100.0 else 0.0

        memorySummary.text = String.format(
            Locale.getDefault(),
            "Total RAM: %.0f MB\nAvailable RAM: %.0f MB\nEstimated used RAM: %.0f MB (%.1f%%)\nLow-memory state: %s",
            totalMb,
            availableMb,
            usedMb,
            usedPercent,
            if (info.lowMemory) "Yes" else "No"
        )
        status.text = "Updated just now • Device-level statistics"
    }
}
