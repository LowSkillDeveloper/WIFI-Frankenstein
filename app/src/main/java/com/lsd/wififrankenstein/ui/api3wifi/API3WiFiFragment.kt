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
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.databinding.FragmentApi3wifiBinding
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class API3WiFiFragment : Fragment() {

    private var _binding: FragmentApi3wifiBinding? = null
    private val binding get() = _binding!!
    private val viewModel: API3WiFiViewModel by viewModels()

    private var currentMethodParams: API3WiFiMethodParams? = null
    private var currentServerApiProtocol: String? = null
    private val devApiMethods = listOf("apiquery", "apiwps", "apidev", "apiranges")
    private val trpcApiMethods = listOf("getpoint", "searchnetworks")
    private var isAdvancedMode = false
    private var isWpaSecMode = false
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

        showThreeWifiMode()
    }

    private fun setupUI() {
        setupApiProviderChipGroup()
        setupApiModeTabs()
        setupServerSpinner()
        setupMethodSpinner()
        setupRequestTypeChips()
        setupExecuteButton()
        setupResponseButtons()
        setupToggleRawResponse()
        setupToggleRawRequest()
    }

    private fun setupToggleRawResponse() {
        val toggle = {
            val show = binding.rawResponseContainer.isVisible
            val newVisibility = if (show) View.GONE else View.VISIBLE
            binding.rawResponseContainer.visibility = newVisibility
            val showState = !show
            val icon = if (showState) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            binding.expandIconLeftRes.setImageResource(icon)
            binding.expandIconRightRes.setImageResource(icon)
            binding.toggleRawResponseButton.text =
                getString(if (showState) R.string.hide_raw else R.string.show_raw)
        }
        binding.toggleRawResponseButton.setOnClickListener { toggle() }
        binding.expandIconLeftRes.setOnClickListener { toggle() }
        binding.expandIconRightRes.setOnClickListener { toggle() }
    }


    private fun setupResponseButtons() {
        binding.copyResponseButton.setOnClickListener {
            val text = binding.responseText.text.toString()
            if (text.isNotEmpty()) {
                val clipboard =
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("API Response", text)
                clipboard.setPrimaryClip(clip)
                showError(getString(R.string.copied_to_clipboard))
            }
        }

        binding.copyRequestButton.setOnClickListener {
            val text = binding.requestText.text.toString()
            if (text.isNotEmpty()) {
                val clipboard =
                    requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
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

    private fun setupApiProviderChipGroup() {
        val tabLayout = binding.apiProviderTabs
        tabLayout.removeAllTabs()
        tabLayout.addTab(tabLayout.newTab().setText(R.string.api_provider_3wifi))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.api_provider_wpasec))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val isNowWpaSec = tab?.position == 1
                if (isNowWpaSec == isWpaSecMode) return

                isWpaSecMode = isNowWpaSec
                if (isWpaSecMode) {
                    showWpaSecMode()
                } else {
                    showThreeWifiMode()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun showSimpleMode() {
        binding.simpleModeContainer.visibility = View.VISIBLE
        binding.advancedModeContainer.visibility = View.GONE

        binding.simpleModeContainer.removeAllViews()

        val servers = viewModel.apiServers.value?.map { it.path } ?: emptyList()
        if (servers.isEmpty()) {
            showNoServersView()
            return
        }

        val simpleView = LayoutInflater.from(requireContext()).inflate(
            R.layout.layout_simple_mode,
            binding.simpleModeContainer,
            false
        )

        val serverSpinnerLayout =
            simpleView.findViewById<TextInputLayout>(R.id.simpleServerSpinnerLayout)
        val serverSpinner = simpleView.findViewById<AutoCompleteTextView>(R.id.simpleServerSpinner)
        val searchTypeToggle =
            simpleView.findViewById<MaterialButtonToggleGroup>(R.id.simpleSearchTypeToggle)
        val inputLayout = simpleView.findViewById<TextInputLayout>(R.id.simpleInputLayout)
        val input = simpleView.findViewById<TextInputEditText>(R.id.simpleInput)
        val searchButton = simpleView.findViewById<MaterialButton>(R.id.simpleSearchButton)

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            servers
        )
        serverSpinner.setAdapter(adapter)
        serverSpinner.setText(servers[0], false)

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

    private fun showNoServersView() {
        val cardView = LayoutInflater.from(requireContext()).inflate(
            R.layout.item_status_card_empty,
            binding.simpleModeContainer,
            false
        ) as ViewGroup

        cardView.findViewById<TextView>(R.id.emptyMessage)?.text =
            getString(R.string.no_servers_configured)
        cardView.findViewById<TextView>(R.id.emptyMessage)?.visibility = View.VISIBLE

        val button = MaterialButton(requireContext()).apply {
            text = getString(R.string.add_api_server)
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = resources.getDimensionPixelOffset(R.dimen.card_corner_radius)
            }
            setOnClickListener { showAddApiServerDialog() }
        }

        val container = cardView.getChildAt(0) as? ViewGroup
        container?.addView(button)

        binding.simpleModeContainer.addView(cardView)
    }

    private fun showAddApiServerDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_api_config, null)
        dialog.setContentView(view)

        val editTextApiUrl = view.findViewById<TextInputEditText>(R.id.editTextApiUrl)
        val textInputApiUrl = view.findViewById<TextInputLayout>(R.id.textInputApiUrl)
        editTextApiUrl.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val url = s?.toString() ?: ""
                textInputApiUrl.endIconDrawable = when {
                    url.isBlank() -> androidx.appcompat.content.res.AppCompatResources.getDrawable(
                        requireContext(),
                        R.drawable.ic_web
                    )

                    url.startsWith("http://") || url.startsWith("https://") -> androidx.appcompat.content.res.AppCompatResources.getDrawable(
                        requireContext(),
                        R.drawable.ic_check
                    )

                    else -> androidx.appcompat.content.res.AppCompatResources.getDrawable(
                        requireContext(),
                        R.drawable.ic_close
                    )
                }
            }
        })

        val autoComplete = view.findViewById<AutoCompleteTextView>(R.id.autoCompleteAuthMethod)
        val textInputReadKey = view.findViewById<TextInputLayout>(R.id.textInputApiReadKey)
        val textInputWriteKey = view.findViewById<TextInputLayout>(R.id.textInputApiWriteKey)
        val textInputLogin = view.findViewById<TextInputLayout>(R.id.textInputLogin)
        val textInputPassword = view.findViewById<TextInputLayout>(R.id.textInputPassword)
        val textViewUserInfo = view.findViewById<TextView>(R.id.textViewUserInfo)
        val buttonAdd = view.findViewById<MaterialButton>(R.id.buttonAddApi)

        val authMethods = arrayOf(
            getString(R.string.auth_method_api_keys),
            getString(R.string.auth_method_login_password),
            getString(R.string.auth_method_no_auth)
        )
        val spinnerAdapter =
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, authMethods)
        autoComplete.setAdapter(spinnerAdapter)
        autoComplete.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> {
                    textInputReadKey.visibility = View.VISIBLE
                    textInputWriteKey.visibility = View.VISIBLE
                    textInputLogin.visibility = View.GONE
                    textInputPassword.visibility = View.GONE
                    textViewUserInfo.visibility = View.GONE
                }

                1 -> {
                    textInputReadKey.visibility = View.GONE
                    textInputWriteKey.visibility = View.GONE
                    textInputLogin.visibility = View.VISIBLE
                    textInputPassword.visibility = View.VISIBLE
                    textViewUserInfo.visibility = View.GONE
                }

                2 -> {
                    textInputReadKey.visibility = View.GONE
                    textInputWriteKey.visibility = View.GONE
                    textInputLogin.visibility = View.GONE
                    textInputPassword.visibility = View.GONE
                    textViewUserInfo.visibility = View.GONE
                }
            }
        }
        autoComplete.setText(authMethods[2], false)
        textInputReadKey.visibility = View.GONE
        textInputWriteKey.visibility = View.GONE

        buttonAdd.setOnClickListener {
            val url = editTextApiUrl.text.toString()
            if (url.isEmpty()) {
                showError(getString(R.string.db_invalid_url))
                return@setOnClickListener
            }

            val authMethodText = autoComplete.text.toString()
            val authMethod = when (authMethodText) {
                getString(R.string.auth_method_api_keys) -> com.lsd.wififrankenstein.ui.dbsetup.AuthMethod.API_KEYS
                getString(R.string.auth_method_login_password) -> com.lsd.wififrankenstein.ui.dbsetup.AuthMethod.LOGIN_PASSWORD
                else -> com.lsd.wififrankenstein.ui.dbsetup.AuthMethod.NO_AUTH
            }

            when (authMethod) {
                com.lsd.wififrankenstein.ui.dbsetup.AuthMethod.API_KEYS -> {
                    val readKey =
                        textInputReadKey.editText?.text.toString().takeIf { it.isNotBlank() }
                            ?: "000000000000"
                    val writeKey =
                        textInputWriteKey.editText?.text.toString().takeIf { it.isNotBlank() }
                            ?: "000000000000"
                    val dbItem =
                        com.lsd.wififrankenstein.ui.dbsetup.ApiServerHelper.createDbItemWithKeys(
                            url, readKey, writeKey, authMethod, getString(R.string.db_type_3wifi)
                        )
                    viewModel.addApiServer(dbItem)
                    dialog.dismiss()
                    showError(getString(R.string.db_added_successfully))
                }

                com.lsd.wififrankenstein.ui.dbsetup.AuthMethod.LOGIN_PASSWORD -> {
                    val login = textInputLogin.editText?.text.toString()
                    val password = textInputPassword.editText?.text.toString()
                    if (login.isNotBlank() && password.isNotBlank()) {
                        buttonAdd.isEnabled = false
                        buttonAdd.text = getString(R.string.detecting_server)

                        val progressDialog =
                            com.google.android.material.dialog.MaterialAlertDialogBuilder(
                                requireContext()
                            )
                                .setView(R.layout.dialog_test_progress)
                                .setCancelable(false)
                                .create()
                        progressDialog.show()

                        viewLifecycleOwner.lifecycleScope.launch {
                            try {
                                val (readKey, writeKey, userInfo) = com.lsd.wififrankenstein.ui.dbsetup.ApiServerHelper.getApiKeysFromLogin(
                                    serverUrl = url,
                                    login = login,
                                    password = password
                                ) { error ->
                                    com.lsd.wififrankenstein.ui.dbsetup.UserManager(requireContext())
                                        .getErrorDesc(error)
                                }

                                progressDialog.dismiss()

                                if (readKey != null) {
                                    val dbItem =
                                        com.lsd.wififrankenstein.ui.dbsetup.ApiServerHelper.createDbItemWithLogin(
                                            serverUrl = url,
                                            readKey = readKey,
                                            writeKey = writeKey ?: "",
                                            login = login,
                                            password = password,
                                            authMethod = authMethod,
                                            userInfo = userInfo,
                                            typeString = getString(R.string.db_type_3wifi)
                                        )
                                    viewModel.addApiServer(dbItem)
                                    dialog.dismiss()
                                    showError(getString(R.string.login_successful))
                                } else {
                                    showError(getString(R.string.login_failed))
                                }
                            } catch (e: Exception) {
                                progressDialog.dismiss()
                                showError(e.message ?: getString(R.string.login_failed))
                            } finally {
                                buttonAdd.isEnabled = true
                                buttonAdd.text = getString(R.string.add)
                            }
                        }
                    } else {
                        showError(getString(R.string.enter_valid_path_or_url))
                    }
                }

                else -> {
                    val dbItem =
                        com.lsd.wififrankenstein.ui.dbsetup.ApiServerHelper.createDbItemWithKeys(
                            url,
                            "000000000000",
                            "000000000000",
                            authMethod,
                            getString(R.string.db_type_3wifi)
                        )
                    viewModel.addApiServer(dbItem)
                    dialog.dismiss()
                    showError(getString(R.string.db_added_successfully))
                }
            }
        }

        dialog.show()
    }

    private fun showAdvancedMode() {
        binding.simpleModeContainer.visibility = View.GONE
        binding.advancedModeContainer.visibility = View.VISIBLE
    }

    private fun showThreeWifiMode() {
        binding.threeWifiContainer.visibility = View.VISIBLE
        binding.wpasecContainer.visibility = View.GONE
        binding.apiModeTabs.visibility = View.VISIBLE
        binding.apiModeTabs.getTabAt(if (isAdvancedMode) 1 else 0)?.select()
        if (isAdvancedMode) {
            showAdvancedMode()
        } else {
            showSimpleMode()
        }
    }

    private fun setupApiModeTabs() {
        binding.apiModeTabs.removeAllTabs()
        binding.apiModeTabs.addTab(binding.apiModeTabs.newTab().setText(R.string.simple_mode))
        binding.apiModeTabs.addTab(binding.apiModeTabs.newTab().setText(R.string.advanced_mode))
        binding.apiModeTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                isAdvancedMode = tab?.position == 1
                if (isAdvancedMode) showAdvancedMode() else showSimpleMode()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun showWpaSecMode() {
        binding.threeWifiContainer.visibility = View.GONE
        binding.wpasecContainer.visibility = View.VISIBLE
        binding.apiModeTabs.visibility = View.GONE

        binding.wpasecContainer.removeAllViews()
        val wpasecView = LayoutInflater.from(requireContext()).inflate(
            R.layout.layout_wpasec_query,
            binding.wpasecContainer,
            false
        )

        val bssidInput =
            wpasecView.findViewById<TextInputEditText>(R.id.wpasecBssidInput)
        val ssidInput =
            wpasecView.findViewById<TextInputEditText>(R.id.wpasecSsidInput)
        val checkButton =
            wpasecView.findViewById<MaterialButton>(R.id.wpasecCheckButton)
        val resultText = wpasecView.findViewById<TextView>(R.id.wpasecResultText)

        checkButton.setOnClickListener {
            val bssid = bssidInput.text.toString().trim()
            val ssid = ssidInput.text.toString().trim()

            if (bssid.isEmpty() || ssid.isEmpty()) {
                showError(getString(R.string.wpasec_empty_fields))
                return@setOnClickListener
            }

            resultText.visibility = View.GONE
            viewModel.checkWpaSec(bssid, ssid)
        }

        binding.wpasecContainer.addView(wpasecView)
    }

    private fun executeSimpleSearch(serverPath: String, input: String) {
        val server = viewModel.apiServers.value?.find { it.path == serverPath }
        if (server == null) {
            showError(getString(R.string.invalid_server))
            return
        }

        if (server.apiProtocol == "3wifi_app") {
            val request = API3WiFiRequest.TrpcSearchNetworks(
                query = if (isSearchByMac) input.uppercase() else input,
                type = if (isSearchByMac) "bssid" else "ssid"
            )
            viewModel.executeRequest(server.path, request, API3WiFiViewModel.RequestType.GET)
        } else {
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
    }

    private fun setupServerSpinner() {
        (binding.serverSpinnerLayout.editText as? AutoCompleteTextView)?.apply {
            setOnItemClickListener { _, _, _, _ ->
                clearMethodParams()
                val serverPath = text.toString()
                val server = viewModel.apiServers.value?.find { it.path == serverPath }
                currentServerApiProtocol = server?.apiProtocol
                updateMethodSpinner()
            }
        }
    }

    private fun updateMethodSpinner() {
        val methods = if (currentServerApiProtocol == "3wifi_app") trpcApiMethods else devApiMethods
        val methodAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            methods
        )
        (binding.methodSpinnerLayout.editText as? AutoCompleteTextView)?.apply {
            setAdapter(methodAdapter)
            setOnItemClickListener { _, _, position, _ ->
                onMethodSelected(methods[position])
            }
        }
    }

    private fun setupMethodSpinner() {
        updateMethodSpinner()
    }

    private fun setupRequestTypeChips() {
        binding.chipPostJson.isChecked = true
        binding.requestTypeChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            validateForm()
            updateRequestTypeInfo()

            val methodText =
                (binding.methodSpinnerLayout.editText as? AutoCompleteTextView)?.text.toString()
        }
    }

    private fun updateRequestTypeInfo() {
        val checkedChip = binding.requestTypeChipGroup.findViewById<Chip>(
            binding.requestTypeChipGroup.checkedChipId
        )
        if (checkedChip != null) {
            binding.requestTypeInfo.visibility = View.VISIBLE
            binding.requestTypeInfo.text =
                getString(R.string.selected_request_type, checkedChip.text)
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
        binding.responseText.text = ""
        binding.resultsCardsContainer.removeAllViews()
        binding.requestTypeInfo.visibility = View.GONE
    }

    private fun validateForm(): Boolean {
        if (!isAdvancedMode) return true

        val serverText =
            (binding.serverSpinnerLayout.editText as? AutoCompleteTextView)?.text.toString()
        val methodText =
            (binding.methodSpinnerLayout.editText as? AutoCompleteTextView)?.text.toString()

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
            isValid = isValid && it.isValid()
        }

        binding.executeButton.isEnabled = isValid
        return isValid
    }

    private fun executeRequest() {
        val serverUrl =
            (binding.serverSpinnerLayout.editText as? AutoCompleteTextView)?.text.toString()
        val selectedServer = viewModel.apiServers.value?.find { it.path == serverUrl }

        if (selectedServer == null) {
            showError(getString(R.string.invalid_server))
            return
        }

        val methodText =
            (binding.methodSpinnerLayout.editText as? AutoCompleteTextView)?.text.toString()
        val isTrpcMethod = methodText == "getpoint" || methodText == "searchnetworks"

        val requestType = if (isTrpcMethod) {
            API3WiFiViewModel.RequestType.GET
        } else {
            when (binding.requestTypeChipGroup.checkedChipId) {
                R.id.chipGet -> API3WiFiViewModel.RequestType.GET
                R.id.chipPostForm -> API3WiFiViewModel.RequestType.POST_FORM
                R.id.chipPostJson -> API3WiFiViewModel.RequestType.POST_JSON
                else -> API3WiFiViewModel.RequestType.POST_JSON
            }
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
                trimmedResponse.startsWith("Retrying with GET") || trimmedResponse.startsWith("GET request response") ||
                trimmedResponse.startsWith("Error:")
            ) {
                continue
            }

            try {
                val json = JSONObject(trimmedResponse)

                if (json.has("result") && json.optJSONObject("result")?.has("data") == true) {
                    val unwrapped = parseTrpcData(json)
                    if (unwrapped != null) {
                        foundValidJson = parseTrpcItems(unwrapped) || foundValidJson
                    }
                    continue
                }

                if (!json.optBoolean("result", false)) {
                    val errorMessage = json.optString("error", getString(R.string.unknown_error))
                    if (!foundValidJson) {
                        addErrorCard(errorMessage)
                    }
                    continue
                }

                foundValidJson = true
                val dataObj = json.optJSONObject("data")
                val dataArr = json.optJSONArray("data")

                when {
                    dataArr != null -> {
                        parseRangesData(dataArr)
                        foundValidJson = true
                    }

                    dataObj != null -> {
                        parseBssidData(dataObj)
                        foundValidJson = true
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

    private fun parseBssidData(data: JSONObject) {
        data.keys().forEach { bssid ->
            when (val item = data.opt(bssid)) {
                is JSONArray -> {
                    if (item.length() > 0) {
                        val first = item.optJSONObject(0)
                        if (first != null && first.has("name") && first.has("score")) {
                            addDevCard(bssid, item)
                        } else {
                            for (i in 0 until item.length()) {
                                val network = item.optJSONObject(i) ?: continue
                                addNetworkCard(network)
                            }
                        }
                    }
                }

                is JSONObject -> {
                    val scores = item.optJSONArray("scores")
                    if (scores != null) {
                        addWpsCard(bssid, scores)
                    }
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun parseRangesData(data: JSONArray) {
        val ctx = requireContext()
        val cardView = LayoutInflater.from(ctx).inflate(
            R.layout.item_api_ranges_card,
            binding.resultsCardsContainer,
            false
        )
        val container = cardView.findViewById<LinearLayout>(R.id.rangesContainer)
        for (i in 0 until data.length()) {
            val range = data.optJSONObject(i) ?: continue
            val rangeTv = TextView(ctx).apply {
                text = "${range.optString("range", "")}  ${range.optString("netname", "")}"
                setTextAppearance(
                    ctx,
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
                setPadding(0, 0, 0, 4)
            }
            val descTv = TextView(ctx).apply {
                text = range.optString("descr", "")
                setTextAppearance(
                    ctx,
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall
                )
                setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                setPadding(0, 0, 0, 12)
            }
            container.addView(rangeTv)
            container.addView(descTv)
        }
        binding.resultsCardsContainer.addView(cardView)
    }

    @SuppressLint("SetTextI18n")
    private fun addWpsCard(bssid: String, scores: JSONArray) {
        val ctx = requireContext()
        val cardView = LayoutInflater.from(ctx).inflate(
            R.layout.item_api_wps_card,
            binding.resultsCardsContainer,
            false
        )
        cardView.findViewById<TextView>(R.id.wpsBssidText).text = bssid
        val container = cardView.findViewById<LinearLayout>(R.id.wpsScoresContainer)
        for (i in 0 until scores.length()) {
            val score = scores.optJSONObject(i) ?: continue
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 4)
            }
            row.addView(TextView(ctx).apply {
                text = score.optString("name", "")
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setTextAppearance(
                    ctx,
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
            })
            row.addView(TextView(ctx).apply {
                text = score.optString("value", "")
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setTextAppearance(
                    ctx,
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            row.addView(TextView(ctx).apply {
                text = String.format("%.0f%%", score.optDouble("score", 0.0) * 100)
                gravity = android.view.Gravity.END
                setTextAppearance(
                    ctx,
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall
                )
            })
            container.addView(row)
        }
        binding.resultsCardsContainer.addView(cardView)
    }

    @SuppressLint("SetTextI18n")
    private fun addDevCard(bssid: String, devices: JSONArray) {
        val ctx = requireContext()
        val cardView = LayoutInflater.from(ctx).inflate(
            R.layout.item_api_device_card,
            binding.resultsCardsContainer,
            false
        )
        cardView.findViewById<TextView>(R.id.devBssidText).text = bssid
        val container = cardView.findViewById<LinearLayout>(R.id.devScoresContainer)
        for (i in 0 until devices.length()) {
            val device = devices.optJSONObject(i) ?: continue
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 4, 0, 4)
            }
            row.addView(TextView(ctx).apply {
                text = device.optString("name", "")
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setTextAppearance(
                    ctx,
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                )
            })
            row.addView(TextView(ctx).apply {
                text = String.format("%.0f%%", device.optDouble("score", 0.0) * 100)
                setTextAppearance(
                    ctx,
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall
                )
            })
            row.addView(TextView(ctx).apply {
                text = "(${device.optInt("count", 0)})"
                setTextAppearance(
                    ctx,
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall
                )
                setTextColor(ContextCompat.getColor(ctx, R.color.text_hint))
            })
            container.addView(row)
        }
        binding.resultsCardsContainer.addView(cardView)
    }

    private fun parseTrpcData(json: JSONObject): Any? {
        val result = json.optJSONObject("result")
        val data = result?.optJSONObject("data")
        return data?.opt("json")
    }

    private fun parseTrpcItems(data: Any): Boolean {
        var found = false
        when (data) {
            is JSONObject -> {
                val networks = data.optJSONArray("networks")
                if (networks != null) {
                    for (i in 0 until networks.length()) {
                        addNetworkCard(normalizeTrpcToCardFields(networks.getJSONObject(i)))
                        found = true
                    }
                } else {
                    addNetworkCard(normalizeTrpcToCardFields(data))
                    found = true
                }
            }

            is JSONArray -> {
                for (i in 0 until data.length()) {
                    addNetworkCard(normalizeTrpcToCardFields(data.getJSONObject(i)))
                    found = true
                }
            }
        }
        return found
    }

    private fun normalizeTrpcToCardFields(item: JSONObject): JSONObject {
        return JSONObject().apply {
            put("bssid", item.optString("bssid", ""))
            put("essid", item.optString("ssid", item.optString("essid", "")))
            put("key", item.optString("password", ""))
            put("wps", item.optString("wpsPin", ""))
            put("sec", item.optString("securityType", item.optString("security", "")))
            val lat = item.optDouble("latitude", 0.0)
            put("lat", if (lat != 0.0) lat else item.optDouble("lat", 0.0))
            val lon = item.optDouble("longitude", 0.0)
            put("lon", if (lon != 0.0) lon else item.optDouble("lng", 0.0))
            put("time", item.optString("time", ""))
            put("manufacturer", item.optString("manufacturer", ""))
            put("name", item.optString("name", ""))
            put("auth", item.optString("auth", ""))
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
        val cardView = LayoutInflater.from(requireContext()).inflate(
            R.layout.item_status_card_error,
            binding.resultsCardsContainer,
            false
        )
        cardView.findViewById<TextView>(R.id.statusMessage).text =
            getString(R.string.error_response, errorMessage)
        binding.resultsCardsContainer.addView(cardView)
    }

    private fun addNoResultsCard() {
        val cardView = LayoutInflater.from(requireContext()).inflate(
            R.layout.item_status_card_empty,
            binding.resultsCardsContainer,
            false
        )
        cardView.findViewById<TextView>(R.id.emptyMessage).text =
            getString(R.string.no_results_found)
        binding.resultsCardsContainer.addView(cardView)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard =
            requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
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

            if (servers.isNotEmpty() && currentServerApiProtocol == null) {
                currentServerApiProtocol = servers[0].apiProtocol
                updateMethodSpinner()
            }

            binding.simpleModeContainer.findViewById<AutoCompleteTextView>(R.id.simpleServerSpinner)
                ?.let { spinner ->
                    val adapter = ArrayAdapter(
                        requireContext(),
                        android.R.layout.simple_dropdown_item_1line,
                        servers.map { it.path }
                    )
                    spinner.setAdapter(adapter)
                    if (servers.isNotEmpty() && spinner.text.isEmpty()) {
                        spinner.setText(servers[0].path, false)
                    }
                } ?: run {
                if (!isAdvancedMode && !isWpaSecMode) {
                    showSimpleMode()
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

        viewModel.wpasecResult.observe(viewLifecycleOwner) { result ->
            if (result == null) return@observe

            val resultText = binding.wpasecContainer.findViewById<TextView>(R.id.wpasecResultText)
                ?: return@observe
            val resultCard =
                binding.wpasecContainer.findViewById<MaterialCardView>(R.id.wpasecResultCard)
                    ?: return@observe
            resultCard.visibility = View.VISIBLE
            resultText.visibility = View.VISIBLE

            if (result.error != null) {
                resultText.text = getString(R.string.wpasec_error, result.error)
                resultCard.setCardBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.error_red
                    )
                )
                resultText.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            } else if (result.isLeaked) {
                resultText.text = getString(R.string.wpasec_leaked)
                resultCard.setCardBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.success_green
                    )
                )
                resultText.setTextColor(ContextCompat.getColor(requireContext(), R.color.white))
            } else {
                resultText.text = getString(R.string.wpasec_not_leaked)
                resultCard.setCardBackgroundColor(
                    ContextCompat.getColor(
                        requireContext(),
                        R.color.warning_orange
                    )
                )
                resultText.setTextColor(ContextCompat.getColor(requireContext(), R.color.black))
            }
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            binding.progressIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
            binding.executeButton.isEnabled = !isLoading && validateForm()

            binding.simpleModeContainer.findViewById<MaterialButton>(
                R.id.simpleSearchButton
            )?.isEnabled = !isLoading

            binding.wpasecContainer.findViewById<MaterialButton>(
                R.id.wpasecCheckButton
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
            (binding.serverSpinnerLayout.editText as? AutoCompleteTextView)?.setText(
                servers[0],
                false
            )
        }
    }

    private fun setupToggleRawRequest() {
        val toggle = {
            val show = binding.rawRequestContainer.isVisible
            val newVisibility = if (show) View.GONE else View.VISIBLE
            binding.rawRequestContainer.visibility = newVisibility
            val showState = !show
            val icon = if (showState) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            binding.expandIconLeftReq.setImageResource(icon)
            binding.expandIconRightReq.setImageResource(icon)
            binding.toggleRawRequestButton.text =
                getString(if (showState) R.string.hide_raw_request else R.string.show_raw_request)
        }
        binding.toggleRawRequestButton.setOnClickListener { toggle() }
        binding.expandIconLeftReq.setOnClickListener { toggle() }
        binding.expandIconRightReq.setOnClickListener { toggle() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}