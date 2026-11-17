package com.azsaidi.shasha.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.azsaidi.shasha.data.Session
import com.azsaidi.shasha.data.Store

@Composable
fun ShashaNav() {
    val nav = rememberNavController()
    val context = LocalContext.current

    val start = remember {
        val accounts = Store.accounts()
        val current = Session.account
        val last = current
            ?: accounts.firstOrNull { it.id == Store.lastAccountId }
            ?: accounts.singleOrNull()
        when {
            last != null -> {
                if (current == null) Session.open(last)
                "home"
            }
            accounts.isEmpty() -> "login"
            else -> "accounts"
        }
    }

    fun resetTo(route: String) {
        nav.navigate(route) {
            popUpTo(nav.graph.id) { inclusive = true }
        }
    }

    NavHost(navController = nav, startDestination = start) {
        composable("accounts") {
            AccountsScreen(
                onOpen = { account ->
                    Session.open(account)
                    resetTo("home")
                },
                onAdd = { nav.navigate("login") },
                onEdit = { account -> nav.navigate("edit/${account.id}") },
                onEmpty = { resetTo("login") },
            )
        }
        composable("login") {
            LoginScreen(
                canGoBack = nav.previousBackStackEntry != null,
                onBack = { nav.popBackStack() },
                onLoggedIn = { account, auth ->
                    Session.open(account, auth)
                    resetTo("home")
                },
            )
        }
        composable("edit/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val account = remember(id) { Store.accounts().firstOrNull { it.id == id } }
            if (account == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
            } else {
                LoginScreen(
                    canGoBack = true,
                    onBack = { nav.popBackStack() },
                    onLoggedIn = { updated, auth ->
                        Session.accountEdited(updated, auth)
                        toastSaved(context)
                        nav.popBackStack()
                    },
                    editing = account,
                )
            }
        }
        composable("home") {
            HomeScreen(
                onOpenMovie = { id -> nav.navigate("movie/$id") },
                onOpenSeries = { id -> nav.navigate("series/$id") },
                onSwitchAccount = { nav.navigate("accounts") },
                onEditAccount = { id -> nav.navigate("edit/$id") },
                onAccountRemoved = { resetTo(if (Store.accounts().isEmpty()) "login" else "accounts") },
            )
        }
        composable("movie/{id}") { entry ->
            MovieScreen(entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable("series/{id}") { entry ->
            SeriesScreen(entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}
