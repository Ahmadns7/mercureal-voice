# Releasing MercuReal Voice

Package: com.mercureal.voice. The release build is produced by `scripts/build-raw.sh` (raw toolchain: aapt2, kotlinc, d8, zipalign, apksigner, bundletool). The build gate runs the pure-Kotlin Naija phrase unit test first and stops on any failure.

## 1. Keystore setup (local only, never committed)

The keystore is not in this repo. `*.jks` and `*.keystore` are in `.gitignore`. Keep the key and its passwords out of git, CI logs, and chat.

Create the release keystore once (RSA 2048, 25 years):

    mkdir -p ~/.keystore && chmod 700 ~/.keystore
    keytool -genkeypair -keystore ~/.keystore/mercureal-release.jks -alias mercureal \
      -keyalg RSA -keysize 2048 -validity 9125 \
      -dname "CN=MercuReal Tech Solutions,O=MercuReal,C=NG"

keytool prompts for the store and key passwords. Use strong ones and store them in a password manager. Back up the `.jks` file: if you lose it, you cannot ship updates under the same Play listing.

Put the signing values in a file only you can read, for example `~/.keystore/mercureal-release.env` with mode 600:

    MERCUREAL_KEYSTORE=/home/you/.keystore/mercureal-release.jks
    MERCUREAL_KEY_ALIAS=mercureal
    MERCUREAL_STORE_PASS=...
    MERCUREAL_KEY_PASS=...

Load it and build:

    set -a; source ~/.keystore/mercureal-release.env; set +a
    export ANDROID_HOME=/path/to/sdk KOTLINC_HOME=/path/to/kotlinc
    export BUNDLETOOL_JAR=/path/to/bundletool-all-1.18.3.jar
    bash scripts/build-raw.sh

With `MERCUREAL_KEYSTORE` set, the script signs the APK with the release key and writes `dist/raw/mercureal-voice-2.0.0-release.apk`, verified with `apksigner verify`. It also signs the AAB with jarsigner and verifies it. Without that variable, it falls back to the debug key and writes `dist/raw/mercureal-voice-2.0.0-debug.apk`. The debug-signed APK is for sideloading only, and the debug-signed AAB is rejected by Play.

Verify a signed build:

    apksigner verify --verbose --print-certs dist/raw/mercureal-voice-2.0.0-release.apk
    jarsigner -verify dist/raw/mercureal-voice-2.0.0.aab

The release certificate's SHA-256 fingerprint should match the one you recorded when you created the key. Record it in your own notes.

Why CI does not sign releases: a keystore in CI means its passwords become secrets in the CI provider. Until those are set up on purpose, release APKs are debug-signed and built with the flow above.

## 2. AAB route with Gradle (if bundletool is unavailable)

bundletool is the default AAB builder in `build-raw.sh`. If you cannot use it, build the AAB with Gradle and Android Studio:

1. Open the project in Android Studio (Koala or newer, JDK 17).
2. Build > Generate Signed Bundle / APK > Android App Bundle.
3. Pick `~/.keystore/mercureal-release.jks`, alias `mercureal`, and enter the passwords.
4. Output: `app/build/outputs/bundle/release/app-release.aab`.

Command line, using the same keystore through the Gradle signing config:

    ./gradlew bundleRelease

Gradle reads the signing values from `signingConfigs` when you add one that reads the `MERCUREAL_*` environment variables. Before you upload, verify with `jarsigner -verify app-release.aab`.

## 3. Google Play listing checklist

- [ ] Developer account created, identity verified, one-time registration fee paid.
- [ ] App created with package name com.mercureal.voice.
- [ ] Upload the signed AAB from section 1 or 2 to an internal testing track first.
- [ ] Enable Play App Signing (Google holds the app signing key; your mercureal-release key is the upload key).
- [ ] Store listing: title "MercuReal Voice", short description (80 characters max), full description, which must match the README's honest caveats (English-only replies, accent-dependent recognition, offline speech pack needed).
- [ ] Graphics: app icon 512x512 PNG, feature graphic 1024x500, at least 2 phone screenshots (16:9 or 9:16, min 320 px).
- [ ] Category, contact email, privacy policy URL (the app stores all data on device; describe that and the optional features that send SMS and location to a contact you choose).
- [ ] Data safety form: declare audio is processed on device, SMS and location are used only when the user asks for them, nothing is shared with third parties, nothing is uploaded.
- [ ] Permissions declaration for SEND_SMS, CALL_PHONE, ACCESS_FINE_LOCATION, PACKAGE_USAGE_STATS, SYSTEM_ALERT_WINDOW, SCHEDULE_EXACT_ALARM, and notification access (Play asks for a justification for each sensitive permission; SMS and accessibility need a core-functionality explanation).
- [ ] Content rating questionnaire.
- [ ] Target audience: 18+ or general, no ads, no in-app purchases.
- [ ] Test on your own phone from the internal track before promoting to production.
- [ ] Release notes: copy the v2.0.0 section of the GitHub release notes.

## 4. F-Droid submission steps

F-Droid builds from source, so the submission is a metadata file in fdroiddata.

1. Confirm the repo builds without any secret: the release APK from `build-raw.sh` uses the debug key when `MERCUREAL_KEYSTORE` is unset, and F-Droid signs with its own key. Keep the tagged source reproducible.
2. Fork https://gitlab.com/fdroid/fdroiddata and create `metadata/com.mercureal.voice.yml`.
3. Required fields: `Categories`, `License` (must be an OSI license; the repo's LICENSE file), `SourceCode`, `IssueTracker`, `AutoName: MercuReal Voice`, `RepoType: git`, `Repo: https://github.com/Ahmadns7/mercureal-voice.git`.
4. Build section: `commit: v2.0.0`, `versionName: 2.0.0`, `versionCode: 12`, and a `gradle` or `output` build block that matches your build. Because the build here uses raw tools, you may need a custom `prebuild` or ask the F-Droid maintainers which build method fits.
5. Anti-features: declare `NonFreeNet` only if a dependency needs it. The Smart mode model download (about 105 MB from Hugging Face) is a network feature: declare it as an anti-feature if F-Droid's rules require it, and say it is opt-in.
6. Open a merge request against fdroiddata. The F-Droid build server runs `fdroid lint`, `fdroid build`, and `fdroid checkupdates` before merging.
7. After merge, check the build log on https://f-droid.org/en/packages/com.mercureal.voice/ and fix any reproducibility issues.

Metadata pointers: https://f-droid.org/docs/Build_Metadata_Reference/ and https://gitlab.com/fdroid/fdroiddata/-/blob/master/CONTRIBUTING.md.

## 5. Per-release checklist

- [ ] Bump versionName and versionCode in `app/build.gradle.kts`, and the matching values in `scripts/build-raw.sh` and README.
- [ ] Run `bash scripts/build-raw.sh` and confirm the Naija unit test line reports 0 failed.
- [ ] Build the release-signed APK and AAB with the keystore environment (section 1), and verify both.
- [ ] Commit with a conventional message: `chore(release): vX.Y.Z`.
- [ ] Tag `vX.Y.Z`, push `main` and the tag.
- [ ] Create the GitHub Release with the APK and AAB, and notes that state what is verified and what is not.
