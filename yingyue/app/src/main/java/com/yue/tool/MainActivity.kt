package com.yue.tool

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.yue.tool.data.ThemePrefs
import com.yue.tool.databinding.ActivityMainBinding
import com.yue.tool.ui.HomeFragment
import com.yue.tool.ui.SettingsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePrefs.apply(ThemePrefs.getMode(this)) // 必须在视图创建前应用
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> { switchToHome(); true }
                R.id.nav_settings -> { switchToSettings(); true }
                else -> false
            }
        }

        // 全新启动：按记忆的标签初始化（selectedItemId 会触发监听器完成切换）
        // 主题切换重建：FragmentManager 自动恢复两个 Fragment，仅需同步底部栏选中项，
        // 监听器会调 show/hide 修正可见性
        if (ThemePrefs.getLastTab(this) == R.id.nav_settings) {
            binding.bottomNav.selectedItemId = R.id.nav_settings
        } else if (savedInstanceState == null) {
            switchToHome()
        }
    }

    private fun switchToHome() {
        ThemePrefs.setLastTab(this, R.id.nav_home)
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        fm.findFragmentByTag(TAG_SETTINGS)?.let { tx.hide(it) }
        val home = fm.findFragmentByTag(TAG_HOME) as? HomeFragment
            ?: HomeFragment().also { tx.add(R.id.fragmentContainer, it, TAG_HOME) }
        tx.show(home)
        tx.commit()
    }

    private fun switchToSettings() {
        ThemePrefs.setLastTab(this, R.id.nav_settings)
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        fm.findFragmentByTag(TAG_HOME)?.let { tx.hide(it) }
        val settings = fm.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment
            ?: SettingsFragment().also { tx.add(R.id.fragmentContainer, it, TAG_SETTINGS) }
        tx.show(settings)
        tx.commit()
    }

    companion object {
        private const val TAG_HOME = "home"
        private const val TAG_SETTINGS = "settings"
    }
}
