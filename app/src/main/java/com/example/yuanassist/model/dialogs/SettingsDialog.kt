package com.example.yuanassist.ui.dialogs

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import com.example.yuanassist.utils.AppConfig
import com.example.yuanassist.utils.ConfigManager
import com.example.yuanassist.utils.DialogUtils

object SettingsDialog {

    fun show(context: Context, onConfigSaved: () -> Unit) {
        val themeContext = DialogUtils.getThemeContext(context)
        val currentConfig = ConfigManager.getAllConfig(context)

        val layout = LinearLayout(themeContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }

        val scrollView = ScrollView(themeContext)
        val scrollContent = LinearLayout(themeContext).apply {
            orientation = LinearLayout.VERTICAL
        }

        // 辅助函数：快速生成输入行
        fun createRow(label: String, defaultValue: String): EditText {
            val row = LinearLayout(themeContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 15, 0, 15)
            }
            val tvLabel = TextView(themeContext).apply {
                text = label
                textSize = 14f
                setTextColor(Color.DKGRAY)
                width = (130 * context.resources.displayMetrics.density).toInt()
            }
            val editText = EditText(themeContext).apply {
                setText(defaultValue)
                inputType = InputType.TYPE_CLASS_NUMBER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tvLabel)
            row.addView(editText)
            scrollContent.addView(row)
            return editText
        }

        val etAttack = createRow("普攻间隔(ms):", currentConfig.intervalAttack.toString())
        val etSkill = createRow("技能间隔(ms):", currentConfig.intervalSkill.toString())
        val etWait = createRow("敌方回合(ms):", currentConfig.waitTurn.toString())
        val etStart = createRow("起始回合:", currentConfig.startTurn.toString())
        val etThreshold = createRow("滑动阈值:", currentConfig.swipeThreshold.toString())
        val etHeight = createRow("输入区高度(%):", currentConfig.inputHeightRatio.toString())
        val etRecordDelay = createRow("录制延迟(ms):", currentConfig.recordDelay.toString())

        // 游戏倍速单选框
        val speedLabel = TextView(themeContext).apply {
            text = "游戏倍速:"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 20, 0, 10)
        }
        scrollContent.addView(speedLabel)

        val rgSpeed = RadioGroup(themeContext).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val rb2x = RadioButton(themeContext).apply { text = "2倍速"; id = 2 }
        val rb3x = RadioButton(themeContext).apply { text = "3倍速"; id = 3 }
        rgSpeed.addView(rb2x)
        rgSpeed.addView(rb3x)
        if (currentConfig.gameSpeed == 2) rgSpeed.check(2) else rgSpeed.check(3)
        scrollContent.addView(rgSpeed)

        scrollView.addView(scrollContent)
        layout.addView(scrollView)

        val builder = AlertDialog.Builder(themeContext)
            .setTitle("参数设置")
            .setView(layout)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)

        val dialog = DialogUtils.safeShowOverlayDialog(builder)
        // 清除 NOT_FOCUSABLE 标志，允许 EditText 弹出输入法
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val attack = etAttack.text.toString().toLong()
                val skill = etSkill.text.toString().toLong()
                val wait = etWait.text.toString().toLong()
                val start = etStart.text.toString().toInt()
                val threshold = etThreshold.text.toString().toInt()
                val height = etHeight.text.toString().toInt()
                val delay = etRecordDelay.text.toString().toLong()
                val speed = if (rgSpeed.checkedRadioButtonId == 2) 2 else 3

                if (attack > 0 && skill > 0 && wait > 0 && start > 0 && threshold > 0 && height in 10..100) {
                    val newConfig = AppConfig(attack, skill, wait, start, threshold, height, delay, speed)
                    ConfigManager.saveSettings(context, newConfig)
                    // 🔴 触发回调，让 Service 重新加载
                    onConfigSaved()
                    Toast.makeText(context, "设置已保存并生效", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    Toast.makeText(context, "参数数值不合理，请检查", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "请输入有效的数字", Toast.LENGTH_SHORT).show()
            }
        }
    }
}