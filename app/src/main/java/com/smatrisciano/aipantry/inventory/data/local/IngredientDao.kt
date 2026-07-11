package com.smatrisciano.aipantry.inventory.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface IngredientDao {

    @Query("SELECT * FROM ingredients ORDER BY detectedAtMillis DESC")
    fun observeAll(): Flow<List<IngredientEntity>>

    @Upsert
    suspend fun upsertAll(ingredients: List<IngredientEntity>)

    @Query("DELETE FROM ingredients WHERE name = :name")
    suspend fun delete(name: String)

    @Query("DELETE FROM ingredients")
    suspend fun clear()
}
