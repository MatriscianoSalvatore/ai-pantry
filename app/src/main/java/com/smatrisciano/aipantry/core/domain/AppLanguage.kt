package com.smatrisciano.aipantry.core.domain

import java.util.Locale

/**
 * Language of the content the app generates (prompts, ingredient names,
 * recipes): Italian if the device is set to Italian, English otherwise, the
 * same rule Android uses to choose between values/ and values-it/ for the UI.
 * Read on every use: if the user changes language, the next scan or recipe
 * follows it.
 */
enum class AppLanguage {
    EN, IT;

    companion object {
        fun current(): AppLanguage = if (Locale.getDefault().language == "it") IT else EN
    }
}
