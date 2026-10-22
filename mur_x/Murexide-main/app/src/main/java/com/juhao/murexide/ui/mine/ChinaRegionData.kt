package com.juhao.murexide.ui.mine

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal data class RegionItem(
    val code: String,
    val name: String
)

internal data class RegionCity(
    val code: String,
    val name: String,
    val districts: List<RegionItem>
)

internal data class RegionProvince(
    val code: String,
    val name: String,
    val cities: List<RegionCity>
)

internal data class RegionIndices(
    val province: Int,
    val city: Int,
    val district: Int
)

@Serializable
private data class RawRegion(
    @SerialName("c") val code: String,
    @SerialName("n") val name: String,
    @SerialName("d") val children: List<RawRegion> = emptyList()
)

/**
 * Offline GB/T 2260 region data derived from province-city-china 8.5.8 (MIT).
 * Direct-controlled municipalities are normalized into the three columns used by the UI.
 */
internal object ChinaRegionData {
    private val json = Json { ignoreUnknownKeys = true }
    private val directDistrictProvincePrefixes = setOf("11", "12", "31", "50", "81", "82")

    @Volatile
    private var cachedRegions: List<RegionProvince>? = null

    fun load(context: Context): List<RegionProvince> {
        cachedRegions?.let { return it }

        return synchronized(this) {
            cachedRegions ?: context.assets
                .open("china_regions.json")
                .bufferedReader(Charsets.UTF_8)
                .use { reader ->
                    json.decodeFromString<List<RawRegion>>(reader.readText())
                }
                .map { it.toRegionProvince() }
                .filter { it.cities.isNotEmpty() }
                .also { cachedRegions = it }
        }
    }

    fun resolve(
        regions: List<RegionProvince>,
        provinceName: String,
        cityName: String,
        districtName: String,
        locationCode: String
    ): RegionIndices {
        if (regions.isEmpty()) return RegionIndices(0, 0, 0)

        if (locationCode.isNotBlank()) {
            regions.forEachIndexed { provinceIndex, province ->
                province.cities.forEachIndexed { cityIndex, city ->
                    val districtIndex = city.districts.indexOfFirst { it.code == locationCode }
                    if (districtIndex >= 0) {
                        return RegionIndices(provinceIndex, cityIndex, districtIndex)
                    }
                }
            }
        }

        val provinceIndex = regions.indexOfFirst { province ->
            province.name == provinceName ||
                (locationCode.length >= 2 && province.code.startsWith(locationCode.take(2)))
        }.coerceAtLeast(0)
        val province = regions[provinceIndex]

        val cityIndex = province.cities.indexOfFirst { city ->
            city.name == cityName ||
                (locationCode.length >= 4 && city.code.startsWith(locationCode.take(4)))
        }.coerceAtLeast(0)
        val city = province.cities[cityIndex]

        val districtIndex = city.districts.indexOfFirst { district ->
            district.code == locationCode || district.name == districtName
        }.coerceAtLeast(0)

        return RegionIndices(provinceIndex, cityIndex, districtIndex)
    }

    private fun RawRegion.toRegionProvince(): RegionProvince {
        val provincePrefix = code.take(2)
        val cities = when {
            provincePrefix in directDistrictProvincePrefixes -> directControlledCities(provincePrefix)
            children.isEmpty() -> listOf(
                RegionCity(
                    code = code,
                    name = name,
                    districts = listOf(RegionItem(code, name))
                )
            )
            else -> children.map { city ->
                val districts = city.children
                    .filterNot { it.name == "市辖区" }
                    .map { RegionItem(it.code, it.name) }
                    .ifEmpty { listOf(RegionItem(city.code, city.cleanName())) }

                RegionCity(
                    code = city.code,
                    name = city.cleanName(),
                    districts = districts
                )
            }
        }

        return RegionProvince(code = code, name = name, cities = cities)
    }

    private fun RawRegion.directControlledCities(provincePrefix: String): List<RegionCity> {
        if (children.isEmpty()) {
            return listOf(
                RegionCity(
                    code = code,
                    name = name,
                    districts = listOf(RegionItem(code, name))
                )
            )
        }

        return children
            .groupBy { child -> child.code.drop(2).take(2) }
            .toSortedMap()
            .map { (cityPart, districts) ->
                RegionCity(
                    code = "$provincePrefix${cityPart}00",
                    name = directControlledCityName(provincePrefix, cityPart),
                    districts = districts
                        .filterNot { it.name == "市辖区" }
                        .map { RegionItem(it.code, it.name) }
                )
            }
    }

    private fun directControlledCityName(provincePrefix: String, cityPart: String): String {
        return when (provincePrefix) {
            "11" -> "北京城区"
            "12" -> "天津城区"
            "31" -> "上海城区"
            "50" -> if (cityPart == "01") "重庆城区" else "重庆郊县"
            "81" -> "香港特别行政区"
            "82" -> "澳门特别行政区"
            else -> "城区"
        }
    }

    private fun RawRegion.cleanName(): String = name.substringAfterLast('-')
}
