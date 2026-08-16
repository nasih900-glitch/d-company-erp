# Putting the D Company app on Google Play (internal testing)

Internal testing gives the tablet **automatic, silent updates** without any
public listing and without a store review. Up to 100 testers, invited by email.

You do all of this yourself — it needs your Google account and a card.

---

## Before you start

- **Tell Rafi NOT to install any APK yet.** See "The signing key" below; installing
  first can force an uninstall later, which would delete any sale still sitting
  in the tablet's offline queue.
- Have the file `d-company-native-3.0.aab` ready (the `.aab`, **not** the `.apk`).
- Card for the one-time **$25** fee.
- Google will ask you to verify your identity with a photo ID. Allow a day or
  two for that to clear before you need the app live.

---

## 1. Create the developer account

1. Go to **play.google.com/console**
2. Sign in with the Google account you want to own this forever. Use one you
   control personally — moving an app between accounts later is painful.
3. Choose **Personal** (not Organization). Organization needs a D-U-N-S number,
   which you do not have and do not need here.
4. Pay the **$25** one-time fee.
5. Complete identity verification.

---

## 2. Create the app

**All apps → Create app**

| Field | Value |
| --- | --- |
| App name | `D Company` |
| Default language | English (India) or English (UK) |
| App or game | App |
| Free or paid | Free |

Tick the declarations, then **Create app**.

---

## 3. The signing key — read this carefully

Android identifies an app by `(package name, signing key)`. Your package name
is `cloud.dcompany.erp` and it is already used by the Capacitor app and the
sideloaded APK, both signed with your own key.

If Play signs with a *different* key, Play's version **cannot install over** an
existing one. The device would need an uninstall first, which wipes the local
database — including sales captured offline that have not synced.

Pick the path that matches reality:

### Path A — no Android device currently has the app installed (simplest)

Let Play generate and manage a new signing key. Nothing to upload. Accept the
default when Play offers Play App Signing.

Choose this if the Redmi Pad has never had either APK installed.

### Path B — the Capacitor app or the APK is already on a device

Upload your existing key so Play keeps signing with it and upgrades work.

Play calls this **"Export and upload a key from Java keystore"** and gives you a
`pepk.jar` tool plus an encryption key that is unique to your app. You run one
command against:

```
keystore : android-native/dcompany-release.keystore
alias    : dcompany
```

Passwords are in `android-native/keystore.properties`.

When you reach that screen, send me the encryption key Play shows you and I will
give you the exact command to run.

---

## 4. Fill in "App content"

Play will not let you release until these are done. Left menu → **App content**:

| Section | What to say |
| --- | --- |
| Privacy policy | `https://dcompany.duckdns.org/privacy.html` |
| App access | Not all features are public — provide a login. Give a staff account so Google can get in if they ever look. |
| Ads | No ads |
| Content rating | Fill the questionnaire — it is a business/utility app |
| Target audience | 18+ |
| Data safety | You collect staff email + name, and customer name/phone. Say so honestly; it is stored on your own server and not shared or sold. |
| Government apps | No |

---

## 5. Upload the build

1. Left menu → **Testing → Internal testing**
2. **Create new release**
3. Upload `d-company-native-3.0.aab`
4. Release name: `3.0 (1)`
5. Release notes: e.g. "First native build. Offline POS, gaming timers, shift close."
6. **Next → Save → Review release → Start rollout to Internal testing**

---

## 6. Add Rafi as a tester

1. Still under **Internal testing**, open the **Testers** tab
2. **Create email list** → name it "D Company staff" → add Rafi's **Gmail**
   address (it must be a Google account; a non-Google email will not work)
3. Save
4. Copy the **"Copy link"** opt-in URL at the bottom

---

## 7. What Rafi does — once

1. Open the opt-in link on the Redmi Pad, signed in as that Gmail account
2. Tap **Accept the invitation**
3. Tap **Download it on Google Play** → Install

That is the last time he has to do anything. **Every future update installs
automatically**, the same as any other app on the tablet.

---

## Shipping an update afterwards

1. I bump `versionCode` (Play rejects any upload that is not higher than the
   last) and build a new `.aab`
2. You upload it to Internal testing and start rollout
3. The tablet updates itself, usually within a few hours

---

## Do not lose the keystore

`android-native/dcompany-release.keystore` and its passwords are the only thing
that can ever update this app on a device that installed it outside Play. If it
is lost, the app must be uninstalled and reinstalled, and the tablet's offline
database goes with it. Keep a copy somewhere that is not just this Mac.
