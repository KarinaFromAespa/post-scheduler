package com.postscheduler.ui.page

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.FacebookSdk
import com.facebook.GraphRequest
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.postscheduler.data.PlannerRepository
import com.postscheduler.data.remote.PageDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject

/** A Page the signed-in Facebook account manages, as returned by /me/accounts. */
data class FacebookPage(val id: String, val name: String, val accessToken: String, val pictureUrl: String? = null)

class PageViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = PlannerRepository(app)

    val selectedPageId: StateFlow<String?> =
        repo.selectedPageId.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val selectedPageName: StateFlow<String?> =
        repo.selectedPageName.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val backendUrl: StateFlow<String> =
        repo.backendUrl().stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _pages = MutableStateFlow<List<PageDto>>(emptyList())
    val pages: StateFlow<List<PageDto>> = _pages.asStateFlow()
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _facebookPages = MutableStateFlow<List<FacebookPage>>(emptyList())
    val facebookPages: StateFlow<List<FacebookPage>> = _facebookPages.asStateFlow()
    private val _fbStatus = MutableStateFlow<String?>(null)
    val fbStatus: StateFlow<String?> = _fbStatus.asStateFlow()
    private val _fbLoggingIn = MutableStateFlow(false)
    val fbLoggingIn: StateFlow<Boolean> = _fbLoggingIn.asStateFlow()
    private var fbCallbackRegistered = false

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        _loading.value = true
        _error.value = null
        runCatching { repo.pages() }
            .onSuccess { _pages.value = it }
            .onFailure { _error.value = "Could not reach the backend — check the backend URL below." }
        _loading.value = false
    }

    /**
     * Facebook Login to connect a Page:
     * the user signs in with Facebook, grants the Page permissions, and the
     * app lists the Pages they manage via /me/accounts — no manual Page ID.
     * The picked Page's token is handed to the backend (POST /api/pages/connect)
     * so the server can publish as that Page.
     *
     * Permissions: pages_show_list and pages_read_engagement to list Pages,
     * pages_manage_posts so the backend may publish. In a dev-mode Facebook
     * app the owner can use these without App Review; other users need the
     * app reviewed and approved by Facebook first.
     *
     * manage_pages is a removed Facebook permission — requesting it makes
     * Facebook reject the login with "Invalid Scopes".
     */
    fun startFacebookLogin(activity: Activity, callbackManager: CallbackManager) {
        // Without a real App ID + client token the SDK is never initialized
        // (see PlannerApp), and calling LoginManager then would crash.
        // Say so instead of starting the login flow.
        if (!FacebookSdk.isInitialized()) {
            _fbStatus.value = "Facebook isn't set up yet — add the App ID and client token from your Facebook Developer app (gradle.properties: planner.facebook.app.id and planner.facebook.client.token), rebuild, and try again."
            return
        }
        if (!fbCallbackRegistered) {
            fbCallbackRegistered = true
            LoginManager.getInstance().registerCallback(
                callbackManager,
                object : FacebookCallback<LoginResult> {
                    override fun onSuccess(result: LoginResult) {
                        _fbLoggingIn.value = false
                        fetchFacebookPages(result.accessToken)
                    }

                    override fun onCancel() {
                        _fbLoggingIn.value = false
                        _fbStatus.value = "Facebook sign-in cancelled."
                    }

                    override fun onError(error: FacebookException) {
                        _fbLoggingIn.value = false
                        _fbStatus.value = "Facebook sign-in failed: ${error.message}"
                    }
                }
            )
        }
        _fbStatus.value = null
        _fbLoggingIn.value = true
        LoginManager.getInstance().logInWithReadPermissions(
            activity,
            listOf("public_profile", "pages_show_list", "pages_read_engagement", "pages_manage_posts", "business_management", "read_insights", "pages_read_user_content")
        )
    }

    private fun fetchFacebookPages(userToken: com.facebook.AccessToken) {
        val request = GraphRequest.newGraphPathRequest(userToken, "/me/accounts") { response ->
            val found = mutableListOf<FacebookPage>()
            runCatching {
                val data = response.jsonObject?.optJSONArray("data") ?: return@runCatching
                for (i in 0 until data.length()) {
                    val obj: JSONObject = data.getJSONObject(i)
                    val token = obj.optString("access_token")
                    if (token.isNotBlank()) {
                        val pictureUrl = obj.optJSONObject("picture")
                            ?.optJSONObject("data")?.optString("url")?.takeIf { it.isNotBlank() }
                        found += FacebookPage(obj.getString("id"), obj.getString("name"), token, pictureUrl)
                    }
                }
            }
            _facebookPages.value = found
            _fbStatus.value = if (found.isEmpty()) {
                "Signed in, but no Pages were returned. The account may manage no Pages, or the app is missing the pages_read_engagement permission."
            } else {
                "Signed in — pick a Page below."
            }
        }
        request.parameters = Bundle().apply { putString("fields", "id,name,access_token,picture.type(large)") }
        request.executeAsync()
    }

    fun selectFacebookPage(page: FacebookPage) = viewModelScope.launch {
        val stored = repo.connectPage(page.id, page.name, page.accessToken, page.pictureUrl)
        _fbStatus.value = if (stored) {
            "Connected ${page.name} — its token is saved on the backend."
        } else {
            "Connected ${page.name} on this phone. The backend has no /api/pages/connect endpoint yet, so its token is not saved there — posts will save locally until the backend is updated."
        }
        refresh()
    }

    fun selectPage(page: PageDto) = viewModelScope.launch {
        repo.setPage(page.id, page.name)
    }

    /** Remove one Page from the backend, from the "Connected Pages"
     * list. This is the only way to delete a backend Page — without it a
     * Page keeps coming back every time the app opens, even after clearing
     * the app's data, because the list is fetched from the backend. Only
     * clears the local selection (and the Facebook session) when the
     * removed Page was the selected one. If it was, switch to the next
     * remaining Page automatically, in list order, instead of leaving the
     * scheduler with no Page while others are still connected. */
    fun removeBackendPage(id: String) = viewModelScope.launch {
        val removed = repo.removePage(id)
        if (!removed) {
            _error.value = "Could not remove the Page from the backend — check the backend URL below."
            refresh()
            return@launch
        }
        val wasSelected = selectedPageId.value == id
        if (wasSelected) {
            repo.setPage(null, null)
            LoginManager.getInstance().logOut()
        }
        _loading.value = true
        _error.value = null
        runCatching { repo.pages() }
            .onSuccess { remaining ->
                _pages.value = remaining
                if (wasSelected) {
                    remaining.firstOrNull { it.id != id }?.let { next ->
                        repo.setPage(next.id, next.name)
                    }
                }
            }
            .onFailure { _error.value = "Could not reach the backend — check the backend URL below." }
        _loading.value = false
    }

    fun saveBackendUrl(url: String) = viewModelScope.launch {
        if (url.isBlank()) return@launch
        repo.setBackendUrl(url)
        refresh()
    }

    fun clearError() { _error.value = null }
}
