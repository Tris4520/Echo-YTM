# Echo YouTube Music extension

YouTube Music for [Echo](https://github.com/brahmkshatriya/echo), using
[InnertubeX v0.11.2](https://github.com/MetrolistGroup/innertubex/tree/v0.11.2).
That release includes Faraday player configuration and the Kotlin-only QuackJS
signature / `n` cipher backend.

## Implemented

- Home, search, library, album, playlist and artist feeds with continuation loading.
- Track loading and direct/HLS audio/video stream extraction through InnertubeX.
- A clean quality menu with Automatic, Low, Medium and High, selected from the
  actual audio formats or video renditions returned for the track.
- Prefer music videos resolves an official video counterpart for normal songs;
  explicit video tracks retain their selected video ID.
- Adaptive video merged with its audio source, plus bounded-range playback adapters.
- SABR first for audio, video and downloads, with automatic direct/HLS fallback.
- Background InnerTubeX player/config/cipher warmup; alternate direct formats are
  deciphered when selected, so quality-menu enrichment does not delay playback.
- Native-only lookahead resolution for the first displayed track and the next
  listed track, with foreground playback priority and expiry-aware cache reuse.
- Selected SABR audio/video sources open in parallel before Echo requests them;
  known-length direct playback releases a 64 KiB first chunk for fast startup.
- Native YouTube timed lyrics where available, then recording-matched LRCLIB
  synced lyrics, with plain YouTube lyrics as the fallback and a memory cache.
- A bounded, in-memory resolution cache with expiry margins, concurrent request
  reuse, account/settings invalidation and one refresh when opening a stream fails.
- Echo's built-in WebView login, capturing cookies, visitor data and account index.
- Manual cookie login through Echo's account form.
- BotGuard PO-token generation using Echo's hidden WebView API.
- In-memory token caching, serialized generation, account invalidation, cancellation,
  timeouts and a failure cooldown.

## Build and verify

Use JDK 21 or newer to run Gradle, with JDK 17 installed for the compilation toolchain.
The wrapper uses Gradle 9.7.1; Android Gradle Plugin 9.3.3 requires Android platform
37 for the selected InnertubeX/QuackJS Android dependencies. Target SDK remains 36
and minimum SDK is 24. Node 22+ runs the separate offline JavaScript tests.

```powershell
# Set ANDROID_HOME to your Android SDK directory if needed.
.\gradlew.bat :ext:test :ext:ktlintCheck :ext:shadowJar :app:assembleDebug
node --test ext/src/test/js/botguard.test.cjs
```

On Linux/macOS, use `./gradlew` with the same tasks. The first build downloads
missing dependencies; subsequent builds can add `--offline`.

The outputs are:

- `app/build/outputs/apk/debug/app-debug.apk`: Android extension package.
- `ext/build/libs/youtubemusic-<version>-all.jar`: shaded JVM artifact.

For Android, import the APK through Echo's extension installer; if it filters for
`.eapk`, copy/rename the APK to `youtubemusic.eapk` first. The JVM JAR is not an
Android DEX package. This project packages an extension and has no standalone
player or sign-in activity.

## Sources and license
See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), [LICENSE](LICENSE) and
[DISCLAIMER.md](DISCLAIMER.md). The extension is independent of YouTube and Google.
