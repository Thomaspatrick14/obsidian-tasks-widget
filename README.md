# Obsidian Tasks (Clean Dark Theme Android Widget)

A clean, minimalist dark theme Android widget for your local **Obsidian** markdown vault. Check or uncheck to-dos right from your home screen without opening Obsidian, with built-in background synchronization to your **Nextcloud** cloud instance via WebDAV.

---

## ✨ Features

1. **Collapsible "Done" Section**:
   - Active to-dos are shown cleanly at the top.
   - Completed tasks are grouped underneath a minimized subtitle header: `Done (N) ▸`.
   - Tap on **Done (N)** to instantly expand and show all completed tasks. Tap again to collapse.
2. **Clean Minimalist Dark Theme**:
   - Clean matte dark background (`#141417`), subtle borders (`#27272A`), and crisp typography.
   - Minimal circular checkboxes (subtle gray ring when unchecked, clean blue circle with white check when checked).
3. **No App Drawer Clutter (Widget-Only)**:
   - Does **not** appear in your launcher/app drawer menu.
   - Only appears in the **Home Screen Widget Picker** and in **Android Settings -> Apps**.
   - Configure anytime directly by tapping the settings gear `⚙` icon on the widget header.
4. **Clean, Perfectly Aligned Quick Add Dialog**:
   - Tap `+` on the widget to pop up a clean, properly aligned dark dialog with auto-focus keyboard to append new tasks directly to your Obsidian note.
5. **Built-in Nextcloud WebDAV Background Sync**:
   - Solves the limitation where Obsidian mobile plugins cannot run in the background.
   - Pushes task checkbox changes directly to Nextcloud in the background via Android `WorkManager`.

---

## 📦 How to Install

The latest APK is assembled at:
- **[`GlassTasks-debug.apk`](file:///home/thomaspatrick/projects/Random%20Projects/LiquidTodoWidget/GlassTasks-debug.apk)**

Install via ADB:
```bash
adb install -r "LiquidTodoWidget/GlassTasks-debug.apk"
```
Or transfer the APK to your phone and tap to install/update.

---

## ⚙️ How to Add to Home Screen

1. Long-press on your phone's Home Screen $\to$ **Widgets** $\to$ choose **Obsidian Tasks**.
2. Tap **Choose Markdown File** and select your note (e.g. `To do.md`).
3. (Optional) Enter your Nextcloud server URL and App Password for automatic background cloud sync.
4. Tap **Apply to Widget**.
