# App icons

Tauri needs these icon files. Generate them all from a single source PNG (1024×1024) with the Tauri CLI:

```bash
npx @tauri-apps/cli icon ./source-icon.png
```

This emits:
- `32x32.png`
- `128x128.png`
- `128x128@2x.png`
- `icon.ico`  (Windows)
- `icon.icns` (macOS)
- Android + iOS sizes (used by Capacitor too, but Capacitor generates its own)

Until you replace the source icon, the build will fail. Place a 1024×1024 PNG of the D Company logo at `frontend/src-tauri/source-icon.png` and run the command above.
