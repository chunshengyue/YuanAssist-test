package com.example.yuanassist.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.yuanassist.model.DailyTask
import com.example.yuanassist.model.DailyTaskPlan
import com.example.yuanassist.utils.RunLogger
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
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

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager =
        service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
    private val templateCache = android.util.LruCache<String, Bitmap>(10)
    private val matchedPointsByTaskId = mutableMapOf<Int, PointF>()
    private val variables = mutableMapOf<String, String>()
    private var runGeneration: Long = 0L
    private var debugRoiView: View? = null

    private data class ScreenshotMapping(
        val displayWidth: Float,
        val displayHeight: Float,
        val screenshotWidth: Int,
        val screenshotHeight: Int,
        val screenshotToDisplayX: Float,
        val screenshotToDisplayY: Float,
        val displayToScreenshotX: Float,
        val displayToScreenshotY: Float,
        val orientationMismatch: Boolean,
        val nonUniformScale: Boolean
    )

    init {
        RunLogger.init(service)
    }

    fun startPlan(
        plan: DailyTaskPlan,
        onCompleted: (Boolean, String) -> Unit,
        onCustomAction: ((DailyTask, () -> Unit, () -> Unit) -> Unit)? = null
    ) {
        runGeneration += 1
        handler.removeCallbacksAndMessages(null)
        clearDebugRoi()
        logDisplayMetrics("startPlan")
        RunLogger.i("引擎启动")
        currentTaskPlan = plan
        currentTaskId = plan.start_task_id
        isRunning = true
        matchedPointsByTaskId.clear()
        variables.clear()
        lastMatchX = 0f
        lastMatchY = 0f
        onPlanCompleted = onCompleted
        customActionHandler = onCustomAction
        executeNextTask()
    }

    fun stop() {
        runGeneration += 1
        handler.removeCallbacksAndMessages(null)
        clearDebugRoi()
        isRunning = false
        matchedPointsByTaskId.clear()
        variables.clear()
        RunLogger.i("用户手动停止引擎")
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
                RunLogger.e("任务 ${task.id} 失败，流程已停止：$errorMsg")
                onPlanCompleted?.invoke(false, errorMsg)
                return
            }

            RunLogger.e("任务 ${task.id} 失败，跳转到 ${task.on_fail}")
            currentTaskId = task.on_fail
        } else {
            val nextTaskId = resolveSuccessTaskId(task)
            RunLogger.i("任务 ${task.id} 成功，下一步=$nextTaskId")
            if (nextTaskId == -1) {
                isRunning = false
                RunLogger.i("引擎执行完成")
                onPlanCompleted?.invoke(true, "全部执行完毕")
                return
            }
            currentTaskId = nextTaskId
        }

        executeNextTask()
    }

    fun dispatchClick(x: Float, y: Float, onComplete: () -> Unit) {
        RunLogger.i("派发点击：x=${x.toInt()}, y=${y.toInt()}")
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
                RunLogger.e("点击手势被系统取消")
                onComplete()
            }
        }, null)
    }

    private fun executeNextTask() {
        if (!isRunning) return
        val generation = runGeneration

        val task = currentTaskPlan?.tasks?.find { it.id == currentTaskId }
        if (task == null) {
            isRunning = false
            val message = "找不到任务 ID: $currentTaskId"
            RunLogger.e(message)
            onPlanCompleted?.invoke(false, message)
            return
        }

        RunLogger.i("准备任务 ${task.id} [${task.action}]，延迟=${task.delay}")
        handler.postDelayed({
            if (!isRunning || generation != runGeneration) return@postDelayed
            try {
                when (task.action) {
                    "CLICK" -> executeClick(task)
                    "SWIPE" -> executeSwipe(task)
                    "BACK" -> executeGlobalBack(task)
                    "SET_VAR" -> executeSetVar(task)
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
                            RunLogger.e("不支持的动作：${task.action}")
                            finishTask(task, false)
                        }
                    }
                }
            } catch (t: Throwable) {
                RunLogger.e("任务 ${task.id} 执行异常：${task.action}", t)
                finishTask(task, false)
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
        val (screenWidth, screenHeight) = getRealScreenSize()
        val widthRatio = screenWidth / BASE_W
        val heightRatio = screenHeight / BASE_H
        val gameScale = min(widthRatio, heightRatio)
        val gameWidth = BASE_W * gameScale
        val gameHeight = BASE_H * gameScale
        val offsetX = (screenWidth - gameWidth) / 2f
        val offsetY = (screenHeight - gameHeight) / 2f
        val realX = offsetX + (designX * gameScale)
        val statusBarHeight = getScaledStatusBarHeight(screenWidth, screenHeight)

        val realY = when (align.lowercase()) {
            "absolute" -> designY * (screenHeight / BASE_H)
            "top" -> statusBarHeight + (designY * gameScale)
            "bottom" -> screenHeight - ((BASE_H - designY) * gameScale)
            else -> offsetY + (designY * gameScale)
        }

        return Pair(realX, realY)
    }

    private fun getRawStatusBarHeight(): Int {
        val resourceId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) service.resources.getDimensionPixelSize(resourceId) else 40
    }

    private fun getPhysicalDisplaySize(): Pair<Int, Int>? {
        @Suppress("DEPRECATION")
        val display = windowManager.defaultDisplay ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null
        }
        val mode = display.mode ?: return null
        return Pair(mode.physicalWidth, mode.physicalHeight)
    }

    private fun getPhysicalToLogicalScale(screenWidth: Float, screenHeight: Float): Float {
        val physicalSize = getPhysicalDisplaySize() ?: return 1f
        val physicalShort = min(physicalSize.first, physicalSize.second).toFloat()
        val physicalLong = max(physicalSize.first, physicalSize.second).toFloat()
        val logicalShort = min(screenWidth, screenHeight)
        val logicalLong = max(screenWidth, screenHeight)
        if (logicalShort <= 0f || logicalLong <= 0f) return 1f

        val widthScale = physicalShort / logicalShort
        val heightScale = physicalLong / logicalLong
        return ((widthScale + heightScale) / 2f).coerceAtLeast(1f)
    }

    private fun getScaledStatusBarHeight(screenWidth: Float, screenHeight: Float): Float {
        val rawStatusBarHeight = getRawStatusBarHeight().toFloat()
        return rawStatusBarHeight / 2f
    }

    private fun getRealScreenSize(): Pair<Float, Float> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            Pair(bounds.width().toFloat(), bounds.height().toFloat())
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
            Pair(point.x.toFloat(), point.y.toFloat())
        }
    }

    private fun logDisplayMetrics(tag: String) {
        val resourcesMetrics = service.resources.displayMetrics
        val (realWidth, realHeight) = getRealScreenSize()
        val rawStatusBarHeight = getRawStatusBarHeight()
        val scaledStatusBarHeight = getScaledStatusBarHeight(realWidth, realHeight)
        RunLogger.i(
            "显示信息[$tag]：逻辑分辨率=${realWidth.toInt()}x${realHeight.toInt()}，resources=${resourcesMetrics.widthPixels}x${resourcesMetrics.heightPixels}"
        )
        RunLogger.i(
            "显示信息[$tag]：状态栏原始高度=$rawStatusBarHeight，修正后=${scaledStatusBarHeight.toInt()}"
        )
    }

    private fun buildScreenshotMapping(bitmap: Bitmap): ScreenshotMapping {
        val (displayWidth, displayHeight) = getRealScreenSize()
        val screenshotToDisplayX = displayWidth / bitmap.width.toFloat()
        val screenshotToDisplayY = displayHeight / bitmap.height.toFloat()
        val displayToScreenshotX = bitmap.width.toFloat() / displayWidth
        val displayToScreenshotY = bitmap.height.toFloat() / displayHeight
        val orientationMismatch =
            (displayWidth >= displayHeight) != (bitmap.width >= bitmap.height)
        val nonUniformScale = abs(displayToScreenshotX - displayToScreenshotY) > 0.02f

        return ScreenshotMapping(
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            screenshotWidth = bitmap.width,
            screenshotHeight = bitmap.height,
            screenshotToDisplayX = screenshotToDisplayX,
            screenshotToDisplayY = screenshotToDisplayY,
            displayToScreenshotX = displayToScreenshotX,
            displayToScreenshotY = displayToScreenshotY,
            orientationMismatch = orientationMismatch,
            nonUniformScale = nonUniformScale
        )
    }

    private fun logScreenshotMapping(taskId: Int, mapping: ScreenshotMapping) {
        RunLogger.i(
            "任务 $taskId 截图缩放：shot->display x=${"%.4f".format(mapping.screenshotToDisplayX)} y=${"%.4f".format(mapping.screenshotToDisplayY)}，逻辑分辨率=${mapping.displayWidth.toInt()}x${mapping.displayHeight.toInt()}，截图=${mapping.screenshotWidth}x${mapping.screenshotHeight}"
        )

        if (mapping.orientationMismatch) {
            RunLogger.e(
                "任务 $taskId 截图方向不一致：逻辑分辨率=${mapping.displayWidth.toInt()}x${mapping.displayHeight.toInt()}，截图=${mapping.screenshotWidth}x${mapping.screenshotHeight}"
            )
        }

        if (mapping.nonUniformScale) {
            RunLogger.e(
                "任务 $taskId 截图缩放不一致：displayToShotX=${"%.4f".format(mapping.displayToScreenshotX)}，displayToShotY=${"%.4f".format(mapping.displayToScreenshotY)}；ROI 预览可能漂移"
            )
        }
    }

    private fun logRoiCenter(
        taskId: Int,
        realCenterX: Float,
        realCenterY: Float,
        debugLeft: Int,
        debugTop: Int,
        debugWidth: Int,
        debugHeight: Int,
        mapping: ScreenshotMapping
    ) {
        val debugCenterX = (debugLeft + debugWidth / 2f) * mapping.screenshotToDisplayX
        val debugCenterY = (debugTop + debugHeight / 2f) * mapping.screenshotToDisplayY
        RunLogger.i(
            "任务 $taskId ROI 中心=(${realCenterX.toInt()}, ${realCenterY.toInt()})，调试框中心=(${debugCenterX.toInt()}, ${debugCenterY.toInt()})"
        )
    }

    private fun toDesignX(value: Float): Float {
        return if (value in 0f..1f) value * BASE_W else value
    }

    private fun toDesignY(value: Float): Float {
        return if (value in 0f..1f) value * BASE_H else value
    }

    private fun executeClick(task: DailyTask) {
        val generation = runGeneration
        val p = task.params ?: return finishTask(task, false)
        val referencedPoint = p.ref_task_id?.let { matchedPointsByTaskId[it] }
        if (referencedPoint != null) {
            RunLogger.i("任务 ${task.id} 点击引用坐标 ref_task_id=${p.ref_task_id}")
            dispatchClick(referencedPoint.x, referencedPoint.y) {
                if (generation == runGeneration && isRunning) {
                    finishTask(task, true)
                }
            }
            return
        }

        if (p.x == null || p.y == null) {
            RunLogger.e("任务 ${task.id} 缺少点击坐标")
            finishTask(task, false)
            return
        }

        val (realX, realY) = calculateRealCoordinate(p.x, p.y, p.align)
        RunLogger.i(
            "任务 ${task.id} 点击坐标：align=${p.align}，设计坐标=(${p.x}, ${p.y})，实际坐标=(${realX.toInt()}, ${realY.toInt()})"
        )
        dispatchClick(realX, realY) {
            if (generation == runGeneration && isRunning) {
                finishTask(task, true)
            }
        }
    }

    private fun executeSwipe(task: DailyTask) {
        val generation = runGeneration
        val p = task.params ?: return finishTask(task, false)
        if (p.startX == null || p.startY == null || p.endX == null || p.endY == null) {
            RunLogger.e("任务 ${task.id} 缺少滑动坐标")
            finishTask(task, false)
            return
        }

        val (rStartX, rStartY) = calculateRealCoordinate(p.startX, p.startY, p.align)
        val (rEndX, rEndY) = calculateRealCoordinate(p.endX, p.endY, p.align)
        val duration = p.duration ?: 300L

        RunLogger.i(
            "任务 ${task.id} 滑动坐标：align=${p.align}，起点=(${rStartX.toInt()}, ${rStartY.toInt()})，终点=(${rEndX.toInt()}, ${rEndY.toInt()})，时长=$duration"
        )

        val path = Path().apply {
            moveTo(rStartX, rStartY)
            lineTo(rEndX, rEndY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (generation == runGeneration && isRunning) {
                    finishTask(task, true)
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                RunLogger.e("滑动手势被系统取消")
                if (generation == runGeneration && isRunning) {
                    finishTask(task, false)
                }
            }
        }, null)
    }

    private fun executeContextClick(task: DailyTask) {
        val generation = runGeneration
        val referencedPoint = task.params?.ref_task_id?.let { matchedPointsByTaskId[it] }
        if (referencedPoint != null) {
            RunLogger.i("任务 ${task.id} 使用上下文坐标点击 ref_task_id=${task.params?.ref_task_id}")
            dispatchClick(referencedPoint.x, referencedPoint.y) {
                if (generation == runGeneration && isRunning) {
                    finishTask(task, true)
                }
            }
            return
        }

        if (lastMatchX == 0f && lastMatchY == 0f) {
            RunLogger.e("任务 ${task.id} 没有可用的最近匹配坐标")
            finishTask(task, false)
            return
        }

        dispatchClick(lastMatchX, lastMatchY) {
            if (generation == runGeneration && isRunning) {
                finishTask(task, true)
            }
        }
    }

    private fun executeGlobalBack(task: DailyTask) {
        RunLogger.i("任务 ${task.id} 执行全局返回")
        val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        finishTask(task, success)
    }

    private fun executeSetVar(task: DailyTask) {
        val params = task.params ?: return finishTask(task, false)
        val varName = params.var_name ?: return finishTask(task, false)
        val varValue = params.var_value ?: return finishTask(task, false)
        variables[varName] = varValue
        RunLogger.i("任务 ${task.id} 设置变量 $varName=$varValue")
        finishTask(task, true)
    }

    private fun resolveSuccessTaskId(task: DailyTask): Int {
        val params = task.params ?: return task.on_success
        val branchVarName = params.branch_var ?: return task.on_success
        val branchRoutes = params.branch_routes ?: return task.on_success
        val currentValue = variables[branchVarName] ?: return task.on_success
        val nextTaskId = branchRoutes[currentValue] ?: task.on_success
        RunLogger.i("任务 ${task.id} 分支跳转：$branchVarName=$currentValue -> $nextTaskId")
        return nextTaskId
    }

    private fun loadTemplateFromAssets(fileName: String): Bitmap? {
        templateCache.get(fileName)?.let {
            RunLogger.i("模板命中缓存：$fileName")
            return it
        }

        return try {
            val bitmap = BitmapFactory.decodeStream(service.assets.open(fileName))
            if (bitmap != null) {
                templateCache.put(fileName, bitmap)
                RunLogger.i("模板已加载：$fileName (${bitmap.width}x${bitmap.height})")
            } else {
                RunLogger.e("模板解码失败：$fileName")
            }
            bitmap
        } catch (t: Throwable) {
            RunLogger.e("模板打开失败：$fileName", t)
            null
        }
    }

    private fun executeMatchTemplate(task: DailyTask) {
        val p = task.params ?: return finishTask(task, false)
        val templateName = p.template_name ?: return finishTask(task, false)
        val threshold = p.threshold
        val generation = runGeneration

        RunLogger.i(
            "任务 ${task.id} 模板匹配：template=$templateName threshold=$threshold roi=${formatRoi(p.roi)} click=${p.click == 1}"
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            RunLogger.e("截图功能要求 Android R 及以上，当前=${Build.VERSION.SDK_INT}")
            finishTask(task, false)
            return
        }

        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            service.mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: AccessibilityService.ScreenshotResult) {
                    if (!isRunning || generation != runGeneration) {
                        screenshotResult.hardwareBuffer.close()
                        return
                    }
                    var swBitmap: Bitmap? = null
                    var searchBitmap: Bitmap? = null
                    var scaledTemplate: Bitmap? = null
                    var ownsScaledTemplate = false

                    try {
                        val buffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        val hwBitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                        swBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hwBitmap?.recycle()
                        buffer.close()

                        if (swBitmap == null) {
                            RunLogger.e("任务 ${task.id} 截图复制失败")
                            finishTask(task, false)
                            return
                        }

                        RunLogger.i(
                            "任务 ${task.id} 截图尺寸=${swBitmap!!.width}x${swBitmap!!.height}"
                        )
                        val mapping = buildScreenshotMapping(swBitmap!!)
                        logScreenshotMapping(task.id, mapping)

                        val widthRatio = swBitmap!!.width / BASE_W
                        val heightRatio = swBitmap!!.height / BASE_H
                        val gameScale = min(widthRatio, heightRatio)
                        val gameWidth = BASE_W * gameScale
                        val offsetX = (swBitmap!!.width - gameWidth) / 2f

                        val rawTemplate = loadTemplateFromAssets(templateName)
                        if (rawTemplate == null) {
                            finishTask(task, false)
                            return
                        }

                        val scaledWidth = (rawTemplate.width * gameScale).toInt().coerceAtLeast(1)
                        val scaledHeight = (rawTemplate.height * gameScale).toInt().coerceAtLeast(1)
                        scaledTemplate = if (scaledWidth == rawTemplate.width && scaledHeight == rawTemplate.height) {
                            rawTemplate
                        } else {
                            ownsScaledTemplate = true
                            Bitmap.createScaledBitmap(rawTemplate, scaledWidth, scaledHeight, true)
                        }

                        searchBitmap = swBitmap
                        var roiOffsetX = 0f
                        var roiOffsetY = 0f
                        var debugLeft = 0
                        var debugTop = 0
                        var debugWidth = swBitmap!!.width
                        var debugHeight = swBitmap!!.height

                        if (p.roi?.align == "dynamic_avatar_bounds") {
                            val topBound = 1254f * gameScale
                            val bottomBound = swBitmap!!.height - (313f * gameScale)
                            val safeY = topBound.toInt().coerceAtLeast(0)
                            val safeH = (bottomBound - topBound).toInt()
                                .coerceAtMost(swBitmap!!.height - safeY)
                                .coerceAtLeast(1)

                            searchBitmap = Bitmap.createBitmap(swBitmap!!, 0, safeY, swBitmap!!.width, safeH)
                            roiOffsetY = safeY.toFloat()
                            debugTop = safeY
                            debugHeight = safeH
                        } else if (p.roi?.align == "dynamic_filter_bounds") {
                            val realCenterY = 1254f * gameScale
                            val realCenterX = offsetX + (gameWidth * 0.9f)
                            val realW = gameWidth * 0.2f
                            val realH = 300f * gameScale
                            val realLeft = realCenterX - (realW / 2f)
                            val realTop = realCenterY - (realH / 2f)

                            val safeX = realLeft.toInt().coerceIn(0, swBitmap!!.width - 1)
                            val safeY = realTop.toInt().coerceIn(0, swBitmap!!.height - 1)
                            val safeW = realW.toInt().coerceAtMost(swBitmap!!.width - safeX).coerceAtLeast(1)
                            val safeH = realH.toInt().coerceAtMost(swBitmap!!.height - safeY).coerceAtLeast(1)

                            searchBitmap = Bitmap.createBitmap(swBitmap!!, safeX, safeY, safeW, safeH)
                            roiOffsetX = safeX.toFloat()
                            roiOffsetY = safeY.toFloat()
                            debugLeft = safeX
                            debugTop = safeY
                            debugWidth = safeW
                            debugHeight = safeH
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
                            val screenshotCenterX = realCenterX * mapping.displayToScreenshotX
                            val screenshotCenterY = realCenterY * mapping.displayToScreenshotY
                            val realW = toDesignX(p.roi.w) * gameScale
                            val realH = toDesignY(p.roi.h) * gameScale
                            val realLeft = screenshotCenterX - (realW / 2f)
                            val realTop = screenshotCenterY - (realH / 2f)

                            val safeX = realLeft.toInt().coerceIn(0, swBitmap!!.width - 1)
                            val safeY = realTop.toInt().coerceIn(0, swBitmap!!.height - 1)
                            val safeW = realW.toInt().coerceAtMost(swBitmap!!.width - safeX).coerceAtLeast(1)
                            val safeH = realH.toInt().coerceAtMost(swBitmap!!.height - safeY).coerceAtLeast(1)

                            searchBitmap = Bitmap.createBitmap(swBitmap!!, safeX, safeY, safeW, safeH)
                            roiOffsetX = safeX.toFloat()
                            roiOffsetY = safeY.toFloat()
                            debugLeft = safeX
                            debugTop = safeY
                            debugWidth = safeW
                            debugHeight = safeH
                            RunLogger.i(
                                "任务 ${task.id} ROI 区域：left=$safeX top=$safeY width=$safeW height=$safeH"
                            )
                            logRoiCenter(
                                taskId = task.id,
                                realCenterX = realCenterX,
                                realCenterY = realCenterY,
                                debugLeft = debugLeft,
                                debugTop = debugTop,
                                debugWidth = debugWidth,
                                debugHeight = debugHeight,
                                mapping = mapping
                            )
                        }

                        showDebugRoi(
                            left = (debugLeft * mapping.screenshotToDisplayX).toInt(),
                            top = (debugTop * mapping.screenshotToDisplayY).toInt(),
                            width = (debugWidth * mapping.screenshotToDisplayX).toInt(),
                            height = (debugHeight * mapping.screenshotToDisplayY).toInt(),
                            holdMs = 1500L
                        )

                        if (searchBitmap!!.width < scaledTemplate!!.width ||
                            searchBitmap!!.height < scaledTemplate!!.height
                        ) {
                            RunLogger.e(
                                "任务 ${task.id} 搜索区域小于模板：search=${searchBitmap!!.width}x${searchBitmap!!.height}，template=${scaledTemplate!!.width}x${scaledTemplate!!.height}"
                            )
                            finishTask(task, false)
                            return
                        }

                        val matchLoc = matchTemplate(searchBitmap!!, scaledTemplate!!, threshold)
                        if (matchLoc != null) {
                            val screenshotMatchX = matchLoc.x + roiOffsetX
                            val screenshotMatchY = matchLoc.y + roiOffsetY
                            lastMatchX = screenshotMatchX * mapping.screenshotToDisplayX
                            lastMatchY = screenshotMatchY * mapping.screenshotToDisplayY
                            matchedPointsByTaskId[task.id] = PointF(lastMatchX, lastMatchY)
                            RunLogger.i(
                                "任务 ${task.id} 匹配成功：template=$templateName，屏幕坐标 x=${lastMatchX.toInt()}, y=${lastMatchY.toInt()}"
                            )
                            if (p.click == 1) {
                                dispatchClick(lastMatchX, lastMatchY) {
                                    if (generation == runGeneration && isRunning) {
                                        finishTask(task, true)
                                    }
                                }
                            } else {
                                if (generation == runGeneration && isRunning) {
                                    finishTask(task, true)
                                }
                            }
                        } else {
                            RunLogger.e("任务 ${task.id} 未匹配到模板：$templateName")
                            if (generation == runGeneration && isRunning) {
                                finishTask(task, false)
                            }
                        }
                    } catch (t: Throwable) {
                        RunLogger.e("任务 ${task.id} 模板匹配异常：template=$templateName", t)
                        if (generation == runGeneration && isRunning) {
                            finishTask(task, false)
                        }
                    } finally {
                        if (ownsScaledTemplate) {
                            scaledTemplate?.recycle()
                        }
                        if (searchBitmap != null && searchBitmap !== swBitmap) {
                            searchBitmap?.recycle()
                        }
                        swBitmap?.recycle()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    RunLogger.e("任务 ${task.id} 截图失败：code=$errorCode template=$templateName")
                    if (generation == runGeneration && isRunning) {
                        finishTask(task, false)
                    }
                }
            }
        )
    }

    private fun formatRoi(roi: com.example.yuanassist.model.ROI?): String {
        if (roi == null) return "null"
        return "x=${roi.x},y=${roi.y},w=${roi.w},h=${roi.h},align=${roi.align}"
    }

    private fun showDebugRoi(left: Int, top: Int, width: Int, height: Int, holdMs: Long) {
        handler.post {
            clearDebugRoi()

            val safeWidth = width.coerceAtLeast(1)
            val safeHeight = height.coerceAtLeast(1)
            val borderView = View(service).apply {
                background = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(4, Color.RED)
                }
            }

            val params = WindowManager.LayoutParams(
                safeWidth,
                safeHeight,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = left
                y = top
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }

            try {
                windowManager.addView(borderView, params)
                debugRoiView = borderView
                RunLogger.i("显示调试框：left=$left top=$top width=$safeWidth height=$safeHeight")
            } catch (t: Throwable) {
                RunLogger.e("显示调试框失败", t)
                clearDebugRoi()
                return@post
            }

            handler.postDelayed({
                if (debugRoiView === borderView) {
                    clearDebugRoi()
                }
            }, holdMs)
        }
    }

    private fun clearDebugRoi() {
        debugRoiView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (_: Throwable) {
            } finally {
                debugRoiView = null
            }
        }
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private inline fun <T> Mat.use(block: (Mat) -> T): T {
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
        val sourceBitmap = if (screenBitmap.config == Bitmap.Config.ARGB_8888) {
            screenBitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            screenBitmap.copy(Bitmap.Config.ARGB_8888, false)
        }

        try {
            return Mat().use { srcMat ->
                Mat().use { tmplMat ->
                    Mat().use { resultMat ->
                        Utils.bitmapToMat(sourceBitmap, srcMat)
                        Utils.bitmapToMat(templateBitmap, tmplMat)
                        Imgproc.cvtColor(srcMat, srcMat, Imgproc.COLOR_RGBA2GRAY)
                        Imgproc.cvtColor(tmplMat, tmplMat, Imgproc.COLOR_RGBA2GRAY)
                        Imgproc.matchTemplate(srcMat, tmplMat, resultMat, Imgproc.TM_CCOEFF_NORMED)

                        val mmLoc = Core.minMaxLoc(resultMat)
                        RunLogger.i("模板匹配分数=${"%.4f".format(mmLoc.maxVal)} threshold=$threshold")

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
        } finally {
            sourceBitmap.recycle()
        }
    }
}
