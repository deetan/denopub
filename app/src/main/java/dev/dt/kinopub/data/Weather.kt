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

/** One later day (owner 2026-11-22: 3–4 more days after tomorrow evening): icon, max/min, chance of rain. */
@Serializable
data class DaySlot(
    val label: String,      // "чт"
    val code: Int,          // WMO weather code (the day's most severe)
    val max: Int,
    val min: Int,
    val cloud: Int,         // mean cloud cover, %
    val precipProb: Int?,   // max chance of rain, %
    val precipMm: Double,   // day total
)

@Serializable
data class Forecast(
    val now: WeatherSlot,
    val hours: List<WeatherSlot>,      // in 3 and 6 hours
    val tomorrow: List<WeatherSlot>,   // 09:00 утро, 14:00 день, 19:00 вечер
    val place: String? = null,         // shown only when not the home location
    val fetchedAt: Long,
    val windMs: Double? = null,        // now, m/s
    val gustMs: Double? = null,
    val windFrom: Int? = null,         // degrees the wind blows FROM
    val aqi: Int? = null,              // US AQI now (0–500, as IQAir etc. show it; Open-Meteo / CAMS model, hourly)
    val pm25: Double? = null,
    val humidity: Int? = null,         // relative humidity now, %
    val days: List<DaySlot> = emptyList(), // the DAYS days after tomorrow
)

/**
 * Weather for the screensaver, from Open-Meteo (free, no key). Location: the owner's default is Belgrade,
 * Palilula; once a day the TV also asks ipwho.is where its IP is, and if that is more than 30 km away
 * (a trip) the forecast follows the IP location (owner 2026-11-15: "Белград, Палилула дефолт, а дальше по ип").
 * Air: trimmed mean of nearby Sensor.Community ground sensors, else the Open-Meteo/CAMS model.
 * Forecast refreshed at most every 15 min, kept in `filesDir/weather.json` so it shows at once.
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
        cached?.let { if (now - it.fetchedAt < 15 * 60_000L) return@withContext it }
        runCatching {
            val (lat, lon, place) = location()
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,relative_humidity_2m,precipitation,cloud_cover,weather_code,is_day,wind_speed_10m,wind_direction_10m,wind_gusts_10m" +
                "&wind_speed_unit=ms" +
                "&hourly=temperature_2m,precipitation_probability,precipitation,cloud_cover,weather_code,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,cloud_cover_mean,precipitation_probability_max,precipitation_sum" +
                "&timezone=auto&forecast_days=${2 + DAYS}"
            val o = json.parseToJsonElement(fetch(url)).jsonObject
            // Air quality is a separate API; a failure there must not lose the forecast.
            val air = runCatching {
                json.parseToJsonElement(fetch(
                    "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=us_aqi,pm2_5&timezone=auto",
                )).jsonObject["current"]!!.jsonObject
            }.onFailure { Log.w(TAG, "weather: air quality: $it") }.getOrNull()
            // Ground sensors (Sensor.Community volunteers) beat the CAMS model when there are enough nearby.
            val rh = o["current"]?.jsonObject?.get("relative_humidity_2m")?.jsonPrimitive?.doubleOrNull
            val ground = runCatching { groundPm25(lat, lon)?.let { humidityCorrected(it, rh) } }.onFailure { Log.w(TAG, "weather: sensors: $it") }.getOrNull()
            val pm25 = ground ?: air?.get("pm2_5")?.jsonPrimitive?.doubleOrNull
            parse(o, place).copy(
                aqi = if (ground != null) usAqi(ground) else air?.get("us_aqi")?.jsonPrimitive?.doubleOrNull?.roundToInt(),
                pm25 = pm25,
                humidity = rh?.roundToInt(),
            ).also {
                cached = it
                cacheFile.writeText(json.encodeToString(Forecast.serializer(), it))
                Log.i(TAG, "weather: ${it.now.temp}° code ${it.now.code} wind ${it.windMs} aqi ${it.aqi} ${place ?: "home"}")
            }
        }.onFailure { Log.w(TAG, "weather: $it") }.getOrNull() ?: cached
    }

    /**
     * PM2.5 of the outdoor Sensor.Community sensors within 3 km (newest reading per sensor, last ~5 min),
     * figure-skating style (owner 2026-11-15): drop the lowest and the highest, average the rest — one
     * sensor near Palilula reads ~170 µg/m³ while its neighbours read 5–12. Null with fewer than 3 sensors.
     */
    private fun groundPm25(lat: Double, lon: Double): Double? {
        val arr = json.parseToJsonElement(fetch("https://data.sensor.community/airrohr/v1/filter/area=$lat,$lon,3")).jsonArray
        val latest = HashMap<String, Pair<String, Double>>()
        for (e in arr) {
            val r = e.jsonObject
            if (r["location"]?.jsonObject?.get("indoor")?.jsonPrimitive?.intOrNull == 1) continue
            val id = r["sensor"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: continue
            val ts = r["timestamp"]?.jsonPrimitive?.content ?: continue
            val v = r["sensordatavalues"]?.jsonArray?.firstOrNull { it.jsonObject["value_type"]?.jsonPrimitive?.content == "P2" }
                ?.jsonObject?.get("value")?.jsonPrimitive?.content?.toDoubleOrNull() ?: continue
            if (v < 0 || v > 1000) continue
            if ((latest[id]?.first ?: "") < ts) latest[id] = ts to v
        }
        val vals = latest.values.map { it.second }.sorted()
        if (vals.size < 3) { Log.i(TAG, "weather: only ${vals.size} sensors nearby, using the model"); return null }
        // Figure-skating style: drop the lowest and the highest, average the rest.
        val m = vals.subList(1, vals.size - 1).average()
        Log.i(TAG, "weather: ${vals.size} sensors PM2.5 ${vals.joinToString { "%.1f".format(it) }} -> trimmed mean %.1f".format(m))
        return m
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
        val hours = listOf(3, 6).mapNotNull { d ->
            val t = hourNow.plusHours(d.toLong())
            times.indexOf(t).takeIf { it >= 0 }?.let { slot(it, "%02d:00".format(t.hour)) }
        }
        val tomorrowDate = nowTime.toLocalDate().plusDays(1)
        val tomorrow = listOf(9 to "утро", 14 to "день", 19 to "вечер").mapNotNull { (hr, label) ->
            times.indexOf(tomorrowDate.atTime(hr, 0)).takeIf { it >= 0 }?.let { slot(it, label) }
        }
        val days = runCatching { days(o["daily"]!!.jsonObject, tomorrowDate) }
            .onFailure { Log.w(TAG, "weather: daily: $it") }.getOrDefault(emptyList())
        return Forecast(
            now, hours, tomorrow, place, System.currentTimeMillis(),
            days = days,
            windMs = cur["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull,
            gustMs = cur["wind_gusts_10m"]?.jsonPrimitive?.doubleOrNull,
            windFrom = cur["wind_direction_10m"]?.jsonPrimitive?.doubleOrNull?.roundToInt(),
        )
    }

    /** The [DAYS] days after [tomorrow], from Open-Meteo `daily`. */
    private fun days(d: JsonObject, tomorrow: java.time.LocalDate): List<DaySlot> {
        val dates = d["time"]!!.jsonArray.map { java.time.LocalDate.parse(it.jsonPrimitive.content) }
        fun ints(k: String) = d[k]!!.jsonArray.map { it.jsonPrimitive.intOrNull }
        fun dbls(k: String) = d[k]!!.jsonArray.map { it.jsonPrimitive.doubleOrNull }
        val code = ints("weather_code"); val max = dbls("temperature_2m_max"); val min = dbls("temperature_2m_min")
        val cloud = dbls("cloud_cover_mean"); val prob = ints("precipitation_probability_max"); val mm = dbls("precipitation_sum")
        return dates.indices.filter { dates[it] > tomorrow }.take(DAYS).mapNotNull { i ->
            val hi = max[i] ?: return@mapNotNull null
            val lo = min[i] ?: return@mapNotNull null
            DaySlot(
                WEEKDAYS[dates[i].dayOfWeek.value - 1], code[i] ?: 0, hi.roundToInt(), lo.roundToInt(),
                (cloud[i] ?: 0.0).roundToInt(), prob[i], mm[i] ?: 0.0,
            )
        }
    }

    companion object {
        const val TAG = "denopub"
        /** Days shown after tomorrow (owner 2026-11-22: "ещё 3–4 дня"). */
        const val DAYS = 4
        private val WEEKDAYS = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
        // Belgrade, Palilula (owner's default, 2026-11-15). Rounded to ~1 km on purpose: the repo is public,
        // and the forecast grid is coarser than that anyway.
        const val HOME_LAT = 44.82
        const val HOME_LON = 20.49

        fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dx = (lon1 - lon2) * 111.32 * cos(Math.toRadians((lat1 + lat2) / 2))
            val dy = (lat1 - lat2) * 110.57
            return sqrt(dx * dx + dy * dy)
        }

        /**
         * Low-cost laser sensors count water-swollen particles as dust when the air is humid. κ-Köhler
         * correction (Di Antonio et al. 2018, for SDS011): PM / (1 + (κ/1.65) / (100/RH − 1)), κ = 0.62;
         * RH capped at 95 % (the formula explodes near saturation). No humidity known → reading as is.
         */
        fun humidityCorrected(pm: Double, rh: Double?): Double {
            if (rh == null) return pm
            val h = rh.coerceIn(0.0, 95.0)
            val out = pm / (1 + (0.62 / 1.65) / (100 / h - 1))
            Log.i(TAG, "weather: PM2.5 %.1f at RH %.0f%% -> %.1f".format(pm, rh, out))
            return out
        }

        /** US AQI from PM2.5 (EPA breakpoints, 2024 revision). */
        fun usAqi(pm: Double): Int {
            val c = (pm * 10).toInt() / 10.0
            val bp = listOf(
                Triple(0.0, 9.0, 0 to 50), Triple(9.1, 35.4, 51 to 100), Triple(35.5, 55.4, 101 to 150),
                Triple(55.5, 125.4, 151 to 200), Triple(125.5, 225.4, 201 to 300), Triple(225.5, 325.4, 301 to 500),
            )
            val (lo, hi, a) = bp.firstOrNull { c <= it.second } ?: return 500
            return ((a.second - a.first) / (hi - lo) * (c - lo) + a.first).roundToInt()
        }

        /** Arrow showing where the wind blows TO (meteorological direction is where it comes FROM). */
        fun windArrow(from: Int): String =
            listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")[(((from + 180) % 360 + 22) / 45) % 8]

        /** US AQI band → short Russian word and the usual colour (ARGB). */
        fun aqiLevel(aqi: Int): Pair<String, Long> = when {
            aqi <= 50 -> "хорошо" to 0xFF00E400
            aqi <= 100 -> "умеренно" to 0xFFFFFF00
            aqi <= 150 -> "вредно чувствит." to 0xFFFF7E00
            aqi <= 200 -> "вредно" to 0xFFFF0000
            aqi <= 300 -> "очень вредно" to 0xFF8F3F97
            else -> "опасно" to 0xFF7E0023
        }

        /** Emoji for a later day: like a daytime slot. */
        fun icon(d: DaySlot): String = icon(WeatherSlot(d.label, d.code, d.max, d.cloud, d.precipProb, d.precipMm, day = true))

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
