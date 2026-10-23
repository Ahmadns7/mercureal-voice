# MercuReal Voice

Offline-first voice control for Android. Speak a command, the phone does it. Nothing is uploaded. All history, aliases and settings live on the device (SharedPreferences).

Package: com.mercureal.voice · versionName 1.3.0 (versionCode 5) · minSdk 26 · targetSdk 35

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
- Routines (new in 1.2.0): a spoken phrase runs a chain of commands in order, waiting for each step's speech before the next. Two examples ship ("good morning", "wind down"). Edit or add routines in the app.
- Media control (new in 1.3.0): "play", "pause", "next", "previous", and "what's playing". With notification access, controls go to the active music session and "what's playing" reads the title and artist. Without it, play/pause/next/previous send the media key instead. Volume is adjusted through AudioManager. With no active session, it says so instead of failing silently.
- Notification reading (new in 1.3.0): incoming notifications are read aloud as "app. title. preview". Off by default. Master toggle and a per-app mute list live on the Home tab. Skipped during do not disturb, on silent or vibrate, for ongoing, transport and progress notifications, for group summaries, and for repeats within 10 seconds. Speech is on-device TTS. Nothing is sent anywhere.
- Reminders (new in 1.2.0): "remind me to X at 6pm", "remind me to X tomorrow at 9", or "remind me to X in 20 minutes". Stored on-device, fired by an exact alarm with a spoken announcement and notification. Reminders are re-armed after reboot.

## Feature table (1.3.0)

| Feature | Version | Needs |
| --- | --- | --- |
| Routines | 1.2.0 | nothing extra |
| Reminders (exact alarm, reboot re-arm) | 1.2.0 | exact alarm permission |
| Media control: play, pause, next, previous, what's playing, volume | 1.3.0 | notification access for session control and "what's playing"; play/pause/next/previous work without it via media key |
| Notification reading (master toggle, per-app mute, DND and silent aware) | 1.3.0 | notification access |

Deferred (not in 1.3.0): wake word, SMS send, screen reader, emergency mode, usage stats, themes and icon packs.

## Permissions

- RECORD_AUDIO: speech recognition (requested at runtime).
- CALL_PHONE: place calls (runtime).
- READ_CONTACTS: find contacts by name (runtime).
- CAMERA: flashlight through CameraManager (runtime).
- FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_MEDIA_PROJECTION: keep listening and capture screens while the app is in the background.
- SYSTEM_ALERT_WINDOW: floating mic overlay (granted in system settings).
- POST_NOTIFICATIONS: foreground service notification (Android 13+).
- com.android.alarm.permission.SET_ALARM: set timers and alarms through the clock app.
- RECEIVE_BOOT_COMPLETED: re-arm saved reminders after a reboot (no runtime prompt).
- SCHEDULE_EXACT_ALARM: fire reminders on time. On Android 12+ the user can deny this in Settings. Reminders then fire late, not on time. The app still schedules them, but the timing is inexact.
- BIND_NOTIFICATION_LISTENER_SERVICE: declared on NotifyListenerService. Not a runtime permission. The user grants it in Settings under notification access. It is needed for both notification reading and media session control. Without it, notifications are not read and "what's playing" is unavailable.
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
