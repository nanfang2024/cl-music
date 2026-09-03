package com.yue.tool.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.yue.tool.R
import com.yue.tool.data.DownloadHistory
import com.yue.tool.data.DownloadRecord
import com.yue.tool.databinding.FragmentDownloadsBinding
import java.io.File

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: DownloadHistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = DownloadHistoryAdapter(
            onShare = { shareRecord(it) },
            onDelete = { confirmDelete(it) }
        )
        binding.listDownloads.layoutManager = LinearLayoutManager(requireContext())
        binding.listDownloads.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) refreshList()
    }

    // show/hide 切换不会触发 onResume，必须在 onHiddenChanged 里刷新
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) refreshList()
    }

    private fun refreshList() {
        val records = DownloadHistory.list(requireContext())
        adapter.submit(records)
        binding.textEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
    }

    // ==================== 操作 ====================

    /** 校验记录对应的文件是否仍存在，返回可用的 Uri 或 null */
    private fun resolveUri(record: DownloadRecord): Uri? {
        return try {
            val uri = Uri.parse(record.uri)
            if (uri.scheme == "content") {
                requireContext().contentResolver.query(
                    uri, arrayOf(MediaStore.Audio.Media._ID), null, null, null
                )?.use { if (it.moveToFirst()) return uri }
                null
            } else {
                val f = File(uri.path ?: return null)
                if (f.exists()) uri else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun shareRecord(record: DownloadRecord) {
        val uri = resolveUri(record) ?: run {
            toast(getString(R.string.file_missing))
            return
        }
        val shareUri = if (uri.scheme == "content") {
            uri
        } else {
            FileProvider.getUriForFile(
                requireContext(), "com.yue.tool.fileprovider", File(uri.path!!)
            )
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(record.format)
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.share_to)))
        } catch (e: Exception) {
            toast(getString(R.string.share_failed))
        }
    }

    private fun confirmDelete(record: DownloadRecord) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_title))
            .setMessage(getString(R.string.delete_msg, record.name))
            .setPositiveButton(getString(R.string.confirm)) { _, _ ->
                // Major #10: 删除记录的同时删除文件
                deleteFile(record)
                DownloadHistory.remove(requireContext(), record)
                adapter.remove(record)
                if (adapter.itemCount == 0) binding.textEmpty.visibility = View.VISIBLE
                toast(getString(R.string.deleted))
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /** 删除实际文件（content URI 用 contentResolver，file URI 用 File.delete） */
    private fun deleteFile(record: DownloadRecord) {
        try {
            val uri = Uri.parse(record.uri)
            if (uri.scheme == "content") {
                requireContext().contentResolver.delete(uri, null, null)
            } else {
                File(uri.path ?: return).takeIf { it.exists() }?.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun mimeFor(format: String): String = when (format.lowercase()) {
        "flac" -> "audio/flac"
        "m4a" -> "audio/mp4"
        "ogg" -> "audio/ogg"
        "wav" -> "audio/wav"
        else -> "audio/mpeg"
    }

    private fun toast(msg: String) {
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
