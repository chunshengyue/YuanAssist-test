package com.example.yuanassist.core

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.example.yuanassist.R
import com.example.yuanassist.model.DailyTaskPlan
import com.example.yuanassist.ui.ScriptLibraryActivity
import com.google.gson.Gson
import kotlin.math.min
import kotlin.math.roundToInt

class DailyWindowManager(private val service: AccessibilityService) {

    companion object {
        private const val BASE_W = 1080f
        private const val BASE_H = 1920f
    }

    private val engine = AutoTaskEngine(service)
    private val stitchEngine = InventoryStitchEngine(service)
    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    private var floatView: View? = null
    private var coordinatePickerView: View? = null
    private var currentTaskPlan: DailyTaskPlan? = null
    private var currentScriptName: String? = null

    fun hideWindow() {
        engine.stop()
        if (stitchEngine.isRunning) {
            stitchEngine.stop()
        }
        DailyScriptLibraryBridge.onDailyPlanSelected = null
        stopCoordinatePicker()
        removeWindow()
    }

    fun showWindow() {
        if (floatView != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 100
        }

        val view = LayoutInflater.from(service).inflate(R.layout.layout_daily_window, null)
        floatView = view

        val btnLibrary = view.findViewById<Button>(R.id.btn_daily_load)
        val btnAction = view.findViewById<Button>(R.id.btn_daily_action)
        val btnStitch = view.findViewById<Button>(R.id.btn_daily_stitch_inventory)
        val btnPick = view.findViewById<Button>(R.id.btn_daily_pick_coordinate)
        val btnClose = view.findViewById<Button>(R.id.btn_daily_close)
        val tvTitle = view.findViewById<TextView>(R.id.tv_daily_title)

        btnLibrary.text = "脚本库"
        btnLibrary.setOnClickListener { openScriptLibrary() }

        btnClose.setOnClickListener {
            engine.stop()
            if (stitchEngine.isRunning) {
                stitchEngine.stop()
            }
            stopCoordinatePicker()
            removeWindow()
        }

        btnAction.setOnClickListener {
            if (engine.isRunning) {
                engine.stop()
                btnAction.text = "开始执行"
                updateStatus("已手动停止")
                return@setOnClickListener
            }

            val plan = currentTaskPlan
            if (plan == null) {
                Toast.makeText(service, "请先从脚本库选择日常 JSON", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnAction.text = "停止执行"
            engine.startPlan(
                plan,
                onCompleted = { success, errorMsg ->
                    handler.post {
                        btnAction.text = "开始执行"
                        updateStatus(
                            if (success) {
                                "脚本执行完成: ${currentScriptName ?: "未命名"}"
                            } else {
                                "异常终止: $errorMsg"
                            }
                        )
                    }
                }
            )
        }

        btnStitch.setOnClickListener {
            if (stitchEngine.isRunning) {
                stitchEngine.stop()
                btnStitch.text = "星石拼图"
                updateStatus("拼图已停止")
                btnAction.isEnabled = true
                btnLibrary.isEnabled = true
                btnPick.isEnabled = true
                return@setOnClickListener
            }

            if (engine.isRunning) {
                Toast.makeText(service, "请先停止日常任务", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnStitch.text = "停止拼图"
            btnAction.isEnabled = false
            btnLibrary.isEnabled = false
            btnPick.isEnabled = false

            stitchEngine.startStitching(
                onStatusUpdate = { statusMsg ->
                    handler.post { updateStatus(statusMsg) }
                },
                onCompleted = {
                    handler.post {
                        btnStitch.text = "星石拼图"
                        btnAction.isEnabled = true
                        btnLibrary.isEnabled = true
                        btnPick.isEnabled = true
                    }
                }
            )
        }

        btnPick.setOnClickListener {
            if (coordinatePickerView == null) {
                startCoordinatePicker()
            } else {
                stopCoordinatePicker()
                updateStatus("已取消取点")
            }
        }

        tvTitle.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        floatView?.let { windowManager.updateViewLayout(it, params) }
                        return true
                    }

                    MotionEvent.ACTION_UP -> {
                        v.performClick()
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(view, params)
    }

    private fun openScriptLibrary() {
        DailyScriptLibraryBridge.onDailyPlanSelected = { fileName, jsonContent ->
            handler.post {
                try {
                    currentTaskPlan = gson.fromJson(jsonContent, DailyTaskPlan::class.java)
                    currentScriptName = fileName
                    updateStatus("已载入脚本: $fileName")
                    Toast.makeText(service, "已载入 $fileName", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    updateStatus("脚本解析失败")
                    Toast.makeText(service, "脚本解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val intent = Intent(service, ScriptLibraryActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(ScriptLibraryActivity.EXTRA_PICK_MODE, ScriptLibraryActivity.PICK_MODE_DAILY_PLAN)
        }
        service.startActivity(intent)
    }

    private fun updateStatus(text: String) {
        handler.post {
            floatView?.findViewById<TextView>(R.id.tv_daily_status)?.text = text
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun startCoordinatePicker() {
        if (coordinatePickerView != null) return

        val overlay = FrameLayout(service).apply {
            setBackgroundColor(Color.parseColor("#66000000"))
        }

        val hintView = TextView(service).apply {
            text = "请点击要记录的位置\n将自动复制坐标到剪贴板"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(40, 40, 40, 40)
            setBackgroundColor(Color.parseColor("#99000000"))
        }

        overlay.addView(
            hintView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        overlay.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    handlePickedCoordinate(event.rawX, event.rawY)
                    stopCoordinatePicker()
                    true
                }

                else -> true
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        coordinatePickerView = overlay
        windowManager.addView(overlay, params)
        updatePickButton("取消取点")
        updateStatus("取点模式已开启")
    }

    private fun stopCoordinatePicker() {
        coordinatePickerView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: IllegalArgumentException) {
            } finally {
                coordinatePickerView = null
            }
        }
        updatePickButton("选点取坐标")
    }

    private fun handlePickedCoordinate(rawX: Float, rawY: Float) {
        val coordinate = buildPickedCoordinate(rawX, rawY)
        copyToClipboard(coordinate.clipboardText)
        updateStatus("已取点: 屏幕(${coordinate.screenX}, ${coordinate.screenY})")
        Toast.makeText(service, coordinate.toastText, Toast.LENGTH_LONG).show()
    }

    private fun buildPickedCoordinate(rawX: Float, rawY: Float): PickedCoordinate {
        val metrics = service.resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()
        val gameScale = min(screenWidth / BASE_W, screenHeight / BASE_H)
        val gameWidth = BASE_W * gameScale
        val gameHeight = BASE_H * gameScale
        val offsetX = (screenWidth - gameWidth) / 2f
        val offsetY = (screenHeight - gameHeight) / 2f

        var statusBarHeight = 0
        val resourceId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            statusBarHeight = service.resources.getDimensionPixelSize(resourceId)
        }

        val screenX = rawX.roundToInt()
        val screenY = rawY.roundToInt()
        val designX = ((rawX - offsetX) / gameScale).roundToInt()
        val centerY = ((rawY - offsetY) / gameScale).roundToInt()
        val topY = ((rawY - statusBarHeight) / gameScale).roundToInt()
        val absoluteY = (rawY * BASE_H / screenHeight).roundToInt()

        val clipboardText = buildString {
            appendLine("屏幕坐标: x=$screenX, y=$screenY")
            appendLine("""center: { "x": $designX, "y": $centerY, "align": "center" }""")
            appendLine("""top: { "x": $designX, "y": $topY, "align": "top" }""")
            append("""absolute: { "x": $designX, "y": $absoluteY, "align": "absolute" }""")
        }

        return PickedCoordinate(
            screenX = screenX,
            screenY = screenY,
            clipboardText = clipboardText,
            toastText = "已复制坐标 ($screenX, $screenY)"
        )
    }

    private fun copyToClipboard(text: String) {
        val clipboard =
            service.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("daily-coordinate", text))
    }

    private fun updatePickButton(text: String) {
        handler.post {
            floatView?.findViewById<Button>(R.id.btn_daily_pick_coordinate)?.text = text
        }
    }

    private fun removeWindow() {
        val view = floatView ?: return
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
        } finally {
            floatView = null
        }
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private data class PickedCoordinate(
        val screenX: Int,
        val screenY: Int,
        val clipboardText: String,
        val toastText: String
    )
}
