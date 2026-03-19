package com.example.yuanassist.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import cn.bmob.v3.BmobQuery
import cn.bmob.v3.exception.BmobException
import cn.bmob.v3.listener.FindListener
import com.bumptech.glide.Glide
import com.example.yuanassist.R
import com.example.yuanassist.core.YuanAssistService
import com.example.yuanassist.model.StrategyPreviewData
import com.example.yuanassist.model.strategy_detail
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import cn.bmob.v3.listener.QueryListener
// 辅助数据结构 (仅限本地或 Intent 传值解析使用)
data class DetailItem(val type: String, val content: String)

class StrategyDetailActivity : AppCompatActivity() {

    private var isLiked = false
    private var isPreviewMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_strategy_detail)

        // 绑定顶部栏
        val topBar: View = findViewById(R.id.top_bar)
        topBar.findViewById<ImageView>(R.id.btn_back).setOnClickListener { onBackPressed() }

        isPreviewMode = intent.getBooleanExtra("IS_PREVIEW", false)

        if (isPreviewMode) {
            val previewDataJson = intent.getStringExtra("PREVIEW_DATA_JSON")
            if (previewDataJson != null) {
                val previewData = Gson().fromJson(previewDataJson, StrategyPreviewData::class.java)
                renderPreviewMode(previewData)
            } else {
                Toast.makeText(this, "预览资料解析失败", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            // 注意：Bmob 的 objectId 是 String 类型
            val strategyId = intent.getStringExtra("STRATEGY_ID")
            if (strategyId != null) {
                loadAndRenderNormalMode(strategyId)
            } else {
                Toast.makeText(this, "攻略ID传递错误", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    // ==========================================
    // 🎨 渲染引擎 1：预览模式
    // ==========================================
    private fun renderPreviewMode(data: StrategyPreviewData) {
        val topBar: View = findViewById(R.id.top_bar)
        topBar.findViewById<TextView>(R.id.tv_top_title).text = "攻略预览：${data.title}"
        topBar.findViewById<TextView>(R.id.tv_top_author_name).text = "作者：我自己"

        findViewById<View>(R.id.layout_bottom_bar).visibility = View.GONE
        findViewById<Button>(R.id.btn_copy_url).visibility = View.GONE
        findViewById<Button>(R.id.btn_import_script).visibility = View.GONE

        val layoutImageContainer = findViewById<View>(R.id.layout_image_container)
        val vpImages = findViewById<ViewPager2>(R.id.vp_detail_images)
        val tvIndicator = findViewById<TextView>(R.id.tv_image_indicator)
        val rvText = findViewById<RecyclerView>(R.id.rv_detail_text)

        val mainLayout = layoutImageContainer.parent as LinearLayout

        // --- 模块 1：◆ 跟打表格 / 攻略原图 ---
        mainLayout.findViewWithTag<View>("DYNAMIC_TABLE_TITLE")?.let { mainLayout.removeView(it) }
        mainLayout.findViewWithTag<View>("DYNAMIC_TABLE_CONTENT")?.let { mainLayout.removeView(it) }

        if (!data.strategyImageUri.isNullOrEmpty() || !data.tableData.isNullOrEmpty()) {
            val tvTableTitle = TextView(this).apply {
                tag = "DYNAMIC_TABLE_TITLE"
                text = "◆ 跟打表格"
                setTextColor(android.graphics.Color.parseColor("#E5C07B"))
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (15 * resources.displayMetrics.density).toInt() }
            }
            mainLayout.addView(tvTableTitle, mainLayout.indexOfChild(layoutImageContainer))
        }

        if (!data.strategyImageUri.isNullOrEmpty()) {
            layoutImageContainer.visibility = View.VISIBLE
            val fakeImageItems = listOf(DetailItem("image", data.strategyImageUri))
            vpImages.adapter = StrategyDetailImagesAdapter(fakeImageItems)
            tvIndicator.text = "1/1"
        } else if (!data.tableData.isNullOrEmpty()) {
            layoutImageContainer.visibility = View.GONE
            val readOnlyTable = createReadOnlyTable(data.tableData!!)
            readOnlyTable.tag = "DYNAMIC_TABLE_CONTENT"
            mainLayout.addView(readOnlyTable, mainLayout.indexOfChild(layoutImageContainer))
        } else {
            layoutImageContainer.visibility = View.GONE
        }

        // --- 模块 2：◆ 出战阵容 (三选一) ---
        val layoutAgents = findViewById<LinearLayout>(R.id.layout_agents_container)
        layoutAgents.removeAllViews()

        when (data.agentType) {
            0 -> { // 密探列表
                val hasAnyStar = checkHasAnyStar(data.agentSelection)
                data.agentSelection?.forEach { agentRawString ->
                    if (!agentRawString.isNullOrEmpty()) {
                        val parts = agentRawString.split("-")
                        val agentName = parts[0].trim()
                        val talents = if (parts.size > 1) {
                            parts[1].split("、").mapNotNull { it.trim().toIntOrNull() }
                        } else emptyList()
                        renderSingleAgentView(agentName, talents, layoutAgents, hasAnyStar)
                    }
                }
            }
            1 -> { // 阵容截图
                if (!data.agentImageUri.isNullOrEmpty()) {
                    val container = RelativeLayout(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = (20 * resources.displayMetrics.density).toInt() }
                    }

                    val agentVp = ViewPager2(this).apply {
                        id = View.generateViewId()
                        layoutParams = RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.MATCH_PARENT, (350 * resources.displayMetrics.density).toInt()
                        )
                    }

                    val agentIndicator = TextView(this).apply {
                        layoutParams = RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            addRule(RelativeLayout.BELOW, agentVp.id)
                            addRule(RelativeLayout.CENTER_HORIZONTAL)
                            topMargin = (10 * resources.displayMetrics.density).toInt()
                        }
                        setBackgroundResource(R.drawable.bg_dark_glass)
                        setPadding(
                            (12 * resources.displayMetrics.density).toInt(), (4 * resources.displayMetrics.density).toInt(),
                            (12 * resources.displayMetrics.density).toInt(), (4 * resources.displayMetrics.density).toInt()
                        )
                        setTextColor(android.graphics.Color.parseColor("#E5C07B"))
                        textSize = 12f
                        setTypeface(null, Typeface.BOLD)
                        text = "1/1"
                    }

                    container.addView(agentVp)
                    container.addView(agentIndicator)
                    layoutAgents.addView(container)

                    agentVp.adapter = StrategyDetailImagesAdapter(listOf(DetailItem("image", data.agentImageUri)))
                }
            }
            2 -> { // 文字描述
                if (!data.agentTextDesc.isNullOrEmpty()) {
                    val tv = TextView(this).apply {
                        text = data.agentTextDesc
                        setTextColor(android.graphics.Color.WHITE)
                        textSize = 14f
                    }
                    layoutAgents.addView(tv)
                }
            }
        }

        // --- 模块 3：图文说明 ---
        val glassContainer = rvText.parent as LinearLayout
        val outerContainer = glassContainer.parent as LinearLayout

        rvText.visibility = View.GONE

        outerContainer.findViewWithTag<View>("DYNAMIC_DESC_TITLE")?.let { outerContainer.removeView(it) }
        glassContainer.findViewWithTag<View>("DYNAMIC_DESC_CONTENT")?.let { glassContainer.removeView(it) }

        if (!data.content.isNullOrEmpty()) {
            glassContainer.visibility = View.VISIBLE

            val tvDescTitle = TextView(this).apply {
                tag = "DYNAMIC_DESC_TITLE"
                text = "◆ 补充说明"
                setTextColor(android.graphics.Color.parseColor("#E5C07B"))
                textSize = 16f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (15 * resources.displayMetrics.density).toInt() }
            }
            outerContainer.addView(tvDescTitle, outerContainer.indexOfChild(glassContainer))

            val tvDescContent = TextView(this).apply {
                tag = "DYNAMIC_DESC_CONTENT"
                text = data.content
                setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
                textSize = 14f
            }
            glassContainer.addView(tvDescContent)
        } else {
            glassContainer.visibility = View.GONE
        }
        }

    // ==========================================
    // ☁️ 渲染引擎 2：正常线上模式 (已对齐新 Model)
    // ==========================================
    private fun loadAndRenderNormalMode(objectId: String) {
        val btnLike = findViewById<LinearLayout>(R.id.btn_like)
        val tvLikeIcon = findViewById<TextView>(R.id.tv_like_icon)
        val btnCopyUrl = findViewById<Button>(R.id.btn_copy_url)
        val btnImportScript = findViewById<Button>(R.id.btn_import_script)
        val topBar = findViewById<View>(R.id.top_bar)

        btnLike.setOnClickListener {
            isLiked = !isLiked
            tvLikeIcon.text = if (isLiked) "♥" else "♡"
            tvLikeIcon.setTextColor(android.graphics.Color.parseColor(if (isLiked) "#F44336" else "#E5C07B"))
        }

        Toast.makeText(this, "加载中...", Toast.LENGTH_SHORT).show()

        val query = BmobQuery<strategy_detail>()
        query.include("author") // 捎带作者信息

        // 🔴 修复点：getObject 必须对应 GetListener，且返回的是单个对象 detail
        query.getObject(objectId, object : QueryListener<strategy_detail>() {
            override fun done(detail: strategy_detail?, e: BmobException?) {
                if (e == null && detail != null) {
                    runOnUiThread {
                        topBar.findViewById<TextView>(R.id.tv_top_title).text = detail.title ?: "无标题"

                        val actualName = detail.author?.nickname?.takeIf { it.isNotBlank() }
                            ?: detail.author?.username
                            ?: "热心玩家"
                        topBar.findViewById<TextView>(R.id.tv_top_author_name).text = "作者：$actualName"

                        val ivAuthorAvatar = topBar.findViewById<ImageView>(R.id.iv_top_author_avatar)
                        if (ivAuthorAvatar != null) {
                            ivAuthorAvatar.visibility = View.VISIBLE
                            val avatarUrl = detail.author?.avatarUrl
                            if (!avatarUrl.isNullOrEmpty()) {
                                com.bumptech.glide.Glide.with(this@StrategyDetailActivity)
                                    .load(avatarUrl)
                                    .circleCrop() // 变圆形
                                    .placeholder(R.drawable.ic_launcher_background)
                                    .error(R.drawable.ic_launcher_background)
                                    .into(ivAuthorAvatar)
                            } else {
                                ivAuthorAvatar.setImageResource(R.drawable.ic_launcher_background)
                            }
                        }

                        // --- 模块 1：◆ 跟打表格 / 攻略原图 / 脚本导入 ---
                        val layoutImageContainer = findViewById<View>(R.id.layout_image_container)
                        val vpImages = findViewById<ViewPager2>(R.id.vp_detail_images)
                        val tvIndicator = findViewById<TextView>(R.id.tv_image_indicator)
                        val mainLayout = layoutImageContainer.parent as LinearLayout

                        mainLayout.findViewWithTag<View>("DYNAMIC_TABLE_TITLE")?.let { mainLayout.removeView(it) }
                        mainLayout.findViewWithTag<View>("DYNAMIC_TABLE_CONTENT")?.let { mainLayout.removeView(it) }
                        mainLayout.findViewWithTag<View>("DYNAMIC_INSTRUCTIONS")?.let { mainLayout.removeView(it) }

                        val hasImage = !detail.strategyImage.isNullOrEmpty()
                        val hasScript = !detail.scriptContent.isNullOrEmpty()

                        if (hasImage || hasScript) {
                            val tvTableTitle = TextView(this@StrategyDetailActivity).apply {
                                tag = "DYNAMIC_TABLE_TITLE"
                                text = if (hasImage) "◆ 攻略原图" else "◆ 跟打表格"
                                setTextColor(android.graphics.Color.parseColor("#E5C07B"))
                                textSize = 16f
                                setTypeface(null, Typeface.BOLD)
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = (15 * resources.displayMetrics.density).toInt() }
                            }
                            mainLayout.addView(tvTableTitle, mainLayout.indexOfChild(layoutImageContainer))
                        }

                        if (hasImage) {
                            layoutImageContainer.visibility = View.VISIBLE
                            val fakeImageItems = listOf(DetailItem("image", detail.strategyImage ?: ""))
                            vpImages.adapter = StrategyDetailImagesAdapter(fakeImageItems)
                            tvIndicator.text = "1/1"
                        } else if (hasScript) {
                            layoutImageContainer.visibility = View.GONE

                            // 🔴 核心修复2：先渲染表格 (排在上面)
                            val tableData = parseScriptContentToTableData(detail.scriptContent!!)
                            val readOnlyTable = createReadOnlyTable(tableData)
                            readOnlyTable.tag = "DYNAMIC_TABLE_CONTENT"
                            mainLayout.addView(readOnlyTable, mainLayout.indexOfChild(layoutImageContainer))

                            // 🔴 核心修复2：再渲染附加指令 (排在表格下面)
                            if (!detail.instructions.isNullOrEmpty()) {
                                val tvInst = TextView(this@StrategyDetailActivity).apply {
                                    tag = "DYNAMIC_INSTRUCTIONS"
                                    setTextColor(android.graphics.Color.parseColor("#9E9E9E"))
                                    textSize = 12f
                                    layoutParams = LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                                    ).apply {
                                        topMargin = (10 * resources.displayMetrics.density).toInt()
                                        bottomMargin = (10 * resources.displayMetrics.density).toInt()
                                    }

                                    try {
                                        val type = object : TypeToken<List<com.example.yuanassist.model.InstructionJson>>() {}.type
                                        val instList: List<com.example.yuanassist.model.InstructionJson> = Gson().fromJson(detail.instructions, type)
                                        val formatted = instList.joinToString("\n") { "回合${it.turn} 动作${it.step}: [${it.type}] ${it.value}" }
                                        text = "附加指令：\n$formatted\n"
                                    } catch(e: Exception) {
                                        text = "附加指令：\n${detail.instructions}\n"
                                    }
                                }
                                // 因为表格已经插在 layoutImageContainer 前面，现在继续插，刚好排在表格下方
                                mainLayout.addView(tvInst, mainLayout.indexOfChild(layoutImageContainer))
                            }
                        } else {
                            layoutImageContainer.visibility = View.GONE
                        }

                        // (C) 始终保留底部的导入脚本按钮功能
                        if (hasScript) {
                            btnImportScript.visibility = View.VISIBLE
                            btnImportScript.setOnClickListener {

                                var cleanAgentsJson = detail.agentSelection ?: ""
                                if (cleanAgentsJson.isNotEmpty() && cleanAgentsJson != "[]") {
                                    try {
                                        val type = object : TypeToken<List<String>>() {}.type
                                        val rawAgentsList: List<String> = Gson().fromJson(cleanAgentsJson, type)

                                        val pureAgentsList = rawAgentsList.map { raw ->
                                            val trimRaw = raw.trim()
                                            if (trimRaw.isBlank()) {
                                                ""
                                            } else {
                                                trimRaw.replaceFirst("^\\d+".toRegex(), "").substringBefore("-").trim()
                                            }
                                        }

                                        cleanAgentsJson = Gson().toJson(pureAgentsList)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }

                                importScriptToService(detail.scriptContent ?: "", detail.config ?: "", detail.instructions ?: "", cleanAgentsJson)
                            }
                        }

                        // 🔴 核心修复3：绑定复制原帖链接功能
                        if (!detail.originalPostUrl.isNullOrEmpty()) {
                            btnCopyUrl.visibility = View.VISIBLE
                            btnCopyUrl.setOnClickListener {
                                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("原帖链接", detail.originalPostUrl)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(this@StrategyDetailActivity, "原帖链接已复制", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            btnCopyUrl.visibility = View.GONE
                        }


                        // --- 模块 2：◆ 出战阵容 (三选一) ---
                        val layoutAgents = findViewById<LinearLayout>(R.id.layout_agents_container)
                        layoutAgents.removeAllViews()

                        when (detail.agentType) {
                            0 -> { // 密探列表
                                if (!detail.agentSelection.isNullOrEmpty()) {
                                    try {
                                        val type = object : TypeToken<List<String>>() {}.type
                                        val agents: List<String> = Gson().fromJson(detail.agentSelection, type)
                                        val hasAnyStar = checkHasAnyStar(agents)
                                        agents.forEach { agentRawString ->
                                            if (agentRawString.isNotEmpty()) {
                                                val parts = agentRawString.split("-")
                                                val agentName = parts[0].trim()
                                                val talents = if (parts.size > 1) {
                                                    parts[1].split("、").mapNotNull { it.trim().toIntOrNull() }
                                                } else emptyList()
                                                renderSingleAgentView(agentName, talents, layoutAgents, hasAnyStar)                                            }
                                        }
                                    } catch (ex: Exception) { ex.printStackTrace() }
                                }
                            }
                            1 -> { // 截图
                                if (!detail.agentImageUrl.isNullOrEmpty()) {
                                    val iv = ImageView(this@StrategyDetailActivity).apply {
                                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 800)
                                        scaleType = ImageView.ScaleType.FIT_CENTER
                                    }
                                    Glide.with(this@StrategyDetailActivity).load(detail.agentImageUrl).into(iv)
                                    layoutAgents.addView(iv)
                                }
                            }
                            2 -> { // 文字
                                if (!detail.agentTextDesc.isNullOrEmpty()) {
                                    val tv = TextView(this@StrategyDetailActivity).apply {
                                        text = detail.agentTextDesc
                                        setTextColor(android.graphics.Color.WHITE)
                                        textSize = 14f
                                    }
                                    layoutAgents.addView(tv)
                                }
                            }
                        }

                        // --- 模块 3：图文说明 ---
                        val rvText = findViewById<RecyclerView>(R.id.rv_detail_text)
                        val glassContainer = rvText.parent as LinearLayout
                        val outerContainer = glassContainer.parent as LinearLayout

                        rvText.visibility = View.GONE // 隐藏没用的 RecyclerView

                        // 清除之前动态添加的旧视图，防止重复叠加
                        outerContainer.findViewWithTag<View>("DYNAMIC_DESC_TITLE")?.let { outerContainer.removeView(it) }
                        glassContainer.findViewWithTag<View>("DYNAMIC_DESC_CONTENT")?.let { glassContainer.removeView(it) }

                        if (!detail.content.isNullOrEmpty()) {
                            glassContainer.visibility = View.VISIBLE // 显示深色玻璃框

                            // (A) 在外面添加金色小标题
                            val tvDescTitle = TextView(this@StrategyDetailActivity).apply {
                                tag = "DYNAMIC_DESC_TITLE"
                                text = "◆ 补充说明"
                                setTextColor(android.graphics.Color.parseColor("#E5C07B"))
                                textSize = 16f
                                setTypeface(null, Typeface.BOLD)
                                layoutParams = LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                                ).apply { bottomMargin = (15 * resources.displayMetrics.density).toInt() }
                            }
                            // 将标题插在玻璃框的正上方
                            outerContainer.addView(tvDescTitle, outerContainer.indexOfChild(glassContainer))

                            // (B) 在深色玻璃框里面添加正文文本
                            val tvDescContent = TextView(this@StrategyDetailActivity).apply {
                                tag = "DYNAMIC_DESC_CONTENT"
                                text = detail.content
                                setTextColor(android.graphics.Color.parseColor("#E0E0E0"))
                                textSize = 14f
                            }
                            glassContainer.addView(tvDescContent)
                        } else {
                            // 如果没有说明，把整个深色玻璃框隐藏掉
                            glassContainer.visibility = View.GONE
                        }
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this@StrategyDetailActivity, "未找到该攻略: ${e?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }

    private fun checkHasAnyStar(agents: List<String?>?): Boolean {
        if (agents == null) return false
        return agents.any { raw ->
            if (raw.isNullOrBlank()) return@any false
            val agentName = raw.split("-")[0].trim()
            val pureName = agentName.substringBefore(" ").trim()
            val match = Regex("^(\\d+)(.*)").find(pureName)
            if (match != null && (match.groupValues[1].toIntOrNull() ?: 0) > 0) {
                true
            } else if (agentName.contains(" ")) {
                val suffix = agentName.substringAfter(" ").trim()
                suffix == "觉醒" || suffix.contains("⭐") || suffix.contains("★")
            } else {
                false
            }
        }
    }
    // 🛠️ 辅助 UI 渲染方法 (保持不变)
    // ==========================================
    private fun renderSingleAgentView(agentDisplayName: String, talents: List<Int>, parentLayout: LinearLayout, forceReserveSpace: Boolean = false) {
        val agentView = layoutInflater.inflate(R.layout.item_strategy_agent, parentLayout, false)
        val ivAvatar = agentView.findViewById<ImageView>(R.id.iv_agent_avatar)
        val tvName = agentView.findViewById<TextView>(R.id.tv_agent_name)
        val tvStar = agentView.findViewById<TextView>(R.id.tv_agent_star)
        val layoutTalents = agentView.findViewById<LinearLayout>(R.id.layout_talents)

        var pureName = agentDisplayName.substringBefore(" ").trim()
        var starText = ""

        val match = Regex("^(\\d+)(.*)").find(pureName)
        if (match != null) {
            val starNum = match.groupValues[1].toIntOrNull() ?: 0
            pureName = match.groupValues[2].trim()
            if (starNum >= 6) {
                starText = "觉醒"
            } else if (starNum in 1..5) {
                starText = "★".repeat(starNum)
            }
        } else if (agentDisplayName.contains(" ")) {
            val suffix = agentDisplayName.substringAfter(" ").trim()
            if (suffix == "觉醒" || suffix.contains("⭐") || suffix.contains("★")) {
                starText = suffix.replace("⭐", "★")
            }
        }

        tvName.text = pureName
        if (starText.isNotEmpty()) {
            tvStar.text = starText
            tvStar.visibility = View.VISIBLE
        } else {
            // 🔴 核心对齐逻辑：如果队友有星级，我就隐身占位 (INVISIBLE)；如果全队都没星级，我就彻底消失 (GONE)
            tvStar.visibility = if (forceReserveSpace) View.INVISIBLE else View.GONE
        }

        if (talents.isNotEmpty()) {
            layoutTalents.visibility = View.VISIBLE
            val agentData = com.example.yuanassist.model.AgentRepository.AGENT_MAP[pureName]

            talents.forEach { talentId ->
                val rawTalentText = agentData?.talents?.get(talentId) ?: "天赋$talentId"
                val (colorHex, displayText) = when {
                    rawTalentText.startsWith("橙") -> Pair("#FFA726", rawTalentText.substring(1))
                    rawTalentText.startsWith("紫") -> Pair("#B388FF", rawTalentText.substring(1))
                    else -> Pair("#64B5F6", rawTalentText)
                }
                val mainColor = android.graphics.Color.parseColor(colorHex)

                val tvTalent = TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = (3 * resources.displayMetrics.density).toInt()
                    }
                    text = displayText
                    textSize = 9f
                    setTextColor(mainColor)
                    gravity = android.view.Gravity.CENTER
                    maxLines = 1

                    background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = 4f * resources.displayMetrics.density
                        setStroke((1 * resources.displayMetrics.density).toInt(), mainColor)
                        setColor(android.graphics.Color.argb(38, android.graphics.Color.red(mainColor), android.graphics.Color.green(mainColor), android.graphics.Color.blue(mainColor)))
                    }
                    val pad = (2 * resources.displayMetrics.density).toInt()
                    setPadding(pad, pad, pad, pad)
                }
                layoutTalents.addView(tvTalent)
            }
        } else {
            layoutTalents.visibility = View.GONE
        }

        try {
            val bitmap = android.graphics.BitmapFactory.decodeStream(assets.open("$pureName.png"))
            ivAvatar.setImageBitmap(bitmap)
        } catch (e: Exception) {
            ivAvatar.setImageResource(R.drawable.ic_launcher_background)
        }

        parentLayout.addView(agentView)
    }

    private fun findAgentImageInAssets(agentName: String): String? {
        return try {
            val assetsList = assets.list("") ?: return null
            val targetName = agentName.trim()
            val matchedFile = assetsList.find { it.substringBeforeLast(".") == targetName }
            if (matchedFile != null) "file:///android_asset/$matchedFile" else null
        } catch (e: Exception) { null }
    }
    private fun importScriptToService(scriptContent: String, configJson: String, instructionsJson: String, agentsJson: String) {
        val realScriptContent = scriptContent.replace("\\n", "\n").replace("\\t", "\t")
        val intent = Intent(this, YuanAssistService::class.java).apply {
            action = "ACTION_IMPORT_SCRIPT"
            putExtra("SCRIPT_CONTENT", realScriptContent)
            if (configJson.isNotEmpty()) {
                putExtra("CONFIG_JSON", configJson)
            }
            if (instructionsJson.isNotEmpty()) {
                putExtra("INSTRUCTIONS_JSON", instructionsJson)
            }
            if (agentsJson.isNotEmpty()) {
                putExtra("AGENTS_JSON", agentsJson)
            }
        }
        startService(intent)
        Toast.makeText(this, "脚本及配置已发送至悬浮窗", Toast.LENGTH_LONG).show()
    }

    private fun createReadOnlyTable(tableData: List<com.example.yuanassist.ui.UploadTurnItem>): View {
        val tableContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = (25 * resources.displayMetrics.density).toInt()
            }
            setBackgroundColor(android.graphics.Color.parseColor("#444444"))
            setPadding(2, 2, 2, 2)
        }

        fun createCell(textStr: String, weight: Float, isHeader: Boolean = false): TextView {
            return TextView(this@StrategyDetailActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight).apply {
                    setMargins(1, 1, 1, 1)
                }
                text = textStr
                textSize = if (isHeader) 13f else 12f
                setTextColor(android.graphics.Color.parseColor(if (isHeader) "#E5C07B" else "#E0E0E0"))
                gravity = Gravity.CENTER
                setBackgroundColor(android.graphics.Color.parseColor(if (isHeader) "#2D2D2D" else "#1A1A1A"))
                val padV = (10 * resources.displayMetrics.density).toInt()
                setPadding(0, padV, 0, padV)
            }
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        headerRow.addView(createCell("回合", 1.2f, true))
        for (i in 1..5) {
            headerRow.addView(createCell(i.toString(), 1f, true))
        }
        headerRow.addView(createCell("备注", 2f, true))
        tableContainer.addView(headerRow)

        tableData.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            row.addView(createCell(item.turnNum.toString(), 1.2f))
            for (i in 0 until 5) {
                val actionText = item.actions.getOrNull(i)?.takeIf { it.isNotBlank() } ?: "-"
                row.addView(createCell(actionText, 1f))
            }
            val remarkText = try {
                item.javaClass.getDeclaredMethod("getRemark").invoke(item) as? String ?: ""
            } catch (e: Exception) {
                // 如果没有 getRemark 方法，尝试通过 when 判断具体类型或者直接降级
                ""
            }
            row.addView(createCell(remarkText, 2f))

            tableContainer.addView(row)
        }
        return tableContainer
    }
    private fun parseScriptContentToTableData(text: String): List<com.example.yuanassist.ui.UploadTurnItem> {
        val realText = text.replace("\\n", "\n").replace("\\t", "\t")

        val lines = realText.split("\n")
        val items = mutableListOf<com.example.yuanassist.ui.UploadTurnItem>()
        var currentTurn = 1

        for (line in lines) {
            val trimLine = line.trim()
            if (trimLine.isEmpty()) continue

            val parts = if (trimLine.contains("\t")) {
                trimLine.split("\t")
            } else {
                trimLine.split(Regex("\\s+"))
            }

            val startIndex = if (parts.isNotEmpty() && (parts[0].contains("回") || parts[0].all { it.isDigit() })) 1 else 0

            val actions = mutableListOf<String>()
            var charIdx = 0
            for (i in startIndex until parts.size) {
                if (charIdx >= 5) break
                val actionText = parts[i].trim()
                actions.add(if (actionText == "-") "" else actionText)
                charIdx++
            }
            while (actions.size < 5) actions.add("")

            items.add(com.example.yuanassist.ui.UploadTurnItem(currentTurn, actions))
            currentTurn++
        }
        return items
    }
}