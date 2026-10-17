package dev.dt.kinopub

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dev.dt.kinopub.data.Repository
import dev.dt.kinopub.data.api.KinopubApi
import dev.dt.kinopub.data.auth.DeviceRegistration
import dev.dt.kinopub.data.auth.TokenStore
import dev.dt.kinopub.data.prefs.LocalPrefs
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/** Manual DI: one process-wide container, no framework. */
class KinopubApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .build()
}

class AppContainer(app: Application) {
    /** For writes that must survive the screen that started them (progress on exit). */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        })
        .build()
    val tokens = TokenStore(app)
    val api = KinopubApi(http, tokens)
    val prefs = LocalPrefs(app)
    val repository = Repository(api, prefs, app.filesDir)
    val registration = DeviceRegistration(api)

    val watchNext = dev.dt.kinopub.system.WatchNext(app)

    /** A "resume this" request from a deep link (Google TV Continue watching row). */
    val pendingPlay = kotlinx.coroutines.flow.MutableStateFlow<dev.dt.kinopub.data.ResumeTarget?>(null)

    /** Splash stays up until the first real screen has content (Home data or the login code). */
    val uiReady = kotlinx.coroutines.flow.MutableStateFlow(false)
}
