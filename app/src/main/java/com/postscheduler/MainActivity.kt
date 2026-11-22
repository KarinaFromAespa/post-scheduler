package com.postscheduler

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.facebook.CallbackManager
import com.postscheduler.data.local.Post
import com.postscheduler.ui.create.CreateScreen
import com.postscheduler.ui.drafts.DraftsScreen
import com.postscheduler.ui.insights.InsightsScreen
import com.postscheduler.ui.page.PageScreen
import com.postscheduler.ui.publish.PublishScreen
import com.postscheduler.ui.theme.PlannerTheme

/**
 * Bottom nav: Create / Publish / Drafts only. No Community tab.
 * Publish is home. FAB goes to Create.
 *
 * The selected tab is read from the NavController's back stack, not from a
 * separate remembered variable — that is the single source of truth for
 * which screen is showing, so the highlighted tab can never disagree with
 * the content (e.g. Drafts showing Create's screen).
 */
class MainActivity : ComponentActivity() {
    // Facebook Login hands its result back through this manager.
    private val fbCallbackManager = CallbackManager.Factory.create()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PlannerTheme { PlannerNav(fbCallbackManager) } }
    }

    @Deprecated("Used by the Facebook SDK login flow")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        fbCallbackManager.onActivityResult(requestCode, resultCode, data)
        super.onActivityResult(requestCode, resultCode, data)
    }
}

private enum class Tab(val route: String, val label: String) {
    CREATE("create", "Create"),
    PUBLISH("publish", "Publish"),
    DRAFTS("drafts", "Drafts"),
    INSIGHTS("insights", "Insights")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlannerNav(fbCallbackManager: CallbackManager) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val currentTab = Tab.entries.firstOrNull { it.route == currentRoute }
    // The scheduled post being edited, if any — set from Publish's Edit
    // action, cleared once the edit is saved or abandoned.
    var postToEdit by remember { mutableStateOf<Post?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Post Scheduler",
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = {
                        nav.navigate("page") { launchSingleTop = true }
                    }) {
                        Icon(Icons.Default.AccountCircle, contentDescription = "Connect Page")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                listOf(Tab.CREATE, Tab.PUBLISH, Tab.DRAFTS, Tab.INSIGHTS).forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = {
                            if (tab == Tab.CREATE) postToEdit = null
                            // A screen pushed on top of a tab — the Page
                            // screen, or Create reached from Publish's
                            // "Schedule a post" — is not a tab, so a plain
                            // tab navigate leaves it in place and the tap
                            // looks dead (only the system Back button got
                            // back to the tab). Unwind to the start
                            // destination first, then switch tabs.
                            nav.popBackStack(nav.graph.startDestinationId, inclusive = false, saveState = true)
                            nav.navigate(tab.route) {
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            when (tab) {
                                Tab.CREATE -> Icon(Icons.Default.Add, null)
                                Tab.PUBLISH -> Icon(Icons.Default.CalendarMonth, null)
                                Tab.DRAFTS -> Icon(Icons.Default.EditNote, null)
                                Tab.INSIGHTS -> Icon(Icons.Default.BarChart, null)
                            }
                        },
                        label = { Text(tab.label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (currentTab != Tab.CREATE && currentRoute != "page") {
                FloatingActionButton(
                    onClick = {
                        postToEdit = null
                        nav.popBackStack(nav.graph.startDestinationId, inclusive = false, saveState = true)
                        nav.navigate(Tab.CREATE.route) {
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation()
                ) {
                    Icon(Icons.Default.Add, "Create")
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav, startDestination = Tab.PUBLISH.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Tab.PUBLISH.route) {
                PublishScreen(
                    onCreate = {
                        postToEdit = null
                        nav.navigate(Tab.CREATE.route) { launchSingleTop = true }
                    },
                    onEdit = { post ->
                        postToEdit = post
                        nav.navigate(Tab.CREATE.route) { launchSingleTop = true }
                    }
                )
            }
            composable(Tab.CREATE.route) {
                CreateScreen(
                    onDone = {
                        postToEdit = null
                        // Saving always returns to Publish, the start
                        // destination — unwind there directly instead of
                        // navigating to it, so a Create screen pushed on
                        // top of a tab is reliably left behind.
                        nav.popBackStack(nav.graph.startDestinationId, inclusive = false, saveState = true)
                    },
                    onConnectPage = { nav.navigate("page") { launchSingleTop = true } },
                    editPost = postToEdit
                )
            }
            composable(Tab.DRAFTS.route) {
                DraftsScreen(
                    onCreate = {
                        postToEdit = null
                        nav.navigate(Tab.CREATE.route) { launchSingleTop = true }
                    },
                    onEdit = { post ->
                        postToEdit = post
                        nav.navigate(Tab.CREATE.route) { launchSingleTop = true }
                    }
                )
            }
            composable(Tab.INSIGHTS.route) { InsightsScreen() }
            composable("page") { PageScreen(callbackManager = fbCallbackManager) }
        }
    }
}
