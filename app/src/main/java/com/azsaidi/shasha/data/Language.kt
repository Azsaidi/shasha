package com.azsaidi.shasha.data

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** App language: follows the system, or is forced to English (UK) or Arabic. */
object Language {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val ARABIC = "ar"

    private fun prefs(context: Context) = context.getSharedPreferences("shasha", Context.MODE_PRIVATE)

    fun current(context: Context): String = prefs(context).getString("language", null) ?: SYSTEM

    fun set(context: Context, value: String) {
        prefs(context).edit().putString("language", value).commit()
    }

    /** Wraps an activity's base context so its resources (and layout direction) use the chosen language. */
    fun wrap(base: Context): Context {
        val locale = when (current(base)) {
            ENGLISH -> Locale.UK
            ARABIC -> Locale("ar")
            else -> return base
        }
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }
}
