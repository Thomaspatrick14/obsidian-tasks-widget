package com.thomaspatrick.obsidiantasks.sync

import android.util.Log
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object WebDavClient {

    private const val TAG = "WebDavClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Resolves the full WebDAV URL for Nextcloud.
     * If user enters baseUrl + remotePath, ensures correct /remote.php/dav/files/user/ structure.
     */
    fun resolveFullWebDavUrl(baseUrl: String, username: String, remotePath: String): String {
        val cleanBase = baseUrl.trim().trimEnd('/')
        val cleanPath = remotePath.trim().trimStart('/')
        return if (cleanBase.contains("/remote.php/dav/files/")) {
            "$cleanBase/$cleanPath"
        } else {
            "$cleanBase/remote.php/dav/files/$username/$cleanPath"
        }
    }

    /**
     * Nextcloud's own OC-ETag header survives reverse proxies. The plain ETag
     * header gets turned into a weak W/"…" tag when a proxy gzips the
     * response, and a weak tag never matches If-Match (every save would 412).
     */
    fun tagOf(headers: Headers): String? =
        (headers["OC-ETag"] ?: headers["ETag"])?.removePrefix("W/")

    /** Transport bound to one note on one server, used by [RemoteTaskStore]. */
    fun transport(baseUrl: String, username: String, password: String, remotePath: String): DavTransport {
        val url = resolveFullWebDavUrl(baseUrl, username, remotePath)
        val auth = Credentials.basic(username, password)
        return object : DavTransport {
            override fun get(ifNoneMatch: String?): DavResponse {
                val req = Request.Builder().url(url).header("Authorization", auth)
                    .header("Cache-Control", "no-cache")
                if (ifNoneMatch != null) req.header("If-None-Match", ifNoneMatch)
                client.newCall(req.get().build()).execute().use { r ->
                    val body = if (r.code in 200..299) r.body?.string() else null
                    return DavResponse(r.code, body, tagOf(r.headers))
                }
            }

            override fun put(body: String, ifMatch: String?, create: Boolean): DavResponse {
                val req = Request.Builder().url(url).header("Authorization", auth)
                if (ifMatch != null) req.header("If-Match", ifMatch)
                if (create) req.header("If-None-Match", "*")
                val rb = body.toRequestBody("text/markdown; charset=utf-8".toMediaType())
                client.newCall(req.put(rb).build()).execute().use { r ->
                    return DavResponse(r.code, null, tagOf(r.headers))
                }
            }
        }
    }

    /**
     * Tests connectivity to Nextcloud WebDAV endpoint.
     */
    fun testConnection(
        baseUrl: String,
        username: String,
        password: String,
        remotePath: String
    ): Result<String> {
        return try {
            val request = Request.Builder()
                .url(resolveFullWebDavUrl(baseUrl, username, remotePath))
                .header("Authorization", Credentials.basic(username, password))
                .head()
                .build()

            client.newCall(request).execute().use { response ->
                when (response.code) {
                    200, 204 -> Result.success("Connected successfully! File found (HTTP ${response.code}).")
                    404 -> Result.success("Connected to Nextcloud! (Note: Remote file does not exist yet).")
                    401, 403 -> Result.failure(IOException("Authentication failed (HTTP ${response.code}). Check username and App Password."))
                    else -> Result.failure(IOException("Server returned unexpected status: ${response.code} ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            Result.failure(e)
        }
    }
}
