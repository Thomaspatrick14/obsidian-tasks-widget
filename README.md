# Obsidian Tasks (Clean Dark Theme Android Widget)

A clean, minimalist dark theme Android widget for your local **Obsidian** markdown vault. Check or uncheck to-dos right from your home screen without opening Obsidian, with built-in background synchronization to your **Nextcloud** cloud instance via WebDAV.

**Author:** [Thomaspatrick14](https://github.com/Thomaspatrick14)  
**Repository:** [https://github.com/Thomaspatrick14/obsidian-tasks-widget](https://github.com/Thomaspatrick14/obsidian-tasks-widget)

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

The latest APK is named:
- **`ObsidianTasks.apk`**

Install via ADB:
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

Build from WSL/Linux:
```bash
cd desktop && npm install && npm test && npm run dist:win
```
Then copy `desktop/dist/win-unpacked/` to e.g. `%LOCALAPPDATA%\Programs\Obsidian Tasks\` and run `Obsidian Tasks.exe`.
