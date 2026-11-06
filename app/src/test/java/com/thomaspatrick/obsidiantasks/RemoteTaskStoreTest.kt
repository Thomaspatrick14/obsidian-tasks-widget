package com.thomaspatrick.obsidiantasks

import com.thomaspatrick.obsidiantasks.sync.DavResponse
import com.thomaspatrick.obsidiantasks.sync.DavTransport
import com.thomaspatrick.obsidiantasks.sync.RemoteTaskStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** Fake Nextcloud: ETag preconditions, optional 423 locks, optional edit between GET and PUT. */
class FakeDav(var text: String) : DavTransport {
    var ver = 1
    var lockFor = 0
    var puts = 0
    var offline = false
    var interleave: (() -> Unit)? = null
    private fun tag() = "\"v$ver\""

    override fun get(ifNoneMatch: String?): DavResponse {
        if (offline) throw IOException("offline")
        if (lockFor > 0) { lockFor--; return DavResponse(423) }
        if (ifNoneMatch == tag()) return DavResponse(304)
        return DavResponse(200, text, tag())
    }

    override fun put(body: String, ifMatch: String?, create: Boolean): DavResponse {
        if (offline) throw IOException("offline")
        interleave?.let { interleave = null; it() }
        if (lockFor > 0) { lockFor--; return DavResponse(423) }
        if (ifMatch != null && ifMatch != tag()) return DavResponse(412)
        text = body; ver++; puts++
        return DavResponse(201, null, tag())
    }
}

class RemoteTaskStoreTest {
    private val note = "- [ ] Rajma\n- [ ] Add sysadmin to agent\n#### DONE\n- [x] Bread\n"
    private fun stateFile() = File.createTempFile("otw-state", ".json").also { it.delete() }
    private fun store(dav: FakeDav, f: File = stateFile()) = RemoteTaskStore(f, dav) { }

    @Test fun toggleUploadsAndClearsQueue() {
        val dav = FakeDav(note); val s = store(dav)
        assertTrue(s.sync())
        assertTrue(s.queue(RemoteTaskStore.TYPE_TOGGLE, 0, "- [ ] Rajma"))
        assertTrue(s.sync())
        assertEquals("- [ ] Add sysadmin to agent\n#### DONE\n- [x] Rajma\n- [x] Bread\n", dav.text)
        assertTrue(s.ops.isEmpty())
    }

    @Test fun editMadeElsewhereIsKept() {
        val dav = FakeDav(note); val s = store(dav)
        s.sync()
        s.queue(RemoteTaskStore.TYPE_ADD, -1, null, "Milk")
        dav.interleave = { dav.text = "- [ ] Eggs\n" + dav.text; dav.ver++ }   // PC saves meanwhile
        assertTrue(s.sync())
        assertEquals("- [ ] Milk\n- [ ] Eggs\n- [ ] Rajma\n- [ ] Add sysadmin to agent\n#### DONE\n- [x] Bread\n", dav.text)
    }

    @Test fun lockedIsRetried() {
        val dav = FakeDav(note); val s = store(dav)
        s.sync()
        s.queue(RemoteTaskStore.TYPE_DELETE, 1, "- [ ] Add sysadmin to agent")
        dav.lockFor = 3
        assertTrue(s.sync())
        assertFalse(dav.text.contains("sysadmin"))
    }

    @Test fun offlineChangeIsKeptAcrossRestartAndUploadedLater() {
        val dav = FakeDav(note); val f = stateFile(); val s = store(dav, f)
        s.sync()
        dav.offline = true
        s.queue(RemoteTaskStore.TYPE_RENAME, 0, "- [ ] Rajma", "Rajma chawal")
        assertFalse(s.sync())
        assertEquals(0, dav.puts)
        val again = store(dav, f)                       // app restarted
        assertEquals("- [ ] Rajma chawal", again.viewLines()!![0])
        dav.offline = false
        assertTrue(again.sync())
        assertTrue(dav.text.startsWith("- [ ] Rajma chawal\n"))
    }

    @Test fun staleTapIsRejected() {
        val dav = FakeDav(note); val s = store(dav)
        s.sync()
        assertFalse(s.queue(RemoteTaskStore.TYPE_TOGGLE, 0, "- [ ] Something else"))
    }

    @Test fun alreadyTickedElsewhereIsDropped() {
        val dav = FakeDav(note); val s = store(dav)
        s.sync()
        s.queue(RemoteTaskStore.TYPE_TOGGLE, 0, "- [ ] Rajma")
        dav.text = "- [ ] Add sysadmin to agent\n#### DONE\n- [x] Rajma\n- [x] Bread\n"; dav.ver++
        assertTrue(s.sync())
        assertEquals(0, dav.puts)
        assertTrue(s.ops.isEmpty())
        assertNull(s.error)
    }

    @Test fun crlfIsPreserved() {
        val dav = FakeDav(note.replace("\n", "\r\n")); val s = store(dav)
        s.sync()
        s.queue(RemoteTaskStore.TYPE_ADD, -1, null, "Milk")
        s.sync()
        assertTrue(dav.text.startsWith("- [ ] Milk\r\n- [ ] Rajma\r\n"))
        assertTrue(dav.text.endsWith("- [x] Bread\r\n"))
    }
}
