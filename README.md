# Obsidian Tasks (Clean Dark Theme Android Widget)

A clean, minimalist dark theme Android widget for your local **Obsidian** markdown vault. Check or uncheck to-dos right from your home screen without opening Obsidian, with built-in background synchronization to your **Nextcloud** cloud instance via WebDAV.

**Author:** [Thomaspatrick14](https://github.com/Thomaspatrick14)  
**Repository:** [https://github.com/Thomaspatrick14/obsidian-tasks-widget](https://github.com/Thomaspatrick14/obsidian-tasks-widget)

---

## 🆕 What's New in v1.1.0

**Fixes the lock and conflict problems with Obsidian's Remotely Save plugin, and adds a Windows desktop app.**

### The problem (v1.0.x)
When the widget ran alongside Remotely Save (Obsidian on the PC and on Android), the same `To do.md` was being written by several programs that didn't know about each other:
- The 15-minute background job had no mode set, so it fell back to **uploading**. It blindly pushed the phone's copy to Nextcloud and could undo edits made on the PC.
- Every tap wrote **both** the phone's vault file and Nextcloud, so Remotely Save saw both copies change and treated it as a conflict.
- The sync button wrote the downloaded copy straight into the vault file, causing another conflict.
- Each new tap cancelled any upload still in progress, and overlapping writes produced `423 Locked` errors.

### The fix
- **One writer per copy.** With Nextcloud on, the widget reads and writes **only the Nextcloud copy**. It never touches the vault file, so Remotely Save sees a plain "changed on the server" download and has nothing to conflict with.
- **Uploads can't overwrite newer saves.** Every upload is conditional (`If-Match` with Nextcloud's `OC-ETag`; the plain `ETag` gets weakened by reverse proxies and never matches). If anyone saved in between, Nextcloud refuses it. The widget re-downloads, re-applies your change and tries again.
- **Changes are queued.** A change is stored as an operation ("tick *Bread*"), not a copy of the whole file, so it survives being offline, restarts and `423 Locked`. The header shows `· N to upload` while changes are waiting.
- **No more blind uploads.** The 15-minute job only downloads the latest copy and uploads queued changes, and it never cancels an upload in progress.
- **Stale taps are rejected.** If the note changed under a task you tapped, the tap is ignored and the list refreshes, instead of editing the wrong line.

> After updating, check that **Remote path** in the widget settings points at the same file Remotely Save syncs, e.g. `JP's Obsidian vault/To do.md`. The old default suggested `/Obsidian/...`.

### New: Windows desktop app
The same widget as a small desktop app for Windows. See **Desktop App (Windows)** below.

---

## ✨ Features

1. **Smart Task Placement**:
   - New tasks added via the widget `+` button are automatically placed at the **very top** of your active to-do list (right under the `To do` header).
   - When a task is checked on the widget, it is automatically moved to the **top of the checked list** (right below `DONE`).
   - If an item under `DONE` is unchecked, it moves right back to the top of the active tasks.
2. **Collapsible "Done" Section**:
   - Active to-dos are shown cleanly at the top.
   - Completed tasks are grouped underneath a minimized subtitle header: `Done (N) ▸`.
   - Tap on **Done (N)** to instantly expand and show all completed tasks. Tap again to collapse.
3. **Clean Minimalist Dark Theme**:
   - Clean matte dark background (`#141417`), subtle borders (`#27272A`), and crisp typography.
   - Minimal circular checkboxes (subtle gray ring when unchecked, clean blue circle with white check when checked).
   - Tapping the checkbox toggles the task; tapping the task text opens the edit/delete dialog.
4. **No App Drawer Clutter (Widget-Only)**:
   - Does **not** appear in your launcher/app drawer menu.
   - Only appears in the **Home Screen Widget Picker** and in **Android Settings -> Apps**.
   - Completely excluded from Android's Recents / Overview tab (`excludeFromRecents="true"`).
   - Configure anytime directly by tapping the settings gear `⚙` icon on the widget header.
5. **Clean, Aligned Quick Add & Edit Dialogs**:
   - Tap `+` on the widget to pop up a clean, properly aligned dark dialog with auto-focus keyboard to insert new tasks directly to your Obsidian note.
6. **Conflict-free Nextcloud sync (works alongside Obsidian's Remotely Save)**:
   - With Nextcloud on, the widget reads and writes **only the Nextcloud copy** of the note. It never writes the note in your phone's vault, so Remotely Save just sees "changed on the server" and downloads it, with no conflict.
   - Every upload is conditional (`If-Match` with Nextcloud's `OC-ETag`). If the PC or phone saved in between, Nextcloud refuses the upload. The widget re-downloads, re-applies your change and tries again, so nothing is overwritten.
   - Changes are queued as operations ("tick *Bread*"), so they survive being offline and `423 Locked` replies, and upload when possible. The header shows `· N to upload` while changes are waiting.
   - Background pull every 15 minutes, plus one when you tap sync. With Nextcloud off, the widget edits the local note directly, as before.

---

## 📦 How to Install

Download from the [latest release](https://github.com/Thomaspatrick14/obsidian-tasks-widget/releases/latest):
- **Android:** `ObsidianTasks.apk`. Installs as an update over v1.0.x and keeps your settings.
- **Windows:** `ObsidianTasks-1.1.0-portable.exe`. A single file with no installer: put it anywhere and double-click it. It isn't code-signed, so on first run Windows SmartScreen may warn you: click **More info → Run anyway**.

Install the APK via ADB:
```bash
adb install -r ObsidianTasks.apk
```
Or download the APK to your phone and tap to install/update.

---

## ⚙️ How to Add to Home Screen

1. Long-press on your phone's Home Screen $\to$ **Widgets** $\to$ choose **Obsidian Tasks**.
2. Tap **Choose Markdown File** and select your note (e.g. `To do.md`).
3. (Optional) Enter your Nextcloud server URL and App Password for automatic background cloud sync.
4. Tap **Apply to Widget**.

---

## 👤 Author
Developed by **[Thomaspatrick14](https://github.com/Thomaspatrick14)**

---

## 🖥️ Desktop App (Windows)

The `desktop/` folder has the same widget as a small Windows desktop app (Electron).

- Frameless, rounded, draggable widget that stays off the taskbar. Its position and size are remembered.
- Tray icon: show/hide, **Always on top**, **Start with Windows**, quit.
- Click the circle to check/uncheck. Click the text to edit it inline (Enter saves, Esc cancels). Hover a task and click ✕ to delete it.
- `+` (or `Ctrl+N`) adds tasks to the top of the list. Markdown links and `[[wikilinks]]` are clickable.
- **Sync through Nextcloud** (settings ⚙): same conflict-free sync as the Android widget. It writes only the Nextcloud copy, with conditional uploads and a queue of changes. **Import from Remotely Save** copies the WebDAV settings from the vault's Remotely Save plugin. The app password is stored encrypted with Windows DPAPI.
- Without Nextcloud, it edits the local note directly and reloads when the file changes.

**Setting up Nextcloud sync:** ⚙ → turn on **Sync through Nextcloud** → **Import from Remotely Save** (or fill in server URL, username, app password and remote path) → **Test** → **Save**. The status line at the bottom shows the last sync time, or how many changes are waiting.

Build from WSL/Linux:
```bash
cd desktop && npm install && npm test
npm run dist:portable   # single-file dist/ObsidianTasks-<version>-portable.exe
npm run dist:win        # unpacked folder dist/win-unpacked/
```
