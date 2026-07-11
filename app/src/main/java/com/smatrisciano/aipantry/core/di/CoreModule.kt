package com.smatrisciano.aipantry.core.di

import androidx.room.Room
import com.smatrisciano.aipantry.inventory.data.local.PantryDatabase
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
}
