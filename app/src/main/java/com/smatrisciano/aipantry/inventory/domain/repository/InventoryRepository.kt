package com.smatrisciano.aipantry.inventory.domain.repository

import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import kotlinx.coroutines.flow.Flow

interface InventoryRepository {
    fun observeInventory(): Flow<List<Ingredient>>
    suspend fun addAll(ingredients: List<Ingredient>)
    suspend fun remove(name: String)
    suspend fun clear()
}
