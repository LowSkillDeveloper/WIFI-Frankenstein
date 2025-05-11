package com.lsd.wififrankenstein.ui.api3wifi

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.snackbar.Snackbar
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentApi3wifiBinding

class API3WiFiFragment : Fragment() {

    private var _binding: FragmentApi3wifiBinding? = null
    private val binding get() = _binding!!
    private val viewModel: API3WiFiViewModel by viewModels()

    private var currentMethodParams: API3WiFiMethodParams? = null
    private val apiMethods = listOf("apiquery", "apiwps", "apidev", "apiranges")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentApi3wifiBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        observeViewModel()
        viewModel.loadApiServers()
    }

    private fun setupUI() {
        setupServerSpinner()
        setupMethodSpinner()
        setupRequestTypeChips()
        setupExecuteButton()
    }

    private fun setupServerSpinner() {
        (binding.serverSpinnerLayout.editText as? AutoCompleteTextView)?.apply {
            setOnItemClickListener { _, _, _, _ ->
                clearMethodParams()
            }
        }
    }

    private fun setupMethodSpinner() {
        val methodAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            apiMethods
        )

        (binding.methodSpinnerLayout.editText as? AutoCompleteTextView)?.apply {
            setAdapter(methodAdapter)
            setOnItemClickListener { _, _, position, _ ->
                onMethodSelected(apiMethods[position])
            }
        }
    }

    private fun setupRequestTypeChips() {
        binding.chipGet.isChecked = true
        binding.requestTypeChipGroup.setOnCheckedStateChangeListener { _, _ ->
            validateForm()
        }
    }

    private fun setupExecuteButton() {
        binding.executeButton.setOnClickListener {
            if (validateForm()) {
                executeRequest()
            }
        }
    }

    private fun onMethodSelected(methodName: String) {
        clearMethodParams()
        currentMethodParams = API3WiFiMethodParams.create(methodName)
        currentMethodParams?.let { params ->
            binding.methodParamsContainer.addView(
                params.createView(requireContext(), binding.methodParamsContainer)
            )
        }
        validateForm()
    }

    private fun clearMethodParams() {
        binding.methodParamsContainer.removeAllViews()
        currentMethodParams?.clear()
        currentMethodParams = null
        binding.responseText.text = null
    }

    private fun validateForm(): Boolean {
        val serverText = (binding.serverSpinnerLayout.editText as? AutoCompleteTextView)?.text.toString()
        val methodText = (binding.methodSpinnerLayout.editText as? AutoCompleteTextView)?.text.toString()

        var isValid = true

        if (serverText.isEmpty()) {
            binding.serverSpinnerLayout.error = getString(R.string.select_server_error)
            isValid = false
        } else {
            binding.serverSpinnerLayout.error = null
        }

        if (methodText.isEmpty()) {
            binding.methodSpinnerLayout.error = getString(R.string.select_method_error)
            isValid = false
        } else {
            binding.methodSpinnerLayout.error = null
        }

        currentMethodParams?.validate()
        isValid = isValid && (currentMethodParams?.isValid() == true)

        binding.executeButton.isEnabled = isValid
        return isValid
    }

    private fun executeRequest() {
        val serverUrl = (binding.serverSpinnerLayout.editText as? AutoCompleteTextView)?.text.toString()
        val selectedServer = viewModel.apiServers.value?.find { it.path == serverUrl }

        if (selectedServer == null) {
            showError(getString(R.string.invalid_server))
            return
        }

        val requestType = when (binding.requestTypeChipGroup.checkedChipId) {
            R.id.chipGet -> API3WiFiViewModel.RequestType.GET
            R.id.chipPostForm -> API3WiFiViewModel.RequestType.POST_FORM
            R.id.chipPostJson -> API3WiFiViewModel.RequestType.POST_JSON
            else -> API3WiFiViewModel.RequestType.GET
        }

        val request = currentMethodParams?.getRequest(selectedServer.apiKey ?: "")
        if (request != null) {
            viewModel.executeRequest(serverUrl, request, requestType)
        } else {
            showError(getString(R.string.invalid_request_params))
        }
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun observeViewModel() {
        viewModel.apiServers.observe(viewLifecycleOwner) { servers ->
            setupServersAdapter(servers.map { it.path })
        }

        viewModel.requestResult.observe(viewLifecycleOwner) { result ->
            binding.responseText.setText(result)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.executeButton.isEnabled = !isLoading
        }
    }

    private fun setupServersAdapter(servers: List<String>) {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            servers
        )
        (binding.serverSpinnerLayout.editText as? AutoCompleteTextView)?.setAdapter(adapter)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}