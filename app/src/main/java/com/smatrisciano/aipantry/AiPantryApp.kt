package com.smatrisciano.aipantry

import android.app.Application
import com.smatrisciano.aipantry.capture.data.di.captureModule
import com.smatrisciano.aipantry.core.di.coreModule
import com.smatrisciano.aipantry.inventory.data.di.inventoryModule
import com.smatrisciano.aipantry.recipes.data.di.recipesModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin

class AiPantryApp : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@AiPantryApp)
            modules(
                coreModule,
                inventoryModule,
                captureModule,
                recipesModule
            )
        }
    }
}
