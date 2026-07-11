package com.smatrisciano.aipantry.inventory.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [IngredientEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PantryDatabase : RoomDatabase() {
    abstract fun ingredientDao(): IngredientDao
}
