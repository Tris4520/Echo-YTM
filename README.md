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

## Versioning

The author is Tris4520. Both the APK and shaded JAR use `v<7-character commit hash>`
as the version name and the number of commits reachable from `HEAD` as the version
code. Commit before building; GitHub Actions fetches the full history for the count.
Source archives and repositories without a first commit fall back to `v1.0.0` / 1.

For a compact distributable, build with `:app:clean :app:assembleDebug`. Incremental
APK packaging can leave unused ZIP space after replaced DEX entries. A fresh
Android package removes that padding without shrinking code or dependencies.
Version v1.1.2 uses this packaging cleanup and retains v1.1.1's playback code.

Version v1.1.3 routes known-length progressive downloads through explicit bounded
`range=start-end` URL requests to avoid Googlevideo's playback-paced transfers.
Each chunk uses its own URL range, with no second HTTP Range header, and validates
the returned bytes. Resume offsets, signed URL proofs and source quality are
preserved. Version v1.2.0 also uses query ranges for known-length direct playback,
with a smaller first chunk to reduce the wait for player sniffing and buffering.

Version v1.2.0 removes the SABR preference and enables SABR for playback and
downloads. Direct download fallback retains the explicit bounded-range speed
fix. All returned SABR audio/video format identities are exposed in the quality
resolver, including alternative codecs, languages and DRC when supplied. Direct
format URL deciphering is deferred until that quality is selected and cached.
Initialization warms InnerTubeX's player config and cipher in the background;
its dummy-video token prewarm is suppressed so real playback does not queue
behind an unrelated WebView attestation.
Fetched home/search/playlist/radio pages prepare their first track using native
requests only. Next-track lookahead waits two seconds after media selection to
give current startup priority. Foreground resolution cancels unrelated lookahead
and allows matching in-flight preparation at most 100 ms before retrying normally.
Selected SABR audio/video streams open together before Echo opens its sources.
Their bounded startup buffers are single-use, hold at most two sources and expire
after ten seconds. Downloads do not use these startup buffers.

Version v1.2.1 limits the visible quality selector to four choices: Automatic and
up to three distinct Low/Medium/High renditions, with actual bitrate or resolution
labels. Tracks with fewer formats show fewer entries. Video playback shows video
renditions; audio playback shows audio bitrates, keeping the selected SABR language.
Prefer music videos checks the selected song's native counterpart link, then
matches official video search results by title and artist. It keeps search ranking
and allows longer video edits/intros. Art tracks and unrelated covers/remixes are
not substituted. Matches are cached for thirty minutes (misses for one minute),
with a three-second lookup budget and background preparation of displayed tracks.
Without a match, the original song plays as audio. Explicit videos bypass matching.
The original song ID remains attached to playlist/like actions; playback and lyrics
use the resolved video ID and playback duration. Account/settings changes clear
the match cache.

Version v1.2.2 restores Eco's feed layout: native-order home/artist carousels,
the original search and library tabs, individual library and More-page cards,
and related recommendations below tracks, albums and playlists. Empty search
opens the unfiltered home feed before its native browse chips. Automatic and
source labels include the selected codec, bitrate and sample rate, using the
player response's average bitrate when available. Echo's separate decoder row
may still omit bitrate when Media3 does not report it for a SABR container.

The shaded artifact keeps Ktor, OkHttp, Okio, kotlinx-io, coroutines and
serialization in private namespaces. These networking libraries exchange types;
relocating only Okio while loading Echo's OkHttp causes a method-signature mismatch.
Version v1.1.1 fixes that packaging error in v1.1.0.

## Sign in

1. Open the extension's account/login screen in Echo and choose WebView login.
2. Sign in to Google and return to YouTube Music.
3. Tap the profile avatar to open the account menu. The extension finishes login
   when the signed-in account-menu request is intercepted.
4. Select the returned YouTube Music account in Echo.

Cookies and visitor data live in Echo's private login `User` record; Echo restores
them through `setLoginUser()`. They are not copied into the extension's ordinary
settings or exposed by `getCurrentUser()`. Echo owns storage protection and its
WebView lifecycle. Signing out clears the InnertubeX session and cached tokens.

The manual login form accepts the full cookie header, including `SAPISID`,
`__Secure-3PAPISID` or `__Secure-1PAPISID`, plus optional visitor data and account
index. It checks cookie structure; it does not prove that the cookies are current.

## PO tokens and network behavior

The provider runs the BotGuard `Create` → snapshot → `GenerateIT` → mint flow.
It generates both visitor-bound and video-bound proofs, using the binding mapping
in InnertubeX v0.11.2's `PlayerClientDirector`. It does not scrape a page for
existing tokens or rely on an undocumented `yt.player.getPoToken` function.

Generation uses a tiny local HTML document and a readiness request. The extension
prepares one fresh, single-use `Create` challenge on its pooled native connection
when WebView support is injected, playback starts, and a challenge is consumed.
Prepared challenges expire after at most one minute and are discarded on session
changes. Preparation waits at most 250 ms on playback's critical path; if it is
unavailable, JavaScript uses the original `Create` request. With a prepared
challenge the WebView only needs `GenerateIT`, plus an interpreter download when
the challenge supplies a URL. Normal HTTP caching is enabled for the interpreter.
The document omits cookies and does not load the
YouTube homepage or player. The provider stores up to 64 token pairs for at most
five minutes, never beyond the reported lifetime minus a safety margin. Echo's
request API tears down each WebView, so the BotGuard minter is reused for the two
bindings in that invocation; it is not retained across different video requests.

Cache misses are serialized and identical requests share the result; cache hits
return immediately even while another video is generating a token. Reinjecting
the same WebView client preserves cached proofs. Generation
has a 12-second outer timeout, a 10-second JavaScript deadline and a shared
60-second failure cooldown. Failures return no token so InnertubeX can select a
different client; parent coroutine cancellation propagates. Challenge preparation
does not open a WebView or resolve a dummy video. The remaining WebView startup,
BotGuard snapshot and integrity RPC still need device and network time.

Faraday's public cipher configuration is stored in settings. InnertubeX owns its
bounded refresh, unknown-player fallback, player-script downloads and extraction
request budget.

## Verification and limits

The JVM tests and JavaScript protocol tests use local fixtures/mocked WebViews and
responses. They cover login parsing, host validation, token bindings, expiry,
single-generation reuse, account changes, failure cooldown, timeout/cancellation,
renderer mapping and pagination. The Android APK and shaded JAR build successfully.
The package contains `botguard.js` and no native cipher libraries.

The user confirmed playback and downloads in earlier builds. Version v1.2.0's
live desktop startup check, after background initialization, read the first
32 KiB of a new SABR audio track 434–591 ms after loading began; replay took
38–44 ms. A new video returned 32 KiB of audio in 157–166 ms and video in 193–201 ms.
These are extension-side samples on the desktop network, without Android WebView
attestation or player decoding; they do not establish device tap-to-sound latency.
An earlier complete SABR transfer matched all 3,938,351 advertised bytes, and a
recording-matched LRCLIB lookup returned 59 timed lines.

Echo common 1.0's raw byte-stream API carries the extension's bounded-range and
SABR adapters. SABR seeks replay and discard bytes to preserve exact offsets;
seeking far into a track can be slow. A failed stream opening retries through
direct transport. SABR failures during reading also get one direct retry, using
the same format, language/DRC identity and byte length. Already delivered bytes
are compared with the replacement before resuming; incompatible containers fail
instead of being spliced together. Attestation rejection invalidates cached PO
tokens, and the failed track uses direct resolution for one minute. Other tracks
continue to prefer SABR. The extension exposes formats returned by the selected YouTube
client; availability depends on the track, account, region and client. It does not
claim Premium entitlement or invent unavailable quality tiers. Lyrics, radio and
playlist editing are implemented. Browse renderers are private APIs and may change.

Lyrics requests use the native mobile lyric renderer's cue ranges when available.
LRCLIB lookups require matching title, artist and duration within three seconds,
prefer the nearest duration, and reject single-line placeholder entries. LRC
timestamps become Echo timed lyric items; plain lyrics are not assigned invented
start times. Results are cached for 30 minutes (misses for one minute), and cleared
on account/language/region changes. Lyrics lookup runs separately from playback.

The resolution cache holds up to 64 requests for at most five minutes, bounded by
the earliest signed URL expiry minus 30 seconds. Unknown expiry uses one minute;
live streams are not retained. The Automatic choice follows extension quality
settings; explicitly selecting High can exceed the automatic video cap.
Video extraction falls back to audio when needed, cancellation propagates and
the original failure remains in the exception chain. Suggestions parse only
suggestion renderers, and playlist moves use the final successor item.

## Device checks

1. Compare home and artist rows, search tabs and library cards with Eco; open More.
2. Open blank search, then a browse chip; search and switch Songs/Videos/Albums.
3. Play with Automatic and confirm kbps in the selected label and stream details;
   switch Low/Medium/High for audio and video.
4. Check a normal song's official-video match, seeking, lyrics and one download.

## Sources and license

Source was inspected through local shallow clones in the ignored `.reference/`
directory, without GitHub authentication. The build uses the published dependencies.
`gittokenforu.properties` is ignored and is not an extension resource.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), [LICENSE](LICENSE) and
[DISCLAIMER.md](DISCLAIMER.md). The extension is independent of YouTube and Google.
