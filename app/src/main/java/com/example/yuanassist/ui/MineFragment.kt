package com.example.yuanassist.ui

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import cn.bmob.v3.BmobUser
import cn.bmob.v3.exception.BmobException
import cn.bmob.v3.listener.SaveListener
import cn.bmob.v3.listener.UpdateListener
import com.bumptech.glide.Glide
import com.example.yuanassist.R
import com.example.yuanassist.model.MyUser
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

class MineFragment : Fragment() {
    private lateinit var tvSyncData: TextView
    private lateinit var ivAvatar: ImageView
    private lateinit var tvNickname: TextView
    private lateinit var tvUsername: TextView
    private lateinit var btnEdit: ImageView
    private lateinit var btnLogin: Button

    // 弹窗相关变量
    private var avatarDialog: AlertDialog? = null
    private var dialogAvatarPreview: ImageView? = null
    private var tempAvatarUri: Uri? = null // 用于暂存用户选中的本地图片Uri

    // 🔴 注册系统相册回调：用户选完图片后，只更新弹窗里的预览图，不上传
    private val pickImageLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            tempAvatarUri = uri
            dialogAvatarPreview?.let {
                Glide.with(this).load(uri).circleCrop().into(it)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_mine, container, false)
        initViews(view)
        loadUserData()
        return view
    }

    private fun initViews(view: View) {
        ivAvatar = view.findViewById(R.id.iv_mine_avatar)
        tvNickname = view.findViewById(R.id.tv_mine_nickname)
        tvUsername = view.findViewById(R.id.tv_mine_username)
        btnEdit = view.findViewById(R.id.btn_edit_profile)
        btnLogin = view.findViewById(R.id.btn_one_click_login)
        tvSyncData = view.findViewById(R.id.tv_sync_data)
        btnLogin.setOnClickListener { performOneClickLogin() }

        // 🔴 1. 点击铅笔：修改昵称弹窗
        btnEdit.setOnClickListener {
            val currentUser = BmobUser.getCurrentUser(MyUser::class.java)
            if (currentUser != null) {
                showEditNicknameDialog(currentUser)
            } else {
                Toast.makeText(requireContext(), "请先创建或登录账号", Toast.LENGTH_SHORT).show()
            }
        }
        tvSyncData.setOnClickListener {
            syncDataFromServer()
        }
        // 🔴 2. 点击头像：修改头像弹窗
        ivAvatar.setOnClickListener {
            val currentUser = BmobUser.getCurrentUser(MyUser::class.java)
            if (currentUser != null) {
                showEditAvatarDialog(currentUser)
            }
        }
    }

    private fun loadUserData() {
        val prefs = requireContext().getSharedPreferences("user_cache", Context.MODE_PRIVATE)
        val currentUser = BmobUser.getCurrentUser(MyUser::class.java)

        if (currentUser != null) {
            val cachedNickname = prefs.getString("nickname", currentUser.nickname) ?: "热心玩家"
            val cachedAvatar = prefs.getString("avatarUrl", currentUser.avatarUrl)

            tvNickname.text = cachedNickname
            tvUsername.text = "设备ID: ${currentUser.username}"
            btnLogin.visibility = View.GONE
            btnEdit.visibility = View.VISIBLE
            tvSyncData.visibility = View.VISIBLE
            if (!cachedAvatar.isNullOrEmpty()) {
                Glide.with(this).load(cachedAvatar).circleCrop()
                    .placeholder(R.drawable.ic_launcher_background)
                    .error(R.drawable.ic_launcher_background).into(ivAvatar)
            }
        } else {
            tvNickname.text = "未登录"
            tvUsername.text = "点击下方按钮绑定当前设备"
            tvSyncData.visibility = View.GONE
            ivAvatar.setImageResource(R.drawable.ic_launcher_background)
            btnLogin.visibility = View.VISIBLE
            btnEdit.visibility = View.GONE
        }
    }

    // ==================== 昵称修改逻辑 ====================
    private fun showEditNicknameDialog(currentUser: MyUser) {
        val prefs = requireContext().getSharedPreferences("user_cache", Context.MODE_PRIVATE)
        val currentName = prefs.getString("nickname", currentUser.nickname) ?: ""

        val editText = EditText(requireContext()).apply {
            setText(currentName)
            setPadding(40, 40, 40, 40)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("修改昵称")
            .setView(editText)
            .setPositiveButton("确认") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != currentName) {
                    updateUserInBmob(currentUser.objectId, newName, null)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 头像修改逻辑 ====================
    private fun showEditAvatarDialog(currentUser: MyUser) {
        tempAvatarUri = null // 每次打开清空暂存
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_avatar_preview, null)

        dialogAvatarPreview = dialogView.findViewById(R.id.iv_dialog_avatar_preview)
        val btnSelect = dialogView.findViewById<Button>(R.id.btn_select_new_avatar)
        val btnCancel = dialogView.findViewById<Button>(R.id.btn_dialog_cancel)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btn_dialog_confirm)

        // 加载当前头像作为初始预览
        val prefs = requireContext().getSharedPreferences("user_cache", Context.MODE_PRIVATE)
        val currentAvatar = prefs.getString("avatarUrl", currentUser.avatarUrl)
        Glide.with(this).load(currentAvatar).circleCrop()
            .placeholder(R.drawable.ic_launcher_background).into(dialogAvatarPreview!!)

        avatarDialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        avatarDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)

        btnSelect.setOnClickListener { pickImageLauncher.launch("image/*") }

        btnCancel.setOnClickListener { avatarDialog?.dismiss() }

        // 🔴 点击确认：此时才开始转File -> 上传图床 -> 更新Bmob
        btnConfirm.setOnClickListener {
            if (tempAvatarUri != null) {
                btnConfirm.text = "上传中..."
                btnConfirm.isEnabled = false
                btnCancel.isEnabled = false
                btnSelect.isEnabled = false

                val cacheFile = uriToCacheFile(tempAvatarUri!!)
                if (cacheFile != null) {
                    uploadImageToImageBed(cacheFile,
                        onSuccess = { url ->
                            activity?.runOnUiThread {
                                updateUserInBmob(currentUser.objectId, null, url)
                                cacheFile.delete() // 上传完清理本地缓存文件
                            }
                        },
                        onError = { error ->
                            activity?.runOnUiThread {
                                Toast.makeText(context ?: return@runOnUiThread, "图片上传失败: $error", Toast.LENGTH_SHORT).show()
                                btnConfirm.text = "确认上传"
                                btnConfirm.isEnabled = true
                                btnCancel.isEnabled = true
                                btnSelect.isEnabled = true
                            }
                        }
                    )
                }
            } else {
                avatarDialog?.dismiss() // 没选新图直接点确认，就当取消处理
            }
        }

        avatarDialog?.show()
    }

    private fun updateUserInBmob(objectId: String, newNickname: String?, newAvatarUrl: String?) {
        val currentUser = BmobUser.getCurrentUser(MyUser::class.java)
        if (currentUser == null) {
            Toast.makeText(context ?: return, "状态异常，请重新登录", Toast.LENGTH_SHORT).show()
            return
        }

        // 修改屬性
        if (newNickname != null) currentUser.nickname = newNickname
        if (newAvatarUrl != null) currentUser.avatarUrl = newAvatarUrl

        currentUser.update(object : UpdateListener() {
            override fun done(e: BmobException?) {
                val ctx = context ?: return
                activity?.runOnUiThread {
                    if (e == null) {
                        Toast.makeText(ctx, "更新成功！", Toast.LENGTH_SHORT).show()

                        val prefs = ctx.getSharedPreferences("user_cache", Context.MODE_PRIVATE)
                        prefs.edit().apply {
                            if (newNickname != null) putString("nickname", newNickname)
                            if (newAvatarUrl != null) putString("avatarUrl", newAvatarUrl)
                            apply()
                        }

                        loadUserData()

                        avatarDialog?.dismiss()
                    } else {
                        avatarDialog?.findViewById<Button>(R.id.btn_dialog_confirm)?.run {
                            text = "确认上传"
                            isEnabled = true
                        }
                    }
                }
            }
        })
    }

    // ==================== 图床上传工具方法 ====================
    private fun uriToCacheFile(uri: Uri): File? {
        return try {
            val inputStream = requireContext().contentResolver.openInputStream(uri) ?: return null
            val file = File(requireContext().cacheDir, "avatar_${System.currentTimeMillis()}.png")
            file.outputStream().use { inputStream.copyTo(it) }
            file
        } catch (e: Exception) { null }
    }

    private fun uploadImageToImageBed(file: File, onSuccess: (String) -> Unit, onError: (String) -> Unit) {
        val uploadUrl = "https://img.scdn.io/api/v1.php"
        val client = okhttp3.OkHttpClient()
        val mediaType = "image/*".toMediaTypeOrNull()
        val fileBody = file.asRequestBody(mediaType)

        val requestBody = okhttp3.MultipartBody.Builder()
            .setType(okhttp3.MultipartBody.FORM)
            .addFormDataPart("image", file.name, fileBody)
            .addFormDataPart("outputFormat", "webp")
            .build()

        val request = okhttp3.Request.Builder().url(uploadUrl).post(requestBody).build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                onError("网络请求失败")
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    try {
                        val json = org.json.JSONObject(responseBody)
                        if (json.optBoolean("success")) {
                            onSuccess(json.optString("url"))
                        } else {
                            onError(json.optString("message", "上传被拒"))
                        }
                    } catch (e: Exception) { onError("JSON解析失败") }
                } else {
                    onError("HTTP ${response.code}")
                }
            }
        })
    }

    // ==================== 一键登录逻辑 ====================
    private fun performOneClickLogin() {
        btnLogin.isEnabled = false
        btnLogin.text = "正在验证设备..."
        val deviceId = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val user = MyUser().apply {
            username = deviceId
            setPassword("123456")
            nickname = "玩家_$deviceId"
        }

        user.signUp(object : SaveListener<MyUser>() {
            override fun done(u: MyUser?, e: BmobException?) {
                val ctx = context ?: return
                activity?.runOnUiThread {
                    if (e == null && u != null) {
                        Toast.makeText(ctx, "账号创建成功！", Toast.LENGTH_SHORT).show()
                        saveToLocalCache(u)
                        loadUserData()
                    } else if (e?.errorCode == 202) {
                        user.login(object : SaveListener<MyUser>() {
                            override fun done(lu: MyUser?, le: BmobException?) {
                                val ctx2 = context ?: return
                                activity?.runOnUiThread {
                                    if (le == null && lu != null) {
                                        saveToLocalCache(lu)
                                        loadUserData()
                                    } else {
                                        Toast.makeText(ctx2, "登录失败: ${le?.message}", Toast.LENGTH_SHORT).show()
                                        btnLogin.isEnabled = true
                                        btnLogin.text = "一键创建/登录该设备账号"
                                    }
                                }
                            }
                        })
                    } else {
                        Toast.makeText(ctx, "创建失败: ${e?.message}", Toast.LENGTH_SHORT).show()
                        btnLogin.isEnabled = true
                        btnLogin.text = "一键创建/登录该设备账号"
                    }
                }
            }
        })
    }

    private fun saveToLocalCache(user: MyUser) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences("user_cache", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("nickname", user.nickname)
            putString("avatarUrl", user.avatarUrl)
            apply()
        }
    }
    private fun syncDataFromServer() {
        val currentUser = BmobUser.getCurrentUser(MyUser::class.java)
        if (currentUser == null) {
            Toast.makeText(context ?: return, "未登录，无法同步", Toast.LENGTH_SHORT).show()
            return
        }

        tvSyncData.text = "⏳ 正在同步..."
        tvSyncData.isEnabled = false

        // 🔴 使用 BmobQuery 精确查找当前用户的最新数据，无视本地缓存
        val query = cn.bmob.v3.BmobQuery<MyUser>()
        query.getObject(currentUser.objectId, object : cn.bmob.v3.listener.QueryListener<MyUser>() {
            override fun done(user: MyUser?, e: BmobException?) {
                val ctx = context ?: return
                activity?.runOnUiThread {
                    tvSyncData.text = "🔄 重新获取线上资料"
                    tvSyncData.isEnabled = true

                    if (e == null && user != null) {
                        Toast.makeText(ctx, "同步成功！", Toast.LENGTH_SHORT).show()

                        // 1. 强行覆盖本地缓存
                        val prefs = ctx.getSharedPreferences("user_cache", Context.MODE_PRIVATE)
                        prefs.edit().apply {
                            putString("nickname", user.nickname)
                            putString("avatarUrl", user.avatarUrl)
                            apply()
                        }

                        // 2. 刷新当前界面的 UI
                        loadUserData()
                    } else {
                        Toast.makeText(ctx, "同步失败: ${e?.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
    }
}