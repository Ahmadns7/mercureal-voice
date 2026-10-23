# MercuReal Voice

[![Latest release](https://img.shields.io/github/v/release/Ahmadns7/mercureal-voice?label=download%20APK&color=10b981)](https://github.com/Ahmadns7/mercureal-voice/releases/latest)

Offline-first voice control for Android. Speak a command, the phone does it. Nothing is uploaded. All history, aliases and settings live on the device (SharedPreferences).

Package: com.mercureal.voice · versionName 1.9.0 (versionCode 12) · minSdk 26 · targetSdk 35

## Features

- Voice command service: Android SpeechRecognizer with EXTRA_PREFER_OFFLINE, continuous listening (on by default) with tap-to-talk fallback.
- Floating mic overlay button (needs "display over other apps" permission).
- Command engine: open app (PackageManager name match), call a contact or number, dial, send SMS, screenshot (MediaProjection), flashlight on/off, timer, alarm, tell the time, read battery level, open wifi and bluetooth settings, volume up/down/mute.
- Spoken confirmation of every action via TextToSpeech.
- Command history (last 100).
- Custom aliases: map a phrase to an app or action.
- Quick actions grid on the home screen.
- Dark UI with emerald accent by default. Theme (dark or light) and accent (emerald, sky, amber) are set in the Home tab appearance section (new in 1.6.0).
- Onboarding screen covering permissions and privacy. Reopen from the Home tab.
- Routines (new in 1.2.0): a spoken phrase runs a chain of commands in order, waiting for each step's speech before the next. Two examples ship ("good morning", "wind down"). Edit or add routines in the app.
- Media control (new in 1.3.0): "play", "pause", "next", "previous", and "what's playing". With notification access, controls go to the active music session and "what's playing" reads the title and artist. Without it, play/pause/next/previous send the media key instead. Volume is adjusted through AudioManager. With no active session, it says so instead of failing silently.
- Notification reading (new in 1.3.0): incoming notifications are read aloud as "app. title. preview". Off by default. Master toggle and a per-app mute list live on the Home tab. Skipped during do not disturb, on silent or vibrate, for ongoing, transport and progress notifications, for group summaries, and for repeats within 10 seconds. Speech is on-device TTS. Nothing is sent anywhere.
- SMS sending (new in 1.4.0): "text <contact> <message>" finds the contact, reads back the recipient and message, and waits for "yes" (or "no") within 60 seconds before sending through SmsManager. The carrier result is announced by voice. If SEND_SMS is denied, it opens the default messaging app composer with the text filled in instead.
- Emergency mode (new in 1.4.0): save an emergency contact and an optional message on the Home tab. Saying "emergency" or "help me" takes one location fix (last fresh fix, else a single fix with a 15 second timeout), builds a maps.google.com link, and texts it to the contact. It asks for "yes" first unless Panic mode is on, in which case it sends right away. Location needs ACCESS_FINE_LOCATION. Without it, the text goes out saying location is unavailable.
- Screen reader (new in 1.5.0): say "read screen" to speak the text of the active window. The accessibility service walks the window tree, keeps visible text and content descriptions, and reads it in pages of about 1200 characters, starting the next page when the previous one finishes speaking. Say "stop reading" to interrupt. Off until you enable it in Accessibility settings (home tab row opens them). It reads only on request and does not act on the screen or log what it reads.
- App usage stats (new in 1.5.0): "how much time on whatsapp today" or "yesterday" answers from UsageStatsManager foreground time, with fuzzy app-name matching shared with "open". "screen time" or "top apps" speaks the top five apps. The Usage tab shows the top five apps today with time bars. Needs usage access (special permission, granted in settings).
- Reminders (new in 1.2.0): "remind me to X at 6pm", "remind me to X tomorrow at 9", or "remind me to X in 20 minutes". Stored on-device, fired by an exact alarm with a spoken announcement and notification. Reminders are re-armed after reboot.

## Languages (new in 1.9.0)

Commands are matched on recognized text. The Home tab has a "Command language" setting: auto (default, tries all languages), English, Nigerian Pidgin, Yoruba, or Hausa. The matcher covers 43 phrasings across open, call, text, battery, time, flashlight and reminder (Pidgin 20, Yoruba 11, Hausa 12). English always works. Examples: "open whatsapp", "make i call mama", "how battery dey", "shine light", "aago melo ni", "kunna fitila", "karfe nawa ne".

Honest limits:
- Recognition depends on the phone's speech service (Google by default). If it does not transcribe accented Pidgin, Yoruba or Hausa speech correctly, the matcher gets the wrong text and cannot fix it. Try the language setting that matches how you speak.
- Replies are spoken in English in 1.9.0. Only the command phrases are multilingual.
- The phrase tables are a starting set, not full grammars. Phrases outside the table are handled by the English grammar as before.

## Feature table (1.8.0)

| Feature | Version | Needs |
| --- | --- | --- |
| Routines | 1.2.0 | nothing extra |
| Reminders (exact alarm, reboot re-arm) | 1.2.0 | exact alarm permission |
| Media control: play, pause, next, previous, what's playing, volume | 1.3.0 | notification access for session control and "what's playing"; play/pause/next/previous work without it via media key |
| Notification reading (master toggle, per-app mute, DND and silent aware) | 1.3.0 | notification access |
| SMS sending with spoken read-back and yes/no confirmation | 1.4.0 | SEND_SMS (runtime); falls back to the messaging app composer if denied |
| Emergency mode: location + maps link texted to saved contact, panic mode | 1.4.0 | SEND_SMS and ACCESS_FINE_LOCATION (runtime); location is optional, the text still goes out without it |
| Screen reader: "read screen" paginated TTS, "stop reading" | 1.5.0 | Accessibility service enabled by the user in Accessibility settings (special access, not a runtime prompt) |
| App usage stats: per-app "how much time" answers, top-5 today with Usage tab | 1.5.0 | PACKAGE_USAGE_STATS (special access, granted in Usage access settings, not a runtime prompt) |
| Offline wake word: foreground listening, duty cycled, suspends on battery saver or below 15% | 1.6.0 | RECORD_AUDIO; an offline speech service with partial results (the app warns in Home if the phone does not offer offline recognition) |
| On-device Smart assistant (SmolLM2-135M via llama.cpp JNI, downloaded on consent, falls back to Rules) | 1.7.0 | about 105 MB download, a few hundred MB RAM while running; 3 GB+ RAM recommended |
| Wake modes: light (default) and full, duty-cycled | 1.7.0 | nothing extra |
| Themes and accent: dark or light, accent emerald, sky or amber | 1.6.0 | nothing extra |
| Alternate launcher icons: default, wave, bolt, mono (activity-alias) | 1.6.0 | nothing extra; the launcher can take a few seconds to show the change |
| Home-screen widgets: mic (tap to listen), routine shortcut (long-press to pick a saved routine, tap to run), status (battery and next reminder). All 250dp wide, dark glass | 1.8.0 | add from the launcher widget picker; the status widget updates on battery changes and when you open it |
| Car mode: full-screen high-contrast screen with four large buttons (listen, notifs, next, home), announces incoming notifications, navigate home, call <contact> | 1.8.0 | RECORD_AUDIO and CALL_PHONE; set a home address for navigate home; notification access for announcements |

Wake word (1.6.0) details: the phrase defaults to "mercureal" and is set in the Home tab. Matching runs on SpeechRecognizer partial results and is approximate: words are compared with a small edit tolerance, so near misses can trigger and some real speech can be missed. Listening runs in short windows with rests between them. It stops after 5 minutes with no speech heard, and it pauses on battery saver or below 15% battery. Battery use depends on the phone's speech service and OEM (HiOS and others can differ), so measure it on your own phone.

Icons (1.6.0): the Home tab appearance section switches between default, wave, bolt and mono. The launcher can take a few seconds (sometimes a relaunch) to show the new icon.

## Permissions

- RECORD_AUDIO: speech recognition (requested at runtime).
- CALL_PHONE: place calls (runtime).
- READ_CONTACTS: find contacts by name (runtime).
- SEND_SMS: send texts through SmsManager for "text" and emergency mode (runtime, added in 1.4.0). Denied means the app opens the messaging composer instead.
- ACCESS_FINE_LOCATION: one-shot location for emergency mode (runtime, added in 1.4.0). ACCESS_COARSE_LOCATION is declared alongside it.
- CAMERA: flashlight through CameraManager (runtime).
- FOREGROUND_SERVICE, FOREGROUND_SERVICE_MICROPHONE, FOREGROUND_SERVICE_MEDIA_PROJECTION: keep listening and capture screens while the app is in the background.
- SYSTEM_ALERT_WINDOW: floating mic overlay (granted in system settings).
- POST_NOTIFICATIONS: foreground service notification (Android 13+).
- com.android.alarm.permission.SET_ALARM: set timers and alarms through the clock app.
- RECEIVE_BOOT_COMPLETED: re-arm saved reminders after a reboot (no runtime prompt).
- SCHEDULE_EXACT_ALARM: fire reminders on time. On Android 12+ the user can deny this in Settings. Reminders then fire late, not on time. The app still schedules them, but the timing is inexact.
- BIND_NOTIFICATION_LISTENER_SERVICE: declared on NotifyListenerService. Not a runtime permission. The user grants it in Settings under notification access. It is needed for both notification reading and media session control. Without it, notifications are not read and "what's playing" is unavailable.
- PACKAGE_USAGE_STATS: screen time from UsageStatsManager (special access, added in 1.5.0). The user grants it in Usage access settings; the app opens that screen from the home tab. Without it, usage answers say access is off and the Usage tab shows an enable button.
- Accessibility service (ScreenReaderService, added in 1.5.0): BIND_ACCESSIBILITY_SERVICE, held by the system. Enabled by the user in Accessibility settings. canRetrieveWindowContent is true so the app can read visible text. It does not perform gestures or inject input.
- ACCESS_NETWORK_STATE: lets the speech engine check connectivity. Not used to send data.

Android has no MEDIA_PROJECTION runtime permission. Screen capture is approved through the system consent dialog, which the app shows once per session. The grant expires when the session ends.

## Setup

1. Install the APK (enable install from unknown sources for your file manager or browser).
2. Open MercuReal Voice and read the onboarding screen.
3. Grant microphone, contacts and phone permissions when asked.
4. Allow display over other apps for the floating mic button.
5. For screenshots, approve the screen capture prompt the first time you ask.

## Architecture (1.7.0)

On-device assistant (Smart mode). The Assistant chat has a Rules/Smart switch. Rules is the original engine: instant, nothing stored. Smart runs a small quantized model (SmolLM2-135M-Instruct, Q4_K_M GGUF, about 105 MB) fully on the phone through llama.cpp (pinned b11838). The model is downloaded only after the user confirms "Download brain" in the chat tab, checked against a SHA-256, and saved in app storage. Inference goes through a minimal JNI bridge (native/llm_jni.cpp) built for arm64-v8a and packed as lib/arm64-v8a/libmercureal_llm.so. The model's reply can be plain text or one JSON action {"say": "...", "run": "..."}. The action runs through the same rules executor as voice commands, so calls, texts, timers and alarms still ask for a yes. If the model is missing, the native library is absent, or the phone has under 3 GB RAM, Smart mode answers with Rules and says why.

Wake word (1.7.0). Still the SpeechRecognizer partial-results approach, not a native keyword model. There was no keyword-spotting SDK available to build in this environment, so the honest change is duty cycling. Wake mode "light" (default) listens for 4 s and rests 12 s. "Full" listens for 6 s and rests 2.5 s, as in 1.6.0. Battery saver and under-15% pause still apply.

RAM and devices: Smart mode needs about 105 MB of storage plus a few hundred MB of RAM while it runs. Phones with 2 GB RAM may struggle or be killed by the system, so use Rules there. Smart mode is a free-form chat on a 135M model, so expect short, sometimes wrong replies. Rules remains the reliable path for commands.

## Build
Gradle project (for Android Studio or CI):

    ./gradlew assembleDebug

Raw toolchain (no Gradle, used for release builds). Needs JDK 17+, Android SDK with platform android-35 and build-tools 35.0.0, and a kotlinc 2.0.x distribution:

    export ANDROID_HOME=/path/to/sdk
    export KOTLINC_HOME=/path/to/kotlinc
    bash scripts/build-raw.sh

Output: dist/raw/mercureal-voice-1.9.0-debug.apk by default (debug key, v2 and v3 schemes). Set MERCUREAL_KEYSTORE (plus MERCUREAL_KEY_ALIAS, MERCUREAL_STORE_PASS, MERCUREAL_KEY_PASS) to get a release-signed APK and an AAB. Keystore setup and the Play and F-Droid steps are in RELEASING.md. The build gate runs the Naija phrase unit test first. The llama.cpp bridge is built separately, see native/.

## Caveats

- Not yet tested on a physical device. SMS and location paths are compile-verified only: carrier delivery reports and GPS fixes need a real phone with a SIM. Expect rough edges around overlay behaviour, screen capture on some vendor ROMs, and offline speech model availability (offline recognition needs the language pack downloaded in Google's speech settings).
- Debug-signed only. Not for the Play Store.
- Volume control uses AudioManager. It does not need accessibility access.
- Screen reader readability depends on the app. Apps that expose text through the accessibility tree (most standard apps) read well. Custom-drawn canvases, WebViews without accessibility enabled, and games often yield little or no text. Pages are read as collected, so order can be off on complex layouts.
- Usage stats on OEM skins (HiOS, MIUI, One UI, ColorOS) can differ from stock. Some vendors hide or delay foreground-time records, reset them on battery optimisation, or list the usage access toggle under a different menu. Numbers are an estimate of foreground time reported by the system, not an exact count. Not verified on a HiOS device yet.
- Design follows the MercuReal palette using framework Views. Material3 components are not used.

## License

MIT. See LICENSE.

Widgets and car mode (1.8.0): the mic widget opens the app and starts listening. The routine widget keeps one routine per widget (long-press to configure, then tap to run it). The status widget shows battery and the next upcoming reminder. Car mode is entered by saying "car mode" or from its long-press launcher shortcut, and exited by saying "exit car mode" or with the exit button. While it is on, incoming notifications are announced aloud and kept in a short queue (up to 20). "navigate home" opens directions to the saved home address in Google Maps. Car mode uses large touch targets and loud text-to-speech, and keeps the screen on. Caveats: car mode has not been tested while driving, and it does not replace hands-free rules where you live.
