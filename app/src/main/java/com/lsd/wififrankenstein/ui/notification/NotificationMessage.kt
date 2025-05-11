package com.lsd.wififrankenstein.ui.notification

import kotlinx.serialization.Serializable

@Serializable
data class NotificationMessage(
    val id: String,
    val title: Map<String, String>,
    val message: Map<String, String>,
    val primaryButton: Map<String, String>,
    val secondaryButton: Map<String, String>? = null,
    val imageUrl: String? = null,
    val linkUrl: String? = null,
    val linkText: Map<String, String>? = null
) {
    fun getLocalizedTitle(languageCode: String): String {
        return title[languageCode] ?: title["en"] ?: title.values.firstOrNull() ?: ""
    }

    fun getLocalizedMessage(languageCode: String): String {
        return message[languageCode] ?: message["en"] ?: message.values.firstOrNull() ?: ""
    }

    fun getLocalizedPrimaryButton(languageCode: String): String {
        return primaryButton[languageCode] ?: primaryButton["en"] ?: primaryButton.values.firstOrNull() ?: "OK"
    }

    fun getLocalizedSecondaryButton(languageCode: String): String? {
        return secondaryButton?.get(languageCode) ?: secondaryButton?.get("en") ?: secondaryButton?.values?.firstOrNull()
    }

    fun getLocalizedLinkText(languageCode: String): String? {
        return linkText?.get(languageCode) ?: linkText?.get("en") ?: linkText?.values?.firstOrNull()
    }
}