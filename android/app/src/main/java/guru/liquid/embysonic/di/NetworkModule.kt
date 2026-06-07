package guru.liquid.embysonic.di

import guru.liquid.embysonic.BuildConfig
import guru.liquid.embysonic.data.coordinator.CoordinatorApi
import guru.liquid.embysonic.data.emby.EmbyApi
import guru.liquid.embysonic.data.net.BaseUrlInterceptor
import guru.liquid.embysonic.data.net.CoordinatorAuthInterceptor
import guru.liquid.embysonic.data.net.CoordinatorClient
import guru.liquid.embysonic.data.net.EmbyAuthInterceptor
import guru.liquid.embysonic.data.net.EmbyClient
import guru.liquid.embysonic.data.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Placeholder base URL; the real host is injected per-request by BaseUrlInterceptor. */
private const val PLACEHOLDER_BASE_URL = "http://localhost/"

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    private fun logging(): HttpLoggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BASIC
        } else {
            HttpLoggingInterceptor.Level.NONE
        }
    }

    @Provides
    @Singleton
    @EmbyClient
    fun provideEmbyOkHttp(settings: SettingsRepository): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(EmbyAuthInterceptor(settings))
            .addInterceptor(BaseUrlInterceptor { settings.snapshot().serverUrl })
            .addInterceptor(logging())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    @CoordinatorClient
    fun provideCoordinatorOkHttp(settings: SettingsRepository): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(CoordinatorAuthInterceptor(settings))
            .addInterceptor(BaseUrlInterceptor { settings.snapshot().coordinatorUrl })
            .addInterceptor(logging())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideEmbyApi(
        @EmbyClient client: OkHttpClient,
        json: Json,
    ): EmbyApi = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(EmbyApi::class.java)

    @Provides
    @Singleton
    fun provideCoordinatorApi(
        @CoordinatorClient client: OkHttpClient,
        json: Json,
    ): CoordinatorApi = Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(CoordinatorApi::class.java)
}
