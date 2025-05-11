package com.lsd.wififrankenstein.ui.dbsetup.localappdb

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.lsd.wififrankenstein.databinding.FragmentLocalDbViewerBinding

class LocalDbViewerFragment : Fragment() {

    private var _binding: FragmentLocalDbViewerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LocalDbViewerViewModel by viewModels()
    private lateinit var adapter: LocalDbRecordAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLocalDbViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupSearchButton()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = LocalDbRecordAdapter { record ->
            showCopyOptionsDialog(record)
        }
        binding.recyclerViewRecords.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewRecords.adapter = adapter
    }

    private fun setupSearchButton() {
        binding.chipFilterByName.isChecked = true
        binding.chipFilterByMac.isChecked = true

        binding.buttonSearch.setOnClickListener {
            val query = binding.editTextSearch.text.toString().trim()
            val filterByName = binding.chipFilterByName.isChecked
            val filterByMac = binding.chipFilterByMac.isChecked
            val filterByPassword = binding.chipFilterByPassword.isChecked
            val filterByWps = binding.chipFilterByWps.isChecked

            if (query.isNotEmpty()) {
                viewModel.searchRecords(query, filterByName, filterByMac, filterByPassword, filterByWps)
            }
        }
    }


    private fun observeViewModel() {
        viewModel.searchResults.observe(viewLifecycleOwner) { records ->
            adapter.submitList(records)
        }
    }

    //харкодед, иправить на стрингс

    private fun showCopyOptionsDialog(record: WifiNetwork) {
        val options = arrayOf("Копировать имя сети", "Копировать MAC адрес", "Копировать пароль", "Копировать WPS код")
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Выберите действие")
        builder.setItems(options) { dialog, which ->
            when (which) {
                0 -> copyToClipboard("Имя сети", record.wifiName)
                1 -> copyToClipboard("MAC адрес", record.macAddress)
                2 -> copyToClipboard("Пароль", record.wifiPassword ?: "Пароль отсутствует")
                3 -> copyToClipboard("WPS код", record.wpsCode ?: "WPS отсутствует")
            }
        }
        builder.show()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = ContextCompat.getSystemService(requireContext(), ClipboardManager::class.java)
        val clip = ClipData.newPlainText(label, text)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(requireContext(), "$label скопировано в буфер обмена", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
