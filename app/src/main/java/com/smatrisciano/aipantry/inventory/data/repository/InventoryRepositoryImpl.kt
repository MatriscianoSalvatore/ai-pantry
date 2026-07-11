package com.smatrisciano.aipantry.inventory.data.repository

import com.smatrisciano.aipantry.inventory.data.local.IngredientDao
import com.smatrisciano.aipantry.inventory.data.local.toDomain
import com.smatrisciano.aipantry.inventory.data.local.toEntity
import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.inventory.domain.repository.InventoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class InventoryRepositoryImpl(
    private val dao: IngredientDao
) : InventoryRepository {

    override fun observeInventory(): Flow<List<Ingredient>> =
        dao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override suspend fun addAll(ingredients: List<Ingredient>) {
        dao.upsertAll(ingredients.map { it.toEntity() })
    }

    override suspend fun remove(name: String) = dao.delete(name)

    override suspend fun clear() = dao.clear()
}
