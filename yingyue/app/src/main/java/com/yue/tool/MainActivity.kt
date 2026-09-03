package com.yue.tool

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.yue.tool.data.ThemePrefs
import com.yue.tool.databinding.ActivityMainBinding
import com.yue.tool.ui.DownloadsFragment
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
            switchTo(item.itemId)
            true
        }

        // 全新启动：按记忆的标签初始化（selectedItemId 会触发监听器完成切换）
        // 主题切换重建：FragmentManager 自动恢复各 Fragment，仅需同步底部栏选中项，
        // 监听器会调 show/hide 修正可见性
        val lastTab = ThemePrefs.getLastTab(this)
        when {
            lastTab == R.id.nav_downloads || lastTab == R.id.nav_settings ->
                binding.bottomNav.selectedItemId = lastTab
            savedInstanceState == null -> switchTo(R.id.nav_home)
        }
    }

    private fun switchTo(itemId: Int) {
        ThemePrefs.setLastTab(this, itemId)
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        // 隐藏所有非目标 Fragment
        listOf(TAG_HOME, TAG_DOWNLOADS, TAG_SETTINGS).forEach { tag ->
            fm.findFragmentByTag(tag)?.let { if (!it.isHidden) tx.hide(it) }
        }
        // 显示目标：不存在则按需创建
        val target: Fragment = when (itemId) {
            R.id.nav_downloads -> fm.findFragmentByTag(TAG_DOWNLOADS)
                ?: DownloadsFragment().also { tx.add(R.id.fragmentContainer, it, TAG_DOWNLOADS) }
            R.id.nav_settings -> fm.findFragmentByTag(TAG_SETTINGS)
                ?: SettingsFragment().also { tx.add(R.id.fragmentContainer, it, TAG_SETTINGS) }
            else -> fm.findFragmentByTag(TAG_HOME)
                ?: HomeFragment().also { tx.add(R.id.fragmentContainer, it, TAG_HOME) }
        }
        if (target.isHidden) tx.show(target)
        tx.commit()
    }

    companion object {
        private const val TAG_HOME = "home"
        private const val TAG_DOWNLOADS = "downloads"
        private const val TAG_SETTINGS = "settings"
    }
}
