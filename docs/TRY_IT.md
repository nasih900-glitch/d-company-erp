# Try D Company ERP — for non-developers

Three options, from "see it in 30 seconds" to "real app on your dock." Pick the one that matches what you want today.

---

## Option 1 — Preview the app right now (no install)

Already done for you. There's a folder at the top of the project called **`preview-app`**.

### Mac
1. Open the `preview-app` folder in Finder.
2. **Double-click** `Open D Company ERP (Mac).command`.
3. First time only: Mac will warn that the file is "from an unidentified developer." Fix it:
   - Right-click the file → **Open** → click **Open** in the dialog.
   - (Or: System Settings → Privacy & Security → click **Open Anyway**.)
4. A Terminal window pops up. Your browser opens at `http://localhost:8765`. **That's the app.**
5. Close the Terminal window to stop.

### Windows
1. Open the `preview-app` folder in File Explorer.
2. **Double-click** `Open D Company ERP (Windows).bat`.
3. A black command window pops up. Your browser opens at `http://localhost:8765`.
4. Close the command window to stop.

**What you'll see**: the full UI, in demo mode. POS cart works, you can click around every screen. Nothing saves — refresh the page and you're back to a clean slate. The yellow "Demo mode" tag in the sidebar reminds you.

> Doesn't work? Install Python 3 from [python.org](https://www.python.org/downloads/) (tick "Add to PATH" on Windows). Python is preinstalled on every Mac.

---

## Option 2 — Real Mac app you install like any other (.dmg)

This gives you a real `D Company ERP.app` in your Applications folder, with its own icon on your dock. **One-time setup, ~15 minutes.** Then any future change is one command to rebuild.

### One-time setup (Mac)

Open Terminal (⌘+Space, type "Terminal", press Enter), paste:

```bash
# 1. Install Homebrew (the standard Mac package manager) if you don't have it
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# 2. Install Node.js + Rust (Tauri needs both)
brew install node
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env

# 3. Install Xcode command-line tools (Tauri needs the macOS compiler)
xcode-select --install
```

### Build the Mac app

```bash
cd "/path/to/d-company-erp/frontend"
npm install
npm run tauri:build:mac
```

When it finishes (~5 min the first time), you'll find:

```
frontend/src-tauri/target/universal-apple-darwin/release/bundle/dmg/D Company ERP_*.dmg
```

**Double-click that `.dmg`** → drag the app into Applications → launch it like any other app. ✓

On first launch, macOS Gatekeeper will warn that it's from an unidentified developer (because you haven't paid Apple $99/yr for a signing certificate). Right-click the app in Applications → **Open** → confirm. From then on it opens normally.

### Build the Windows app (.exe) — on a Windows PC

```powershell
# In PowerShell, one-time setup:
winget install OpenJS.NodeJS
winget install Rustlang.Rustup
winget install Microsoft.VisualStudio.2022.BuildTools
rustup default stable

# Then build:
cd "path\to\d-company-erp\frontend"
npm install
npm run tauri:build:win
```

Output: `frontend\src-tauri\target\x86_64-pc-windows-msvc\release\bundle\nsis\*.exe`. Double-click to install. SmartScreen will warn → "More info" → "Run anyway".

---

## Option 3 — Real phone app (.apk for Android, App Store for iPhone)

### Android (.apk)

```bash
# One-time:
brew install --cask android-studio   # then open Android Studio once to install SDK

cd frontend
npm install
npx cap add android

# Each build:
npm run cap:android:build
# APK lands at: android/app/build/outputs/apk/release/app-release.apk
```

Copy the .apk to the phone (email it to yourself, AirDrop on Android, or USB). On the phone, tap the .apk → allow "Install unknown apps" → install. Done.

### iPhone / iPad

iOS requires a Mac + Xcode + an Apple Developer Program account ($99/yr). Without the account, you can sideload to your own phone for 7 days at a time only. With the account:

```bash
# One-time:
xcode-select --install        # already done if you did Option 2
sudo gem install cocoapods

cd frontend
npm install
npx cap add ios
npx cap open ios              # opens Xcode

# In Xcode:
# 1. Click the project, set your Team (your Apple ID).
# 2. Product → Archive → Distribute App → TestFlight & App Store.
```

---

## After the preview: what do I do next?

The demo doesn't save anything because there's no backend running. To use this for real, two things have to happen:

1. **Deploy the backend** (the "server" that holds the data). Cheapest path is [`docs/CLOUD_DEPLOY.md`](CLOUD_DEPLOY.md) — about $15/month on Render. Truly free path is Oracle Cloud Always Free (forever $0).
2. **Build the installer with the cloud URL baked in.** Set `VITE_API_URL=https://api.yourdomain.com/api/v1` before `npm run tauri:build:mac` (or wherever you're building). Then every device that installs that build points at the same server.

When you're ready, ask and I'll write the truly-free deployment guide (`FREE_DEPLOY.md`) — it'll walk you from "blank Oracle account" to "live backend with all your installers pointing at it" in about an hour.

---

## Troubleshooting

**"This Mac can't open the file"** — Right-click → Open instead of double-clicking. macOS quarantines downloaded files; right-click → Open whitelists them.

**"Python is not recognized" on Windows** — You skipped the "Add to PATH" checkbox during Python install. Reinstall it from python.org and tick that box.

**The browser opens to a blank page** — Wait 2 seconds and refresh. The local server takes a moment to start.

**Port 8765 is already in use** — Edit the launcher script (open it with TextEdit / Notepad) and change `PORT=8765` to e.g. `PORT=8766`.
