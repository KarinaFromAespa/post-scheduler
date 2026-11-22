package com.postscheduler.ui.page

import android.app.Activity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.facebook.CallbackManager
import com.postscheduler.ui.components.PageAvatar

/**
 * Connect a Facebook Page: sign in
 * with Facebook, grant the Page permissions, and pick one of the Pages the
 * account manages. The Page's token goes to the backend so the server can
 * publish as that Page.
 */
@Composable
fun PageScreen(
    vm: PageViewModel = viewModel(),
    callbackManager: CallbackManager? = null
) {
    val pages by vm.pages.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val selectedId by vm.selectedPageId.collectAsState()
    val backendUrl by vm.backendUrl.collectAsState()
    val facebookPages by vm.facebookPages.collectAsState()
    val fbStatus by vm.fbStatus.collectAsState()
    val fbLoggingIn by vm.fbLoggingIn.collectAsState()

    var url by remember(backendUrl) { mutableStateOf(backendUrl) }
    val activity = LocalContext.current as Activity
    val hasConnectedPage = selectedId != null || pages.isNotEmpty()
    // The Facebook picker lists Pages the account manages. Hide the ones
    // already connected to the backend, otherwise a connected Page shows
    // twice on this screen (once in the picker, once under Connected
    // Pages) right after signing in, until navigating away clears the
    // picker. The picker is for connecting *more* Pages, so it only
    // lists Pages that are not connected yet.
    val connectedIds = pages.map { it.id }.toSet()
    val unconnectedFacebookPages = facebookPages.filter { it.id !in connectedIds }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    ) {
        Text("Connect Page", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))

        Text(if (hasConnectedPage) "Connect more pages" else "Connect with Facebook", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Sign in with the Facebook account that manages the Page. The app lists your Pages — pick one and its token goes to the backend.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (callbackManager != null) vm.startFacebookLogin(activity, callbackManager)
            },
            enabled = callbackManager != null && !fbLoggingIn,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (fbLoggingIn) "Signing in…" else if (hasConnectedPage) "Connect more pages" else "Connect with Facebook") }
        fbStatus?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        if (unconnectedFacebookPages.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            unconnectedFacebookPages.forEach { page ->
                PageRow(
                    name = page.name,
                    id = page.id,
                    selected = page.id == selectedId,
                    onClick = { vm.selectFacebookPage(page) },
                    pictureUrl = page.pictureUrl
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        Text("Connected Pages", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        if (loading) {
            Text("Loading…")
        } else if (pages.isEmpty()) {
            Text(
                "No Pages returned by the backend. Either the backend has no Page token configured yet, or it is unreachable — check the backend URL below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            pages.forEach { page ->
                PageRow(
                    name = page.name,
                    id = page.id,
                    selected = page.id == selectedId,
                    onClick = { vm.selectPage(page) },
                    pictureUrl = page.pictureUrl,
                    onRemove = { vm.removeBackendPage(page.id) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.refresh() }) { Text("Refresh pages") }
        Spacer(Modifier.height(20.dp))

        Text("Backend", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url, onValueChange = { url = it },
            label = { Text("Backend URL") }, modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.saveBackendUrl(url) }) { Text("Save backend URL") }
        Spacer(Modifier.height(12.dp))
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun PageRow(
    name: String,
    id: String,
    selected: Boolean,
    onClick: () -> Unit,
    pictureUrl: String? = null,
    onRemove: (() -> Unit)? = null
) {
    Row(
        Modifier.fillMaxWidth().selectable(
            selected = selected, onClick = onClick, role = Role.RadioButton
        ).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        PageAvatar(
            pictureUrl = pictureUrl,
            name = name,
            size = 36.dp,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium)
            Text(
                "ID: $id", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Remove this Page from the backend. Without it a backend Page
        // keeps coming back every time the app opens, even after clearing
        // the app's data, because the list is fetched from the backend.
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Remove $name from the backend"
                )
            }
        }
    }
}
