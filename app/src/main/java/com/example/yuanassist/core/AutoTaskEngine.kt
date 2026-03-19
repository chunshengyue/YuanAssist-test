package com.example.yuanassist.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.example.yuanassist.model.DailyTask
import com.example.yuanassist.model.DailyTaskPlan
import com.example.yuanassist.utils.RunLogger
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.imgproc.Imgproc
import kotlin.math.min

class AutoTaskEngine(private val service: AccessibilityService) {

    companion object {
        private const val BASE_W = 1080f
        private const val BASE_H = 1920f
    }

    var isRunning = false
    var lastMatchX: Float = 0f
    var lastMatchY: Float = 0f

    private var currentTaskPlan: DailyTaskPlan? = null
    private var currentTaskId: Int = -1
    private var onPlanCompleted: ((Boolean, String) -> Unit)? = null
    private var customActionHandler: ((DailyTask, onSuccess: () -> Unit, onFail: () -> Unit) -> Unit)? =
        null

    private val screenWidth = service.resources.displayMetrics.widthPixels
    private val screenHeight = service.resources.displayMetrics.heightPixels
    private val handler = Handler(Looper.getMainLooper())
    private val templateCache = android.util.LruCache<String, Bitmap>(10)
    private val matchedPointsByTaskId = mutableMapOf<Int, PointF>()

    fun startPlan(
        plan: DailyTaskPlan,
        onCompleted: (Boolean, String) -> Unit,
        onCustomAction: ((DailyTask, () -> Unit, () -> Unit) -> Unit)? = null
    ) {
        RunLogger.i("--- 引擎启动：开始执行任务流 ---")
        currentTaskPlan = plan
        currentTaskId = plan.start_task_id
        isRunning = true
        matchedPointsByTaskId.clear()
        lastMatchX = 0f
        lastMatchY = 0f
        onPlanCompleted = onCompleted
        customActionHandler = onCustomAction
        executeNextTask()
    }

    fun stop() {
        isRunning = false
        matchedPointsByTaskId.clear()
        onPlanCompleted?.invoke(false, "已手动停止")
    }

    fun finishTask(task: DailyTask, isSuccess: Boolean) {
        if (!isSuccess) {
            if (task.on_fail == -1) {
                isRunning = false
                val errorMsg = when (task.action) {
                    "MATCH_TEMPLATE" -> "找不到图片: ${task.params?.template_name}"
                    "CLICK_DYNAMIC_BUTTON" -> "找不到动态按钮: ${task.params?.button_name}"
                    "CLICK", "SWIPE" -> "执行 ${task.action} 动作失败"
                    else -> "未知错误: ${task.action}"
                }
                RunLogger.e("任务中止 -> $errorMsg")
                onPlanCompleted?.invoke(false, errorMsg)
                return
            }

            RunLogger.e("步骤 ${task.id} 失败，跳转到容错步骤: ${task.on_fail}")
            currentTaskId = task.on_fail
        } else {
            RunLogger.i("步骤 ${task.id} 执行成功")
            if (task.on_success == -1) {
                isRunning = false
                RunLogger.i("--- 引擎执行完毕 ---")
                onPlanCompleted?.invoke(true, "全部执行完毕")
                return
            }
            currentTaskId = task.on_success
        }

        executeNextTask()
    }

    fun dispatchClick(x: Float, y: Float, onComplete: () -> Unit) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onComplete()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                RunLogger.e("点击操作被系统打断")
                onComplete()
            }
        }, null)
    }

    private fun executeNextTask() {
        if (!isRunning) return

        val task = currentTaskPlan?.tasks?.find { it.id == currentTaskId }
        if (task == null) {
            isRunning = false
            val message = "找不到任务 ID: $currentTaskId"
            onPlanCompleted?.invoke(false, message)
            RunLogger.e(message)
            return
        }

        RunLogger.i("准备执行步骤 ${task.id} [${task.action}]")
        handler.postDelayed({
            if (!isRunning) return@postDelayed
            when (task.action) {
                "CLICK" -> executeClick(task)
                "SWIPE" -> executeSwipe(task)
                "BACK" -> executeGlobalBack(task)
                "CLICK_LAST_MATCH", "CLICK_LAST_OCR" -> executeContextClick(task)
                "MATCH_TEMPLATE" -> executeMatchTemplate(task)
                else -> {
                    if (customActionHandler != null) {
                        customActionHandler!!.invoke(
                            task,
                            { finishTask(task, true) },
                            { finishTask(task, false) }
                        )
                    } else {
                        finishTask(task, false)
                    }
                }
            }
        }, task.delay)
    }

    private fun calculateRealCoordinate(
        baseX: Float,
        baseY: Float,
        align: String
    ): Pair<Float, Float> {
        val designX = toDesignX(baseX)
        val designY = toDesignY(baseY)
        val widthRatio = screenWidth / BASE_W
        val heightRatio = screenHeight / BASE_H
        val gameScale = min(widthRatio, heightRatio)
        val gameWidth = BASE_W * gameScale
        val gameHeight = BASE_H * gameScale
        val offsetX = (screenWidth - gameWidth) / 2f
        val offsetY = (screenHeight - gameHeight) / 2f
        val realX = offsetX + (designX * gameScale)

        var statusBarHeight = 40
        val resourceId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            statusBarHeight = service.resources.getDimensionPixelSize(resourceId)
        }

        val realY = when (align.lowercase()) {
            "absolute" -> designY * (screenHeight / BASE_H)
            "top" -> statusBarHeight + (designY * gameScale)
            "bottom" -> screenHeight - ((BASE_H - designY) * gameScale)
            else -> offsetY + (designY * gameScale)
        }

        return Pair(realX, realY)
    }

    private fun toDesignX(value: Float): Float {
        return if (value in 0f..1f) value * BASE_W else value
    }

    private fun toDesignY(value: Float): Float {
        return if (value in 0f..1f) value * BASE_H else value
    }

    private fun executeClick(task: DailyTask) {
        val p = task.params ?: return finishTask(task, false)
        val referencedPoint = p.ref_task_id?.let { matchedPointsByTaskId[it] }
        if (referencedPoint != null) {
            dispatchClick(referencedPoint.x, referencedPoint.y) { finishTask(task, true) }
            return
        }

        if (p.x == null || p.y == null) {
            finishTask(task, false)
            return
        }

        val (realX, realY) = calculateRealCoordinate(p.x, p.y, p.align)
        dispatchClick(realX, realY) { finishTask(task, true) }
    }

    private fun executeSwipe(task: DailyTask) {
        val p = task.params ?: return finishTask(task, false)
        if (p.startX == null || p.startY == null || p.endX == null || p.endY == null) {
            finishTask(task, false)
            return
        }

        val (rStartX, rStartY) = calculateRealCoordinate(p.startX, p.startY, p.align)
        val (rEndX, rEndY) = calculateRealCoordinate(p.endX, p.endY, p.align)
        val duration = p.duration ?: 300L

        val path = Path().apply {
            moveTo(rStartX, rStartY)
            lineTo(rEndX, rEndY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                finishTask(task, true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                RunLogger.e("滑动执行被系统打断")
                finishTask(task, false)
            }
        }, null)
    }

    private fun executeContextClick(task: DailyTask) {
        val referencedPoint = task.params?.ref_task_id?.let { matchedPointsByTaskId[it] }
        if (referencedPoint != null) {
            dispatchClick(referencedPoint.x, referencedPoint.y) { finishTask(task, true) }
            return
        }

        if (lastMatchX == 0f && lastMatchY == 0f) {
            finishTask(task, false)
            return
        }

        dispatchClick(lastMatchX, lastMatchY) { finishTask(task, true) }
    }

    private fun executeGlobalBack(task: DailyTask) {
        val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        finishTask(task, success)
    }

    private fun loadTemplateFromAssets(fileName: String): Bitmap? {
        templateCache.get(fileName)?.let { return it }
        return try {
            val bitmap = BitmapFactory.decodeStream(service.assets.open(fileName))
            if (bitmap != null) {
                templateCache.put(fileName, bitmap)
            }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun executeMatchTemplate(task: DailyTask) {
        val p = task.params ?: return finishTask(task, false)
        val templateName = p.template_name ?: return finishTask(task, false)
        val threshold = p.threshold

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            finishTask(task, false)
            return
        }

        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                    val buffer = screenshotResult.hardwareBuffer
                    val colorSpace = screenshotResult.colorSpace
                    val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                    val swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)

                    hwBitmap?.recycle()
                    buffer.close()

                    if (swBitmap == null) {
                        finishTask(task, false)
                        return
                    }

                    val widthRatio = swBitmap.width / BASE_W
                    val heightRatio = swBitmap.height / BASE_H
                    val gameScale = min(widthRatio, heightRatio)
                    val gameWidth = BASE_W * gameScale
                    val offsetX = (swBitmap.width - gameWidth) / 2f

                    val rawTemplate = loadTemplateFromAssets(templateName)
                    if (rawTemplate == null) {
                        swBitmap.recycle()
                        finishTask(task, false)
                        return
                    }

                    val scaledTemplate = Bitmap.createScaledBitmap(
                        rawTemplate,
                        (rawTemplate.width * gameScale).toInt().coerceAtLeast(1),
                        (rawTemplate.height * gameScale).toInt().coerceAtLeast(1),
                        true
                    )
                    if (scaledTemplate !== rawTemplate) {
                        rawTemplate.recycle()
                    }

                    var searchBitmap = swBitmap
                    var roiOffsetX = 0f
                    var roiOffsetY = 0f

                    if (p.roi?.align == "dynamic_avatar_bounds") {
                        val topBound = 1254f * gameScale
                        val bottomBound = swBitmap.height - (313f * gameScale)
                        val safeY = topBound.toInt().coerceAtLeast(0)
                        val safeH = (bottomBound - topBound).toInt()
                            .coerceAtMost(swBitmap.height - safeY)
                            .coerceAtLeast(1)

                        searchBitmap = Bitmap.createBitmap(swBitmap, 0, safeY, swBitmap.width, safeH)
                        roiOffsetY = safeY.toFloat()
                    } else if (p.roi?.align == "dynamic_filter_bounds") {
                        val realCenterY = 1254f * gameScale
                        val realCenterX = offsetX + (gameWidth * 0.9f)
                        val realW = gameWidth * 0.2f
                        val realH = 300f * gameScale
                        val realLeft = realCenterX - (realW / 2f)
                        val realTop = realCenterY - (realH / 2f)

                        val safeX = realLeft.toInt().coerceIn(0, swBitmap.width - 1)
                        val safeY = realTop.toInt().coerceIn(0, swBitmap.height - 1)
                        val safeW = realW.toInt().coerceAtMost(swBitmap.width - safeX).coerceAtLeast(1)
                        val safeH = realH.toInt().coerceAtMost(swBitmap.height - safeY).coerceAtLeast(1)

                        searchBitmap = Bitmap.createBitmap(swBitmap, safeX, safeY, safeW, safeH)
                        roiOffsetX = safeX.toFloat()
                        roiOffsetY = safeY.toFloat()
                    } else if (
                        p.roi != null &&
                        p.roi.x != null &&
                        p.roi.y != null &&
                        p.roi.w != null &&
                        p.roi.h != null
                    ) {
                        val (realCenterX, realCenterY) = calculateRealCoordinate(
                            p.roi.x,
                            p.roi.y,
                            p.roi.align
                        )
                        val realW = toDesignX(p.roi.w) * gameScale
                        val realH = toDesignY(p.roi.h) * gameScale
                        val realLeft = realCenterX - (realW / 2f)
                        val realTop = realCenterY - (realH / 2f)

                        val safeX = realLeft.toInt().coerceIn(0, swBitmap.width - 1)
                        val safeY = realTop.toInt().coerceIn(0, swBitmap.height - 1)
                        val safeW = realW.toInt().coerceAtMost(swBitmap.width - safeX).coerceAtLeast(1)
                        val safeH = realH.toInt().coerceAtMost(swBitmap.height - safeY).coerceAtLeast(1)

                        searchBitmap = Bitmap.createBitmap(swBitmap, safeX, safeY, safeW, safeH)
                        roiOffsetX = safeX.toFloat()
                        roiOffsetY = safeY.toFloat()
                    }

                    if (searchBitmap.width >= scaledTemplate.width &&
                        searchBitmap.height >= scaledTemplate.height
                    ) {
                        val matchLoc = matchTemplate(searchBitmap, scaledTemplate, threshold)
                        if (matchLoc != null) {
                            lastMatchX = matchLoc.x + roiOffsetX
                            lastMatchY = matchLoc.y + roiOffsetY
                            matchedPointsByTaskId[task.id] = PointF(lastMatchX, lastMatchY)
                            finishTask(task, true)
                        } else {
                            finishTask(task, false)
                        }
                    } else {
                        finishTask(task, false)
                    }

                    scaledTemplate.recycle()
                    if (searchBitmap !== swBitmap) {
                        searchBitmap.recycle()
                    }
                    swBitmap.recycle()
                }

                override fun onFailure(errorCode: Int) {
                    finishTask(task, false)
                }
            }
        )
    }

    private inline fun <T> org.opencv.core.Mat.use(block: (org.opencv.core.Mat) -> T): T {
        try {
            return block(this)
        } finally {
            release()
        }
    }

    private fun matchTemplate(
        screenBitmap: Bitmap,
        templateBitmap: Bitmap,
        threshold: Float
    ): PointF? {
        val swBitmap = screenBitmap.copy(Bitmap.Config.ARGB_8888, false)

        return org.opencv.core.Mat().use { srcMat ->
            org.opencv.core.Mat().use { tmplMat ->
                org.opencv.core.Mat().use { resultMat ->
                    Utils.bitmapToMat(swBitmap, srcMat)
                    Utils.bitmapToMat(templateBitmap, tmplMat)
                    Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_RGBA2GRAY)
                    Imgproc.cvtColor(tmplMat, tmplMat, Imgproc.COLOR_RGBA2GRAY)
                    Imgproc.matchTemplate(srcMat, tmplMat, resultMat, Imgproc.TM_CCOEFF_NORMED)

                    val mmLoc = Core.minMaxLoc(resultMat)
                    swBitmap.recycle()

                    if (mmLoc.maxVal >= threshold) {
                        val x = mmLoc.maxLoc.x + templateBitmap.width / 2.0
                        val y = mmLoc.maxLoc.y + templateBitmap.height / 2.0
                        PointF(x.toFloat(), y.toFloat())
                    } else {
                        null
                    }
                }
            }
        }
    }
}
