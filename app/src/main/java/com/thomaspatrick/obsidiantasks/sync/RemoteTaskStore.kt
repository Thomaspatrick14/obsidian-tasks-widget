package com.thomaspatrick.obsidiantasks.sync

import com.thomaspatrick.obsidiantasks.data.MarkdownTaskManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Nextcloud mode. Rules that stop the widget fighting Obsidian's Remotely Save plugin:
 *
 * 1. The widget only writes the Nextcloud copy. It never writes the note in the
 *    phone's vault; Remotely Save pulls the change down on its next run as a
 *    plain "remote changed" download, so there is nothing to conflict with.
 * 2. Every upload is conditional (If-Match: last OC-ETag we saw). If anyone
 *    saved in between, Nextcloud answers 412, we download the newer version
 *    and re-apply our pending changes to it. Nothing is overwritten.
 * 3. Changes are queued as operations ("tick task 'Bread'"), not file
 *    snapshots, so they survive being re-applied, being offline, and
 *    "423 Locked" while another client is mid-upload.
 */
class RemoteTaskStore(
    private val stateFile: File,
    private val transport: DavTransport,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {

    data class Op(
        val id: String,
        val type: String,          // toggle | add | rename | delete
        val title: String,
        val done: Boolean = false,
        val newTitle: String? = null
    )

    var base: String? = null; private set
    var etag: String? = null; private set
    val ops = mutableListOf<Op>()
    var lastSync: Long = 0; private set
    var error: String? = null; private set

    init { load() }

    // ---- what the widget shows: last server copy + changes not uploaded yet ----

    @Synchronized
    fun viewLines(): List<String>? {
        val text = base ?: return null
        return applyOps(MarkdownTaskManager.splitText(text).first, ops)
    }

    /** Queue a change made on the widget. Returns false if the task is no longer where the user tapped. */
    @Synchronized
    fun queue(type: String, lineIndex: Int, expectedLine: String?, newTitle: String? = null): Boolean {
        val lines = viewLines() ?: return false
        val op: Op = if (type == TYPE_ADD) {
            Op(newId(), TYPE_ADD, newTitle?.trim().orEmpty())
        } else {
            val line = lines.getOrNull(lineIndex) ?: return false
            if (expectedLine != null && line != expectedLine) return false
            val m = MarkdownTaskManager.TASK_PATTERN.matchEntire(line) ?: return false
            Op(newId(), type, m.groupValues[4].trim(), m.groupValues[3].equals("x", true), newTitle?.trim())
        }
        if (applyOp(lines, op) == null) return false
        ops.add(op)
        save()
        return true
    }

    // ---- sync (blocking; call from a background thread / Worker) ----

    @Synchronized
    fun sync(): Boolean {
        return try {
            syncOnce()
            error = null
            lastSync = System.currentTimeMillis()
            true
        } catch (e: DavException) {
            error = e.message
            false
        } catch (e: IOException) {
            error = "Can't reach Nextcloud (${e.message ?: e.javaClass.simpleName})."
            false
        } finally {
            save()
        }
    }

    private fun syncOnce() {
        val backoff = longArrayOf(500, 1000, 2000, 4000, 8000)
        var attempt = 0
        while (attempt <= backoff.size) {
            val remote = transport.get(if (base != null) etag else null)
            when {
                remote.status == 304 -> Unit
                remote.status == 404 -> { base = ""; etag = null }
                remote.status == 423 && attempt < backoff.size -> { sleeper(backoff[attempt++]); continue }
                remote.status in 200..299 -> { base = remote.body ?: ""; etag = remote.etag }
                else -> throw DavException(describe(remote.status))
            }
            val exists = remote.status != 404

            val snapshot = ops.toList()
            if (snapshot.isEmpty()) return

            val (lines, shape) = MarkdownTaskManager.splitText(base!!)
            val next = MarkdownTaskManager.joinText(applyOps(lines, snapshot), shape)
            val doneIds = snapshot.map { it.id }.toSet()
            if (next == base) {            // already applied elsewhere / no-ops
                ops.removeAll { it.id in doneIds }
                return
            }

            val put = transport.put(next, if (exists) etag else null, !exists)
            when {
                put.status in 200..299 -> {
                    base = next
                    etag = put.etag     // null forces a full GET next time
                    ops.removeAll { it.id in doneIds }
                    if (ops.isEmpty()) return
                    attempt = 0
                }
                put.status == 412 -> attempt++          // someone saved first: re-read, re-apply
                put.status == 423 && attempt < backoff.size -> sleeper(backoff[attempt++])
                else -> throw DavException(describe(put.status))
            }
        }
        throw DavException("Couldn't save to Nextcloud yet (busy or locked). Your changes are kept and will retry.")
    }

    // ---- persistence ----

    private fun load() {
        try {
            if (!stateFile.exists()) return
            val j = JSONObject(stateFile.readText())
            base = if (j.isNull("base")) null else j.getString("base")
            etag = if (j.isNull("etag")) null else j.getString("etag")
            lastSync = j.optLong("lastSync", 0)
            error = if (j.isNull("error")) null else j.optString("error")
            val arr = j.optJSONArray("ops") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                ops.add(
                    Op(
                        o.getString("id"), o.getString("type"), o.getString("title"),
                        o.optBoolean("done"), if (o.isNull("newTitle")) null else o.optString("newTitle")
                    )
                )
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun save() {
        val arr = JSONArray()
        ops.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("type", it.type).put("title", it.title)
                    .put("done", it.done).put("newTitle", it.newTitle ?: JSONObject.NULL)
            )
        }
        val j = JSONObject()
            .put("base", base ?: JSONObject.NULL)
            .put("etag", etag ?: JSONObject.NULL)
            .put("lastSync", lastSync)
            .put("error", error ?: JSONObject.NULL)
            .put("ops", arr)
        val tmp = File(stateFile.path + ".tmp")
        tmp.writeText(j.toString())
        java.nio.file.Files.move(
            tmp.toPath(), stateFile.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE
        )
    }

    companion object {
        const val TYPE_TOGGLE = "toggle"
        const val TYPE_ADD = "add"
        const val TYPE_RENAME = "rename"
        const val TYPE_DELETE = "delete"

        private fun newId() = "${System.currentTimeMillis()}-${(Math.random() * 1e9).toLong()}"

        private fun findTask(lines: List<String>, title: String, done: Boolean): Int =
            lines.indexOfFirst {
                val m = MarkdownTaskManager.TASK_PATTERN.matchEntire(it)
                m != null && m.groupValues[4].trim() == title && m.groupValues[3].equals("x", true) == done
            }

        /** Null when the op no longer applies (task gone or already in that state). */
        fun applyOp(lines: List<String>, op: Op): List<String>? {
            if (op.type == TYPE_ADD) return MarkdownTaskManager.addLines(lines, op.title)
            val i = findTask(lines, op.title, op.done)
            if (i == -1) return null
            return when (op.type) {
                TYPE_TOGGLE -> MarkdownTaskManager.toggleLines(lines, i)
                TYPE_RENAME -> MarkdownTaskManager.renameLines(lines, i, op.newTitle ?: return null)
                TYPE_DELETE -> MarkdownTaskManager.deleteLines(lines, i)
                else -> null
            }
        }

        fun applyOps(lines: List<String>, ops: List<Op>): List<String> =
            ops.fold(lines) { acc, op -> applyOp(acc, op) ?: acc }

        fun describe(status: Int) = when (status) {
            401, 403 -> "Nextcloud login failed. Check username and app password."
            423 -> "Nextcloud says the note is locked. Will retry."
            404 -> "Note not found on Nextcloud. Check the remote path."
            else -> "Nextcloud answered HTTP $status."
        }
    }
}

class DavException(message: String) : IOException(message)

data class DavResponse(val status: Int, val body: String? = null, val etag: String? = null)

interface DavTransport {
    /** GET; send If-None-Match when [ifNoneMatch] is set (304 = unchanged). */
    fun get(ifNoneMatch: String?): DavResponse
    /** PUT; If-Match when [ifMatch] is set, If-None-Match: * when [create]. */
    fun put(body: String, ifMatch: String?, create: Boolean): DavResponse
}
