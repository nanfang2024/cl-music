package com.yue.tool.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.yue.tool.R
import com.yue.tool.data.DownloadHistory
import com.yue.tool.data.DownloadRecord
import com.yue.tool.databinding.FragmentDownloadsBinding

class DownloadsFragment : Fragment() {

    private var _binding: FragmentDownloadsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: DownloadHistoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = DownloadHistoryAdapter(
            onPlay = { playRecord(it) },
            onShare = { shareRecord(it) },
            onDelete = { deleteRecord(it) }
        )
        binding.recyclerDownloads.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerDownloads.adapter = adapter
        refreshList()
    }

    /**
     * 底栏用 show/hide 切换，onResume 只在首次创建时触发一次；
     * 必须在 onHiddenChanged 里刷新，否则下载后回到本页列表不更新（v1.1 的缺陷）
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && _binding != null) refreshList()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) refreshList()
    }

    private fun refreshList() {
        val records = DownloadHistory.list(requireContext())
        adapter.submit(records)
        binding.textEmptyDownloads.isVisible = records.isEmpty()
        binding.recyclerDownloads.isVisible = records.isNotEmpty()
    }

    private fun resolveUri(record: DownloadRecord): Uri? {
        return try {
            if (record.uri.startsWith("content:")) {
                // Android 10+：MediaStore URI，检查是否仍有效
                val uri = record.uri.toUri()
                val cursor = requireContext().contentResolver.query(
                    uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null
                )
                if (cursor == null || !cursor.moveToFirst()) null else uri
            } else {
                val file = java.io.File(record.uri)
                if (!file.exists()) {
                    null
                } else {
                    androidx.core.content.FileProvider.getUriForFile(
                        requireContext(), "com.yue.tool.fileprovider", file
                    )
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun playRecord(record: DownloadRecord) {
        val uri = resolveUri(record)
        if (uri == null) {
            toast(getString(R.string.file_missing))
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeFor(record.format))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivitySafely(intent)
    }

    private fun shareRecord(record: DownloadRecord) {
        val uri = resolveUri(record)
        if (uri == null) {
            toast(getString(R.string.file_missing))
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(record.format)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivitySafely(Intent.createChooser(intent, record.name))
    }

    private fun deleteRecord(record: DownloadRecord) {
        DownloadHistory.remove(requireContext(), record)
        refreshList()
        Snackbar.make(binding.root, R.string.delete_done, Snackbar.LENGTH_SHORT).show()
    }

    private fun startActivitySafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.no_app_to_handle))
        }
    }

    private fun mimeFor(ext: String) = when (ext.lowercase()) {
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
