package dev.dt.kinopub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.dt.kinopub.AppContainer
import dev.dt.kinopub.data.ResumeTarget
import dev.dt.kinopub.ui.auth.AuthScreen
import dev.dt.kinopub.ui.details.DetailsScreen
import dev.dt.kinopub.ui.home.HomeScreen
import dev.dt.kinopub.ui.player.PlayerScreen
import dev.dt.kinopub.ui.search.SearchScreen
import dev.dt.kinopub.ui.theme.K

object Routes {
    const val AUTH = "auth"
    const val HOME = "home"
    const val DETAILS = "details/{id}"
    const val SEARCH = "search"
    const val PLAYER = "player/{id}?season={season}&episode={episode}&start={start}"

    fun details(id: Long) = "details/$id"
    fun player(t: ResumeTarget) =
        "player/${t.itemId}?season=${t.season ?: -1}&episode=${t.episode}&start=${t.startSec}"
}

@Composable
fun KinoNavHost(c: AppContainer) {
    val nav = rememberNavController()
    // Decide the start screen once, after DataStore has loaded.
    val loggedIn by produceState<Boolean?>(null) { value = c.tokens.current() != null }
    Box(Modifier.fillMaxSize().background(K.bg)) {
        val start = loggedIn ?: return@Box
        NavHost(nav, startDestination = if (start) Routes.HOME else Routes.AUTH) {
            composable(Routes.AUTH) {
                AuthScreen(c) {
                    nav.navigate(Routes.HOME) { popUpTo(Routes.AUTH) { inclusive = true } }
                }
            }
            composable(Routes.HOME) {
                HomeScreen(
                    c,
                    onPlay = { nav.navigate(Routes.player(it)) },
                    onOpen = { nav.navigate(Routes.details(it)) },
                    onSearch = { nav.navigate(Routes.SEARCH) },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(c, onOpen = { nav.navigate(Routes.details(it)) })
            }
            composable(Routes.DETAILS, listOf(navArgument("id") { type = NavType.LongType })) { e ->
                DetailsScreen(
                    c, e.arguments!!.getLong("id"),
                    onPlay = { nav.navigate(Routes.player(it)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.PLAYER,
                listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("season") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("episode") { type = NavType.IntType; defaultValue = 1 },
                    navArgument("start") { type = NavType.IntType; defaultValue = 0 },
                ),
            ) { e ->
                val a = e.arguments!!
                PlayerScreen(
                    c,
                    ResumeTarget(
                        itemId = a.getLong("id"),
                        season = a.getInt("season").takeIf { it >= 0 },
                        episode = a.getInt("episode"),
                        startSec = a.getInt("start"),
                    ),
                    onExit = { nav.popBackStack() },
                )
            }
        }
    }
}
