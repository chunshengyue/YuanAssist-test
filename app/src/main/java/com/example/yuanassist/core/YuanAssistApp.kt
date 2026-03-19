package com.example.yuanassist.core

import android.app.Application
import android.util.Log
import cn.bmob.v3.Bmob

class YuanAssistApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Bmob.initialize(this, "ce7c10b39d790e195d0f3e11bf1945ee")
        } catch (e: Exception) {
            Log.e("YuanAssistApp", "Bmob 初始化失败: ${e.message}", e)
        }
    }
}