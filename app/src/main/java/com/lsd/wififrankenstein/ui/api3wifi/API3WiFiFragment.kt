package com.lsd.wififrankenstein.ui.api3wifi

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentApi3wifiBinding

class API3WiFiFragment : Fragment() {

    private var _binding: FragmentApi3wifiBinding? = null
    private val binding get() = _binding!!
    private val viewModel: API3WiFiViewModel by viewModels()

    private var currentMethodParams: API3WiFiMethodParams? = null
    private val apiMethods = listOf("apiquery", "apiwps", "apidev", "apiranges")
    private var isAdvancedMode = false
    private var isSearchByMac = true

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

        showSimpleMode()
    }

    private fun setupUI() {
        setupModeSwitch()
        setupServerSpinner()
        setupMethodSpinner()
        setupRequestTypeChips()
        setupExecuteButton()
    }

    private fun setupModeSwitch() {
        binding.advancedModeSwitch.setOnCheckedChangeListener { _, isChecked ->
            isAdvancedMode = isChecked
            if (isChecked) {
                showAdvancedMode()
            } else {
                showSimpleMode()
            }
        }
    }

    private fun showSimpleMode() {
        binding.simpleModeContainer.visibility = View.VISIBLE
        binding.advancedModeContainer.visibility = View.GONE

        binding.simpleModeContainer.removeAllViews()
        val simpleView = LayoutInflater.from(requireContext()).inflate(
            R.layout.layout_simple_mode,
            binding.simpleModeContainer,
            false
        )

        val serverSpinnerLayout = simpleView.findViewById<TextInputLayout>(R.id.simpleServerSpinnerLayout)
        val serverSpinner = simpleView.findViewById<AutoCompleteTextView>(R.id.simpleServerSpinner)
        val searchTypeToggle = simpleView.findViewById<MaterialButtonToggleGroup>(R.id.simpleSearchTypeToggle)
        val inputLayout = simpleView.findViewById<TextInputLayout>(R.id.simpleInputLayout)
        val input = simpleView.findViewById<TextInputEditText>(R.id.simpleInput)
        val searchButton = simpleView.findViewById<com.google.android.material.button.MaterialButton>(R.id.simpleSearchButton)

        val servers = viewModel.apiServers.value?.map { it.path } ?: emptyList()
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            servers
        )
        serverSpinner.setAdapter(adapter)
        if (servers.isNotEmpty()) {
            serverSpinner.setText(servers[0], false)
        }

        searchTypeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                isSearchByMac = checkedId == R.id.buttonSearchMac
                if (isSearchByMac) {
                    inputLayout.hint = getString(R.string.enter_mac_address)
                } else {
                    inputLayout.hint = getString(R.string.enter_network_name)
                }
                input.text?.clear()
                inputLayout.error = null
            }
        }

        searchButton.setOnClickListener {
            val selectedServer = serverSpinner.text.toString()
            val inputText = input.text.toString().trim()

            if (selectedServer.isEmpty()) {
                serverSpinnerLayout.error = getString(R.string.select_server_error)
                return@setOnClickListener
            }

            if (inputText.isEmpty()) {
                inputLayout.error = getString(R.string.error_empty_input)
                return@setOnClickListener
            }

            serverSpinnerLayout.error = null
            inputLayout.error = null
            executeSimpleSearch(selectedServer, inputText)
        }

        binding.simpleModeContainer.addView(simpleView)
    }

    private fun showAdvancedMode() {
        binding.simpleModeContainer.visibility = View.GONE
        binding.advancedModeContainer.visibility = View.VISIBLE
    }

    private fun executeSimpleSearch(serverPath: String, input: String) {
        val server = viewModel.apiServers.value?.find { it.path == serverPath }
        if (server == null) {
            showError(getString(R.string.invalid_server))
            return
        }

        val request = if (isSearchByMac) {
            API3WiFiRequest.ApiQuery(
                key = server.apiKey ?: "",
                bssidList = listOf(input.uppercase()),
                essidList = null,
                exactPairs = null,
                sens = false
            )
        } else {
            API3WiFiRequest.ApiQuery(
                key = server.apiKey ?: "",
                bssidList = null,
                essidList = listOf(input),
                exactPairs = null,
                sens = false
            )
        }

        viewModel.executeRequest(server.path, request, API3WiFiViewModel.RequestType.POST_JSON)
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
        binding.chipPostJson.isChecked = true
        binding.requestTypeChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            validateForm()
            updateRequestTypeInfo()

            val methodText = (binding.methodSpinnerLayout.editText as? AutoCompleteTextView)?.text.toString()
            if (methodText == "apiquery" && currentMethodParams is API3WiFiMethodParams.ApiQuery) {
                checkGetRequestAvailability()
            }
        }
    }

    private fun updateRequestTypeInfo() {
        val checkedChip = binding.requestTypeChipGroup.findViewById<Chip>(
            binding.requestTypeChipGroup.checkedChipId
        )
        if (checkedChip != null) {
            binding.requestTypeInfo.visibility = View.VISIBLE
            binding.requestTypeInfo.text = getString(R.string.selected_request_type, checkedChip.text)
        }
    }

    private fun checkGetRequestAvailability() {
        val params = currentMethodParams as? API3WiFiMethodParams.ApiQuery ?: return

        val bssidCount = when (params.currentQueryType) {
            API3WiFiMethodParams.ApiQuery.QueryType.BSSID_ONLY -> params.bssidList.size
            API3WiFiMethodParams.ApiQuery.QueryType.ESSID_ONLY -> params.essidList.size
            API3WiFiMethodParams.ApiQuery.QueryType.EXACT_MATCH -> params.exactPairs.size
        }

        val getChip = binding.chipGet
        if (bssidCount > 1) {
            getChip.isEnabled = false
            if (getChip.isChecked) {
                binding.chipPostJson.isChecked = true
            }
            showError(getString(R.string.get_request_single_only))
        } else {
            getChip.isEnabled = true
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

            if (params is API3WiFiMethodParams.ApiQuery) {
                params.setOnDataChangedListener {
                    checkGetRequestAvailability()
                }
            }
        }
        validateForm()
    }

    private fun clearMethodParams() {
        binding.methodParamsContainer.removeAllViews()
        currentMethodParams?.clear()
        currentMethodParams = null
        binding.responseText.text = null
        binding.requestTypeInfo.visibility = View.GONE
    }

    private fun validateForm(): Boolean {
        if (!isAdvancedMode) return true

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

        currentMethodParams?.let {
            if (methodText == "apiquery" || methodText == "apiwps" || methodText == "apidev") {
                isValid = isValid && true
            } else if (methodText == "apiranges") {
                isValid = isValid && it.isValid()
            }
        }

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
            else -> API3WiFiViewModel.RequestType.POST_JSON
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

            binding.simpleModeContainer.findViewById<AutoCompleteTextView>(R.id.simpleServerSpinner)?.let { spinner ->
                val adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_dropdown_item_1line,
                    servers.map { it.path }
                )
                spinner.setAdapter(adapter)
                if (servers.isNotEmpty() && spinner.text.isEmpty()) {
                    spinner.setText(servers[0].path, false)
                }
            }
        }

        viewModel.requestResult.observe(viewLifecycleOwner) { result ->
            binding.responseText.setText(result)
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.executeButton.isEnabled = !isLoading && validateForm()

            binding.simpleModeContainer.findViewById<com.google.android.material.button.MaterialButton>(
                R.id.simpleSearchButton
            )?.isEnabled = !isLoading
        }
    }

    private fun setupServersAdapter(servers: List<String>) {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            servers
        )
        (binding.serverSpinnerLayout.editText as? AutoCompleteTextView)?.setAdapter(adapter)

        if (servers.isNotEmpty() && isAdvancedMode) {
            (binding.serverSpinnerLayout.editText as? AutoCompleteTextView)?.setText(servers[0], false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}