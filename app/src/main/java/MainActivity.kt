package com.oasisbg

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
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

// --- 1. Модели за данни & Retrofit API ---

data class Element(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val tags: Map<String, String>?
)

data class OverpassResponse(val elements: List<Element>)

enum class MapCategory(
    val label: String,
    val icon: String,
    val osmKey: String,
    val osmValue: String,
    val colorHex: String
) {
    FOUNTAINS("Чешми", "🚰", "amenity", "drinking_water", "#0288D1"),
    TOILETS("Тоалетни", "🚻", "amenity", "toilets", "#7B1FA2"),
    ART("Стрийт Арт", "🎨", "tourism", "artwork", "#F57C00"),
    DOG_PARKS("Кучета", "🐕", "leisure", "dog_park", "#388E3C"),
    RECYCLING("Рециклиране", "♻️", "amenity", "recycling", "#00796B")
}

val OVERPASS_SERVERS = listOf(
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.osm.ch/api/interpreter",
    "https://overpass.nchc.org.tw/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
)

interface OverpassApi {
    @FormUrlEncoded
    @POST
    suspend fun getNodes(@Url url: String, @Field("data") query: String): OverpassResponse

    companion object {
        fun create(): OverpassApi {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(25, TimeUnit.SECONDS)
                .readTimeout(25, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "OasisBG-MobileApp/1.5 (Android)")
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

// --- 2. Помощни функции за UI и Маркери ---

private fun getTintedMarkerIcon(context: Context, colorHex: String): Drawable? {
    val drawable = ContextCompat.getDrawable(context, org.osmdroid.library.R.drawable.marker_default)?.mutate()
    if (drawable != null) {
        DrawableCompat.setTint(drawable, Color.parseColor(colorHex))
    }
    return drawable
}

private fun formatSpotDetails(category: MapCategory, tags: Map<String, String>?): String {
    if (tags.isNullOrEmpty()) return "Няма допълнителни данни"

    val details = mutableListOf<String>()

    tags["operator"]?.let { details.add("Стопанин: $it") }
    tags["opening_hours"]?.let { details.add("Работно време: $it") }
    tags["fee"]?.let {
        val feeText = if (it == "no") "Безплатно" else "Платено ($it)"
        details.add("Такса: $feeText")
    }
    tags["wheelchair"]?.let {
        val wcText = if (it == "yes") "Да" else "Не"
        details.add("Достъп за колички: $wcText")
    }
    tags["description"]?.let { details.add("Описание: $it") }
    tags["note"]?.let { details.add("Бележка: $it") }

    return if (details.isEmpty()) "Обект от OSM категория '${category.label}'" else details.joinToString("\n")
}

// --- 3. Главен Activity ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    var selectedCategory by remember { mutableStateOf(MapCategory.FOUNTAINS) }
    var isLoading by remember { mutableStateOf(false) }
    var activeJob by remember { mutableStateOf<Job?>(null) }

    var searchCenterGeoPoint by remember { mutableStateOf(GeoPoint(42.6977, 23.3219)) } // София

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

    fun loadData(category: MapCategory, center: GeoPoint) {
        activeJob?.cancel()

        activeJob = coroutineScope.launch {
            isLoading = true
            try {
                val query = """
                    [out:json][timeout:25];
                    node["${category.osmKey}"="${category.osmValue}"](around:3000,${center.latitude},${center.longitude});
                    out body;
                """.trimIndent()

                var response: OverpassResponse? = null
                var lastException: Exception? = null

                for (serverUrl in OVERPASS_SERVERS) {
                    try {
                        response = api.getNodes(serverUrl, query)
                        if (response != null) break
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        lastException = e
                        Log.w("OasisBG", "Сървър $serverUrl пропадна: ${e.message}")
                    }
                }

                if (response == null) {
                    throw lastException ?: Exception("Няма връзка със сървърите.")
                }

                mapView.overlays.clear()
                mapView.overlays.add(mapEventsOverlay)
                mapView.overlays.add(myLocationOverlay)

                // 1. Червен маркер за избраната точка на търсене
                val centerMarker = Marker(mapView).apply {
                    position = center
                    title = "Избрана локация"
                    snippet = "Център на търсене (радиус 3 км)"
                    icon = getTintedMarkerIcon(context, "#D32F2F") // Ярко червен цвят
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                mapView.overlays.add(centerMarker)
                centerMarker.showInfoWindow() // Показва балона за избраната точка автоматично

                // 2. Синя прозрачна окръжност
                val circle = Polygon().apply {
                    points = Polygon.pointsAsCircle(center, 3000.0)
                    fillColor = Color.argb(35, 33, 150, 243)
                    strokeColor = Color.argb(120, 33, 150, 243)
                    strokeWidth = 3f
                }
                mapView.overlays.add(circle)

                // 3. Цветни маркери за обектите с балон за подробности
                val poiIcon = getTintedMarkerIcon(context, category.colorHex)

                if (response.elements.isEmpty()) {
                    Toast.makeText(context, "Няма намерени обекти от тип '${category.label}'", Toast.LENGTH_SHORT).show()
                } else {
                    response.elements.forEach { element ->
                        val marker = Marker(mapView).apply {
                            position = GeoPoint(element.lat, element.lon)
                            title = element.tags?.get("name") ?: "${category.icon} ${category.label}"
                            snippet = formatSpotDetails(category, element.tags)
                            icon = poiIcon
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        }
                        mapView.overlays.add(marker)
                    }
                }
                mapView.invalidate()
            } catch (e: CancellationException) {
                // Игнорира се анулирането при нов клик
            } catch (e: Exception) {
                Log.e("OasisBG", "Грешка при зареждане", e)
                Toast.makeText(context, "Мрежова грешка: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            } finally {
                isLoading = false
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            val userLocation = getUserLocation(context)
            if (userLocation != null) {
                searchCenterGeoPoint = GeoPoint(userLocation.latitude, userLocation.longitude)
                mapView.controller.setCenter(searchCenterGeoPoint)
            }
        }
        loadData(selectedCategory, searchCenterGeoPoint)
    }

    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    LaunchedEffect(selectedCategory, searchCenterGeoPoint) {
        loadData(selectedCategory, searchCenterGeoPoint)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

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
                shape = RoundedCornerShape(24.dp),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
            ) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(MapCategory.entries) { category ->
                        FilterChip(
                            selected = selectedCategory == category,
                            onClick = { selectedCategory = category },
                            label = { Text("${category.icon} ${category.label}") }
                        )
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
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