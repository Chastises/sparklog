package com.example.sparklog.sync

import com.example.sparklog.data.Prefs
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Development only: asks the server to back up and empty its database. */
object ServerReset {
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    /** Blocking. Returns the name of the backup file the server made; throws with a readable message. */
    fun run(prefs: Prefs): String {
        val url = (prefs.serverUrl.trimEnd('/') + "/admin/reset").toHttpUrlOrNull() ?: error("The server URL is not valid")
        val body = JSONObject().put("confirm", "RESET").toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).header("Authorization", "Bearer " + prefs.token).post(body).build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) error("The server is too old for reset. Update the server first.")
            if (resp.code == 401) error("The server rejected the token")
            if (!resp.isSuccessful) error("Server returned ${resp.code}")
            return JSONObject(resp.body!!.string()).optString("backup")
        }
    }
}
