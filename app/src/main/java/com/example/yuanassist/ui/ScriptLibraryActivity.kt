package com.example.yuanassist.ui

import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.yuanassist.core.DailyScriptLibraryBridge
import com.example.yuanassist.core.LocalScriptJson
import com.example.yuanassist.model.DailyTaskPlan
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File

class ScriptLibraryActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PICK_MODE = "pick_mode"
        const val PICK_MODE_DAILY_PLAN = "daily_plan"
    }

    private enum class EntryType {
        LEGACY_SCRIPT,
        DAILY_PLAN
    }

    private data class LibraryEntry(
        val file: File,
        val type: EntryType,
        val displayName: String
    )

    private val gson = Gson()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val entries = loadEntries()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 30, 30, 30)
            setBackgroundColor(Color.parseColor("#F5F5F5"))
        }

        val title = TextView(this).apply {
            text = if (isDailyPickerMode()) "选择日常脚本" else "本地脚本库"
            textSize = 24f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 30)
        }
        root.addView(title)

        val listView = ListView(this)
        root.addView(
            listView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        setContentView(root)

        if (entries.isEmpty()) {
            Toast.makeText(this, "脚本库为空", Toast.LENGTH_SHORT).show()
            return
        }

        listView.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            entries.map { it.displayName }
        )

        listView.setOnItemClickListener { _, _, position, _ ->
            val entry = entries[position]
            if (isDailyPickerMode()) {
                if (entry.type != EntryType.DAILY_PLAN) {
                    Toast.makeText(this, "这个文件不是日常任务 JSON", Toast.LENGTH_SHORT).show()
                    return@setOnItemClickListener
                }
                selectDailyPlan(entry)
            } else {
                when (entry.type) {
                    EntryType.LEGACY_SCRIPT -> showLegacyScriptPreview(entry.file)
                    EntryType.DAILY_PLAN -> showDailyPlanPreview(entry.file)
                }
            }
        }
    }

    private fun isDailyPickerMode(): Boolean {
        return intent.getStringExtra(EXTRA_PICK_MODE) == PICK_MODE_DAILY_PLAN
    }

    private fun loadEntries(): List<LibraryEntry> {
        val dir = File(filesDir, "scripts")
        val files = dir.listFiles { _, name -> name.endsWith(".json", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

        val filtered = files.mapNotNull { file ->
            when (detectEntryType(file)) {
                EntryType.LEGACY_SCRIPT -> LibraryEntry(
                    file = file,
                    type = EntryType.LEGACY_SCRIPT,
                    displayName = "[跟打] ${file.nameWithoutExtension}"
                )

                EntryType.DAILY_PLAN -> LibraryEntry(
                    file = file,
                    type = EntryType.DAILY_PLAN,
                    displayName = "[日常] ${file.nameWithoutExtension}"
                )

                null -> null
            }
        }

        return if (isDailyPickerMode()) {
            filtered.filter { it.type == EntryType.DAILY_PLAN }
        } else {
            filtered
        }
    }

    private fun detectEntryType(file: File): EntryType? {
        return try {
            val jsonObject = JsonParser.parseString(file.readText()).asJsonObject
            when {
                jsonObject.has("start_task_id") && jsonObject.has("tasks") -> EntryType.DAILY_PLAN
                jsonObject.has("scriptContent") && jsonObject.has("config") -> EntryType.LEGACY_SCRIPT
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun selectDailyPlan(entry: LibraryEntry) {
        try {
            val content = entry.file.readText()
            gson.fromJson(content, DailyTaskPlan::class.java)
            DailyScriptLibraryBridge.onDailyPlanSelected?.invoke(
                entry.file.nameWithoutExtension,
                content
            )
            Toast.makeText(this, "已选择 ${entry.file.nameWithoutExtension}", Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, "脚本解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDailyPlanPreview(file: File) {
        try {
            val plan = gson.fromJson(file.readText(), DailyTaskPlan::class.java)
            val summary = buildString {
                appendLine("起始任务: ${plan.start_task_id}")
                appendLine("任务总数: ${plan.tasks.size}")
                appendLine()
                plan.tasks.forEach { task ->
                    appendLine(
                        "ID ${task.id} | ${task.action} | success -> ${task.on_success} | fail -> ${task.on_fail}"
                    )
                }
            }

            AlertDialog.Builder(this)
                .setTitle(file.nameWithoutExtension)
                .setMessage(summary.trim())
                .setPositiveButton("关闭", null)
                .setNegativeButton("删除") { _, _ ->
                    file.delete()
                    recreate()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "预览失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLegacyScriptPreview(file: File) {
        try {
            val scriptObj = gson.fromJson(file.readText(), LocalScriptJson::class.java)
            val scrollView = ScrollView(this)
            val content = TextView(this).apply {
                setPadding(40, 40, 40, 40)
                setTextColor(Color.BLACK)
                textSize = 14f
                text = buildString {
                    appendLine("标题: ${scriptObj.title ?: file.nameWithoutExtension}")
                    appendLine()
                    appendLine("脚本内容:")
                    appendLine(scriptObj.scriptContent)
                    appendLine()
                    appendLine("附加指令:")
                    if (scriptObj.instructions.isNullOrEmpty()) {
                        append("无")
                    } else {
                        scriptObj.instructions.forEach { ins ->
                            appendLine("${ins.type} | T${ins.turn} | step ${ins.step} | value ${ins.value}")
                        }
                    }
                }
            }
            scrollView.addView(content)

            AlertDialog.Builder(this)
                .setTitle(scriptObj.title ?: file.nameWithoutExtension)
                .setView(scrollView)
                .setPositiveButton("关闭", null)
                .setNegativeButton("删除") { _, _ ->
                    file.delete()
                    recreate()
                }
                .show()
        } catch (e: Exception) {
            Toast.makeText(this, "预览失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
