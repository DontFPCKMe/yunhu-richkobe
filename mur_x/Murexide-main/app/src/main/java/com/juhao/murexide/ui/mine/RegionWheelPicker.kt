package com.juhao.murexide.ui.mine

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
internal fun RegionWheelPicker(
    regions: List<RegionProvince>,
    provinceName: String,
    cityName: String,
    districtName: String,
    locationCode: String,
    enabled: Boolean,
    onRegionSelected: (province: String, city: String, district: String, code: String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (regions.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(260.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
        return
    }

    var provinceIndex by remember { mutableIntStateOf(0) }
    var cityIndex by remember { mutableIntStateOf(0) }
    var districtIndex by remember { mutableIntStateOf(0) }
    var lastPublishedCode by remember { mutableStateOf<String?>(null) }

    fun publishSelection(
        province: RegionProvince,
        city: RegionCity,
        district: RegionItem
    ) {
        lastPublishedCode = district.code
        onRegionSelected(province.name, city.name, district.name, district.code)
    }

    LaunchedEffect(regions, provinceName, cityName, districtName, locationCode) {
        if (locationCode != lastPublishedCode) {
            val resolved = ChinaRegionData.resolve(
                regions = regions,
                provinceName = provinceName,
                cityName = cityName,
                districtName = districtName,
                locationCode = locationCode
            )
            provinceIndex = resolved.province
            cityIndex = resolved.city
            districtIndex = resolved.district

            val province = regions[resolved.province]
            val city = province.cities[resolved.city]
            val district = city.districts[resolved.district]
            lastPublishedCode = district.code

            if (
                province.name != provinceName ||
                city.name != cityName ||
                district.name != districtName ||
                district.code != locationCode
            ) {
                onRegionSelected(province.name, city.name, district.name, district.code)
            }
        }
    }

    val safeProvinceIndex = provinceIndex.coerceIn(regions.indices)
    val selectedProvince = regions[safeProvinceIndex]
    val cities = selectedProvince.cities
    val safeCityIndex = cityIndex.coerceIn(cities.indices)
    val selectedCity = cities[safeCityIndex]
    val districts = selectedCity.districts
    val safeDistrictIndex = districtIndex.coerceIn(districts.indices)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WheelColumn(
            options = regions.map { RegionItem(it.code, it.name) },
            selectedIndex = safeProvinceIndex,
            enabled = enabled,
            onSelected = { index ->
                val province = regions[index]
                val city = province.cities.first()
                val district = city.districts.first()
                provinceIndex = index
                cityIndex = 0
                districtIndex = 0
                publishSelection(province, city, district)
            },
            modifier = Modifier.weight(1f)
        )

        key(selectedProvince.code) {
            WheelColumn(
                options = cities.map { RegionItem(it.code, it.name) },
                selectedIndex = safeCityIndex,
                enabled = enabled,
                onSelected = { index ->
                    val city = cities[index]
                    val district = city.districts.first()
                    cityIndex = index
                    districtIndex = 0
                    publishSelection(selectedProvince, city, district)
                },
                modifier = Modifier.weight(1f)
            )
        }

        key(selectedCity.code) {
            WheelColumn(
                options = districts,
                selectedIndex = safeDistrictIndex,
                enabled = enabled,
                onSelected = { index ->
                    districtIndex = index
                    publishSelection(selectedProvince, selectedCity, districts[index])
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegionPickerBottomSheet(
    regions: List<RegionProvince>,
    provinceName: String,
    cityName: String,
    districtName: String,
    locationCode: String,
    onDismissRequest: () -> Unit,
    onConfirm: (province: String, city: String, district: String, code: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedProvince by remember(provinceName, locationCode) { mutableStateOf(provinceName) }
    var selectedCity by remember(cityName, locationCode) { mutableStateOf(cityName) }
    var selectedDistrict by remember(districtName, locationCode) { mutableStateOf(districtName) }
    var selectedCode by remember(locationCode) { mutableStateOf(locationCode) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismissRequest) {
                Text("取消")
            }
            Text(
                text = "选择所在地",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = {
                    onConfirm(
                        selectedProvince,
                        selectedCity,
                        selectedDistrict,
                        selectedCode
                    )
                },
                enabled = selectedCode.isNotBlank()
            ) {
                Text("确定")
            }
        }

        RegionWheelPicker(
            regions = regions,
            provinceName = selectedProvince,
            cityName = selectedCity,
            districtName = selectedDistrict,
            locationCode = selectedCode,
            enabled = true,
            onRegionSelected = { province, city, district, code ->
                selectedProvince = province
                selectedCity = city
                selectedDistrict = district
                selectedCode = code
            },
            modifier = Modifier.padding(horizontal = 18.dp)
        )

        Spacer(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.navigationBars)
                .height(16.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WheelColumn(
    options: List<RegionItem>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 52.dp
) {
    val safeSelectedIndex = selectedIndex.coerceIn(options.indices)
    val optionsKey = remember(options) {
        options.joinToString(separator = "|") { it.code }
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeSelectedIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)
    val coroutineScope = rememberCoroutineScope()
    val currentSelectedIndex by rememberUpdatedState(safeSelectedIndex)
    val currentOnSelected by rememberUpdatedState(onSelected)
    val centeredIndex by remember(listState, optionsKey) {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter =
                (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            layoutInfo.visibleItemsInfo.minByOrNull { item ->
                abs(item.offset + item.size / 2 - viewportCenter)
            }?.index?.coerceIn(options.indices) ?: safeSelectedIndex
        }
    }

    LaunchedEffect(safeSelectedIndex, optionsKey) {
        if (!listState.isScrollInProgress && centeredIndex != safeSelectedIndex) {
            listState.scrollToItem(safeSelectedIndex)
        }
    }

    LaunchedEffect(listState, optionsKey) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { isScrolling -> !isScrolling }
            .map { centeredIndex.coerceIn(options.indices) }
            .distinctUntilChanged()
            .collect { index ->
                if (index != currentSelectedIndex) currentOnSelected(index)
            }
    }

    Box(modifier = modifier.fillMaxHeight()) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .height(itemHeight - 6.dp)
                .padding(horizontal = 2.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = itemHeight * 2),
            flingBehavior = flingBehavior,
            userScrollEnabled = enabled
        ) {
            itemsIndexed(
                items = options,
                key = { _, item -> item.code }
            ) { index, item ->
                val distance = abs(index - centeredIndex)
                val isCentered = distance == 0
                val contentAlpha = when (distance) {
                    0 -> 1f
                    1 -> 0.58f
                    else -> 0.32f
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .padding(horizontal = 2.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(enabled = enabled) {
                            coroutineScope.launch {
                                listState.animateScrollToItem(index)
                            }
                        }
                        .padding(horizontal = 4.dp)
                        .graphicsLayer { alpha = contentAlpha },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.name,
                        style = if (isCentered) {
                            MaterialTheme.typography.titleSmall.copy(
                                fontSize = item.name.wheelFontSize(),
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            MaterialTheme.typography.bodyMedium.copy(
                                fontSize = item.name.wheelFontSize()
                            )
                        },
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun String.wheelFontSize() = when {
    length >= 9 -> 10.sp
    length >= 7 -> 11.sp
    length >= 5 -> 12.sp
    else -> 14.sp
}
