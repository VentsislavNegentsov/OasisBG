package com.oasisbg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
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
import androidx.compose.foundation.shape.RoundedCornerShape
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

// --- 1. Модели за данни, Категории (2 Нива), Локализация & API ---

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
}

data class OverpassResponse(val elements: List<Element>)

data class CachedQueryResult(
    val category: PoiCategory,
    val center: GeoPoint,
    val elements: List<Element>
)

// Ниво 1: Главни Групи Категории
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

// Ниво 2: Конкретни Обекти (POI)
enum class PoiCategory(
    val mainCategory: MainCategory,
    val labelBg: String,
    val labelEn: String,
    val icon: String,
    val osmKey: String,
    val osmValue: String,
    val colorHex: String
) {
    // Вода & Хигиена
    FOUNTAINS(MainCategory.WATER_HYGIENE, "Чешми", "Fountains", "🚰", "amenity", "drinking_water", "#0288D1"),
    TOILETS(MainCategory.WATER_HYGIENE, "Тоалетни", "Toilets", "🚻", "amenity", "toilets", "#7B1FA2"),
    SPRINGS(MainCategory.WATER_HYGIENE, "Извори", "Springs", "🏞️", "natural", "spring", "#00ACC1"),

    // Отдих & Спорт
    BENCHES(MainCategory.LEISURE, "Пейки", "Benches", "🪑", "amenity", "bench", "#8D6E63"),
    PLAYGROUNDS(MainCategory.LEISURE, "Площадки", "Playgrounds", "🛝", "leisure", "playground", "#E91E63"),
    FITNESS(MainCategory.LEISURE, "Външен фитнес", "Outdoor Gym", "🏋️", "leisure", "fitness_station", "#4CAF50"),
    DOG_PARKS(MainCategory.LEISURE, "Кучета", "Dog Parks", "🐕", "leisure", "dog_park", "#388E3C"),
    PICNIC(MainCategory.LEISURE, "Пикник", "Picnic Areas", "🧺", "leisure", "picnic_site", "#FF9800"),
    VIEWPOINTS(MainCategory.LEISURE, "Гледки", "Viewpoints", "🌅", "tourism", "viewpoint", "#9C27B0"),

    // Транспорт
    EV_CHARGING(MainCategory.TRANSPORT, "EV Зарядни", "EV Chargers", "⚡", "amenity", "charging_station", "#FBC02D"),
    BIKE_PARKING(MainCategory.TRANSPORT, "Велостойки", "Bike Parking", "🚲", "amenity", "bicycle_parking", "#009688"),
    BIKE_RENTAL(MainCategory.TRANSPORT, "Колела под наем", "Bike Rental", "🚴", "amenity", "bicycle_rental", "#00BCD4"),
    BIKE_REPAIR(MainCategory.TRANSPORT, "Велоремонт", "Bike Repair", "🔧", "amenity", "bike_repair_station", "#607D8B"),

    // Еко & Рециклиране
    RECYCLING(MainCategory.ECO, "Рециклиране", "Recycling", "♻️", "amenity", "recycling", "#00796B"),

    // Култура & Град
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
                .connectTimeout(35, TimeUnit.SECONDS)
                .readTimeout(35, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "OasisUrban-MobileApp/3.0 (Android)")
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
    tags["description"]?.let {
        details.add(if (lang == AppLanguage.BG) "Описание: $it" else "Description: $it")
    }

    return if (details.isEmpty()) {
        if (lang == AppLanguage.BG) "Обект от OSM категория '${category.labelBg}'"
        else "Object from OSM category '${category.labelEn}'"
    } else details.joinToString("\n")
}

// --- 3. Главен Activity ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Configuration.getInstance().userAgentValue = packageName

        setContent {
            MaterialTheme {
                MainScreen()
            }
        }
    }
}

// --- 4. UI Компоненти ---

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val api = remember { OverpassApi.create() }

    val cacheList = remember { mutableStateListOf<CachedQueryResult>() }

    var currentLanguage by remember { mutableStateOf(AppLanguage.BG) }

    // Начални състояния: "Вода & Хигиена" -> "Чешми"
    var selectedMainCategory by remember { mutableStateOf(MainCategory.WATER_HYGIENE) }
    var selectedPoiCategory by remember { mutableStateOf(PoiCategory.FOUNTAINS) }

    var isLoading by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    var searchCenterGeoPoint by remember { mutableStateOf(GeoPoint(42.6977, 23.3219)) }

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
            overlays.add(mapEventsOverlay)
        }
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
                    searchCenterGeoPoint = point
                    mapView.controller.animateTo(point)
                }
            }
        }
    }

    fun renderElements(elements: List<Element>, category: PoiCategory, lang: AppLanguage) {
        val poiIcon = createEmojiMarkerIcon(context, category.icon, category.colorHex)
        elements.forEach { element ->
            val lat = element.actualLat
            val lon = element.actualLon
            if (lat != null && lon != null) {
                val marker = Marker(mapView).apply {
                    position = GeoPoint(lat, lon)
                    title = element.tags?.get("name") ?: "${category.icon} ${category.label(lang)}"
                    snippet = formatSpotDetails(category, element.tags, lang)
                    icon = poiIcon
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                mapView.overlays.add(marker)
            }
        }
        mapView.invalidate()
    }

    fun loadData(category: PoiCategory, center: GeoPoint, lang: AppLanguage) {
        activeJob?.cancel()

        mapView.overlays.clear()
        mapView.overlays.add(mapEventsOverlay)
        mapView.overlays.add(myLocationOverlay)

        val centerMarker = Marker(mapView).apply {
            position = center
            title = if (lang == AppLanguage.BG) "Избрана локация" else "Selected location"
            snippet = if (lang == AppLanguage.BG) "Център на търсене (радиус 3 км)" else "Search center (3 km radius)"
            icon = createEmojiMarkerIcon(context, "📍", "#D32F2F")
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        }
        mapView.overlays.add(centerMarker)
        centerMarker.showInfoWindow()

        val circle = Polygon().apply {
            points = Polygon.pointsAsCircle(center, 3000.0)
            fillPaint.color = Color.argb(35, 33, 150, 243)
            outlinePaint.color = Color.argb(120, 33, 150, 243)
            outlinePaint.strokeWidth = 3f
        }
        mapView.overlays.add(circle)
        mapView.invalidate()

        // Проверка в кеша
        val cachedHit = cacheList.firstOrNull { cached ->
            cached.category == category && center.distanceToAsDouble(cached.center) < 1000.0
        }

        if (cachedHit != null) {
            renderElements(cachedHit.elements, category, lang)
            val msg = if (lang == AppLanguage.BG) "⚡ Заредено от кеша" else "⚡ Loaded from cache"
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            return
        }

        // Заявка към Overpass API
        activeJob = coroutineScope.launch {
            isLoading = true
            try {
                val query = """
                    [out:json][timeout:35];
                    (
                      node["${category.osmKey}"="${category.osmValue}"](around:3000,${center.latitude},${center.longitude});
                      way["${category.osmKey}"="${category.osmValue}"](around:3000,${center.latitude},${center.longitude});
                      relation["${category.osmKey}"="${category.osmValue}"](around:3000,${center.latitude},${center.longitude});
                    );
                    out center;
                """.trimIndent()

                var bestResponse: OverpassResponse? = null
                var lastException: Exception? = null

                for (serverUrl in OVERPASS_SERVERS) {
                    try {
                        val res = api.getNodes(serverUrl, query)
                        if (res.elements.isNotEmpty()) {
                            bestResponse = res
                            break
                        } else if (bestResponse == null) {
                            bestResponse = res
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        lastException = e
                        Log.w("OasisUrban", "Сървър $serverUrl пропадна: ${e.message}")
                    }
                }

                val finalResponse = bestResponse ?: throw (lastException ?: Exception(
                    if (lang == AppLanguage.BG) "Няма връзка със сървърите." else "No server connection."
                ))

                if (finalResponse.elements.isEmpty()) {
                    val msg = if (lang == AppLanguage.BG) {
                        "Няма намерени '${category.labelBg}' наоколо"
                    } else {
                        "No '${category.labelEn}' found nearby"
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                } else {
                    cacheList.add(CachedQueryResult(category, center, finalResponse.elements))
                    renderElements(finalResponse.elements, category, lang)
                }
            } catch (e: CancellationException) {
                // Прекъснато при нова заявка
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
                searchCenterGeoPoint = point
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

    LaunchedEffect(selectedPoiCategory, searchCenterGeoPoint, currentLanguage) {
        loadData(selectedPoiCategory, searchCenterGeoPoint, currentLanguage)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

        // Двуредово меню отгоре
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

                    // РЕД 1: Основни Категории
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(MainCategory.entries) { mainCat ->
                            FilterChip(
                                selected = selectedMainCategory == mainCat,
                                onClick = {
                                    selectedMainCategory = mainCat
                                    // При смяна на основна категория автоматично избираме първия обект от нея
                                    val firstSub = PoiCategory.entries.firstOrNull { it.mainCategory == mainCat }
                                    if (firstSub != null) {
                                        selectedPoiCategory = firstSub
                                    }
                                },
                                label = { Text("${mainCat.icon} ${mainCat.label(currentLanguage)}", fontSize = 13.sp) }
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp, horizontal = 12.dp),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    )

                    // РЕД 2: Обекти от избраната категория
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

        // 1. Компактен бутон за език (Долу вляво)
        SmallFloatingActionButton(
            onClick = {
                currentLanguage = if (currentLanguage == AppLanguage.BG) AppLanguage.EN else AppLanguage.BG
            },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(bottom = 20.dp, start = 16.dp),
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            contentColor = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = if (currentLanguage == AppLanguage.BG) "🇧🇬 BG" else "🇬🇧 EN",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }

        // 2. Компактен бутон за текуща GPS локация (Долу вдясно)
        SmallFloatingActionButton(
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
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(bottom = 20.dp, end = 16.dp),
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            contentColor = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("🎯", fontSize = 18.sp)
        }
    }
}

// --- 5. Помощни функции ---

@SuppressLint("MissingPermission")
private fun getUserLocation(context: Context): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
}