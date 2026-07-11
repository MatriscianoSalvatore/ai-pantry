package com.smatrisciano.aipantry.inventory.data.di

import com.smatrisciano.aipantry.inventory.data.repository.InventoryRepositoryImpl
import com.smatrisciano.aipantry.inventory.domain.repository.InventoryRepository
import com.smatrisciano.aipantry.inventory.presentation.InventoryViewModel
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val inventoryModule = module {
    singleOf(::InventoryRepositoryImpl) { bind<InventoryRepository>() }
    viewModelOf(::InventoryViewModel)
}
