# Desktop builds (Tauri)

Tauri wraps the same React app into native macOS and Windows apps. Smaller and faster than Electron because it uses the OS's built-in WebView instead of bundling Chromium.

## Prerequisites (developer machine)

- **Rust toolchain**: `curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh`
- **macOS**: Xcode Command Line Tools (`xcode-select --install`)
- **Windows**: Microsoft C++ Build Tools (installed via Visual Studio Installer) + WebView2 (preinstalled on Windows 10/11)
- **Linux**: `webkit2gtk-4.1`, `libssl-dev`, `librsvg2-dev`, `libsoup-3.0-dev`, `libayatana-appindicator3-dev`

## Dev (live-reload native window)

```bash
cd frontend
npm install
npm run tauri:dev
```

This opens a real macOS/Windows window pointing at `http://localhost:5173`. Hot reload works exactly like the browser.

## Production build

```bash
# 1. Set the production API URL
export VITE_API_URL=https://api.dcompany.cloud/api/v1
export VITE_APP_VERSION=$(git describe --tags --always)

# 2a. macOS — universal binary (Intel + Apple Silicon)
rustup target add aarch64-apple-darwin x86_64-apple-darwin
npm run tauri:build:mac
#   Output: src-tauri/target/universal-apple-darwin/release/bundle/dmg/D Company ERP_*.dmg

# 2b. Windows
rustup target add x86_64-pc-windows-msvc
npm run tauri:build:win
#   Output: src-tauri/target/x86_64-pc-windows-msvc/release/bundle/{nsis,msi}/*
```

## Code signing

**macOS**: Without a Developer ID Application certificate, the `.dmg` will trigger Gatekeeper warnings. Get one from your Apple Developer account (paid), then set in `tauri.conf.json`:
```json
"macOS": { "signingIdentity": "Developer ID Application: D Company (TEAMID)" }
```

**Windows**: Without a code-signing cert, SmartScreen will warn users. Options:
- **Azure Trusted Signing** — Microsoft's new service, ~$10/mo. Easiest path now.
- **EV Code Signing certificate from DigiCert / Sectigo** — ~$300/yr, eliminates SmartScreen immediately.
- **OV (Organization Validation) Code Signing** — ~$100/yr, builds reputation over time.

See `docs/DISTRIBUTION.md` for the full publishing playbook.
