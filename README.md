# Phone Sync Speaker v0.1

Android 10+ prototype. Install the same app on two Android phones.

## Build
Open this folder in Android Studio (JDK 17), let Gradle sync, then Build > Build APK(s).

## Test
1. Put both phones on the same Wi-Fi, or connect Phone 2 to Phone 1's hotspot.
2. Phone 1: START HOST / SHARE AUDIO. Grant Record Audio and Android playback/screen capture consent.
3. Note the Host IP shown.
4. Phone 2: enter that IP and CONNECT AS SPEAKER.
5. On Phone 1, play capturable media in another app.

## Current prototype limitations
- Android 10+ only.
- Source apps can opt out of AudioPlaybackCapture; protected/DRM audio may be silent.
- v0.1 uses raw PCM over TCP for simplicity. It has buffering but not yet clock-synchronized DSP/drift correction.
- Manual Host IP entry is intentional for v0.1 reliability. Automatic nearby discovery can be added after basic streaming is validated on the user's two phones.

## In-app updater (v0.2)
The app now includes CHECK FOR UPDATE. Configure `updateManifestUrl` in MainActivity.kt to a public HTTPS `update.json` URL. An example is included as `update.json.example`.

For every future release: increase `versionCode`, build the APK using the SAME signing key, upload the APK, and update `update.json`. The app will detect the higher version, download the APK, and open Android's installer. Android still requires user approval and may require enabling "Install unknown apps" for this app.
