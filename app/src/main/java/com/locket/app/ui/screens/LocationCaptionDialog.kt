package com.locket.app.ui.screens

import android.location.Location
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.util.Locale

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun LocationCaptionDialog(
    currentLocation: Location,
    onDismiss: () -> Unit,
    onSelect: (CaptionPlace) -> Unit
) {
    val yellow = Color(0xFFFFC943)
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var nearby by remember { mutableStateOf(emptyList<CaptionPlace>()) }
    var results by remember { mutableStateOf(emptyList<CaptionPlace>()) }
    var currentArea by remember { mutableStateOf("Vị trí hiện tại") }
    var nearbyLoading by remember { mutableStateOf(true) }
    var searchLoading by remember { mutableStateOf(false) }
    var nearbyError by remember { mutableStateOf<String?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var nearbyRetry by remember { mutableIntStateOf(0) }
    var searchRetry by remember { mutableIntStateOf(0) }

    LaunchedEffect(currentLocation.latitude, currentLocation.longitude, nearbyRetry) {
        nearbyLoading = true
        nearbyError = null
        try {
            coroutineScope {
                val areaTask = async {
                    try { CaptionLocationData.placeName(context, currentLocation) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                }
                val nearbyTask = async { LocationCaptionCatalog.nearby(currentLocation) }
                currentArea = areaTask.await()?.takeIf { it.isNotBlank() } ?: "Vị trí hiện tại"
                nearby = nearbyTask.await()
                if (nearby.isEmpty()) nearbyError = "Chưa có địa điểm được lập bản đồ gần đây."
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            nearbyError = e.message ?: "Không tải được địa điểm gần đây."
        } finally {
            nearbyLoading = false
        }
    }

    LaunchedEffect(query, searchRetry, currentLocation.latitude, currentLocation.longitude) {
        results = emptyList()
        searchError = null
        val term = query.trim()
        if (term.length < 2) {
            searchLoading = false
            return@LaunchedEffect
        }
        searchLoading = true
        try {
            delay(450L)
            results = LocationCaptionCatalog.search(term, currentLocation)
            if (results.isEmpty()) searchError = "Không tìm thấy địa điểm phù hợp."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            searchError = e.message ?: "Không tìm được địa điểm."
        } finally {
            searchLoading = false
        }
    }

    val currentPlace = remember(currentLocation.latitude, currentLocation.longitude, currentArea) {
        CaptionPlace(
            name = currentArea,
            subtitle = "Dùng tọa độ GPS hiện tại",
            latitude = currentLocation.latitude,
            longitude = currentLocation.longitude,
            category = "Vị trí hiện tại",
            osmId = "current-location"
        )
    }

    CaptionPickerSheet(onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Chọn địa điểm",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(100) },
                placeholder = { Text("Tìm địa điểm, quán ăn, cửa hàng…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Xóa tìm kiếm") }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                item {
                    Text("Vị trí hiện tại", color = Color.White.copy(alpha = 0.58f),
                        fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
                item {
                    LocationPickerRow(currentPlace, null, selected = true) { onSelect(currentPlace) }
                }

                if (query.trim().length < 2) {
                    item {
                        Text("Gần bạn", color = Color.White.copy(alpha = 0.58f),
                            fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    }
                    items(nearby, key = { it.osmId }) { place ->
                        LocationPickerRow(place, distanceLabel(place.distanceMeters)) { onSelect(place) }
                    }
                    if (nearbyLoading) item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = yellow)
                            Spacer(Modifier.width(9.dp))
                            Text("Đang tìm địa điểm gần bạn…", fontSize = 13.sp)
                        }
                    }
                    nearbyError?.let { message ->
                        item {
                            Text(message, color = Color.White.copy(alpha = 0.70f),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp), fontSize = 13.sp)
                            TextButton(onClick = { nearbyRetry++ }) { Text("Tải lại", color = yellow) }
                        }
                    }
                } else {
                    item {
                        Text("Kết quả tìm kiếm", color = Color.White.copy(alpha = 0.58f),
                            fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    }
                    items(results, key = { it.osmId }) { place ->
                        LocationPickerRow(place, distanceLabel(place.distanceMeters)) { onSelect(place) }
                    }
                    if (searchLoading) item {
                        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = yellow)
                            Spacer(Modifier.width(9.dp))
                            Text("Đang tìm…", fontSize = 13.sp)
                        }
                    }
                    searchError?.let { message ->
                        item {
                            Text(message, color = Color.White.copy(alpha = 0.70f),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp), fontSize = 13.sp)
                            TextButton(onClick = { searchRetry++ }) { Text("Thử lại", color = yellow) }
                        }
                    }
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "Tọa độ gửi tới Photon để tìm gần bạn · © OpenStreetMap",
                    color = Color.White.copy(alpha = 0.42f),
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
                TextButton(onClick = onDismiss) { Text("Hủy", color = Color.White) }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun LocationPickerRow(
    place: CaptionPlace,
    distance: String?,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = if (selected) 0.14f else 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (selected) Icons.Default.MyLocation else Icons.Default.Place,
                null,
                tint = if (selected) Color(0xFFFFC943) else Color.White.copy(alpha = 0.82f),
                modifier = Modifier.size(23.dp)
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(place.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (place.subtitle.isNotBlank()) {
                Text(place.subtitle, color = Color.White.copy(alpha = 0.58f), fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        distance?.let { Text(it, color = Color.White.copy(alpha = 0.48f), fontSize = 11.sp) }
    }
}

private fun distanceLabel(meters: Float?): String? {
    if (meters == null || !meters.isFinite()) return null
    return if (meters < 1000f) "${meters.toInt()} m"
    else String.format(Locale.getDefault(), "%.1f km", meters / 1000f)
}
