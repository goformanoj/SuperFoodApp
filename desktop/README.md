# `desktop/` — JARVIS for Windows (Compose for Desktop)

EXECUTION_PLAN Part I. A native desktop client added beside `app/`. It talks to the
**same Worker** as the phone, through the **same `ProxyClient`**.

## Run it

```sh
./gradlew :desktop:run
```

Build a standalone app (a folder with `JARVIS.exe`, no JDK needed to launch it):

```sh
./gradlew :desktop:createDistributable
# → desktop/build/compose/binaries/main/app/JARVIS/JARVIS.exe
```

`./gradlew :desktop:packageMsi` makes an installer (Start-menu entry + shortcut).

## Secrets: one place, shared with the Android build

Both builds read the same Gradle properties. Put them in your **user-level**
`%USERPROFILE%\.gradle\gradle.properties`, never in the repo:

```properties
PROXY_SECRET=...            # required: the Worker's X-Proxy-Secret
FIREBASE_WEB_API_KEY=...    # required: public Firebase web API key (anonymous identity)
GOOGLE_WEB_CLIENT_ID=...    # optional (phone sign-in)
GOOGLE_DESKTOP_CLIENT_ID=...      # optional: desktop "Sign in with Google"
GOOGLE_DESKTOP_CLIENT_SECRET=...  # (a "Desktop app" OAuth client — see below)
```

**Desktop Google sign-in** needs its own OAuth client, because Google registers each kind
of app separately. In Google Cloud Console → APIs & Services → Credentials for project
`jarvis-os-4efe3`, choose Create credentials → OAuth client ID → **Desktop app**. Put its ID
and secret in the two keys above and rebuild. The flow is Google's standard one for installed
apps: the browser opens Google's own page, the answer returns to a one-shot server on
127.0.0.1 (PKCE-protected), and Firebase `signInWithIdp` links the laptop to the same account
as the phone, so the owner's Pro plan applies here too.

Secrets are **baked in at build time**, so rebuild after changing them. The
generated `BuildConfig` lives in `desktop/build/` (ignored), and CI builds this
module with no secrets at all.

## How it shares code without moving it

`build.gradle.kts` → `SHARED_FROM_APP` compiles a list of Android-free files straight
from `app/src/main`: `ProxyClient`, `GroqClient`, `Markers`, `MemoryActions`,
`MemoryFormat`, `DebugLog`, and so on. The phone app's files do not move, so this
module cannot break the Android build.

The few Android objects those files call have desktop versions with the same names
under `src/main/kotlin/com/jarvis/os/`: `Identity` (file-backed, not
SharedPreferences), `UsageStats`, and the generated `BuildConfig`.

**Rule:** only add a file to `SHARED_FROM_APP` if it imports nothing from
`android.*`/`androidx.*`. The later `:shared` KMP module (Part I step 5) replaces
this list.

## What it does today, and what it doesn't

- ✅ Typed chat with JARVIS, the same brain and account allowance model as the phone
  (anonymous Firebase identity → free plan).
- ✅ Remembers facts (`<<REMEMBER|…>>` / `<<FORGET|…>>`, same rules as the phone),
  sent as context on every turn; the conversation persists across restarts.
- ✅ Never shows a marker. A phone-only action (open app, tap, alarm…) is stripped
  and explained.
- ⏳ Not yet: voice (mic + TTS), Google sign-in (needs a browser loopback OAuth
  flow), desktop automation (the headline feature: scripts/OS APIs, not screen
  poking).

State lives in `%APPDATA%\JarvisOS\` (`chat.json`, `identity.properties`).
