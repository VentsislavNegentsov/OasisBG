package com.oasisbg

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
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
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

// --- 1. Модели за данни & Retrofit API ---

data class Center(val lat: Double, val lon: Double)

data class Element(
    val id: Long,
    val type: String,
    val lat: Double?,
    val lon: Double?,
    val center: Center?,
    val tags: Map<String, String>?
) {
    val computedLat: Double get() = lat ?: center?.lat ?: 0.0
    val computedLon: Double get() = lon ?: center?.lon ?: 0.0
}

data class OverpassResponse(val elements: List<Element>)

enum class MapCategory(val label: String, val icon: String, val osmKey: String, val osmValue: String) {
    FOUNTAINS("Чешми", "🚰", "amenity", "drinking_water"),
    TOILETS("Тоалетни", "🚻", "amenity", "toilets"),
    ART("Стрийт Арт", "🎨", "tourism", "artwork"),
    DOG_PARKS("Кучета", "🐕", "leisure", "dog_park"),
    RECYCLING("Рециклиране", "♻️", "amenity", "recycling")
}

interface OverpassApi {
    // Използваме POST вместо GET за избягване на проблеми с кодирането на символи
    @FormUrlEncoded
    @POST("api/interpreter")
    suspend fun getNodes(@Field("data") query: String): OverpassResponse

    companion object {
        fun create(): OverpassApi {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "OasisBGApp/1.0 (Android Native)")
                        .build()
                    chain.proceed(request)
                }
                .build()

            return Retrofit.Builder()
                .baseUrl("https://overpass.kumi.systems/") // Изключително бърз EU огледален сървър
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(OverpassApi::class.java)
        }
    }
}

// --- 2. Главен Activity ---

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

// --- 3. UI Компоненти ---

@Composable
fun MainScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val api = remember { OverpassApi.create() }

    var selectedCategory by remember { mutableStateOf(MapCategory.FOUNTAINS) }
    var isLoading by remember { mutableStateOf(false) }
    var currentGeoPoint by remember { mutableStateOf(GeoPoint(42.6977, 23.3219)) } // София център

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
            controller.setCenter(currentGeoPoint)
        }
    }

    val myLocationOverlay = remember {
        MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).apply {
            enableMyLocation()
        }
    }

    fun loadData(category: MapCategory, center: GeoPoint) {
        coroutineScope.launch {
            isLoading = true
            try {
                // Изчистен Overpass QL синтаксис
                val query = """
                    [out:json][timeout:25];
                    nwr["${category.osmKey}"="${category.osmValue}"](around:5000,${center.latitude},${center.longitude});
                    out center;
                """.trimIndent()

                val response = api.getNodes(query)

                mapView.overlays.clear()
                mapView.overlays.add(myLocationOverlay)

                if (response.elements.isEmpty()) {
                    Toast.makeText(context, "Няма намерени обекти в района", Toast.LENGTH_SHORT).show()
                } else {
                    response.elements.forEach { element ->
                        if (element.computedLat != 0.0 && element.computedLon != 0.0) {
                            val marker = Marker(mapView).apply {
                                position = GeoPoint(element.computedLat, element.computedLon)
                                title = element.tags?.get("name") ?: category.label
                                snippet = element.tags?.get("description") ?: "OasisBG Spot"
                            }
                            mapView.overlays.add(marker)
                        }
                    }
                }
                mapView.invalidate()
            } catch (e: Exception) {
                Log.e("OasisBG", "Error fetching data", e)
                Toast.makeText(context, "Грешка: ${e.message}", Toast.LENGTH_LONG).show()
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
                currentGeoPoint = GeoPoint(userLocation.latitude, userLocation.longitude)
                mapView.controller.setCenter(currentGeoPoint)
            }
        }
        loadData(selectedCategory, currentGeoPoint)
    }

    LaunchedEffect(Unit) {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    LaunchedEffect(selectedCategory) {
        loadData(selectedCategory, currentGeoPoint)
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

// --- 4. Помощни функции ---

@SuppressLint("MissingPermission")
private fun getUserLocation(context: Context): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    return locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
}