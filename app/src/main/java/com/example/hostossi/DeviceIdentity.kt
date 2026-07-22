package com.example.hostossi

import android.content.Context
import android.os.Build
import android.provider.Settings

/** Stable identity used to associate an Android sensor with the phone that owns it. */
object DeviceIdentity {
    fun id(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        return "android:${androidId.ifBlank { Build.FINGERPRINT.hashCode().toString(16) }}"
    }

    fun name(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }
        .joinToString(" ")
        .replaceFirstChar { it.uppercase() }
        .ifBlank { "Android device" }
}
