package com.logan.runtimeintelligence.sample

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.logan.runtimedebug.AuthorizedDebugProbe

class SampleActivity : Activity() {
    private val probe = AuthorizedDebugProbe("OwnedSampleApp")
    private var syntheticOperationCount = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val output = TextView(this).apply {
            text = "Authorised Debug SDK sample\nOnly synthetic metrics and this process' memory are reported."
            textSize = 16f
            setPadding(24, 24, 24, 24)
        }
        val button = Button(this).apply {
            text = "Record synthetic operation + memory snapshot"
            setOnClickListener {
                syntheticOperationCount++
                probe.recordMetric("synthetic.operation_count", syntheticOperationCount)
                val memory = probe.memorySnapshot()
                output.text = "Synthetic operations: $syntheticOperationCount\n" +
                    "Java heap: ${memory.javaHeapUsedBytes} / ${memory.javaHeapLimitBytes} bytes\n" +
                    "Process PSS: ${memory.processPssKb} KB\n" +
                    "Native heap: ${memory.nativeHeapAllocatedBytes} bytes\n\n" +
                    "No network payloads, credentials or other apps' memory are collected."
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(output)
            addView(button)
        })
    }
}
