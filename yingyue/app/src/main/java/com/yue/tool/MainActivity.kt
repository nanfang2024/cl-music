package com.yue.tool

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AnimationUtils
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.yue.tool.data.ThemePrefs
import com.yue.tool.databinding.ActivityMainBinding
import com.yue.tool.player.PlayerManager
import com.yue.tool.ui.DownloadsFragment
import com.yue.tool.ui.HomeFragment
import com.yue.tool.ui.PlayerFragment
import com.yue.tool.ui.SettingsFragment

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    // Android 13+ 需要通知权限才能显示常驻播放通知
    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 主题已由 App.onCreate() 应用，无需在此重复调用
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // 绑定迷你播放器
        PlayerManager.bindMini(
            bar = binding.miniPlayer,
            cover = binding.miniCover,
            name = binding.miniName,
            artist = binding.miniArtist,
            btnPlay = binding.miniBtnPlay,
            progress = binding.miniProgress
        )
        // 停止按钮
        binding.miniBtnStop.setOnClickListener {
            PlayerManager.stop()
        }
        // 点迷你条其他区域 → 打开全屏播放器
        binding.miniPlayer.setOnClickListener { openPlayer() }

        // 返回键/收起播放器时同步隐藏浮层
        supportFragmentManager.addOnBackStackChangedListener {
            if (supportFragmentManager.findFragmentByTag(TAG_PLAYER) == null &&
                binding.playerOverlay.visibility == View.VISIBLE
            ) {
                binding.playerOverlay.startAnimation(
                    AnimationUtils.loadAnimation(this, R.anim.slide_down)
                )
                binding.playerOverlay.visibility = View.GONE
            }
        }
        // 旋转屏幕重建时恢复浮层可见性
        if (supportFragmentManager.findFragmentByTag(TAG_PLAYER) != null) {
            binding.playerOverlay.visibility = View.VISIBLE
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

    /** 打开全屏播放器页面（浮层覆盖含底部导航的整个界面） */
    fun openPlayer() {
        val fm = supportFragmentManager
        if (fm.findFragmentByTag(TAG_PLAYER) != null) return
        binding.playerOverlay.visibility = View.VISIBLE
        binding.playerOverlay.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.slide_up)
        )
        fm.beginTransaction()
            .add(R.id.playerOverlay, PlayerFragment(), TAG_PLAYER)
            .addToBackStack(TAG_PLAYER)
            .commit()
    }

    // 注意：不再在 onPause 暂停 —— 由前台服务 + WakeLock 保证熄屏/切后台持续播放
    override fun onDestroy() {
        super.onDestroy()
        PlayerManager.unbindMini()
        // 不调用 stop()：Activity 销毁后由前台服务继续播放，
        // 通知栏可控制，重新打开 App 后迷你条自动恢复
    }

    /** 跳转到下载页（Snackbar「查看」用） */
    fun switchToDownloads() {
        binding.bottomNav.selectedItemId = R.id.nav_downloads
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
        private const val TAG_PLAYER = "player"
    }
}
