package com.example.sparklog.sync

import com.example.sparklog.data.Prefs
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One article in the retailer price list kept on the server. [net] is your price per [unit], ex VAT. */
data class CatalogItem(
    val eNumber: String,
    val name: String,
    val unit: String,
    val perPack: Double,
    val net: Double,
    val metalSurcharge: Boolean,
    val supplier: String,
)

/** Searches the price list on the sync server (see server/catalog.py). Needs network and a configured server. */
object CatalogClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Blocking; call from a background dispatcher. Throws with a readable message on failure. */
    fun search(prefs: Prefs, query: String, limit: Int = 30): List<CatalogItem> {
        if (prefs.serverUrl.isBlank() || prefs.token.isBlank()) error("Set the server URL and token in Settings first")
        val url = (prefs.serverUrl.trimEnd('/') + "/catalog/search").toHttpUrlOrNull()
            ?.newBuilder()?.addQueryParameter("q", query)?.addQueryParameter("limit", limit.toString())?.build()
            ?: error("The server URL in Settings is not valid")
        val req = Request.Builder().url(url).header("Authorization", "Bearer " + prefs.token).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) error("No price list on the server yet")
            if (!resp.isSuccessful) error("Server returned ${resp.code}")
            val items = JSONObject(resp.body!!.string()).getJSONArray("items")
            return (0 until items.length()).map { i ->
                val o = items.getJSONObject(i)
                CatalogItem(
                    eNumber = o.getString("eNumber"), name = o.getString("name"), unit = o.optString("unit", "pcs"),
                    perPack = o.optDouble("perPack", 1.0), net = o.optDouble("net", 0.0),
                    metalSurcharge = o.optBoolean("metalSurcharge", false), supplier = o.optString("supplier", ""),
                )
            }
        }
    }
}
