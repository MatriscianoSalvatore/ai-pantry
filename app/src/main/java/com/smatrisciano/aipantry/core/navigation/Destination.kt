package com.smatrisciano.aipantry.core.navigation

sealed interface Destination {

    data class Route(val route: String)

    abstract class InventoryRoutes {
        companion object {
            val graphRoot = Route("inventory")
        }
    }

    abstract class CaptureRoutes {
        companion object {
            val graphRoot = Route("capture")
        }
    }


    abstract class RecipeRoutes {
        companion object {
            val graphRoot = Route("recipes")
            val list = Route("${graphRoot.route}/list")
            val detail = Route("${graphRoot.route}/detail/{${NavArgument.RecipeId.argument}}")

            fun detail(recipeId: Int) = Route("${graphRoot.route}/detail/$recipeId")
        }
    }
}

enum class NavArgument(val argument: String) {
    RecipeId("recipeId")
}
