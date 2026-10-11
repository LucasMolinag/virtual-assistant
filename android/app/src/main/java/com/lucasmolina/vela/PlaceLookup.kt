package com.lucasmolina.vela

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.cos

object PlaceLookup {
    private val cache = object : LinkedHashMap<String, String>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 40
    }

    fun lookup(context: Context, query: String): String {
        val name = cleanQuery(query)
        if (name.length < 3) return JSONObject().put("error", "Tell me the name of a place.").toString()
        val here = here(context)
        val key = cacheKey(name, here)
        synchronized(cache) {
            cache[key]?.let { return it }
        }
        val result = try {
            val best = choose(search(context, name, here), here)
            if (best == null) {
                JSONObject().put("error", "I couldn't find that place.").toString()
            } else {
                val phone = best.phone.ifBlank { phoneFor(best, name, here) }
                JSONObject()
                    .put("name", name)
                    .put("address", best.address.ifBlank { name })
                    .put("phone", phone)
                    .toString()
            }
        } catch (_: Exception) {
            JSONObject().put("error", "I couldn't look up that place.").toString()
        }
        if (!JSONObject(result).has("error")) {
            synchronized(cache) { cache[key] = result }
        }
        return result
    }

    private fun cleanQuery(query: String): String {
        return query.trim()
            .replace(Regex("""^(?:the\s+)?(?:nearest|closest|nearby)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:near me|nearby|around here)$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun cacheKey(name: String, here: Here?): String {
        val country = here?.country.orEmpty()
        val place = name.lowercase()
        if (here == null) return "$place|$country"
        val lat = Math.round(here.lat * 10) / 10.0
        val lng = Math.round(here.lng * 10) / 10.0
        return "$place|$country|$lat|$lng"
    }

    private data class Here(val lat: Double, val lng: Double, val country: String, val label: String)

    private data class Hit(val address: String, val phone: String, val lat: Double?, val lng: Double?, val country: String)

    @SuppressLint("MissingPermission")
    private fun here(context: Context): Here? {
        val point = freshLocation(context) ?: return null
        val area = reverse(context, point.first, point.second)
        val country = area?.countryCode?.uppercase().orEmpty().ifBlank { deviceCountry(context) }
        val label = listOf(area?.locality, area?.adminArea, area?.countryName ?: countryName(country))
            .filter { !it.isNullOrBlank() }
            .distinct()
            .joinToString(", ")
        return Here(point.first, point.second, country, label)
    }

    @SuppressLint("MissingPermission")
    private fun freshLocation(context: Context): Pair<Double, Double>? {
        val coarse = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!coarse && !fine) return null
        val manager = context.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val providers = buildList {
                if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
                if (fine) add(LocationManager.GPS_PROVIDER)
            }
            for (provider in providers) {
                val current = currentFix(manager, provider) ?: continue
                return current.latitude to current.longitude
            }
        }
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER).mapNotNull { provider ->
            try {
                if (manager.isProviderEnabled(provider)) manager.getLastKnownLocation(provider) else null
            } catch (_: Exception) {
                null
            }
        }.filter { System.currentTimeMillis() - it.time < 30 * 60 * 1000 }
            .maxByOrNull { it.time }
            ?.let { it.latitude to it.longitude }
    }

    @SuppressLint("MissingPermission")
    private fun currentFix(manager: LocationManager, provider: String): Location? {
        if (!manager.isProviderEnabled(provider)) return null
        val latch = CountDownLatch(1)
        var found: Location? = null
        return try {
            manager.getCurrentLocation(provider, null, { it.run() }) { location ->
                found = location
                latch.countDown()
            }
            if (!latch.await(4, TimeUnit.SECONDS)) return null
            found
        } catch (_: Exception) {
            null
        }
    }

    private fun deviceCountry(context: Context): String {
        val telephony = context.getSystemService(TelephonyManager::class.java)
        val network = telephony?.networkCountryIso?.takeIf { it.length == 2 }
        val sim = telephony?.simCountryIso?.takeIf { it.length == 2 }
        return (network ?: sim)?.uppercase().orEmpty().ifBlank { Locale.getDefault().country.uppercase() }
    }

    private fun countryName(code: String): String {
        if (code.length != 2) return ""
        return Locale("", code).getDisplayCountry(Locale.ENGLISH)
    }

    private fun search(context: Context, name: String, here: Here?): List<Hit> {
        val country = here?.country?.ifBlank { deviceCountry(context) } ?: deviceCountry(context)
        val focused = listOf(name, here?.label?.ifBlank { countryName(country) } ?: countryName(country))
            .filter { it.isNotBlank() }
            .joinToString(", ")
        val hits = mutableListOf<Hit>()
        hits += nominatim(focused, here, country, bounded = here != null)
        if (hits.isEmpty()) hits += nominatim(name, here, country, bounded = false)
        hits += geocode(context, focused, here, country)
        if (hits.isEmpty() && focused != name) hits += geocode(context, "$name, ${countryName(country)}", here, country)
        return hits.filter { hit ->
            val code = hit.country.uppercase()
            country.isBlank() || code.isBlank() || code == country
        }.filter { hit ->
            if (here == null || hit.lat == null || hit.lng == null) return@filter true
            distanceMiles(here.lat, here.lng, hit.lat, hit.lng) <= 120
        }
    }

    private fun choose(hits: List<Hit>, here: Here?): Hit? {
        if (hits.isEmpty()) return null
        if (here == null) return hits.first()
        return hits.minByOrNull { hit ->
            if (hit.lat == null || hit.lng == null) 10_000.0
            else distanceMiles(here.lat, here.lng, hit.lat, hit.lng)
        }
    }

    private fun geocode(context: Context, query: String, here: Here?, country: String): List<Hit> {
        if (!Geocoder.isPresent() || query.isBlank()) return emptyList()
        val matches = if (here != null) geocodeIn(context, query, here, 0.8) else geocodeIn(context, query, null, 0.0)
        return matches.map { address ->
            Hit(
                formatAddress(address),
                "",
                address.latitude.takeIf { address.hasLatitude() },
                address.longitude.takeIf { address.hasLongitude() },
                address.countryCode?.uppercase().orEmpty().ifBlank { country },
            )
        }
    }

    private fun geocodeIn(context: Context, query: String, here: Here?, span: Double): List<Address> {
        val geocoder = Geocoder(context, Locale.getDefault())
        val lowerLat = here?.let { it.lat - span }
        val lowerLng = here?.let { it.lng - span / cos(Math.toRadians(it.lat)).coerceAtLeast(0.2) }
        val upperLat = here?.let { it.lat + span }
        val upperLng = here?.let { it.lng + span / cos(Math.toRadians(it.lat)).coerceAtLeast(0.2) }
        if (Build.VERSION.SDK_INT >= 33) {
            val latch = CountDownLatch(1)
            var found = emptyList<Address>()
            val listener = object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    found = addresses.toList()
                    latch.countDown()
                }

                override fun onError(errorMessage: String?) {
                    latch.countDown()
                }
            }
            if (lowerLat != null && lowerLng != null && upperLat != null && upperLng != null) {
                geocoder.getFromLocationName(query, 5, lowerLat, lowerLng, upperLat, upperLng, listener)
            } else {
                geocoder.getFromLocationName(query, 5, listener)
            }
            latch.await(5, TimeUnit.SECONDS)
            return found
        }
        @Suppress("DEPRECATION")
        return if (lowerLat != null && lowerLng != null && upperLat != null && upperLng != null) {
            geocoder.getFromLocationName(query, 5, lowerLat, lowerLng, upperLat, upperLng).orEmpty()
        } else {
            geocoder.getFromLocationName(query, 5).orEmpty()
        }
    }

    private fun reverse(context: Context, lat: Double, lng: Double): Address? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale.getDefault())
        if (Build.VERSION.SDK_INT >= 33) {
            val latch = CountDownLatch(1)
            var found: Address? = null
            geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) {
                    found = addresses.firstOrNull()
                    latch.countDown()
                }

                override fun onError(errorMessage: String?) {
                    latch.countDown()
                }
            })
            latch.await(4, TimeUnit.SECONDS)
            return found
        }
        @Suppress("DEPRECATION")
        return geocoder.getFromLocation(lat, lng, 1)?.firstOrNull()
    }

    private fun distanceMiles(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lng1, lat2, lng2, results)
        return results[0] / 1609.34
    }

    private fun formatAddress(address: Address): String {
        if (address.maxAddressLineIndex >= 0) {
            val line = address.getAddressLine(0)?.trim().orEmpty()
            if (line.isNotEmpty()) return line
        }
        return listOf(address.featureName, address.thoroughfare, address.locality, address.adminArea, address.postalCode)
            .filter { !it.isNullOrBlank() }
            .distinct()
            .joinToString(", ")
    }

    private fun phoneFor(hit: Hit, name: String, here: Here?): String {
        val near = if (hit.lat != null && hit.lng != null) Here(hit.lat, hit.lng, hit.country, "") else here
        return nominatim(hit.address.ifBlank { name }, near, hit.country.ifBlank { here?.country.orEmpty() }, bounded = false)
            .firstOrNull()?.phone.orEmpty()
    }

    private fun nominatim(query: String, here: Here?, country: String, bounded: Boolean): List<Hit> {
        if (query.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val countryParam = country.takeIf { it.length == 2 }?.let { "&countrycodes=${it.lowercase()}" }.orEmpty()
        val bias = if (here != null) "&lat=${here.lat}&lon=${here.lng}" else ""
        val box = if (bounded && here != null) "&bounded=1&viewbox=${viewBox(here, 0.8)}" else ""
        val connection = (URL("https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&extratags=1&limit=5&q=$encoded$countryParam$bias$box").openConnection() as HttpURLConnection)
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.setRequestProperty("User-Agent", "Vella/1.0 (personal calendar assistant)")
        return try {
            if (connection.responseCode !in 200..299) return emptyList()
            connection.inputStream.bufferedReader().use { reader ->
                val rows = JSONArray(reader.readText())
                (0 until rows.length()).map { index ->
                    val row = rows.getJSONObject(index)
                    val address = row.optJSONObject("address")
                    Hit(
                        row.optString("display_name"),
                        row.optJSONObject("extratags")?.optString("phone").orEmpty(),
                        row.optString("lat").toDoubleOrNull(),
                        row.optString("lon").toDoubleOrNull(),
                        address?.optString("country_code")?.uppercase().orEmpty(),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun viewBox(here: Here, span: Double): String {
        val lngPad = span / cos(Math.toRadians(here.lat)).coerceAtLeast(0.2)
        val left = here.lng - lngPad
        val top = here.lat + span
        val right = here.lng + lngPad
        val bottom = here.lat - span
        return "$left,$top,$right,$bottom"
    }
}
