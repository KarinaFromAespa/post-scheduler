package com.postscheduler.data.remote

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.postscheduler.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

private val Context.dataStore by preferencesDataStore("planner_settings")
private val BACKEND_URL_KEY = stringPreferencesKey("backend_url")
private val PAGE_ID_KEY = stringPreferencesKey("page_id")
private val PAGE_NAME_KEY = stringPreferencesKey("page_name")

class SettingsStore(private val context: Context) {
    val backendUrl: Flow<String> = context.dataStore.data.map {
        it[BACKEND_URL_KEY] ?: BuildConfig.BACKEND_URL
    }
    suspend fun setBackendUrl(url: String) {
        context.dataStore.edit { it[BACKEND_URL_KEY] = url.trim().trimEnd('/') }
    }
    fun currentBackendUrl(): String = runBlocking { backendUrl.first() }

    val pageId: Flow<String?> = context.dataStore.data.map { it[PAGE_ID_KEY] }
    val pageName: Flow<String?> = context.dataStore.data.map { it[PAGE_NAME_KEY] }
    suspend fun setPage(id: String?, name: String?) {
        context.dataStore.edit {
            if (id == null) it.remove(PAGE_ID_KEY) else it[PAGE_ID_KEY] = id
            if (name == null) it.remove(PAGE_NAME_KEY) else it[PAGE_NAME_KEY] = name
        }
    }
    fun currentPageId(): String? = runBlocking { pageId.first() }
    fun currentPageName(): String? = runBlocking { pageName.first() }
}

object ApiFactory {
    fun create(baseUrl: String): PlannerApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        // Backend API key: sent as X-Api-Key on every call. The backend
        // rejects /api/* without it once API_KEY is set on the VM. The
        // key lives in BuildConfig (from ~/.gradle/gradle.properties,
        // never in the repo); when it is empty no header is sent, so a
        // backend without a key set still works.
        val apiKey = okhttp3.Interceptor { chain ->
            val req = chain.request().newBuilder().apply {
                if (BuildConfig.API_KEY.isNotEmpty()) header("X-Api-Key", BuildConfig.API_KEY)
            }.build()
            chain.proceed(req)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(apiKey)
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PlannerApi::class.java)
    }
}
