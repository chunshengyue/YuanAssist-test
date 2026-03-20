package com.example.yuanassist.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object RunLogger {
    private const val TAG = "GameAssist_RunLog"
    private const val LOG_DIR_NAME = "run_logs"
    private const val LOG_FILE_NAME = "latest.log"
    private const val MAX_IN_MEMORY = 1000

    private val logs = mutableListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        ensureLogDir()
    }

    @Synchronized
    fun clear() {
        logs.clear()
        writeLogFile("")
    }

    fun i(message: String) {
        append("I", message, null)
    }

    fun e(message: String) {
        append("E", message, null)
    }

    fun e(message: String, throwable: Throwable) {
        append("E", message, throwable)
    }

    @Synchronized
    fun getAllLogs(): String {
        if (logs.isNotEmpty()) {
            return logs.joinToString("\n")
        }
        return readLogFile()
    }

    fun getLogFilePath(): String? {
        return logFile()?.absolutePath
    }

    @Synchronized
    fun readLogFile(): String {
        val file = logFile() ?: return ""
        return if (file.exists()) file.readText() else ""
    }

    @Synchronized
    private fun append(level: String, message: String, throwable: Throwable?) {
        val time = timeFormat.format(Date())
        val baseLine = "[$time] [$level] $message"
        val lines = mutableListOf(baseLine)
        if (throwable != null) {
            lines += "${throwable.javaClass.simpleName}: ${throwable.message ?: "(no message)"}"
            lines += Log.getStackTraceString(throwable).trimEnd()
        }

        lines.forEach { line ->
            logs.add(line)
            if (logs.size > MAX_IN_MEMORY) {
                logs.removeAt(0)
            }
            if (level == "E") {
                Log.e(TAG, line)
            } else {
                Log.i(TAG, line)
            }
        }

        appendToFile(lines.joinToString("\n", postfix = "\n"))
    }

    private fun ensureLogDir() {
        logFile()?.parentFile?.mkdirs()
    }

    private fun logFile(): File? {
        val context = appContext ?: return null
        return File(File(context.filesDir, LOG_DIR_NAME), LOG_FILE_NAME)
    }

    private fun appendToFile(content: String) {
        val file = logFile() ?: return
        ensureLogDir()
        file.appendText(content)
    }

    private fun writeLogFile(content: String) {
        val file = logFile() ?: return
        ensureLogDir()
        file.writeText(content)
    }
}
