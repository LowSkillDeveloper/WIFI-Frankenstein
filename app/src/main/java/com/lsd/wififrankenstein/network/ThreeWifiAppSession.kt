package com.lsd.wififrankenstein.network

import android.app.Application
import android.content.Context
import com.lsd.wififrankenstein.ui.dbsetup.DbItem
import com.lsd.wififrankenstein.ui.dbsetup.DbSetupViewModel
import com.lsd.wififrankenstein.ui.dbsetup.DbType
import com.lsd.wififrankenstein.util.Log

object ThreeWifiAppSession {

    const val TAG = "ThreeWifiAppSession"
    const val PROTOCOL_APP = "3wifi_app"

    fun isAppServer(db: DbItem): Boolean =
        db.dbType == DbType.WIFI_API && db.apiProtocol == PROTOCOL_APP

    fun isGuestServer(db: DbItem): Boolean = isAppServer(db) && db.jwtToken.isNullOrBlank()

    fun isAuthenticatedServer(db: DbItem): Boolean =
        isAppServer(db) && !db.jwtToken.isNullOrBlank()

    fun guestServers(servers: List<DbItem>): List<DbItem> = servers.filter(::isGuestServer)

    fun authenticatedServers(servers: List<DbItem>): List<DbItem> =
        servers.filter(::isAuthenticatedServer)

    fun firstAuthenticated(servers: List<DbItem>): DbItem? =
        servers.firstOrNull(::isAuthenticatedServer)

    fun canSignIn(db: DbItem): Boolean =
        isAppServer(db) && !db.login.isNullOrBlank() && !db.password.isNullOrBlank()

    suspend fun loadServers(context: Context): List<DbItem> {
        val app = context.applicationContext as Application
        val viewModel = DbSetupViewModel.getInstance(app)
        viewModel.loadDbList()
        return viewModel.dbList.value.orEmpty()
    }

    suspend fun authenticatedServer(context: Context): DbItem? =
        firstAuthenticated(loadServers(context))

    suspend fun hasAuthenticated(context: Context): Boolean = authenticatedServer(context) != null

    fun client(context: Context, server: DbItem): ThreeWifiAppClient =
        ThreeWifiAppClient.get(context, server.path)

    suspend fun currentToken(context: Context, server: DbItem): String? {
        server.jwtToken?.takeIf { it.isNotBlank() }?.let { return it }
        if (!canSignIn(server)) return null
        return when (val outcome = authenticate(context, server)) {
            is ThreeWifiLoginOutcome.Authenticated -> outcome.token
            else -> {
                Log.d(TAG, "Silent sign-in unavailable for ${server.path}: $outcome")
                null
            }
        }
    }

    suspend fun authenticate(context: Context, server: DbItem): ThreeWifiLoginOutcome {
        val login = server.login
        val password = server.password
        if (login.isNullOrBlank() || password.isNullOrBlank()) {
            return ThreeWifiLoginOutcome.Rejected("Credentials are missing")
        }
        val outcome = client(context, server).login(login, password)
        if (outcome is ThreeWifiLoginOutcome.Authenticated) {
            persistToken(context, server, outcome.token)
        }
        return outcome
    }

    suspend fun refresh(context: Context, server: DbItem): String? {
        val stale = server.jwtToken?.takeIf { it.isNotBlank() } ?: return null
        val fresh = try {
            client(context, server).refreshSession(stale)
        } catch (e: Exception) {
            Log.d(TAG, "Session refresh failed for ${server.path}: ${e.message}")
            null
        }
        if (fresh != null) {
            persistToken(context, server, fresh)
        }
        return fresh
    }

    private fun persistToken(context: Context, server: DbItem, token: String) {
        if (server.jwtToken == token) return
        val app = context.applicationContext as Application
        val viewModel = DbSetupViewModel.getInstance(app)
        viewModel.updateDbItem(server.copy(jwtToken = token))
        Log.d(TAG, "Stored refreshed token for ${server.path}")
    }
}
