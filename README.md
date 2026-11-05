# AC Control

A sleek, futuristic remote for Panasonic **MirAIe** air conditioners (built for CS/CU-SU18BKY3W).

## Install on your phone
1. Open the **Releases** page of this repo (right side, or `/releases`).
2. Tap **AC-Control.apk** under the newest build.
3. Open the download → allow installs from your browser once → **Install**.
4. Sign in with your MirAIe email/mobile and password.

## What it controls
Power · temperature dial · mode (Cool/Dry/Fan/Auto) · fan (Auto/Quiet/Low/Med/High) · Normal/Eco/Powerful · vertical swing · Converti capacity · nanoe · unit display · 7-day energy.

## How it works
```
App (WebView UI) ── Kotlin bridge ── HTTPS login ─► MirAIe cloud
                                └── MQTT over TLS ─► mqtt.miraie.in:8883 ─► AC
```
- UI: `app/src/main/assets/index.html`
- Cloud client: `app/src/main/java/com/ahiraj/accontrol/MiraieClient.kt`
- Login is stored encrypted on the phone (Android Keystore) and sent only to MirAIe.

## Notes
- **Unofficial.** The MirAIe API is not public; endpoints come from the open-source
  [miraie-ac](https://pypi.org/project/miraie-ac-in/) library and may change.
- Needs internet (same as the Panasonic app).
- Every push to `main` builds a new APK with GitHub Actions.
