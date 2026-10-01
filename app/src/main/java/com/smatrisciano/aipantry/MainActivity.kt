package com.smatrisciano.aipantry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smatrisciano.aipantry.capture.presentation.CaptureActions
import com.smatrisciano.aipantry.capture.presentation.CaptureScreenRoot
import com.smatrisciano.aipantry.core.navigation.Destination
import com.smatrisciano.aipantry.core.navigation.NavArgument
import com.smatrisciano.aipantry.core.presentation.theme.AiPantryTheme
import com.smatrisciano.aipantry.inventory.presentation.InventoryActions
import com.smatrisciano.aipantry.inventory.presentation.InventoryScreenRoot
import com.smatrisciano.aipantry.recipes.presentation.RecipeDetailScreenRoot
import com.smatrisciano.aipantry.recipes.presentation.RecipesActions
import com.smatrisciano.aipantry.recipes.presentation.RecipesScreenRoot
import com.smatrisciano.aipantry.recipes.presentation.RecipesViewModel
import org.koin.androidx.compose.KoinAndroidContext
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KoinAndroidContext {
                AiPantryTheme {
                    AiPantryNavHost()
                }
            }
        }
    }
}

@Composable
private fun AiPantryNavHost() {
    val navController = rememberNavController()

    // Material shared-axis X: the new screen slides in from the side as the old one
    // drifts away, both fading; reversed when going back
    NavHost(
        navController = navController,
        startDestination = Destination.InventoryRoutes.graphRoot.route,
        enterTransition = {
            slideInHorizontally(tween(NAV_ANIMATION_MILLIS)) { it / 5 } + fadeIn(tween(NAV_ANIMATION_MILLIS))
        },
        exitTransition = {
            slideOutHorizontally(tween(NAV_ANIMATION_MILLIS)) { -it / 10 } + fadeOut(tween(NAV_ANIMATION_MILLIS / 2))
        },
        popEnterTransition = {
            slideInHorizontally(tween(NAV_ANIMATION_MILLIS)) { -it / 10 } + fadeIn(tween(NAV_ANIMATION_MILLIS))
        },
        popExitTransition = {
            slideOutHorizontally(tween(NAV_ANIMATION_MILLIS)) { it / 5 } + fadeOut(tween(NAV_ANIMATION_MILLIS / 2))
        }
    ) {
        composable(Destination.InventoryRoutes.graphRoot.route) {
            InventoryScreenRoot(
                onNavigation = { navigation ->
                    when (navigation) {
                        InventoryActions.Navigation.GoToCapture ->
                            navController.navigate(Destination.CaptureRoutes.graphRoot.route)
                        InventoryActions.Navigation.GoToRecipes ->
                            navController.navigate(Destination.RecipeRoutes.graphRoot.route)
                    }
                }
            )
        }

        composable(Destination.CaptureRoutes.graphRoot.route) {
            CaptureScreenRoot(
                onNavigation = { navigation ->
                    when (navigation) {
                        CaptureActions.Navigation.GoBack -> navController.popBackStack()
                    }
                }
            )
        }

        navigation(
            route = Destination.RecipeRoutes.graphRoot.route,
            startDestination = Destination.RecipeRoutes.list.route
        ) {
            composable(Destination.RecipeRoutes.list.route) { backStackEntry ->
                val viewModel: RecipesViewModel = backStackEntry.sharedViewModel(navController)
                RecipesScreenRoot(
                    viewModel = viewModel,
                    onNavigation = { navigation ->
                        when (navigation) {
                            is RecipesActions.Navigation.GoToDetail ->
                                navController.navigate(
                                    Destination.RecipeRoutes.detail(navigation.recipeIndex).route
                                )
                            RecipesActions.Navigation.GoBack -> navController.popBackStack()
                        }
                    }
                )
            }

            composable(
                route = Destination.RecipeRoutes.detail.route,
                arguments = listOf(
                    navArgument(NavArgument.RecipeIndex.argument) { type = NavType.IntType }
                )
            ) { backStackEntry ->
                val viewModel: RecipesViewModel = backStackEntry.sharedViewModel(navController)
                val recipeIndex =
                    backStackEntry.arguments?.getInt(NavArgument.RecipeIndex.argument) ?: 0
                RecipeDetailScreenRoot(
                    viewModel = viewModel,
                    recipeIndex = recipeIndex,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

private const val NAV_ANIMATION_MILLIS = 300

/** ViewModel shared by the destinations of the same nav graph. */
@Composable
private inline fun <reified T : ViewModel> NavBackStackEntry.sharedViewModel(
    navController: NavController
): T {
    val graphRoute = destination.parent?.route ?: return koinViewModel()
    val parentEntry = remember(this) { navController.getBackStackEntry(graphRoute) }
    return koinViewModel(viewModelStoreOwner = parentEntry)
}
