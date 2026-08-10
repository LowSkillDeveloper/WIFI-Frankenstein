package com.lsd.wififrankenstein.ui.welcome

import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.lsd.wififrankenstein.R
import com.lsd.wififrankenstein.WelcomeViewModel
import com.lsd.wififrankenstein.ui.dbsetup.AuthMethod
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.ui.dbsetup.UserManager
import com.lsd.wififrankenstein.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

class ApiServerHelper(
    private val fragment: Fragment,
    private val buttonAddApi: Button,
    private val editTextApiUrl: android.widget.EditText,
    private val spinnerAuthMethodWelcome: AutoCompleteTextView,
    private val textInputApiReadKeyWelcome: TextInputLayout,
    private val textInputApiWriteKeyWelcome: TextInputLayout,
    private val textInputLoginWelcome: TextInputLayout,
    private val textInputPasswordWelcome: TextInputLayout,
    private val textViewUserInfoWelcome: TextView,
    private val recyclerViewApiServers: androidx.recyclerview.widget.RecyclerView,
    private val dbSetupViewModel: DbSetupViewModel,
    private val welcomeViewModel: WelcomeViewModel,
    private val apiServersAdapter: WelcomeDatabaseAdapter
) {

    fun setupButtons() {
        buttonAddApi.setOnClickListener {
            val url = editTextApiUrl.text.toString()
            if (url.isNotEmpty()) {
                val authMethodText = spinnerAuthMethodWelcome.text.toString()
                val authMethod = when (authMethodText) {
                    fragment.getString(R.string.auth_method_api_keys) -> AuthMethod.API_KEYS
                    fragment.getString(R.string.auth_method_login_password) -> AuthMethod.LOGIN_PASSWORD
                    else -> AuthMethod.NO_AUTH
                }

                when (authMethod) {
                    AuthMethod.API_KEYS -> {
                        val readKey = textInputApiReadKeyWelcome.editText?.text.toString()
                            .takeIf { it.isNotBlank() } ?: "000000000000"
                        val writeKey = textInputApiWriteKeyWelcome.editText?.text.toString()
                            .takeIf { it.isNotBlank() } ?: "000000000000"
                        addApiServerWithKeys(url, readKey, writeKey, authMethod)
                    }

                    AuthMethod.LOGIN_PASSWORD -> {
                        val login = textInputLoginWelcome.editText?.text.toString()
                        val password = textInputPasswordWelcome.editText?.text.toString()
                        if (login.isNotBlank() && password.isNotBlank()) {
                            addApiServerWithLogin(url, login, password, authMethod)
                        } else {
                            showError(fragment.getString(R.string.enter_valid_path_or_url))
                        }
                    }

                    AuthMethod.NO_AUTH -> {
                        addApiServerWithKeys(url, "000000000000", "000000000000", authMethod)
                    }
                }
            } else {
                showError(fragment.getString(R.string.db_invalid_url))
            }
        }
    }

    fun setupAuthMethodSpinner() {
        val authMethods = arrayOf(
            fragment.getString(R.string.auth_method_api_keys),
            fragment.getString(R.string.auth_method_login_password),
            fragment.getString(R.string.auth_method_no_auth)
        )

        val adapter = ArrayAdapter(
            fragment.requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            authMethods
        )
        spinnerAuthMethodWelcome.setAdapter(adapter)

        spinnerAuthMethodWelcome.setOnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> showApiKeysFields()
                1 -> showLoginPasswordFields()
                2 -> showNoAuthFields()
            }
        }

        spinnerAuthMethodWelcome.setText(authMethods[0], false)
        showApiKeysFields()
    }

    fun showApiKeysFields() {
        textInputApiReadKeyWelcome.isVisible = true
        textInputApiWriteKeyWelcome.isVisible = true
        textInputLoginWelcome.isVisible = false
        textInputPasswordWelcome.isVisible = false
        textViewUserInfoWelcome.isVisible = false
    }

    fun showLoginPasswordFields() {
        textInputApiReadKeyWelcome.isVisible = false
        textInputApiWriteKeyWelcome.isVisible = false
        textInputLoginWelcome.isVisible = true
        textInputPasswordWelcome.isVisible = true
        textViewUserInfoWelcome.isVisible = false
    }

    fun showNoAuthFields() {
        textInputApiReadKeyWelcome.isVisible = false
        textInputApiWriteKeyWelcome.isVisible = false
        textInputLoginWelcome.isVisible = false
        textInputPasswordWelcome.isVisible = false
        textViewUserInfoWelcome.isVisible = false
    }

    fun clearApiInputs() {
        editTextApiUrl.text?.clear()
        textInputApiReadKeyWelcome.editText?.text?.clear()
        textInputApiWriteKeyWelcome.editText?.text?.clear()
        textInputLoginWelcome.editText?.text?.clear()
        textInputPasswordWelcome.editText?.text?.clear()
        textViewUserInfoWelcome.isVisible = false
    }

    fun addApiServerWithKeys(
        serverUrl: String,
        readKey: String,
        writeKey: String,
        authMethod: AuthMethod
    ) {
        var url = serverUrl
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://$url"
        }
        url = url.trimEnd('/')

        val dbItem = DbItem(
            id = UUID.randomUUID().toString(),
            path = url,
            directPath = null,
            type = fragment.getString(R.string.db_type_3wifi),
            dbType = DbType.WIFI_API,
            apiReadKey = readKey,
            apiWriteKey = writeKey,
            authMethod = authMethod,
            originalSizeInMB = 0f,
            cachedSizeInMB = 0f
        )

        fragment.lifecycleScope.launch {
            try {
                dbSetupViewModel.addDb(dbItem)
                welcomeViewModel.addSelectedDatabase(dbItem)
                delay(100)

                withContext(Dispatchers.Main) {
                    forceUpdateDbList()
                    clearApiInputs()
                    showSuccess(fragment.getString(R.string.db_added_successfully))
                }
            } catch (e: Exception) {
                Log.e("ApiServerHelper", "Error adding API server", e)
                showError(fragment.getString(R.string.operation_failed))
            }
        }
    }

    fun addApiServerWithLogin(
        serverUrl: String,
        login: String,
        password: String,
        authMethod: AuthMethod
    ) {
        val progressDialog = MaterialAlertDialogBuilder(fragment.requireContext())
            .setView(R.layout.dialog_test_progress)
            .setCancelable(false)
            .create()

        progressDialog.show()

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val (readKey, writeKey, userInfo) = getApiKeysFromLogin(serverUrl, login, password)

                progressDialog.dismiss()

                if (readKey != null) {
                    var url = serverUrl
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    url = url.trimEnd('/')

                    val dbItem = DbItem(
                        id = UUID.randomUUID().toString(),
                        path = url,
                        directPath = null,
                        type = fragment.getString(R.string.db_type_3wifi),
                        dbType = DbType.WIFI_API,
                        apiReadKey = readKey,
                        apiWriteKey = writeKey,
                        login = login,
                        password = password,
                        authMethod = authMethod,
                        userNick = userInfo?.first,
                        userLevel = userInfo?.second,
                        originalSizeInMB = 0f,
                        cachedSizeInMB = 0f
                    )

                    dbSetupViewModel.addDb(dbItem)
                    welcomeViewModel.addSelectedDatabase(dbItem)
                    delay(100)

                    withContext(Dispatchers.Main) {
                        forceUpdateDbList()

                        val userManager = UserManager(fragment.requireContext())
                        val levelText = userInfo?.second?.let { userManager.getTextGroup(it) } ?: ""
                        val userInfoText =
                            fragment.getString(R.string.user_info, userInfo?.first ?: "", levelText)
                        textViewUserInfoWelcome.text = userInfoText
                        textViewUserInfoWelcome.isVisible = true

                        clearApiInputs()
                        showSuccess(fragment.getString(R.string.login_successful))
                    }
                } else {
                    showError(fragment.getString(R.string.login_failed))
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                showError(e.message ?: fragment.getString(R.string.login_failed))
            }
        }
    }

    private fun forceUpdateDbList() {
        fragment.lifecycleScope.launch {
            try {
                dbSetupViewModel.loadDbList()
                val dbList = dbSetupViewModel.dbList.value ?: emptyList()
                val apiServers = dbList.filter { it.dbType == DbType.WIFI_API }
                withContext(Dispatchers.Main) {
                    apiServersAdapter.submitList(apiServers)
                }
            } catch (e: Exception) {
                Log.e("ApiServerHelper", "Error updating database list", e)
            }
        }
    }

    private suspend fun getApiKeysFromLogin(
        serverUrl: String,
        login: String,
        password: String
    ): Triple<String?, String?, Pair<String, Int>?> {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("$serverUrl/api/apikeys")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                val postData = "login=${URLEncoder.encode(login, "UTF-8")}&" +
                        "password=${URLEncoder.encode(password, "UTF-8")}&" +
                        "genread=1"

                connection.outputStream.use { it.write(postData.toByteArray()) }

                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)

                if (json.getBoolean("result")) {
                    val profile = json.getJSONObject("profile")
                    val keys = json.getJSONArray("data")

                    var readKey: String? = null
                    var writeKey: String? = null

                    for (i in 0 until keys.length()) {
                        val keyData = keys.getJSONObject(i)
                        val access = keyData.getString("access")
                        when (access) {
                            "read" -> readKey = keyData.getString("key")
                            "write" -> writeKey = keyData.getString("key")
                        }
                    }

                    val userInfo = Pair(
                        profile.getString("nick"),
                        profile.getInt("level")
                    )

                    Triple(readKey, writeKey, userInfo)
                } else {
                    val error = json.getString("error")
                    val userManager = UserManager(fragment.requireContext())
                    val errorDesc = userManager.getErrorDesc(error)
                    throw Exception(errorDesc)
                }
            } catch (e: Exception) {
                throw e
            }
        }
    }

    private fun showError(message: String) {
        Snackbar.make(fragment.requireView(), message, Snackbar.LENGTH_SHORT).show()
    }

    private fun showSuccess(message: String) {
        Snackbar.make(fragment.requireView(), message, Snackbar.LENGTH_SHORT).show()
    }
}
