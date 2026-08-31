package com.spotnaut

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Url
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// --- 1. Модели за данни ---

enum class AppLanguage { BG, EN }

data class Center(val lat: Double, val lon: Double)

data class Element(
    val id: Long,
    val lat: Double?,
    val lon: Double?,
    val center: Center?,
    val tags: Map<String, String>?
) {
    val actualLat: Double? get() = lat ?: center?.lat
    val actualLon: Double? get() = lon ?: center?.lon

    fun belongsToCategory(category: PoiCategory): Boolean {
        return tags?.get(category.osmKey) == category.osmValue
    }

    fun getLocalizedTitle(category: PoiCategory, lang: AppLanguage): String {
        val nameBg = tags?.get("name:bg")
        val nameEn = tags?.get("name:en")
        val nameDefault = tags?.get("name")

        val name = when (lang) {
            AppLanguage.BG -> nameBg ?: nameDefault
            AppLanguage.EN -> nameEn ?: nameDefault
        }
        return name ?: "${category.icon} ${category.label(lang)}"
    }
}

data class OverpassResponse(val elements: List<Element>)

data class CachedAreaResult(
    val center: GeoPoint,
    val radiusKm: Float,
    val elements: List<Element>
)

data class OsrmResponse(val routes: List<OsrmRoute>?)
data class OsrmRoute(
    val geometry: OsrmGeometry?,
    val legs: List<OsrmLeg>?
)
data class OsrmGeometry(
    val coordinates: List<List<Double>>?
)
data class OsrmLeg(val steps: List<OsrmStep>?)
data class OsrmStep(
    val distance: Double,
    val name: String?,
    val maneuver: OsrmManeuver?
)
data class OsrmManeuver(
    val type: String?,
    val modifier: String?,
    val location: List<Double>?
)

data class NavigationData(
    val points: List<GeoPoint>,
    val steps: List<OsrmStep>
)

enum class MainCategory(
    val labelBg: String,
    val labelEn: String,
    val icon: String
) {
    WATER_HYGIENE("Вода & Хигиена", "Water & Hygiene", "💧"),
    LEISURE("Отдих & Спорт", "Leisure & Sport", "🌳"),
    TRANSPORT("Транспорт", "Transport", "🚲"),
    ECO("Еко & Рециклиране", "Eco & Recycling", "♻️"),
    CULTURE("Култура & Град", "Culture & City", "🎨");

    fun label(lang: AppLanguage): String = if (lang == AppLanguage.BG) labelBg else labelEn
}

enum class PoiCategory(
    val mainCategory: MainCategory,
    val labelBg: String,
    val labelEn: String,
    val icon: String,
    val osmKey: String,
    val osmValue: String,
    val colorHex: String
) {
    FOUNTAINS(MainCategory.WATER_HYGIENE, "Чешми", "Fountains", "🚰", "amenity", "drinking_water", "#0288D1"),
    TOILETS(MainCategory.WATER_HYGIENE, "Тоалетни", "Toilets", "🚻", "amenity", "toilets", "#7B1FA2"),
    SPRINGS(MainCategory.WATER_HYGIENE, "Извори", "Springs", "🏞️", "natural", "spring", "#00ACC1"),

    BENCHES(MainCategory.LEISURE, "Пейки", "Benches", "🪑", "amenity", "bench", "#8D6E63"),
    PLAYGROUNDS(MainCategory.LEISURE, "Площадки", "Playgrounds", "🛝", "leisure", "playground", "#E91E63"),
    FITNESS(MainCategory.LEISURE, "Външен фитнес", "Outdoor Gym", "🏋️", "leisure", "fitness_station", "#4CAF50"),
    DOG_PARKS(MainCategory.LEISURE, "Кучета", "Dog Parks", "🐕", "leisure", "dog_park", "#388E3C"),
    PICNIC(MainCategory.LEISURE, "Пикник", "Picnic Areas", "🧺", "leisure", "picnic_site", "#FF9800"),
    VIEWPOINTS(MainCategory.LEISURE, "Гледки", "Viewpoints", "🌅", "tourism", "viewpoint", "#9C27B0"),

    EV_CHARGING(MainCategory.TRANSPORT, "EV Зарядни", "EV Chargers", "⚡", "amenity", "charging_station", "#FBC02D"),
    BIKE_PARKING(MainCategory.TRANSPORT, "Велостойки", "Bike Parking", "🚲", "amenity", "bicycle_parking", "#009688"),
    BIKE_RENTAL(MainCategory.TRANSPORT, "Колела под наем", "Bike Rental", "🚴", "amenity", "bicycle_rental", "#00BCD4"),
    BIKE_REPAIR(MainCategory.TRANSPORT, "Велоремонт", "Bike Repair", "🔧", "amenity", "bike_repair_station", "#607D8B"),

    RECYCLING(MainCategory.ECO, "Рециклиране", "Recycling", "♻️", "amenity", "recycling", "#00796B"),

    ART(MainCategory.CULTURE, "Стрийт Арт", "Street Art", "🎨", "tourism", "artwork", "#F57C00"),
    BOOKCASE(MainCategory.CULTURE, "Книги", "Bookcases", "📚", "amenity", "public_bookcase", "#8D6E63"),
    PARCEL_LOCKER(MainCategory.CULTURE, "Шкафчета", "Parcel Lockers", "📦", "amenity", "parcel_locker", "#FF5722"),
    MONUMENTS(MainCategory.CULTURE, "Паметници", "Monuments", "🗿", "historic", "monument", "#78909C");

    fun label(lang: AppLanguage): String = if (lang == AppLanguage.BG) labelBg else labelEn
}

val OVERPASS_SERVERS = listOf(
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.osm.ch/api/interpreter",
    "https://overpass.nchc.org.tw/api/interpreter"
)

interface OverpassApi {
    @FormUrlEncoded
    @POST
    suspend fun getNodes(@Url url: String, @Field("data") query: String): OverpassResponse

    companion object {
        fun create(): OverpassApi {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "SpotNaut/1.0 (Android)")
                        .build()
                    chain.proceed(request)
                }
                .build()

            return Retrofit.Builder()
                .baseUrl("https://overpass-api.de/")
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(OverpassApi::class.java)
        }
    }
}

// --- 2. Automotive Navigation Vector Arrow UI ---

@Composable
fun AutomotiveManeuverIcon(
    step: OsrmStep?,
    modifier: Modifier = Modifier.size(52.dp),
    arrowColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    secondaryColor: Color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.35f)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val strokeW = w * 0.12f
        val stroke = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val secondaryStroke = Stroke(width = strokeW * 0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)

        val maneuver = step?.maneuver
        val type = maneuver?.type ?: "straight"
        val mod = maneuver?.modifier ?: "straight"

        when {
            type == "arrive" -> {
                // Иконка Пристигане (Финална цел)
                val path = Path().apply {
                    moveTo(w * 0.5f, h * 0.8f)
                    lineTo(w * 0.5f, h * 0.25f)
                }
                drawPath(path, arrowColor, style = stroke)
                drawCircle(color = arrowColor, radius = w * 0.18f, center = Offset(w * 0.5f, h * 0.25f))
            }
            type == "roundabout" || type == "rotary" -> {
                // Кръгово движение
                drawArc(
                    color = arrowColor,
                    startAngle = 40f,
                    sweepAngle = 280f,
                    useCenter = false,
                    topLeft = Offset(w * 0.2f, h * 0.25f),
                    size = Size(w * 0.6f, h * 0.6f),
                    style = stroke
                )
                val arrowHead = Path().apply {
                    moveTo(w * 0.72f, h * 0.18f)
                    lineTo(w * 0.85f, h * 0.32f)
                    lineTo(w * 0.65f, h * 0.38f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
            mod == "uturn" -> {
                // Обратен завой (U-Turn)
                val path = Path().apply {
                    moveTo(w * 0.7f, h * 0.85f)
                    lineTo(w * 0.7f, h * 0.45f)
                    cubicTo(w * 0.7f, h * 0.15f, w * 0.3f, h * 0.15f, w * 0.3f, h * 0.45f)
                    lineTo(w * 0.3f, h * 0.82f)
                }
                drawPath(path, arrowColor, style = stroke)
                val arrowHead = Path().apply {
                    moveTo(w * 0.18f, h * 0.68f)
                    lineTo(w * 0.30f, h * 0.85f)
                    lineTo(w * 0.42f, h * 0.68f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
            mod == "right" -> {
                // Завой надясно 90°
                val bgPath = Path().apply {
                    moveTo(w * 0.3f, h * 0.85f)
                    lineTo(w * 0.3f, h * 0.2f)
                }
                drawPath(bgPath, secondaryColor, style = secondaryStroke)

                val mainPath = Path().apply {
                    moveTo(w * 0.3f, h * 0.85f)
                    lineTo(w * 0.3f, h * 0.45f)
                    lineTo(w * 0.75f, h * 0.45f)
                }
                drawPath(mainPath, arrowColor, style = stroke)
                val arrowHead = Path().apply {
                    moveTo(w * 0.60f, h * 0.32f)
                    lineTo(w * 0.78f, h * 0.45f)
                    lineTo(w * 0.60f, h * 0.58f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
            mod == "left" -> {
                // Завой наляво 90°
                val bgPath = Path().apply {
                    moveTo(w * 0.7f, h * 0.85f)
                    lineTo(w * 0.7f, h * 0.2f)
                }
                drawPath(bgPath, secondaryColor, style = secondaryStroke)

                val mainPath = Path().apply {
                    moveTo(w * 0.7f, h * 0.85f)
                    lineTo(w * 0.7f, h * 0.45f)
                    lineTo(w * 0.25f, h * 0.45f)
                }
                drawPath(mainPath, arrowColor, style = stroke)
                val arrowHead = Path().apply {
                    moveTo(w * 0.40f, h * 0.32f)
                    lineTo(w * 0.22f, h * 0.45f)
                    lineTo(w * 0.40f, h * 0.58f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
            mod == "slight right" -> {
                // Леко надясно (45°)
                val mainPath = Path().apply {
                    moveTo(w * 0.35f, h * 0.85f)
                    lineTo(w * 0.35f, h * 0.55f)
                    lineTo(w * 0.72f, h * 0.25f)
                }
                drawPath(mainPath, arrowColor, style = stroke)
                val arrowHead = Path().apply {
                    moveTo(w * 0.52f, h * 0.22f)
                    lineTo(w * 0.75f, h * 0.22f)
                    lineTo(w * 0.75f, h * 0.45f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
            mod == "slight left" -> {
                // Леко наляво (45°)
                val mainPath = Path().apply {
                    moveTo(w * 0.65f, h * 0.85f)
                    lineTo(w * 0.65f, h * 0.55f)
                    lineTo(w * 0.28f, h * 0.25f)
                }
                drawPath(mainPath, arrowColor, style = stroke)
                val arrowHead = Path().apply {
                    moveTo(w * 0.48f, h * 0.22f)
                    lineTo(w * 0.25f, h * 0.22f)
                    lineTo(w * 0.25f, h * 0.45f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
            mod == "sharp right" -> {
                // Остър десен завой
                val mainPath = Path().apply {
                    moveTo(w * 0.35f, h * 0.85f)
                    lineTo(w * 0.35f, h * 0.35f)
                    lineTo(w * 0.75f, h * 0.7f)
                }
                drawPath(mainPath, arrowColor, style = stroke)
                val arrowHead = Path().apply {
                    moveTo(w * 0.75f, h * 0.48f)
                    lineTo(w * 0.78f, h * 0.72f)
                    lineTo(w * 0.54f, h * 0.72f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
            mod == "sharp left" -> {
                // Остър ляв завой
                val mainPath = Path().apply {
                    moveTo(w * 0.65f, h * 0.85f)
                    lineTo(w * 0.65f, h * 0.35f)
                    lineTo(w * 0.25f, h * 0.7f)
                }
                drawPath(mainPath, arrowColor, style = stroke)
                val arrowHead = Path().apply {
                    moveTo(w * 0.25f, h * 0.48f)
                    lineTo(w * 0.22f, h * 0.72f)
                    lineTo(w * 0.46f, h * 0.72f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
            type == "fork" -> {
                // Разклонение (Fork)
                val leftFork = Path().apply {
                    moveTo(w * 0.5f, h * 0.85f)
                    lineTo(w * 0.5f, h * 0.55f)
                    lineTo(w * 0.25f, h * 0.25f)
                }
                val rightFork = Path().apply {
                    moveTo(w * 0.5f, h * 0.55f)
                    lineTo(w * 0.75f, h * 0.25f)
                }

                val isRight = mod.contains("right")
                drawPath(leftFork, if (isRight) secondaryColor else arrowColor, style = stroke)
                drawPath(rightFork, if (isRight) arrowColor else secondaryColor, style = stroke)
            }
            else -> {
                // Продължете направо (Straight)
                val mainPath = Path().apply {
                    moveTo(w * 0.5f, h * 0.85f)
                    lineTo(w * 0.5f, h * 0.22f)
                }
                drawPath(mainPath, arrowColor, style = stroke)
                val arrowHead = Path().apply {
                    moveTo(w * 0.32f, h * 0.38f)
                    lineTo(w * 0.50f, h * 0.18f)
                    lineTo(w * 0.68f, h * 0.38f)
                }
                drawPath(arrowHead, arrowColor, style = stroke)
            }
        }
    }
}

// --- 3. Помощни функции ---

suspend fun fetchStreetRouteDetails(start: GeoPoint, target: GeoPoint): NavigationData = withContext(Dispatchers.IO) {
    try {
        val url = "https://router.project-osrm.org/route/v1/foot/" +
                "${start.longitude},${start.latitude};${target.longitude},${target.latitude}" +
                "?overview=full&geometries=geojson&steps=true"

        val client = OkHttpClient()
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val json = response.body?.string() ?: return@withContext NavigationData(listOf(start, target), emptyList())

        val osrmResponse = Gson().fromJson(json, OsrmResponse::class.java)
        val route = osrmResponse.routes?.firstOrNull()
        val coords = route?.geometry?.coordinates?.map { GeoPoint(it[1], it[0]) } ?: listOf(start, target)
        val steps = route?.legs?.firstOrNull()?.steps ?: emptyList()

        NavigationData(coords, steps)
    } catch (e: Exception) {
        Log.e("SpotNaut", "Грешка при извличане на маршрут", e)
        NavigationData(listOf(start, target), emptyList())
    }
}

fun sliceRouteFromCurrentLocation(userPoint: GeoPoint, fullPoints: List<GeoPoint>): List<GeoPoint> {
    if (fullPoints.isEmpty()) return emptyList()

    var minDistance = Double.MAX_VALUE
    var closestIndex = 0

    for (i in fullPoints.indices) {
        val dist = userPoint.distanceToAsDouble(fullPoints[i])
        if (dist < minDistance) {
            minDistance = dist
            closestIndex = i
        }
    }

    val remaining = fullPoints.subList(closestIndex, fullPoints.size).toMutableList()
    remaining.add(0, userPoint)
    return remaining
}

fun isUserOffRoute(userPoint: GeoPoint, fullPoints: List<GeoPoint>, thresholdMeters: Double = 35.0): Boolean {
    if (fullPoints.isEmpty()) return false
    val minDistance = fullPoints.minOf { userPoint.distanceToAsDouble(it) }
    return minDistance > thresholdMeters
}

fun getManeuverText(step: OsrmStep?, lang: AppLanguage): String {
    if (step == null) {
        return if (lang == AppLanguage.BG) "Следвайте маршрута" else "Follow the route"
    }

    val maneuver = step.maneuver
    val modifier = maneuver?.modifier
    val type = maneuver?.type
    val streetName = if (!step.name.isNullOrBlank()) step.name else ""

    val text = when {
        type == "arrive" -> if (lang == AppLanguage.BG) "Пристигане на целта" else "Arriving at destination"
        type == "roundabout" || type == "rotary" -> if (lang == AppLanguage.BG) "На кръговото излезте" else "At roundabout take exit"
        modifier == "slight right" -> if (lang == AppLanguage.BG) "Леко надясно" else "Slight right"
        modifier == "right" -> if (lang == AppLanguage.BG) "Завийте надясно" else "Turn right"
        modifier == "sharp right" -> if (lang == AppLanguage.BG) "Остър десен завой" else "Sharp right"
        modifier == "slight left" -> if (lang == AppLanguage.BG) "Леко наляво" else "Slight left"
        modifier == "left" -> if (lang == AppLanguage.BG) "Завийте наляво" else "Turn left"
        modifier == "sharp left" -> if (lang == AppLanguage.BG) "Остър ляв завой" else "Sharp left"
        modifier == "uturn" -> if (lang == AppLanguage.BG) "Обратен завой" else "U-turn"
        else -> if (lang == AppLanguage.BG) "Продължете напред" else "Continue straight"
    }

    return if (streetName.isBlank()) text else "$text по $streetName"
}

fun updateZoomBasedOnSpeed(mapView: MapView, speedMps: Float) {
    val speedKmH = if (speedMps <= 0f) 0f else speedMps * 3.6f

    // 0 км/ч (покой на бюро / изчакване) -> Максимален Zoom 18.5
    val targetZoom = when {
        speedKmH < 5f -> 18.5
        speedKmH < 25f -> 17.0
        speedKmH < 50f -> 15.5
        else -> 14.0
    }

    if (abs(mapView.zoomLevelDouble - targetZoom) > 0.2) {
        mapView.controller.zoomTo(targetZoom, 400L)
    }
}

private fun createEmojiMarkerIcon(
    context: Context,
    emoji: String,
    backgroundColorHex: String,
    isSelected: Boolean = false
): Drawable {
    val density = context.resources.displayMetrics.density
    val sizePx = ((if (isSelected) 46 else 40) * density).toInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(bitmap)

    val baseColor = AndroidColor.parseColor(backgroundColorHex)
    val colorToUse = if (isSelected) {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(baseColor, hsv)
        hsv[2] *= 0.45f
        AndroidColor.HSVToColor(hsv)
    } else {
        baseColor
    }

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorToUse
        style = Paint.Style.FILL
    }

    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = if (isSelected) AndroidColor.YELLOW else AndroidColor.WHITE
        style = Paint.Style.STROKE
        strokeWidth = (if (isSelected) 3.5f else 2.5f) * density
    }

    val radius = (sizePx / 2f) - (3 * density)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, radius, bgPaint)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, radius, strokePaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = (if (isSelected) 22f else 19f) * density
        textAlign = Paint.Align.CENTER
    }
    val textY = (sizePx / 2f) - ((textPaint.descent() + textPaint.ascent()) / 2f)
    canvas.drawText(emoji, sizePx / 2f, textY, textPaint)

    return BitmapDrawable(context.resources, bitmap)
}

private fun formatSpotDetails(category: PoiCategory, tags: Map<String, String>?, lang: AppLanguage): String {
    if (tags.isNullOrEmpty()) {
        return if (lang == AppLanguage.BG) "Няма допълнителни данни" else "No additional details"
    }

    val details = mutableListOf<String>()

    tags["operator"]?.let { details.add(if (lang == AppLanguage.BG) "Стопанин: $it" else "Operator: $it") }
    tags["opening_hours"]?.let { details.add(if (lang == AppLanguage.BG) "Работно време: $it" else "Opening hours: $it") }
    tags["fee"]?.let {
        val feeText = if (it == "no") (if (lang == AppLanguage.BG) "Безплатно" else "Free")
        else (if (lang == AppLanguage.BG) "Платено ($it)" else "Fee ($it)")
        details.add(if (lang == AppLanguage.BG) "Такса: $feeText" else "Fee: $feeText")
    }

    val desc = when (lang) {
        AppLanguage.BG -> tags["description:bg"] ?: tags["description"]
        AppLanguage.EN -> tags["description:en"] ?: tags["description"]
    }
    desc?.let { details.add(if (lang == AppLanguage.BG) "Описание: $it" else "Description: $it") }

    return details.ifEmpty {
        listOf(if (lang == AppLanguage.BG) "Обект от OSM категория '${category.labelBg}'" else "Object from OSM category '${category.labelEn}'")
    }.joinToString("\n")
}

private fun buildUnifiedOverpassQuery(lat: Double, lon: Double, radiusMeters: Int): String {
    val subQueries = PoiCategory.entries.joinToString("\n") { cat ->
        """
        node["${cat.osmKey}"="${cat.osmValue}"](around:$radiusMeters,$lat,$lon);
        way["${cat.osmKey}"="${cat.osmValue}"](around:$radiusMeters,$lat,$lon);
        relation["${cat.osmKey}"="${cat.osmValue}"](around:$radiusMeters,$lat,$lon);
        """.trimIndent()
    }

    return """
        [out:json][timeout:10];
        (
          $subQueries
        );
        out center;
    """.trimIndent()
}

private fun openGoogleMaps(context: Context, target: GeoPoint, label: String) {
    val uri = Uri.parse("google.navigation:q=${target.latitude},${target.longitude}")
    val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    } else {
        val genericUri = Uri.parse("geo:${target.latitude},${target.longitude}?q=${target.latitude},${target.longitude}($label)")
        context.startActivity(Intent(Intent.ACTION_VIEW, genericUri))
    }
}

// --- 4. Главен Activity ---

class MainActivity : ComponentActivity() {
    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        Configuration.getInstance().userAgentValue = packageName

        setContent {
            MainScreen()
        }
    }
}

// --- 5. UI Компоненти ---

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("SpotNautPrefs", Context.MODE_PRIVATE) }

    var isDarkMode by remember { mutableStateOf(prefs.getBoolean("is_dark_mode", false)) }

    MaterialTheme(
        colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme()
    ) {
        val coroutineScope = rememberCoroutineScope()
        val api = remember { OverpassApi.create() }

        val cacheList = remember { mutableStateListOf<CachedAreaResult>() }

        var currentLanguage by remember { mutableStateOf(AppLanguage.BG) }
        var selectedMainCategory by remember { mutableStateOf(MainCategory.WATER_HYGIENE) }
        var selectedPoiCategory by remember { mutableStateOf(PoiCategory.FOUNTAINS) }

        var radiusKm by remember { mutableStateOf(2.0f) }

        var isLoading by remember { mutableStateOf(false) }
        var activeJob by remember { mutableStateOf<Job?>(null) }

        var isInitialSettling by remember { mutableStateOf(true) }

        var showMenu by remember { mutableStateOf(false) }
        var showAboutDialog by remember { mutableStateOf(false) }

        var searchCenterGeoPoint by remember { mutableStateOf(GeoPoint(42.6977, 23.3219)) }

        var currentlySelectedMarker by remember { mutableStateOf<Marker?>(null) }
        var selectedTargetGeoPoint by remember { mutableStateOf<GeoPoint?>(null) }
        var selectedTargetTitle by remember { mutableStateOf<String?>(null) }
        var selectedTargetDetails by remember { mutableStateOf<String?>(null) }

        var isGuidanceActive by remember { mutableStateOf(false) }

        var rawRoutePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
        var displayRoutePoints by remember { mutableStateOf<List<GeoPoint>>(emptyList()) }
        var navigationSteps by remember { mutableStateOf<List<OsrmStep>>(emptyList()) }

        var currentLocation by remember { mutableStateOf<Location?>(null) }
        var lastRouteFetchTime by remember { mutableLongStateOf(0L) }

        fun updateSearchCenterIfMoved(newPoint: GeoPoint) {
            if (searchCenterGeoPoint.distanceToAsDouble(newPoint) > 50.0) {
                searchCenterGeoPoint = newPoint
            }
        }

        val currentSubCategories = remember(selectedMainCategory) {
            PoiCategory.entries.filter { it.mainCategory == selectedMainCategory }
        }

        fun deselectCurrentMarker() {
            currentlySelectedMarker?.let { prevMarker ->
                val prevCat = (prevMarker.relatedObject as? PoiCategory)
                if (prevCat != null) {
                    prevMarker.icon = createEmojiMarkerIcon(context, prevCat.icon, prevCat.colorHex, isSelected = false)
                }
                prevMarker.closeInfoWindow()
            }
            currentlySelectedMarker = null
            selectedTargetGeoPoint = null
            selectedTargetTitle = null
            selectedTargetDetails = null
        }

        val mapEventsOverlay = remember {
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                    if (!isGuidanceActive) {
                        deselectCurrentMarker()
                    }
                    return false
                }

                override fun longPressHelper(p: GeoPoint): Boolean {
                    searchCenterGeoPoint = p
                    return true
                }
            })
        }

        val mapView = remember {
            MapView(context).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(15.0)
                controller.setCenter(searchCenterGeoPoint)
            }
        }

        val navigationPolyline = remember {
            Polyline().apply {
                outlinePaint.color = AndroidColor.parseColor("#0288D1")
                outlinePaint.strokeWidth = 16f
            }
        }

        DisposableEffect(Unit) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    currentLocation = location
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            try {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 800L, 0.5f, listener)
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 1.0f, listener)
            } catch (e: SecurityException) {
                Log.e("SpotNaut", "Location permission missing", e)
            }

            onDispose { locationManager.removeUpdates(listener) }
        }

        // Компас
        val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
        val rotationSensor = remember { sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }

        DisposableEffect(sensorManager, rotationSensor) {
            if (rotationSensor == null) {
                onDispose { }
            } else {
                var lastAzimuth = 0f
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        if (event?.sensor?.type == Sensor.TYPE_ROTATION_VECTOR) {
                            val rotationMatrix = FloatArray(9)
                            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

                            val orientation = FloatArray(3)
                            SensorManager.getOrientation(rotationMatrix, orientation)

                            var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                            if (azimuth < 0) azimuth += 360f

                            if (abs(azimuth - lastAzimuth) > 1.5f) {
                                lastAzimuth = azimuth
                                mapView.post {
                                    mapView.mapOrientation = -azimuth
                                    mapView.invalidate()
                                }
                            }
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }

                sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
                onDispose { sensorManager.unregisterListener(listener) }
            }
        }

        LaunchedEffect(Unit) {
            mapView.overlays.add(mapEventsOverlay)
        }

        LaunchedEffect(isDarkMode) {
            if (isDarkMode) {
                val inverseMatrix = ColorMatrix(floatArrayOf(
                    -1.0f, 0.0f, 0.0f, 0.0f, 255.0f,
                    0.0f, -1.0f, 0.0f, 0.0f, 255.0f,
                    0.0f, 0.0f, -1.0f, 0.0f, 255.0f,
                    0.0f, 0.0f, 0.0f, 1.0f, 0.0f
                ))
                mapView.overlayManager.tilesOverlay.setColorFilter(ColorMatrixColorFilter(inverseMatrix))
            } else {
                mapView.overlayManager.tilesOverlay.setColorFilter(null)
            }
            mapView.invalidate()
        }

        val myLocationOverlay = remember {
            MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
                enableMyLocation()
            }
        }

        // --- МОМЕНТАЛНО ИМАЖИРАНЕ ПРИ СТАРТ НА НАВИГАЦИЯ ---
        LaunchedEffect(isGuidanceActive) {
            if (isGuidanceActive) {
                // Веднага при пускане на навигацията форсираме Zoom 18.5 (покой / стартова позиция на бюро)
                mapView.controller.zoomTo(18.5, 500L)

                val userPoint = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
                    ?: myLocationOverlay.myLocation
                    ?: getUserLocation(context)?.let { GeoPoint(it.latitude, it.longitude) }

                userPoint?.let { mapView.controller.animateTo(it) }
            }
        }

        // --- Двигател за Навигация в реално време ---
        LaunchedEffect(currentLocation, isGuidanceActive) {
            val loc = currentLocation
            if (isGuidanceActive && loc != null) {
                val userPoint = GeoPoint(loc.latitude, loc.longitude)

                mapView.controller.animateTo(userPoint)
                val speedMps = if (loc.hasSpeed()) loc.speed else 0f
                updateZoomBasedOnSpeed(mapView, speedMps)

                val now = System.currentTimeMillis()
                if (selectedTargetGeoPoint != null && (rawRoutePoints.isEmpty() || isUserOffRoute(userPoint, rawRoutePoints))) {
                    if (now - lastRouteFetchTime > 4000) {
                        lastRouteFetchTime = now
                        val navData = fetchStreetRouteDetails(userPoint, selectedTargetGeoPoint!!)
                        rawRoutePoints = navData.points
                        navigationSteps = navData.steps
                    }
                }

                if (rawRoutePoints.isNotEmpty()) {
                    displayRoutePoints = sliceRouteFromCurrentLocation(userPoint, rawRoutePoints)
                }
            }
        }

        // Зареждане на маршрута при първоначално активиране
        LaunchedEffect(isGuidanceActive, selectedTargetGeoPoint) {
            if (isGuidanceActive && selectedTargetGeoPoint != null) {
                val userPoint = currentLocation?.let { GeoPoint(it.latitude, it.longitude) }
                    ?: myLocationOverlay.myLocation
                    ?: getUserLocation(context)?.let { GeoPoint(it.latitude, it.longitude) }

                if (userPoint != null) {
                    val navData = fetchStreetRouteDetails(userPoint, selectedTargetGeoPoint!!)
                    rawRoutePoints = navData.points
                    displayRoutePoints = sliceRouteFromCurrentLocation(userPoint, rawRoutePoints)
                    navigationSteps = navData.steps
                }
            } else {
                rawRoutePoints = emptyList()
                displayRoutePoints = emptyList()
                navigationSteps = emptyList()
            }
        }

        LaunchedEffect(displayRoutePoints) {
            if (displayRoutePoints.isNotEmpty()) {
                navigationPolyline.setPoints(displayRoutePoints)
                if (!mapView.overlays.contains(navigationPolyline)) {
                    mapView.overlays.add(navigationPolyline)
                }
            } else {
                mapView.overlays.remove(navigationPolyline)
            }
            mapView.invalidate()
        }

        LaunchedEffect(myLocationOverlay) {
            myLocationOverlay.runOnFirstFix {
                val loc = myLocationOverlay.myLocation
                if (loc != null) {
                    val point = GeoPoint(loc.latitude, loc.longitude)
                    (context as? Activity)?.runOnUiThread {
                        updateSearchCenterIfMoved(point)
                        mapView.controller.animateTo(point)
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            delay(3000)
            isInitialSettling = false
        }

        fun renderCategoryElements(allElements: List<Element>, category: PoiCategory, lang: AppLanguage) {
            val filtered = allElements.filter { it.belongsToCategory(category) }
            val normalPoiIcon = createEmojiMarkerIcon(context, category.icon, category.colorHex, isSelected = false)
            val selectedPoiIcon = createEmojiMarkerIcon(context, category.icon, category.colorHex, isSelected = true)

            filtered.forEach { element ->
                val lat = element.actualLat
                val lon = element.actualLon
                if (lat != null && lon != null) {
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(lat, lon)
                        title = element.getLocalizedTitle(category, lang)
                        snippet = formatSpotDetails(category, element.tags, lang)
                        icon = normalPoiIcon
                        relatedObject = category
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    }

                    marker.setOnMarkerClickListener { m, _ ->
                        currentlySelectedMarker?.let { prevMarker ->
                            val prevCat = (prevMarker.relatedObject as? PoiCategory) ?: category
                            prevMarker.icon = createEmojiMarkerIcon(context, prevCat.icon, prevCat.colorHex, isSelected = false)
                        }

                        m.icon = selectedPoiIcon
                        currentlySelectedMarker = m
                        m.showInfoWindow()

                        selectedTargetGeoPoint = m.position
                        selectedTargetTitle = m.title
                        selectedTargetDetails = m.snippet
                        true
                    }

                    mapView.overlays.add(marker)
                }
            }
            mapView.invalidate()

            if (filtered.isEmpty()) {
                val msg = if (lang == AppLanguage.BG) "Няма намерени '${category.labelBg}' в този радиус"
                else "No '${category.labelEn}' found in this radius"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }

        fun loadOrFilterData(category: PoiCategory, center: GeoPoint, radius: Float, lang: AppLanguage, forceReload: Boolean = false) {
            activeJob?.cancel()
            deselectCurrentMarker()
            isGuidanceActive = false

            mapView.overlays.clear()
            mapView.overlays.add(mapEventsOverlay)
            mapView.overlays.add(myLocationOverlay)

            val radiusMeters = (radius * 1000).toInt()
            val centerMarker = Marker(mapView).apply {
                position = center
                title = if (lang == AppLanguage.BG) "Избрана локация" else "Selected location"
                snippet = if (lang == AppLanguage.BG) "Център (радиус ${String.format("%.1f", radius)} км)" else "Center (radius ${String.format("%.1f", radius)} km)"
                icon = createEmojiMarkerIcon(context, "📍", "#D32F2F")
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            }
            mapView.overlays.add(centerMarker)

            val circle = Polygon().apply {
                points = Polygon.pointsAsCircle(center, radiusMeters.toDouble())
                fillPaint.color = AndroidColor.argb(35, 33, 150, 243)
                outlinePaint.color = AndroidColor.argb(120, 33, 150, 243)
                outlinePaint.strokeWidth = 3f
            }
            mapView.overlays.add(circle)
            mapView.invalidate()

            if (!forceReload) {
                val cachedHit = cacheList.firstOrNull { cached ->
                    center.distanceToAsDouble(cached.center) < 500.0 && abs(cached.radiusKm - radius) < 0.2f
                }
                if (cachedHit != null) {
                    renderCategoryElements(cachedHit.elements, category, lang)
                    return
                }
            }

            activeJob = coroutineScope.launch {
                isLoading = true
                try {
                    val query = buildUnifiedOverpassQuery(center.latitude, center.longitude, radiusMeters)
                    var bestResponse: OverpassResponse? = null
                    var lastException: Exception? = null

                    for (serverUrl in OVERPASS_SERVERS) {
                        var attemptSuccess = false
                        for (attempt in 1..5) {
                            try {
                                val res = api.getNodes(serverUrl, query)
                                if (res.elements.isNotEmpty()) {
                                    bestResponse = res
                                    attemptSuccess = true
                                    break
                                } else if (bestResponse == null) {
                                    bestResponse = res
                                    attemptSuccess = true
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                lastException = e
                                delay(1000)
                            }
                        }
                        if (attemptSuccess && bestResponse?.elements?.isNotEmpty() == true) break
                    }

                    val finalResponse = bestResponse ?: throw (lastException ?: Exception("Network failure"))
                    cacheList.add(CachedAreaResult(center, radius, finalResponse.elements))
                    renderCategoryElements(finalResponse.elements, category, lang)

                } catch (e: CancellationException) {
                    // Canceled
                } catch (e: Exception) {
                    Log.e("SpotNaut", "Error", e)
                    Toast.makeText(context, e.localizedMessage, Toast.LENGTH_LONG).show()
                } finally {
                    isLoading = false
                }
            }
        }

        val locationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            if (permissions.values.contains(true)) {
                myLocationOverlay.enableMyLocation()
                getUserLocation(context)?.let { userLocation ->
                    val point = GeoPoint(userLocation.latitude, userLocation.longitude)
                    updateSearchCenterIfMoved(point)
                    mapView.controller.animateTo(point)
                }
            }
        }

        LaunchedEffect(Unit) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }

        LaunchedEffect(selectedPoiCategory, searchCenterGeoPoint, radiusKm, currentLanguage, isInitialSettling) {
            if (isInitialSettling) return@LaunchedEffect
            delay(500)
            loadOrFilterData(selectedPoiCategory, searchCenterGeoPoint, radiusKm, currentLanguage)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { mapView },
                modifier = Modifier.fillMaxSize()
            )

            // --- Automotive Navigation Табела със стрелка ---
            if (isGuidanceActive && selectedTargetGeoPoint != null) {
                val loc = currentLocation ?: myLocationOverlay.lastFix

                val nextStep = navigationSteps.firstOrNull { step ->
                    val stepLoc = step.maneuver?.location
                    if (stepLoc != null && loc != null) {
                        val stepPoint = GeoPoint(stepLoc[1], stepLoc[0])
                        val userPoint = GeoPoint(loc.latitude, loc.longitude)
                        userPoint.distanceToAsDouble(stepPoint) > 10.0
                    } else true
                } ?: navigationSteps.lastOrNull()

                val distToNextStepMeters = remember(loc, nextStep) {
                    val stepLoc = nextStep?.maneuver?.location
                    if (stepLoc != null && loc != null) {
                        val stepPoint = GeoPoint(stepLoc[1], stepLoc[0])
                        val userPoint = GeoPoint(loc.latitude, loc.longitude)
                        userPoint.distanceToAsDouble(stepPoint).toInt()
                    } else 0
                }

                val instructionText = getManeuverText(nextStep, currentLanguage)

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = 12.dp,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            // Canvas Автомобилна Стрелка
                            AutomotiveManeuverIcon(
                                step = nextStep,
                                modifier = Modifier
                                    .size(54.dp)
                                    .padding(end = 12.dp)
                            )

                            Column {
                                val distText = if (distToNextStepMeters >= 1000) {
                                    String.format("%.1f км", distToNextStepMeters / 1000f)
                                } else {
                                    "$distToNextStepMeters м"
                                }

                                Text(
                                    text = if (currentLanguage == AppLanguage.BG) "След $distText" else "In $distText",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 20.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = instructionText,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f),
                                    maxLines = 2
                                )
                            }
                        }

                        Button(
                            onClick = { isGuidanceActive = false },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(if (currentLanguage == AppLanguage.BG) "Край" else "End", fontSize = 13.sp)
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = 8.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        shape = RoundedCornerShape(20.dp),
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    ) {
                        Column(modifier = Modifier.padding(vertical = 6.dp)) {

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LazyRow(
                                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    items(MainCategory.entries) { mainCat ->
                                        FilterChip(
                                            selected = selectedMainCategory == mainCat,
                                            onClick = {
                                                selectedMainCategory = mainCat
                                                val firstSub = PoiCategory.entries.firstOrNull { it.mainCategory == mainCat }
                                                if (firstSub != null) selectedPoiCategory = firstSub
                                            },
                                            label = { Text("${mainCat.icon} ${mainCat.label(currentLanguage)}", fontSize = 13.sp) }
                                        )
                                    }
                                }

                                Box(modifier = Modifier.padding(end = 4.dp)) {
                                    IconButton(onClick = { showMenu = true }) {
                                        Text("⋮", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                    }

                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(if (currentLanguage == AppLanguage.BG) "За приложението" else "About") },
                                            onClick = {
                                                showMenu = false
                                                showAboutDialog = true
                                            }
                                        )
                                        HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text(if (currentLanguage == AppLanguage.BG) "Изход" else "Exit") },
                                            onClick = {
                                                showMenu = false
                                                (context as? Activity)?.finish()
                                            }
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            )

                            LazyRow(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(currentSubCategories) { poi ->
                                    FilterChip(
                                        selected = selectedPoiCategory == poi,
                                        onClick = { selectedPoiCategory = poi },
                                        label = { Text("${poi.icon} ${poi.label(currentLanguage)}", fontSize = 12.sp) }
                                    )
                                }
                            }
                        }
                    }

                    if (isLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            if (selectedTargetGeoPoint != null && !isGuidanceActive) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 82.dp, start = 12.dp, end = 12.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    shadowElevation = 10.dp,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = selectedTargetTitle ?: "",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { deselectCurrentMarker() },
                                modifier = Modifier.size(26.dp)
                            ) {
                                Text("✕", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (!selectedTargetDetails.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = selectedTargetDetails ?: "",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                                maxLines = 3
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    isGuidanceActive = true
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(
                                    text = if (currentLanguage == AppLanguage.BG) "Вградена\nНавигация" else "Built-in\nGuidance",
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    selectedTargetGeoPoint?.let { pt ->
                                        openGoogleMaps(context, pt, selectedTargetTitle ?: "Target")
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(
                                    text = "Google Maps\nWaze",
                                    fontSize = 13.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp, start = 8.dp, end = 8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ) {
                    TextButton(
                        onClick = {
                            currentLanguage = if (currentLanguage == AppLanguage.BG) AppLanguage.EN else AppLanguage.BG
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = if (currentLanguage == AppLanguage.BG) "🇧🇬 BG" else "🇬🇧 EN",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ) {
                    IconButton(
                        onClick = {
                            isDarkMode = !isDarkMode
                            prefs.edit().putBoolean("is_dark_mode", isDarkMode).apply()
                        }
                    ) {
                        Text(text = if (isDarkMode) "🌙" else "☀️", fontSize = 18.sp)
                    }
                }

                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (currentLanguage == AppLanguage.BG) "Радиус: ${String.format("%.1f", radiusKm)} км"
                            else "Radius: ${String.format("%.1f", radiusKm)} km",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Slider(
                            value = radiusKm,
                            onValueChange = { radiusKm = it },
                            valueRange = 1.0f..5.0f,
                            steps = 7,
                            modifier = Modifier.height(22.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ) {
                    IconButton(
                        onClick = {
                            if (!isInitialSettling && !isLoading) {
                                loadOrFilterData(selectedPoiCategory, searchCenterGeoPoint, radiusKm, currentLanguage, forceReload = true)
                            }
                        },
                        enabled = !isLoading
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Text("🔄", fontSize = 16.sp)
                        }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(18.dp),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ) {
                    IconButton(
                        onClick = {
                            val myLoc = myLocationOverlay.myLocation
                            val geoPoint = if (myLoc != null) GeoPoint(myLoc.latitude, myLoc.longitude)
                            else getUserLocation(context)?.let { GeoPoint(it.latitude, it.longitude) }

                            if (geoPoint != null) {
                                searchCenterGeoPoint = geoPoint
                                mapView.controller.animateTo(geoPoint)
                            } else {
                                val msg = if (currentLanguage == AppLanguage.BG) "Търсене на GPS сигнал..." else "Searching for GPS signal..."
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("🎯", fontSize = 18.sp)
                    }
                }
            }

            if (showAboutDialog) {
                val scrollState = rememberScrollState()

                AlertDialog(
                    onDismissRequest = { showAboutDialog = false },
                    title = {
                        Column {
                            Text("SpotNaut", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text("by Ventsislav Negentsov", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    text = {
                        Box(modifier = Modifier.fillMaxWidth().verticalScroll(scrollState)) {
                            Text(
                                text = if (currentLanguage == AppLanguage.BG) {
                                    """
                                    SpotNaut е твоят интерактивен градски помощник.

                                    🌟 Възможности:
                                    • Automotive Navigation с векторни стрелки за завои и кръгови кръстовища.
                                    • Моментално форсиране на Zoom 18.5 при покой/старт на навигация.
                                    • Динамично скъсяване на маршрутната линия.
                                    • Автоматично преизчисляване при отклонение.
                                    • Компас и нощен режим.
                                    """.trimIndent()
                                } else {
                                    """
                                    SpotNaut is your interactive urban companion.

                                    🌟 Features:
                                    • Automotive HUD Navigation with vector maneuver arrows.
                                    • Instant Zoom 18.5 focus upon starting navigation.
                                    • Dynamic route polyline trimming.
                                    • Auto re-routing when off path.
                                    • Compass and Night Mode.
                                    """.trimIndent()
                                },
                                fontSize = 13.sp,
                                lineHeight = 18.sp
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAboutDialog = false }) {
                            Text(if (currentLanguage == AppLanguage.BG) "Затвори" else "Close")
                        }
                    }
                )
            }
        }
    }
}

// --- 6. Помощни функции за локация ---

@SuppressLint("MissingPermission")
private fun getUserLocation(context: Context): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
}