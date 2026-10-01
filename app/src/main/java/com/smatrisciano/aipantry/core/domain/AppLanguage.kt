package com.smatrisciano.aipantry.core.domain

import java.util.Locale

/**
 * Lingua dei contenuti generati dall'app (prompt, nomi degli ingredienti,
 * ricette): italiano se il device è in italiano, inglese altrimenti — la
 * stessa regola con cui Android sceglie tra values/ e values-it/ per la UI.
 * Letta a ogni uso: se l'utente cambia lingua, la scansione o la ricetta
 * successiva la segue.
 */
enum class AppLanguage {
    EN, IT;

    companion object {
        fun current(): AppLanguage = if (Locale.getDefault().language == "it") IT else EN
    }
}
