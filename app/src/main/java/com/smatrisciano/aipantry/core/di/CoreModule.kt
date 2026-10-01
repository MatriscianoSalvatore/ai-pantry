package com.smatrisciano.aipantry.core.di

import androidx.room.Room
import com.google.android.play.core.aipacks.AiPackManagerFactory
import com.smatrisciano.aipantry.core.data.WaitTimeEstimator
import com.smatrisciano.aipantry.core.data.ai.LlmEngineHolder
import com.smatrisciano.aipantry.core.data.ai.ModelRepository
import com.smatrisciano.aipantry.inventory.data.local.PantryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val coreModule = module {
    single {
        Room.databaseBuilder(
            androidContext(),
            PantryDatabase::class.java,
            "pantry.db"
        ).fallbackToDestructiveMigration(dropAllTables = true).build()
    }
    single { get<PantryDatabase>().ingredientDao() }

    single { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    single { AiPackManagerFactory.getInstance(androidContext()) }
    single { LlmEngineHolder(androidContext()) }
    single { ModelRepository(androidContext(), get(), get(), get()) }
    single { WaitTimeEstimator(androidContext()) }
}
