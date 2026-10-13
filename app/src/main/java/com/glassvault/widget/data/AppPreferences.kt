package com.glassvault.widget.data

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var fileUriString: String?
        get() = prefs.getString(KEY_FILE_URI, null)
        set(value) = prefs.edit().putString(KEY_FILE_URI, value).apply()

    var fileName: String
        get() = prefs.getString(KEY_FILE_NAME, "Obsidian Tasks") ?: "Obsidian Tasks"
        set(value) = prefs.edit().putString(KEY_FILE_NAME, value).apply()

    var showCompleted: Boolean
        get() = prefs.getBoolean(KEY_SHOW_COMPLETED, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_COMPLETED, value).apply()

    var isDoneExpanded: Boolean
        get() = prefs.getBoolean(KEY_DONE_EXPANDED, false)
        set(value) = prefs.edit().putBoolean(KEY_DONE_EXPANDED, value).apply()

    // Nextcloud WebDAV Cloud Sync Settings
    var isCloudSyncEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLOUD_SYNC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_CLOUD_SYNC_ENABLED, value).apply()

    var nextcloudUrl: String
        get() = prefs.getString(KEY_NEXTCLOUD_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NEXTCLOUD_URL, value).apply()

    var nextcloudUsername: String
        get() = prefs.getString(KEY_NEXTCLOUD_USER, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NEXTCLOUD_USER, value).apply()

    var nextcloudPassword: String
        get() = prefs.getString(KEY_NEXTCLOUD_PASS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NEXTCLOUD_PASS, value).apply()

    var nextcloudRemotePath: String
        get() = prefs.getString(KEY_NEXTCLOUD_PATH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_NEXTCLOUD_PATH, value).apply()

    var lastSyncTimestamp: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_TIME, value).apply()

    var lastSyncStatus: String
        get() = prefs.getString(KEY_LAST_SYNC_STATUS, "Never synced") ?: "Never synced"
        set(value) = prefs.edit().putString(KEY_LAST_SYNC_STATUS, value).apply()

    var lastKnownEtag: String?
        get() = prefs.getString(KEY_LAST_ETAG, null)
        set(value) = prefs.edit().putString(KEY_LAST_ETAG, value).apply()

    companion object {
        private const val PREFS_NAME = "glass_tasks_prefs"
        private const val KEY_FILE_URI = "key_file_uri"
        private const val KEY_FILE_NAME = "key_file_name"
        private const val KEY_SHOW_COMPLETED = "key_show_completed"
        private const val KEY_DONE_EXPANDED = "key_done_expanded"
        private const val KEY_CLOUD_SYNC_ENABLED = "key_cloud_sync_enabled"
        private const val KEY_NEXTCLOUD_URL = "key_nextcloud_url"
        private const val KEY_NEXTCLOUD_USER = "key_nextcloud_user"
        private const val KEY_NEXTCLOUD_PASS = "key_nextcloud_pass"
        private const val KEY_NEXTCLOUD_PATH = "key_nextcloud_path"
        private const val KEY_LAST_SYNC_TIME = "key_last_sync_time"
        private const val KEY_LAST_SYNC_STATUS = "key_last_sync_status"
        private const val KEY_LAST_ETAG = "key_last_etag"
    }
}
