package com.smatrisciano.aipantry.inventory.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.inventory.domain.models.IngredientSource

@Entity(tableName = "ingredients")
data class IngredientEntity(
    @PrimaryKey val name: String,
    val quantity: String,
    val confidence: Float,
    val source: String,
    val detectedAtMillis: Long
)

fun IngredientEntity.toDomain() = Ingredient(
    name = name,
    quantity = quantity,
    confidence = confidence,
    source = runCatching { IngredientSource.valueOf(source) }.getOrDefault(IngredientSource.MANUAL),
    detectedAtMillis = detectedAtMillis
)

fun Ingredient.toEntity() = IngredientEntity(
    name = name,
    quantity = quantity,
    confidence = confidence,
    source = source.name,
    detectedAtMillis = detectedAtMillis
)
