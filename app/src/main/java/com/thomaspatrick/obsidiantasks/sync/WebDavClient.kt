package com.thomaspatrick.obsidiantasks.sync

import android.util.Log
import okhttp3.Credentials
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

        // If the user already provided the full URL with remote.php/dav/files
        return if (cleanBase.contains("/remote.php/dav/files/")) {
            "$cleanBase/$cleanPath"
        } else {
            "$cleanBase/remote.php/dav/files/$username/$cleanPath"
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
            val fullUrl = resolveFullWebDavUrl(baseUrl, username, remotePath)
            val credential = Credentials.basic(username, password)

            // Issue a HEAD request to verify endpoint and authentication
            val request = Request.Builder()
                .url(fullUrl)
                .header("Authorization", credential)
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

    /**
     * Uploads markdown content to Nextcloud WebDAV via HTTP PUT.
     */
    fun uploadFile(
        baseUrl: String,
        username: String,
        password: String,
        remotePath: String,
        content: String
    ): Result<String> {
        return try {
            val fullUrl = resolveFullWebDavUrl(baseUrl, username, remotePath)
            val credential = Credentials.basic(username, password)
            val mediaType = "text/markdown; charset=utf-8".toMediaType()
            val body = content.toRequestBody(mediaType)

            val request = Request.Builder()
                .url(fullUrl)
                .header("Authorization", credential)
                .put(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val etag = response.header("ETag") ?: ""
                    Log.d(TAG, "Uploaded successfully to $fullUrl (ETag: $etag)")
                    Result.success(etag)
                } else {
                    Result.failure(IOException("Upload failed with HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            Result.failure(e)
        }
    }

    /**
     * Downloads markdown content from Nextcloud WebDAV via HTTP GET.
     */
    fun downloadFile(
        baseUrl: String,
        username: String,
        password: String,
        remotePath: String
    ): Result<String> {
        return try {
            val fullUrl = resolveFullWebDavUrl(baseUrl, username, remotePath)
            val credential = Credentials.basic(username, password)

            val request = Request.Builder()
                .url(fullUrl)
                .header("Authorization", credential)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyString = response.body?.string() ?: ""
                    Result.success(bodyString)
                } else {
                    Result.failure(IOException("Download failed with HTTP ${response.code}: ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            Result.failure(e)
        }
    }
}
