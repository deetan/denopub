package dev.dt.kinopub.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** One forecast point: the screensaver shows an icon (cloudiness / rain / snow), temperature and precipitation. */
@Serializable
data class WeatherSlot(
    val label: String,
    val code: Int,          // WMO weather code
    val temp: Int,          // °C
    val cloud: Int,         // %
    val precipProb: Int?,   // %
    val precipMm: Double,
    val day: Boolean,
)

@Serializable
data class Forecast(
    val now: WeatherSlot,
    val hours: List<WeatherSlot>,      // in 2, 4 and 6 hours
    val tomorrow: List<WeatherSlot>,   // 09:00 утро, 14:00 день, 19:00 вечер
    val place: String? = null,         // shown only when not the home location
    val fetchedAt: Long,
)

/**
 * Weather for the screensaver, from Open-Meteo (free, no key). Location: the owner's default is Belgrade,
 * Palilula; once a day the TV also asks ipwho.is where its IP is, and if that is more than 30 km away
 * (a trip) the forecast follows the IP location (owner 2026-11-15: "Белград, Палилула дефолт, а дальше по ип").
 * Forecast refreshed at most every 30 min, kept in `filesDir/weather.json` so it shows at once.
 */
class Weather(private val http: OkHttpClient, filesDir: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheFile = File(filesDir, "weather.json")
    @Volatile private var cached: Forecast? = runCatching { json.decodeFromString<Forecast>(cacheFile.readText()) }.getOrNull()
    @Volatile private var located: Triple<Double, Double, String?>? = null
    @Volatile private var locatedAt = 0L

    /** Last known forecast at once (may be stale), for the first frame. */
    fun last(): Forecast? = cached

    suspend fun get(): Forecast? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cached?.let { if (now - it.fetchedAt < 30 * 60_000L) return@withContext it }
        runCatching {
            val (lat, lon, place) = location()
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,precipitation,cloud_cover,weather_code,is_day" +
                "&hourly=temperature_2m,precipitation_probability,precipitation,cloud_cover,weather_code,is_day" +
                "&timezone=auto&forecast_days=3"
            val o = json.parseToJsonElement(fetch(url)).jsonObject
            parse(o, place).also {
                cached = it
                cacheFile.writeText(json.encodeToString(Forecast.serializer(), it))
                Log.i(TAG, "weather: ${it.now.temp}° code ${it.now.code} ${place ?: "home"}")
            }
        }.onFailure { Log.w(TAG, "weather: $it") }.getOrNull() ?: cached
    }

    private fun fetch(url: String): String =
        http.newCall(Request.Builder().url(url).header("User-Agent", ArtCatalog.USER_AGENT).build()).execute().use { r ->
            val body = r.body?.string()
            if (!r.isSuccessful || body == null) error("HTTP ${r.code} for $url")
            body
        }

    /** Home (Palilula) unless the IP says we are elsewhere; looked up at most once a day. */
    private fun location(): Triple<Double, Double, String?> {
        val now = System.currentTimeMillis()
        located?.let { if (now - locatedAt < 24 * 3600_000L) return it }
        val home = Triple(HOME_LAT, HOME_LON, null as String?)
        val result = runCatching {
            val o = json.parseToJsonElement(fetch("https://ipwho.is/")).jsonObject
            if (o["success"]?.jsonPrimitive?.contentOrNull != "true") error("ipwho.is: ${o["message"]}")
            val lat = o["latitude"]!!.jsonPrimitive.double
            val lon = o["longitude"]!!.jsonPrimitive.double
            val city = o["city"]?.jsonPrimitive?.contentOrNull
            val km = distanceKm(lat, lon, HOME_LAT, HOME_LON)
            Log.i(TAG, "weather: ip says $city, ${km.roundToInt()} km from home")
            if (km > 30) Triple(lat, lon, city) else home
        }.onFailure { Log.w(TAG, "weather: ip lookup failed: $it") }.getOrDefault(home)
        located = result; locatedAt = now
        return result
    }

    private fun parse(o: JsonObject, place: String?): Forecast {
        val cur = o["current"]!!.jsonObject
        val h = o["hourly"]!!.jsonObject
        val times = h["time"]!!.jsonArray.map { LocalDateTime.parse(it.jsonPrimitive.content) }
        fun ints(k: String) = h[k]!!.jsonArray.map { it.jsonPrimitive.intOrNull }
        fun dbls(k: String) = h[k]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull }
        val temp = dbls("temperature_2m"); val prob = ints("precipitation_probability"); val mm = dbls("precipitation")
        val cloud = ints("cloud_cover"); val code = ints("weather_code"); val isDay = ints("is_day")
        fun slot(i: Int, label: String) = WeatherSlot(
            label, code[i] ?: 0, (temp[i] ?: 0.0).roundToInt(), cloud[i] ?: 0, prob[i], mm[i] ?: 0.0, isDay[i] == 1,
        )
        val nowTime = LocalDateTime.parse(cur["time"]!!.jsonPrimitive.content)
        val hourNow = nowTime.truncatedTo(ChronoUnit.HOURS)
        val iNow = times.indexOf(hourNow).coerceAtLeast(0)
        val now = WeatherSlot(
            "Сейчас", cur["weather_code"]!!.jsonPrimitive.int, cur["temperature_2m"]!!.jsonPrimitive.double.roundToInt(),
            cur["cloud_cover"]!!.jsonPrimitive.int, prob[iNow], cur["precipitation"]!!.jsonPrimitive.double,
            cur["is_day"]!!.jsonPrimitive.int == 1,
        )
        val hours = listOf(2, 4, 6).mapNotNull { d ->
            val t = hourNow.plusHours(d.toLong())
            times.indexOf(t).takeIf { it >= 0 }?.let { slot(it, "%02d:00".format(t.hour)) }
        }
        val tomorrowDate = nowTime.toLocalDate().plusDays(1)
        val tomorrow = listOf(9 to "утро", 14 to "день", 19 to "вечер").mapNotNull { (hr, label) ->
            times.indexOf(tomorrowDate.atTime(hr, 0)).takeIf { it >= 0 }?.let { slot(it, label) }
        }
        return Forecast(now, hours, tomorrow, place, System.currentTimeMillis())
    }

    companion object {
        const val TAG = "denopub"
        // Belgrade, Palilula (owner's default).
        const val HOME_LAT = 44.814
        const val HOME_LON = 20.478

        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dx = (lon1 - lon2) * 111.32 * cos(Math.toRadians((lat1 + lat2) / 2))
            val dy = (lat1 - lat2) * 110.57
            return sqrt(dx * dx + dy * dy)
        }

        /** Emoji for a slot: rain/snow/storm/fog from the WMO code, otherwise by cloud cover. */
        fun icon(s: WeatherSlot): String = when (s.code) {
            95, 96, 99 -> "⛈"
            71, 73, 75, 77, 85, 86 -> "🌨"
            51, 53, 55, 56, 57 -> "🌦"
            61, 63, 65, 66, 67, 80, 81, 82 -> "🌧"
            45, 48 -> "🌫"
            else -> when {
                s.cloud < 20 -> if (s.day) "☀️" else "🌙"
                s.cloud < 50 -> if (s.day) "🌤" else "🌙"
                s.cloud < 85 -> "⛅"
                else -> "☁️"
            }
        }
    }
}
