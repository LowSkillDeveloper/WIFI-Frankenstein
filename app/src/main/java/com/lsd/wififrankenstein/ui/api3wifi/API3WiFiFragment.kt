package com.lsd.wififrankenstein.ui.api3wifi

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentApi3wifiBinding
import org.json.JSONObject

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
        setupResponseButtons()
        setupToggleRawResponse()
        setupToggleRawRequest()
    }

    private fun setupToggleRawResponse() {
        binding.toggleRawResponseButton.setOnClickListener {
            val isVisible = binding.rawResponseContainer.isVisible
            if (isVisible) {
                binding.rawResponseContainer.visibility = View.GONE
                binding.toggleRawResponseButton.text = getString(R.string.show_raw)
                binding.toggleRawResponseButton.setIconResource(R.drawable.ic_expand_more)
            } else {
                binding.rawResponseContainer.visibility = View.VISIBLE
                binding.toggleRawResponseButton.text = getString(R.string.hide_raw)
                binding.toggleRawResponseButton.setIconResource(R.drawable.ic_expand_less)
            }
        }
    }


    private fun setupResponseButtons() {
        binding.copyResponseButton.setOnClickListener {
            val text = binding.responseText.text.toString()
            if (text.isNotEmpty()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("API Response", text)
                clipboard.setPrimaryClip(clip)
                showError(getString(R.string.copied_to_clipboard))
            }
        }

        binding.copyRequestButton.setOnClickListener {
            val text = binding.requestText.text.toString()
            if (text.isNotEmpty()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("API Request", text)
                clipboard.setPrimaryClip(clip)
                showError(getString(R.string.copied_to_clipboard))
            }
        }

        binding.clearRequestButton.setOnClickListener {
            binding.requestText.text = ""
        }

        binding.clearResponseButton.setOnClickListener {
            binding.responseText.text = ""
            binding.requestText.text = ""
            binding.resultsCardsContainer.removeAllViews()
        }
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
        val searchButton = simpleView.findViewById<MaterialButton>(R.id.simpleSearchButton)

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
                key = server.apiReadKey ?: "000000000000",
                bssidList = listOf(input.uppercase()),
                essidList = null,
                sens = false
            )
        } else {
            API3WiFiRequest.ApiQuery(
                key = server.apiReadKey ?: "000000000000",
                bssidList = listOf("*"),
                essidList = listOf(input),
                sens = false
            )
        }

        viewModel.executeRequest(server.path, request, API3WiFiViewModel.RequestType.GET)
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
        binding.responseText.text = ""
        binding.resultsCardsContainer.removeAllViews()
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

        val request = currentMethodParams?.getRequest(selectedServer.apiReadKey ?: "000000000000")
        if (request != null) {
            viewModel.executeRequest(serverUrl, request, requestType)
        } else {
            showError(getString(R.string.invalid_request_params))
        }
    }

    private fun showError(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun parseAndDisplayResults(jsonResponse: String) {
        binding.resultsCardsContainer.removeAllViews()

        val responses = jsonResponse.split(getString(R.string.separator_line))
        var foundValidJson = false

        for (response in responses) {
            val trimmedResponse = response.trim()
            if (trimmedResponse.isEmpty() || trimmedResponse.startsWith("POST request failed") ||
                trimmedResponse.startsWith("Retrying with GET") || trimmedResponse.startsWith("GET request response")) {
                continue
            }

            try {
                val json = JSONObject(trimmedResponse)

                if (!json.optBoolean("result", false)) {
                    val errorMessage = json.optString("error", getString(R.string.unknown_error))
                    if (!foundValidJson) {
                        addErrorCard(errorMessage)
                    }
                    continue
                }

                foundValidJson = true
                val data = json.optJSONObject("data") ?: continue

                data.keys().forEach { bssid ->
                    val networks = data.optJSONArray(bssid) ?: return@forEach

                    for (i in 0 until networks.length()) {
                        val network = networks.optJSONObject(i) ?: continue
                        addNetworkCard(network)
                    }
                }
            } catch (_: Exception) {
                continue
            }
        }

        if (binding.resultsCardsContainer.isEmpty() && !foundValidJson) {
            addNoResultsCard()
        }
    }

    @SuppressLint("DefaultLocale")
    private fun addNetworkCard(network: JSONObject) {
        val cardView = LayoutInflater.from(requireContext()).inflate(
            R.layout.item_api_result_card,
            binding.resultsCardsContainer,
            false
        )

        val bssidText = cardView.findViewById<TextView>(R.id.bssidText)
        val essidText = cardView.findViewById<TextView>(R.id.essidText)
        val keyText = cardView.findViewById<TextView>(R.id.keyText)
        val wpsText = cardView.findViewById<TextView>(R.id.wpsText)
        val securityText = cardView.findViewById<TextView>(R.id.securityText)
        val coordinatesText = cardView.findViewById<TextView>(R.id.coordinatesText)
        val timeText = cardView.findViewById<TextView>(R.id.timeText)
        val expandButton = cardView.findViewById<MaterialButton>(R.id.expandButton)
        val detailsContainer = cardView.findViewById<View>(R.id.detailsContainer)
        val copyKeyButton = cardView.findViewById<MaterialButton>(R.id.copyKeyButton)
        val copyWpsButton = cardView.findViewById<MaterialButton>(R.id.copyWpsButton)
        val openMapButton = cardView.findViewById<MaterialButton>(R.id.openMapButton)

        val bssid = network.optString("bssid", "")
        val essid = network.optString("essid", getString(R.string.unknown))
        val key = network.optString("key", "")
        val wps = network.optString("wps", "")
        val security = network.optString("sec", getString(R.string.unknown))
        val lat = network.optDouble("lat", 0.0)
        val lon = network.optDouble("lon", 0.0)
        val time = network.optString("time", getString(R.string.unknown))

        bssidText.text = bssid
        essidText.text = essid
        keyText.text = if (key.isEmpty()) getString(R.string.not_available) else key
        wpsText.text = if (wps.isEmpty() || wps == "0") getString(R.string.not_available) else wps
        securityText.text = security
        coordinatesText.text = if (lat != 0.0 && lon != 0.0) {
            String.format("%.6f, %.6f", lat, lon)
        } else {
            getString(R.string.not_available)
        }
        timeText.text = if (time == "None") getString(R.string.unknown) else time

        if (key.isNotEmpty()) {
            copyKeyButton.visibility = View.VISIBLE
            copyKeyButton.setOnClickListener {
                copyToClipboard(getString(R.string.wifi_key), key)
            }
        }

        if (wps.isNotEmpty() && wps != "0") {
            copyWpsButton.visibility = View.VISIBLE
            copyWpsButton.setOnClickListener {
                copyToClipboard(getString(R.string.wps_pin), wps)
            }
        }

        if (lat != 0.0 && lon != 0.0) {
            openMapButton.visibility = View.VISIBLE
            openMapButton.setOnClickListener {
                openMapWithCoordinates(essid, lat, lon)
            }
        }

        var isExpanded = false
        expandButton.setOnClickListener {
            isExpanded = !isExpanded
            detailsContainer.visibility = if (isExpanded) View.VISIBLE else View.GONE
            expandButton.setIconResource(
                if (isExpanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
        }

        binding.resultsCardsContainer.addView(cardView)
    }

    private fun addErrorCard(errorMessage: String) {
        val ctx = requireContext()
        val errorColor = ContextCompat.getColor(ctx, R.color.error_red)

        val errorCard = MaterialCardView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setCardBackgroundColor(errorColor)
            setContentPadding(16, 16, 16, 16)
        }

        val errorText = TextView(ctx).apply {
            text = getString(R.string.error_response, errorMessage)
            setTextColor(errorColor)
        }

        errorCard.addView(errorText)
        binding.resultsCardsContainer.addView(errorCard)
    }

    private fun addNoResultsCard() {
        val noResultsCard = MaterialCardView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setContentPadding(16, 16, 16, 16)
        }

        val noResultsText = TextView(requireContext()).apply {
            text = getString(R.string.no_results_found)
            gravity = android.view.Gravity.CENTER
        }

        noResultsCard.addView(noResultsText)
        binding.resultsCardsContainer.addView(noResultsCard)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        showError(getString(R.string.copied_to_clipboard))
    }

    private fun openMapWithCoordinates(name: String, lat: Double, lon: Double) {
        val uri = "geo:$lat,$lon?q=$lat,$lon(${Uri.encode(name)})".toUri()
        val mapIntent = Intent(Intent.ACTION_VIEW, uri)

        if (mapIntent.resolveActivity(requireContext().packageManager) != null) {
            startActivity(Intent.createChooser(mapIntent, getString(R.string.choose_map_app)))
        } else {
            val browserUri = "https://maps.google.com/maps?q=$lat,$lon".toUri()
            val browserIntent = Intent(Intent.ACTION_VIEW, browserUri)
            startActivity(browserIntent)
        }
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
            binding.responseText.text = result
            parseAndDisplayResults(result)
        }

        viewModel.requestInfo.observe(viewLifecycleOwner) { requestInfo ->
            binding.requestText.text = requestInfo
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.executeButton.isEnabled = !isLoading && validateForm()

            binding.simpleModeContainer.findViewById<MaterialButton>(
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

    private fun setupToggleRawRequest() {
        binding.toggleRawRequestButton.setOnClickListener {
            val isVisible = binding.rawRequestContainer.isVisible
            if (isVisible) {
                binding.rawRequestContainer.visibility = View.GONE
                binding.toggleRawRequestButton.text = getString(R.string.show_raw_request)
                binding.toggleRawRequestButton.setIconResource(R.drawable.ic_expand_more)
            } else {
                binding.rawRequestContainer.visibility = View.VISIBLE
                binding.toggleRawRequestButton.text = getString(R.string.hide_raw_request)
                binding.toggleRawRequestButton.setIconResource(R.drawable.ic_expand_less)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}