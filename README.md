# MercuReal Voice

Offline-first voice control for Android. Speak a command, the phone does it. Nothing is uploaded. All history, aliases and settings live on the device (SharedPreferences).

Package: com.mercureal.voice · versionName 1.0.1 · minSdk 26 · targetSdk 35

## Features

- Voice command service: Android SpeechRecognizer with EXTRA_PREFER_OFFLINE, continuous listening (on by default) with tap-to-talk fallback.
- Floating mic overlay button (needs "display over other apps" permission).
- Command engine: open app (PackageManager name match), call a contact or number, dial, send SMS, screenshot (MediaProjection), flashlight on/off, timer, alarm, tell the time, read battery level, open wifi and bluetooth settings, volume up/down/mute.
- Spoken confirmation of every action via TextToSpeech.
- Command history (last 100).
- Custom aliases: map a phrase to an app or action.
- Quick actions grid on the home screen.
- Dark UI, emerald accent (#10B981) on #0B0F0E.
- Onboarding screen covering permissions and privacy. Reopen from the Home tab.

## Permissions

- RECORD_AUDIO: speech recognition (requested at runtime).
- CALL_PHONE: place calls (runtime).
- READ_CONTACTS: find contacts by name (runtime).
- CAMERA: flashlight through CameraManager (runtime).
- FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_MEDIA_PROJECTION: keep listening and capture screens while the app is in the background.
- SYSTEM_ALERT_WINDOW: floating mic overlay (granted in system settings).
- POST_NOTIFICATIONS: foreground service notification (Android 13+).
- com.android.alarm.permission.SET_ALARM: set timers and alarms through the clock app.
- ACCESS_NETWORK_STATE: lets the speech engine check connectivity. Not used to send data.

Android has no MEDIA_PROJECTION runtime permission. Screen capture is approved through the system consent dialog, which the app shows once per session. The grant expires when the session ends.

## Setup

1. Install the APK (enable install from unknown sources for your file manager or browser).
2. Open MercuReal Voice and read the onboarding screen.
3. Grant microphone, contacts and phone permissions when asked.
4. Allow display over other apps for the floating mic button.
5. For screenshots, approve the screen capture prompt the first time you ask.

## Build

Gradle project (for Android Studio or CI):

    ./gradlew assembleDebug

Raw toolchain (no Gradle, used for the v1.0.0 APK). Needs JDK 17+, Android SDK with platform android-35 and build-tools 35.0.0, and a kotlinc 2.0.x distribution:

    export ANDROID_HOME=/path/to/sdk
    export KOTLINC_HOME=/path/to/kotlinc
    bash scripts/build-raw.sh

Output: dist/raw/mercureal-voice-1.0.0-debug.apk (signed with the debug key, v2 and v3 schemes).

## Caveats

- v1.0.0 has not been tested on a physical device. Expect rough edges around overlay behaviour, screen capture on some vendor ROMs, and offline speech model availability (offline recognition needs the language pack downloaded in Google's speech settings).
- Debug-signed only. Not for the Play Store.
- Volume control uses AudioManager. It does not need accessibility access.
- Design follows the MercuReal palette using framework Views. Material3 components are not used.

## License

MIT. See LICENSE.
