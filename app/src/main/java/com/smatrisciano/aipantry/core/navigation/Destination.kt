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

    abstract class AiSetupRoutes {
        companion object {
            val graphRoot = Route("ai_setup")
        }
    }

    abstract class RecipeRoutes {
        companion object {
            val graphRoot = Route("recipes")
            val list = Route("${graphRoot.route}/list")
            val detail = Route("${graphRoot.route}/detail/{${NavArgument.RecipeIndex.argument}}")

            fun detail(index: Int) = Route("${graphRoot.route}/detail/$index")
        }
    }
}

enum class NavArgument(val argument: String) {
    RecipeIndex("recipeIndex")
}
