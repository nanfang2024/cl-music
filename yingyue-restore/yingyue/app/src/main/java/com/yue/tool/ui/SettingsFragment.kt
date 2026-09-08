package com.yue.tool.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.yue.tool.R
import com.yue.tool.data.ThemePrefs
import com.yue.tool.databinding.FragmentSettingsBinding
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // 主题选择
        val mode = ThemePrefs.getMode(requireContext())
        binding.radioSystem.isChecked = mode == ThemePrefs.MODE_SYSTEM
        binding.radioLight.isChecked = mode == ThemePrefs.MODE_LIGHT
        binding.radioDark.isChecked = mode == ThemePrefs.MODE_DARK

        binding.themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val newMode = when (checkedId) {
                R.id.radioLight -> ThemePrefs.MODE_LIGHT
                R.id.radioDark -> ThemePrefs.MODE_DARK
                else -> ThemePrefs.MODE_SYSTEM
            }
            ThemePrefs.setMode(requireContext(), newMode)
            ThemePrefs.apply(newMode)
        }

        // 下载路径展示
        binding.textPathValue.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Environment.DIRECTORY_MUSIC + "/" + com.yue.tool.download.Downloader.DIR_NAME
        } else {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                com.yue.tool.download.Downloader.DIR_NAME
            ).absolutePath
        }

        // Telegram 频道
        binding.cardTelegram.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/ngtool")))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.open_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
