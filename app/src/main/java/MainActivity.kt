package com.oasisbg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
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

// --- 1. Модели за данни & Категории ---

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
                        .header("User-Agent", "OasisUrban-CitySpotMap/3.5 (Android)")
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

// --- 2. Помощни функции ---

private fun createEmojiMarkerIcon(context: Context, emoji: String, backgroundColorHex: String): Drawable {
    val density = context.resources.displayMetrics.density
    val sizePx = (42 * density).toInt()
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor(backgroundColorHex)
        style = Paint.Style.FILL
    }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    val radius = (sizePx / 2f) - (2 * density)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, radius, bgPaint)
    canvas.drawCircle(sizePx / 2f, sizePx / 2f, radius, strokePaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20f * density
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

    tags["operator"]?.let {
        details.add(if (lang == AppLanguage.BG) "Стопанин: $it" else "Operator: $it")
    }
    tags["opening_hours"]?.let {
        details.add(if (lang == AppLanguage.BG) "Работно време: $it" else "Opening hours: $it")
    }
    tags["fee"]?.let {
        val feeText = if (it == "no") {
            if (lang == AppLanguage.BG) "Безплатно" else "Free"
        } else {
            if (lang == AppLanguage.BG) "Платено ($it)" else "Fee ($it)"
        }
        details.add(if (lang == AppLanguage.BG) "Такса: $feeText" else "Fee: $feeText")
    }
    tags["wheelchair"]?.let {
        val wcText = if (it == "yes") {
            if (lang == AppLanguage.BG) "Да" else "Yes"
        } else {
            if (lang == AppLanguage.BG) "Не" else "No"
        }
        details.add(if (lang == AppLanguage.BG) "Достъп за колички: $wcText" else "Wheelchair access: $wcText")
    }

    val desc = when (lang) {
        AppLanguage.BG -> tags["description:bg"] ?: tags["description"]
        AppLanguage.EN -> tags["description:en"] ?: tags["description"]
    }
    desc?.let { details.add(if (lang == AppLanguage.BG) "Описание: $it" else "Description: $it") }

    return if (details.isEmpty()) {
        if (lang == AppLanguage.BG) "Обект от OSM категория '${category.labelBg}'"
        else "Object from OSM category '${category.labelEn}'"
    } else details.joinToString("\n")
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

// --- 3. Главен Activity ---

class MainActivity : ComponentActivity() {
    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 🔒 Заключване в Portrait режим
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        enableEdgeToEdge()
        Configuration.getInstance().userAgentValue = packageName

        setContent {
            MainScreen()
        }
    }
}

// --- 4. UI Компоненти ---

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("OasisUrbanPrefs", Context.MODE_PRIVATE) }

    var isDarkMode by remember {
        mutableStateOf(prefs.getBoolean("is_dark_mode", false))
    }

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

        fun updateSearchCenterIfMoved(newPoint: GeoPoint) {
            if (searchCenterGeoPoint.distanceToAsDouble(newPoint) > 50.0) {
                searchCenterGeoPoint = newPoint
            }
        }

        val currentSubCategories = remember(selectedMainCategory) {
            PoiCategory.entries.filter { it.mainCategory == selectedMainCategory }
        }

        val mapEventsOverlay = remember {
            MapEventsOverlay(object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: GeoPoint): Boolean = false

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

        // 🧭 СИСТЕМЕН СЕНЗОР ЗА ВЪРТЕНЕ НА КАРТАТА С SENSORMANAGER
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

                            // Превръщаме в градуси 0 - 360
                            var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
                            if (azimuth < 0) azimuth += 360f

                            // Опресняваме само при промяна над 1.5 градуса
                            if (abs(azimuth - lastAzimuth) > 1.5f) {
                                lastAzimuth = azimuth
                                mapView.post {
                                    mapView.mapOrientation = -azimuth
                                    mapView.invalidate() // ⚡ Задължително за да се преначертае картата!
                                }
                            }
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }

                sensorManager.registerListener(
                    listener,
                    rotationSensor,
                    SensorManager.SENSOR_DELAY_GAME
                )

                onDispose {
                    sensorManager.unregisterListener(listener)
                }
            }
        }

        LaunchedEffect(Unit) {
            mapView.overlays.add(mapEventsOverlay)
        }

        // Прилагане на тъмен режим върху картата
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
            val poiIcon = createEmojiMarkerIcon(context, category.icon, category.colorHex)

            filtered.forEach { element ->
                val lat = element.actualLat
                val lon = element.actualLon
                if (lat != null && lon != null) {
                    val marker = Marker(mapView).apply {
                        position = GeoPoint(lat, lon)
                        title = element.getLocalizedTitle(category, lang)
                        snippet = formatSpotDetails(category, element.tags, lang)
                        icon = poiIcon
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    }
                    mapView.overlays.add(marker)
                }
            }
            mapView.invalidate()

            if (filtered.isEmpty()) {
                val msg = if (lang == AppLanguage.BG) {
                    "Няма намерени '${category.labelBg}' в този радиус"
                } else {
                    "No '${category.labelEn}' found in this radius"
                }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }

        fun loadOrFilterData(category: PoiCategory, center: GeoPoint, radius: Float, lang: AppLanguage, forceReload: Boolean = false) {
            activeJob?.cancel()

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
                fillPaint.color = Color.argb(35, 33, 150, 243)
                outlinePaint.color = Color.argb(120, 33, 150, 243)
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
                                Log.w("OasisUrban", "Опит $attempt/5 за $serverUrl пропадна: ${e.message}")
                                delay(1000)
                            }
                        }

                        if (attemptSuccess && bestResponse?.elements?.isNotEmpty() == true) {
                            break
                        }
                    }

                    val finalResponse = bestResponse ?: throw (lastException ?: Exception(
                        if (lang == AppLanguage.BG) "Няма връзка със сървърите." else "No server connection."
                    ))

                    cacheList.add(CachedAreaResult(center, radius, finalResponse.elements))
                    renderCategoryElements(finalResponse.elements, category, lang)

                } catch (e: CancellationException) {
                    // Игнориране при отменена корутина
                } catch (e: Exception) {
                    Log.e("OasisUrban", "Грешка при зареждане", e)
                    val errPrefix = if (lang == AppLanguage.BG) "Мрежова грешка: " else "Network error: "
                    Toast.makeText(context, "$errPrefix${e.localizedMessage}", Toast.LENGTH_LONG).show()
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

            // ГОРНО МЕНЮ С КАТЕГОРИИ
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
                                            if (firstSub != null) {
                                                selectedPoiCategory = firstSub
                                            }
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

            // ДОЛНА КОНТРОЛНА ЛЕНТА
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp, start = 8.dp, end = 8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. БУТОН ЗА СМЯНА НА ЕЗИК
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

                // 2. БУТОН ЗА ТЕМА (ДНЕВНА / НОЩНА)
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
                        Text(
                            text = if (isDarkMode) "🌙" else "☀️",
                            fontSize = 18.sp
                        )
                    }
                }

                // 3. СЛАЙДЕР ЗА РАДИУС
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(18.dp),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (currentLanguage == AppLanguage.BG) {
                                "Радиус: ${String.format("%.1f", radiusKm)} км"
                            } else {
                                "Radius: ${String.format("%.1f", radiusKm)} km"
                            },
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

                // 4. БУТОН ЗА ОПРЕСНЯВАНЕ
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
                            Text(
                                text = "🔄",
                                fontSize = 16.sp
                            )
                        }
                    }
                }

                // 5. БУТОН ЗА ТЕКУЩА ПОЗИЦИЯ (GPS)
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ) {
                    IconButton(
                        onClick = {
                            val myLoc = myLocationOverlay.myLocation
                            val geoPoint = if (myLoc != null) {
                                GeoPoint(myLoc.latitude, myLoc.longitude)
                            } else {
                                getUserLocation(context)?.let { GeoPoint(it.latitude, it.longitude) }
                            }

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

            // ДИАЛОГ "ABOUT" С ВЕРТИКАЛЕН СКРОЛ
            if (showAboutDialog) {
                val scrollState = rememberScrollState()

                AlertDialog(
                    onDismissRequest = { showAboutDialog = false },
                    title = {
                        Column {
                            Text(
                                text = "Oasis Urban: City Spot Map",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "by Ventsislav Negentsov",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    text = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState)
                        ) {
                            Text(
                                text = if (currentLanguage == AppLanguage.BG) {
                                    """
                                    Oasis Urban: City Spot Map е твоят интерактивен градски помощник за бързо и лесно откриване на обекти около теб.

                                    🌟 Достъпни обекти за търсене:
                                    • Чешми
                                    • Тоалетни
                                    • Извори
                                    • Пейки
                                    • Детски площадки
                                    • Външни фитнеси
                                    • Кучешки паркове
                                    • Зони за пикник
                                    • Панорамни гледки
                                    • Зарядни станции за електромобили
                                    • Велостойки
                                    • Колела под наем
                                    • Станции за велоремонт
                                    • Контейнери за рециклиране
                                    • Стрийт арт
                                    • Улични библиотеки
                                    • Шкафчета за пратки
                                    • Паметници

                                    🌟 Възможности:
                                    • Динамичен компас и ориентация на картата спрямо устройството.
                                    • Регулиране на радиуса на търсене от 1.0 до 5.0 км.
                                    • Дневна и Нощна тема с автоматична промяна на картата.
                                    • Сканиране на произволна точка с продължително натискане (Long Press) върху картата.
                                    • Поддръжка на български и английски език.
                                    """.trimIndent()
                                } else {
                                    """
                                    Oasis Urban: City Spot Map is your interactive urban companion for discovering useful spots around you.

                                    🌟 Searchable Spot Types:
                                    • Drinking Fountains
                                    • Public Toilets
                                    • Water Springs
                                    • Benches
                                    • Playgrounds
                                    • Outdoor Gyms
                                    • Dog Parks
                                    • Picnic Areas
                                    • Viewpoints
                                    • EV Charging Stations
                                    • Bicycle Parking
                                    • Bike Rental Stations
                                    • Bike Repair Stations
                                    • Recycling Containers
                                    • Street Art
                                    • Public Bookcases
                                    • Parcel Lockers
                                    • Monuments

                                    🌟 Features:
                                    • Dynamic compass and real-time device map orientation.
                                    • Search radius adjustment from 1.0 to 5.0 km.
                                    • Day / Night mode with automatic map contrast adjustment.
                                    • Scan any custom location with a long press on the map.
                                    • Bulgarian and English language support.
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

// --- 5. Помощни функции за локация ---

@SuppressLint("MissingPermission")
private fun getUserLocation(context: Context): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
}