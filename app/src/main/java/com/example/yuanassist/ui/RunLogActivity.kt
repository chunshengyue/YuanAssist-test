package com.example.yuanassist.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.yuanassist.R
import com.example.yuanassist.utils.RunLogger

class RunLogActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RunLogger.init(this)
        setContentView(R.layout.activity_run_log)

        val tvLog = findViewById<TextView>(R.id.tv_run_log_content)
        val logs = RunLogger.getAllLogs()
        val logPath = RunLogger.getLogFilePath()

        tvLog.text = if (logs.isNotBlank()) {
            buildString {
                if (!logPath.isNullOrBlank()) {
                    appendLine("Log file: $logPath")
                    appendLine()
                }
                append(logs)
            }
        } else {
            "No logs yet."
        }
    }
}
