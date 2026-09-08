package com.yue.tool

import android.app.Application
import com.yue.tool.data.ThemePrefs

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Major #12: 在 Application.onCreate 中提前应用主题
        // 确保 Activity 创建时主题已正确设置，避免重建时状态丢失
        ThemePrefs.apply(ThemePrefs.getMode(this))
    }
}
