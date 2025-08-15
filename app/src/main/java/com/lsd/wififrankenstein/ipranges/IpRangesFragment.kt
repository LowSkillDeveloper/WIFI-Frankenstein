package com.lsd.wififrankenstein.ui.ipranges

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentIpRangesBinding

class IpRangesFragment : Fragment() {

    private var _binding: FragmentIpRangesBinding? = null
    private val binding get() = _binding!!
    private val viewModel: IpRangesViewModel by viewModels()
    private lateinit var adapter: IpRangesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentIpRangesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupViews()
        observeViewModel()

        viewModel.loadSources()
    }

    private fun setupRecyclerView() {
        adapter = IpRangesAdapter(
            onCopyClick = { range ->
                copyToClipboard(range.range)
            },
            onSelectionChanged = { selectedItems ->
                updateSelectionUI(selectedItems.size)
            }
        )
        binding.resultsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.resultsRecyclerView.adapter = adapter
    }

    private fun setupViews() {
        binding.searchButton.setOnClickListener {
            val latitude = binding.latitudeInput.text.toString().trim()
            val longitude = binding.longitudeInput.text.toString().trim()
            val radius = binding.radiusInput.text.toString().trim()

            if (validateInput(latitude, longitude, radius)) {
                val selectedSources = getSelectedSources()
                if (selectedSources.isNotEmpty()) {
                    viewModel.searchIpRanges(
                        latitude.toDouble(),
                        longitude.toDouble(),
                        radius.toDouble(),
                        selectedSources
                    )
                } else {
                    showError(getString(R.string.select_at_least_one_source))
                }
            }
        }

        binding.selectAllButton.setOnClickListener {
            adapter.selectAll()
        }

        binding.copySelectedButton.setOnClickListener {
            val selectedItems = adapter.getSelectedItems()
            if (selectedItems.isNotEmpty()) {
                copySelectedToClipboard(selectedItems)
            } else {
                showError(getString(R.string.no_ranges_selected))
            }
        }
    }

    private fun observeViewModel() {
        viewModel.sources.observe(viewLifecycleOwner) { sources ->
            setupSourceCheckboxes(sources)
        }

        viewModel.ipRanges.observe(viewLifecycleOwner) { ranges ->
            adapter.submitList(ranges)

            binding.statusText.visibility = View.VISIBLE
            binding.statusText.text = if (ranges.isEmpty()) {
                getString(R.string.no_ip_ranges_found)
            } else {
                getString(R.string.ip_ranges_found, ranges.size)
            }

            binding.actionButtonsContainer.visibility = if (ranges.isNotEmpty()) View.VISIBLE else View.GONE
            updateSelectionUI(0)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.searchButton.isEnabled = !isLoading

            if (isLoading) {
                binding.statusText.visibility = View.VISIBLE
                binding.statusText.text = getString(R.string.searching_ip_ranges)
                binding.actionButtonsContainer.visibility = View.GONE
            }
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                showError(it)
                viewModel.clearError()
            }
        }
    }

    private fun setupSourceCheckboxes(sources: List<IpRangeSource>) {
        binding.sourcesContainer.removeAllViews()

        sources.forEach { source ->
            val checkBox = MaterialCheckBox(requireContext()).apply {
                text = source.name
                isChecked = source.isSelected
                setOnCheckedChangeListener { _, isChecked ->
                    viewModel.updateSourceSelection(source.id, isChecked)
                }
            }
            binding.sourcesContainer.addView(checkBox)
        }
    }

    private fun updateSelectionUI(selectedCount: Int) {
        if (selectedCount > 0) {
            binding.selectionCountText.visibility = View.VISIBLE
            binding.selectionCountText.text = getString(R.string.selected_count, selectedCount)
            binding.selectAllButton.text = getString(R.string.clear_selection)
            binding.selectAllButton.setOnClickListener {
                adapter.clearSelection()
            }
        } else {
            binding.selectionCountText.visibility = View.GONE
            binding.selectAllButton.text = getString(R.string.select_all)
            binding.selectAllButton.setOnClickListener {
                adapter.selectAll()
            }
        }
    }

    private fun validateInput(latitude: String, longitude: String, radius: String): Boolean {
        var isValid = true

        val lat = latitude.toDoubleOrNull()
        if (lat == null || lat < -90 || lat > 90) {
            binding.latitudeInputLayout.error = getString(R.string.invalid_coordinates)
            isValid = false
        } else {
            binding.latitudeInputLayout.error = null
        }

        val lon = longitude.toDoubleOrNull()
        if (lon == null || lon < -180 || lon > 180) {
            binding.longitudeInputLayout.error = getString(R.string.invalid_coordinates)
            isValid = false
        } else {
            binding.longitudeInputLayout.error = null
        }

        val rad = radius.toDoubleOrNull()
        if (rad == null || rad < 0.1 || rad > 25) {
            binding.radiusInputLayout.error = getString(R.string.invalid_radius)
            isValid = false
        } else {
            binding.radiusInputLayout.error = null
        }

        return isValid
    }

    private fun getSelectedSources(): List<String> {
        val selectedSources = mutableListOf<String>()
        for (i in 0 until binding.sourcesContainer.childCount) {
            val checkBox = binding.sourcesContainer.getChildAt(i) as MaterialCheckBox
            if (checkBox.isChecked) {
                selectedSources.add(viewModel.sources.value?.get(i)?.id ?: "")
            }
        }
        return selectedSources
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(getString(R.string.range), text)
        clipboard.setPrimaryClip(clip)
        showError(getString(R.string.copied_to_clipboard))
    }

    private fun copySelectedToClipboard(ranges: List<IpRangeResult>) {
        val text = ranges.joinToString("\n") { "${it.range} - ${it.description}" }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("IP Ranges", text)
        clipboard.setPrimaryClip(clip)
        showError(getString(R.string.ranges_copied, ranges.size))
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}