package com.yue.tool.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.yue.tool.R
import com.yue.tool.data.ThemePrefs
import com.yue.tool.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始选中态（避免在 onResume 里反复 set 触发监听器）
        when (ThemePrefs.getMode(requireContext())) {
            ThemePrefs.MODE_LIGHT -> binding.radioLight.isChecked = true
            ThemePrefs.MODE_DARK -> binding.radioDark.isChecked = true
            else -> binding.radioSystem.isChecked = true
        }

        // 主题三态切换
        binding.groupTheme.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.radioLight -> ThemePrefs.MODE_LIGHT
                R.id.radioDark -> ThemePrefs.MODE_DARK
                else -> ThemePrefs.MODE_SYSTEM
            }
            ThemePrefs.setMode(requireContext(), mode) // 持久化并触发 Activity 重建
        }

        // Telegram 频道入口
        binding.cardTelegram.setOnClickListener { openTelegram() }
    }

    private fun openTelegram() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/ngtool")))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(requireContext(), R.string.no_app_to_handle, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
