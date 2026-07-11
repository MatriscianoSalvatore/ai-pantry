package com.smatrisciano.aipantry.recipes.data

import com.smatrisciano.aipantry.inventory.domain.models.Ingredient
import com.smatrisciano.aipantry.recipes.domain.RecipeGenerator
import com.smatrisciano.aipantry.recipes.domain.models.Difficulty
import com.smatrisciano.aipantry.recipes.domain.models.Recipe
import kotlinx.coroutines.delay

/**
 * Fallback demo: ricette pre-generate coerenti con l'inventario dello script
 * Droidcon, con progressi scanditi per simulare l'inferenza dell'LLM.
 */
class DemoRecipeGenerator : RecipeGenerator {

    override val engineName = "Demo LLM"

    override suspend fun generate(
        ingredients: List<Ingredient>,
        onProgress: (String) -> Unit
    ): List<Recipe> {
        val available = ingredients.map { it.name.lowercase() }.toSet()
        onProgress("Loading model on-device…")
        delay(700)
        onProgress("Reasoning over ${ingredients.size} available ingredients…")
        delay(900)
        onProgress("Ranking recipes by ingredient coverage…")
        delay(800)
        onProgress("Writing step-by-step instructions…")
        delay(600)

        return demoRecipes
            .sortedByDescending { recipe ->
                recipe.usedIngredients.count { it.lowercase() in available }
            }
    }

    private val demoRecipes = listOf(
        Recipe(
            title = "Pasta al tonno e pomodoro",
            whySuitable = "Uses 5 of your ingredients in one dish — the highest coverage of your pantry with zero waste.",
            prepTimeMinutes = 20,
            difficulty = Difficulty.EASY,
            usedIngredients = listOf("Pasta", "Canned tuna", "Tomatoes", "Onions", "Parmigiano"),
            steps = listOf(
                "Bring a large pot of salted water to a boil and cook the pasta until al dente.",
                "Meanwhile, finely slice one onion and sauté it in olive oil over medium heat until translucent.",
                "Chop the tomatoes and add them to the pan. Simmer for 8 minutes until they break down into a sauce.",
                "Drain the tuna and fold it into the sauce. Season with salt and pepper.",
                "Drain the pasta, toss it in the sauce with a splash of cooking water.",
                "Serve with grated Parmigiano on top."
            ),
            variants = listOf(
                "Add fresh basil leaves at the end for extra freshness.",
                "Skip the Parmigiano for a lighter, dairy-free version."
            )
        ),
        Recipe(
            title = "Caprese",
            whySuitable = "Your mozzarella, tomatoes and basil are exactly the classic trio — no cooking required, ready in 5 minutes.",
            prepTimeMinutes = 5,
            difficulty = Difficulty.EASY,
            usedIngredients = listOf("Mozzarella", "Tomatoes", "Basil"),
            steps = listOf(
                "Slice the tomatoes and mozzarella into rounds of similar thickness.",
                "Alternate tomato and mozzarella slices on a plate.",
                "Tuck fresh basil leaves between the slices.",
                "Drizzle generously with olive oil, season with salt and pepper."
            ),
            variants = listOf(
                "Add a drop of balsamic glaze if available.",
                "Cube everything and toss as a salad instead of plating."
            )
        ),
        Recipe(
            title = "Pasta al pomodoro con basilico",
            whySuitable = "A timeless classic that uses your tomatoes and basil at their peak, plus the pasta from your pantry.",
            prepTimeMinutes = 25,
            difficulty = Difficulty.EASY,
            usedIngredients = listOf("Pasta", "Tomatoes", "Basil", "Onions", "Parmigiano"),
            steps = listOf(
                "Sauté half a sliced onion in olive oil until golden.",
                "Add chopped tomatoes and a pinch of salt. Simmer gently for 15 minutes.",
                "Cook the pasta in salted boiling water until al dente.",
                "Tear the basil leaves into the sauce off the heat.",
                "Toss the pasta with the sauce and finish with grated Parmigiano."
            ),
            variants = listOf(
                "Blend the sauce for a smooth texture.",
                "Add a spoon of milk to soften the acidity of the tomatoes."
            )
        ),
        Recipe(
            title = "Toast filante",
            whySuitable = "A quick melty snack that uses up your mozzarella — perfect if you only have 10 minutes.",
            prepTimeMinutes = 10,
            difficulty = Difficulty.EASY,
            usedIngredients = listOf("Mozzarella", "Tomatoes"),
            missingIngredients = listOf("Bread"),
            steps = listOf(
                "Slice the mozzarella and pat it dry to avoid soggy toast.",
                "Layer mozzarella and thin tomato slices between two slices of bread.",
                "Toast in a hot pan (or toaster press) for 3–4 minutes per side until the cheese melts.",
                "Cut diagonally and serve hot."
            ),
            variants = listOf(
                "Add a basil leaf inside before toasting.",
                "Sprinkle Parmigiano on the outside for a crispy crust."
            )
        ),
        Recipe(
            title = "Pasta gratinata",
            whySuitable = "Turns your pasta, milk and both cheeses into a comforting baked dish — great for using leftovers.",
            prepTimeMinutes = 40,
            difficulty = Difficulty.MEDIUM,
            usedIngredients = listOf("Pasta", "Milk", "Mozzarella", "Parmigiano", "Onions"),
            steps = listOf(
                "Preheat the oven to 200°C.",
                "Cook the pasta 2 minutes less than the package time and drain.",
                "Warm the milk with a sliced onion for 5 minutes, then remove the onion.",
                "Toss the pasta with the warm milk, diced mozzarella and half the Parmigiano.",
                "Transfer to a baking dish, top with the remaining Parmigiano.",
                "Bake for 15–20 minutes until golden and bubbling."
            ),
            variants = listOf(
                "Add tuna for a heartier version.",
                "Mix in chopped tomatoes before baking for extra moisture."
            )
        )
    )
}
