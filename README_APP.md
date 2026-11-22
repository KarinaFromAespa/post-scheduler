# Post Scheduler — Android app (Kotlin + Jetpack Compose)

This is the `app/` side of the monorepo [`KarinaFromAespa/post-scheduler`](https://github.com/KarinaFromAespa/post-scheduler). The backend (`backend/`) owns the queue, the publisher, and Facebook publishing; the app is the local-first client.

## What it does

- **Publish tab (home):** Today list, hourly slots, one card per scheduled item (time / caption / media collage, status chip, 3-dot menu: Publish now / Retry / Update schedule / Edit / Delete). Multiple items can share a slot, each with its own status and failure. Pause / Resume queue (nothing deleted).
- **Create tab:** composer for feed post / reel (single MP4) / story (one photo). Caption only — hashtags go in the caption box; there is no separate hashtag field. Stories have no caption. Schedule to a time, save as draft (keeps a picked time as the draft's planned time), or Publish Now (bypasses the queue entirely).
- **Background media upload:** a photo starts uploading the moment it is picked, so saving never waits on an upload. The tile shows a circular progress overlay while it uploads; a failed upload shows a retry. Removing a picked photo deletes it from the server too. The save buttons stay disabled while any upload is in flight.
- **Drafts tab:** saved drafts, with Edit / Delete and Publish now.
- **Insights tab:** total approximate earnings for the current month only. No per-post data, no likes/comments/shares.
- **Pages:** connect Pages with Facebook Login; switch between them from the Page screen or the Create tab dropdown. Each Page has its own queue and drafts.
- No Community tab, no "Created by …" line, no recycle/repost, no home-screen widgets. Bottom nav: Create / Publish / Drafts / Insights.

## How it works

- **Local-first:** queue and drafts live in Room (`planner.db`) so the Today list works offline. Every create / publish-now / pause also calls the Node backend, which owns the 1-minute publisher, media storage, and Facebook publishing. If the backend is unreachable, the local copy is kept and the error is shown — nothing is silently dropped.
- **Backend URL:** `planner.backend.url` in `gradle.properties` (default `https://postschedulerapp.duckdns.org`), changeable at runtime via `SettingsStore` (DataStore). The API key (`planner.api.key`) is sent as `X-Api-Key` on every request. Short-notice scheduling (2–10 min ahead) works via the app's own queue + the backend's 1-minute publisher, not Facebook native scheduling.
- **Facebook Login:** the Facebook SDK handles login; the picked Page's token is sent to the backend (which stores it and exchanges it for a long-lived token). The app never stores a Page token on the phone.
- **Crop:** v1 relies on the system photo picker / editor before the URI reaches the composer — no custom cropper is shipped.

## Build

Prerequisites: JDK 17, Android SDK 35, Gradle wrapper 8.9 (the system Gradle 9.x is incompatible with AGP 8.5.2 — use the wrapper).

```sh
./gradlew assembleDebug        # debug APK
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Start the backend first (`backend/` — Node), or the app will show "Backend unreachable" and work from the local queue only.

## Configuration

`gradle.properties` (committed file keeps placeholders; real values go in the machine-local `~/.gradle/gradle.properties` — never in the repo):

| Property | What it does |
|---|---|
| `planner.backend.url` | Backend base URL (e.g. `https://postschedulerapp.duckdns.org`). |
| `planner.api.key` | Backend API key, sent as `X-Api-Key`. |
| `planner.facebook.app.id` | Facebook App ID. |
| `planner.facebook.client.token` | Facebook client token. |

## Files

- `app/build.gradle.kts` — Compose BOM, Room, Retrofit, Coil, DataStore, WorkManager, Facebook SDK
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/postscheduler/MainActivity.kt` — nav (Create / Publish / Drafts / Insights)
- `app/src/main/java/com/postscheduler/data/local/` — Room: `Post`, `PostDao`, `AppDatabase`, `InsightEntity`
- `app/src/main/java/com/postscheduler/data/remote/` — Retrofit `PlannerApi`, `ApiFactory` + `SettingsStore`
- `app/src/main/java/com/postscheduler/data/PlannerRepository.kt` — local-first queue/drafts/publish/pause/insights, background temp upload
- `app/src/main/java/com/postscheduler/ui/publish/` — Today list + hourly slots
- `app/src/main/java/com/postscheduler/ui/create/` — composer (`CreateScreen`, `CreateViewModel`)
- `app/src/main/java/com/postscheduler/ui/insights/` — total-only earnings
- `app/src/main/java/com/postscheduler/ui/components/` — `PostCard`, `MediaRow` (upload overlay), `PageAvatar`, pickers
- `app/src/main/java/com/postscheduler/ui/theme/Theme.kt`
