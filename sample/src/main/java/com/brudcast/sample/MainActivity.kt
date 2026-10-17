package com.brudcast.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.brudcast.sample.screens.AccountScreen
import com.brudcast.sample.screens.CheckoutScreen
import com.brudcast.sample.screens.HomeScreen
import com.brudcast.sample.screens.ProductScreen
import com.brudcast.sample.screens.ShopScreen
import com.brudcast.sample.screens.SignInScreen
import com.brudcast.sample.ui.BrudcastTheme
import com.brudcast.sample.ui.Rule
import com.brudcast.sample.ui.tokens
import com.brudcast.sdk.Brudcast
import com.brudcast.sdk.inapp.inApp
import com.brudcast.sdk.push.push

/** The sample's only activity: sign-in when signed out, the tabbed app when signed in. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openLink(intent)
        setContent {
            BrudcastTheme {
                Surface(Modifier.fillMaxSize()) {
                    val user by SampleSession.current.collectAsState()
                    val signedIn = user
                    if (signedIn == null) SignInScreen() else SignedInApp(this, signedIn)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Brudcast.push.handleIntent(intent)
        openLink(intent)
    }

    private fun openLink(intent: Intent) {
        intent.data?.takeIf { it.scheme == SampleApplication.DEEP_LINK_SCHEME }?.let(SampleRouter::open)
    }
}

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Outlined.Home),
    SHOP("shopTab", "Shop", Icons.Outlined.ShoppingCart),
    ACCOUNT("account", "Account", Icons.Outlined.AccountCircle),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignedInApp(activity: Activity, user: SampleSession.User) {
    val navigation = rememberNavController()
    LaunchedEffect(navigation) {
        SampleRouter.destinations.collect { destination ->
            navigation.open(Tab.SHOP)
            if (destination == SampleRouter.Destination.CHECKOUT) navigation.navigate("checkout/${Catalog.featured.id}")
        }
    }
    val entry by navigation.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val isTabRoot = route == null || Tab.entries.any { it.route == route } || route == "shop"
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Brudcast Sample", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.tokens.brand) },
                    navigationIcon = {
                        if (!isTabRoot) {
                            IconButton(onClick = { navigation.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = { InboxButton(activity) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                )
                Rule()
            }
        },
        bottomBar = {
            Column {
                Rule()
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    Tab.entries.forEach { tab ->
                        val selected = entry?.destination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigation.open(tab) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("${tab.route}Tab"),
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.tokens.navActive,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navigation, startDestination = Tab.HOME.route, Modifier.padding(padding)) {
            composable(Tab.HOME.route) { HomeScreen(activity, user, onShop = { navigation.open(Tab.SHOP) }) }
            navigation(startDestination = "shop", route = Tab.SHOP.route) {
                composable("shop") { ShopScreen(onOpen = { navigation.navigate("product/${it.id}") }) }
                composable("product/{id}") { backStack ->
                    val product = Catalog.product(backStack.arguments?.getString("id"))
                    ProductScreen(product, onBuy = { navigation.navigate("checkout/${product.id}") })
                }
                composable("checkout/{id}") { backStack ->
                    CheckoutScreen(Catalog.product(backStack.arguments?.getString("id")), onDone = { navigation.popBackStack("shop", inclusive = false) })
                }
            }
            composable(Tab.ACCOUNT.route) { AccountScreen(activity, user) }
        }
    }
}

/** The inbox bell, badged with the unread count, as an app puts it in its top bar. */
@Composable
private fun InboxButton(activity: Activity) {
    val unread by Brudcast.inApp.inbox.unreadCount.collectAsState()
    IconButton(onClick = { Brudcast.inApp.inbox.present(activity) }, modifier = Modifier.testTag("inboxButton")) {
        BadgedBox(badge = {
            if (unread > 0) Badge(containerColor = MaterialTheme.colorScheme.primary) { Text(unread.toString()) }
        }) {
            Icon(Icons.Outlined.Notifications, contentDescription = "Inbox, $unread unread")
        }
    }
}

private fun NavHostController.open(tab: Tab) {
    navigate(tab.route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
