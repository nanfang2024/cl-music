package com.yue.tool

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.yue.tool.data.ThemePrefs
import com.yue.tool.databinding.ActivityMainBinding
import com.yue.tool.player.PlayerManager
import com.yue.tool.ui.DownloadsFragment
import com.yue.tool.ui.HomeFragment
import com.yue.tool.ui.SettingsFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题已由 App.onCreate() 应用，无需在此重复调用
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 绑定迷你播放器
        PlayerManager.bind(
            bar = binding.miniPlayer,
            cover = binding.miniCover,
            name = binding.miniName,
            artist = binding.miniArtist,
            btnPlay = binding.miniBtnPlay
        )
        // 停止按钮
        binding.miniBtnStop.setOnClickListener {
            PlayerManager.stop()
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            switchTo(item.itemId)
            true
        }

        val lastTab = ThemePrefs.getLastTab(this)
        when {
            lastTab == R.id.nav_downloads || lastTab == R.id.nav_settings ->
                binding.bottomNav.selectedItemId = lastTab
            savedInstanceState == null -> switchTo(R.id.nav_home)
        }
    }

    override fun onPause() {
        super.onPause()
        // Activity 切后台时暂停播放
        if (PlayerManager.getCurrentTrackId() != null) {
            PlayerManager.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        PlayerManager.unbind()
        PlayerManager.stop()
    }

    private fun switchTo(itemId: Int) {
        ThemePrefs.setLastTab(this, itemId)
        val fm = supportFragmentManager
        val tx = fm.beginTransaction()
        listOf(TAG_HOME, TAG_DOWNLOADS, TAG_SETTINGS).forEach { tag ->
            fm.findFragmentByTag(tag)?.let { if (!it.isHidden) tx.hide(it) }
        }
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
