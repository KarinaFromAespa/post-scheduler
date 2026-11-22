# Post Scheduler

A scheduler for Facebook Pages: compose a feed post, reel, or story once, queue it for a time, and let the backend publish it for you. No queue cap, multiple items per slot, and short-notice scheduling (2–10 minutes ahead) via its own 1-minute publisher — not Facebook's native scheduling, which needs 10+ minutes.

This repo holds the **Android app** (`app/`, Kotlin + Jetpack Compose) and the **backend** (`backend/`, Node + Express) that owns the queue, the publisher, and media storage. A matching **website** lives in a separate repo: [`post-scheduler-web`](https://github.com/KarinaFromAespa/post-scheduler-web) — same backend, same spec, no build step.

> **Status:** v1, live in daily use. The app and website both talk to the same backend. The backend is the source of truth for the queue; the app also keeps a local copy so the Today list works offline.

## Features

- **Queue & publish** — schedule posts, reels, and stories for a time; the backend publishes them on schedule. Multiple items can share one time slot, each with its own status and failure.
- **Short-notice scheduling** — 2–10 minutes ahead works, because the backend's 1-minute publisher (not Facebook native scheduling) does the publishing.
- **Publish Now** — bypasses the queue entirely and publishes immediately.
- **Pause / Resume queue** — holds all scheduled posts without deleting them.
- **Drafts** — a separate state; a draft keeps its planned time so it can be scheduled later without re-picking it.
- **Background media upload** — a photo starts uploading the moment it is picked, so saving never waits on an upload. A circular progress overlay shows the upload on the tile; removing a picked photo deletes it from the server too. Abandoned uploads are reclaimed automatically.
- **Per-Page queues** — each connected Facebook Page has its own queue and drafts; switching Pages switches the list.
- **Insights** — total approximate earnings for the current month only (no per-post data, no likes/comments/shares). Two Graph API calls per load.
- **Facebook Login** — connect Pages with Facebook Login; the Page token is stored on the backend (never returned by the API) and exchanged for a long-lived token (60 days).
- **No queue cap, no Community tab, no recycle/repost, no home-screen widgets.**

## Architecture

```
Android app (Kotlin/Compose) ─┐
                               ├─ HTTPS ──► Caddy ──► Backend (Node, :25565)
Website (static SPA) ──────────┘   (DuckDNS)      1-min publisher
                                                      │
                                   ┌──────────────────┼──────────────────┐
                                   │ in-memory + JSON │ Oracle (primary) │
                                   │ (local backup)   │ Neon (DR backup) │
                                   └──────────────────┴──────────────────┘
                                   Media: local disk cache + Oracle bucket
                                          + Neon Object Storage
```

- **Backend** — Node + Express. In-memory working copy, a local JSON file as backup, and write-through to Oracle Autonomous DB (primary) and Neon Postgres (disaster-recovery copy). Media goes to local disk (serving cache) + Oracle Object Storage + Neon Object Storage. A 1-minute publisher picks up due posts. An API key (`X-Api-Key`) protects every `/api/*` route.
- **App** — Kotlin + Jetpack Compose, Room for the local queue (offline-first), Retrofit for the backend. Facebook Login via the Facebook SDK.
- **Website** — static HTML/CSS/JS, no build step. It calls the backend through a Netlify Function that injects the API key server-side, so the key never appears in the browser.
- **HTTPS** — Caddy on the VM terminates TLS (Let's Encrypt, auto-renewed) for a free DuckDNS subdomain and reverse-proxies to the backend.

## Quick start (local development)

You can run the whole thing locally with no cloud accounts at all. The backend works with just a JSON file and local disk; the app points at it.

### Backend

```sh
cd backend
npm install
npm start
```

That starts it on `http://localhost:3000`. With no env vars set it uses a local JSON store (`data/planner.json`, gitignored) and local `uploads/`, and publishing fails honestly with "Facebook Page token not configured" — it never invents a published post id. Set the env vars in [Configuration](#configuration) to connect a real Page, a database, and object storage.

### Android app

Prerequisites: Android Studio (or JDK 17 + Android SDK 35), and a device or emulator.

```sh
# Build a debug APK
./gradlew assembleDebug
# Install on the connected device
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app talks to the backend URL in `gradle.properties` (`planner.backend.url`, default `https://postschedulerapp.duckdns.org`). For local development, point it at `http://<your-pc-ip>:3000` and set the same URL in the app's Settings. You also need a Facebook App ID and client token — see [Facebook setup](#facebook-setup).

### Website

No build step. Serve the folder (or drag it onto Netlify) and set the backend URL on the Connect tab, or leave it empty to use the Netlify Function. See [`post-scheduler-web`](https://github.com/KarinaFromAespa/post-scheduler-web) for its own README.

## Server setup (production)

This is the setup the project runs on. It is one way to do it — the [alternatives](#alternatives) section lists simpler and different options.

### What you need

| Piece | What we use | Cost |
|---|---|---|
| A server | Oracle Cloud Always Free VM (VM.Standard.E2.1.Micro, 1 OCPU / 1 GB) | Free |
| A domain | DuckDNS subdomain (`postschedulerapp.duckdns.org`) | Free |
| HTTPS | Caddy + Let's Encrypt (auto-renewed) | Free |
| Primary database | Oracle Autonomous Database (Always Free, OLTP) | Free |
| Primary media | Oracle Object Storage bucket (Always Free, 20 GB) | Free |
| DR backup | Neon Postgres + Neon Object Storage (free tier) | Free |
| Frontend hosting | Netlify (manual drag-and-drop deploy) | Free |

### 1. Create the VM and install the backend

```sh
# On the VM (Ubuntu 24.04)
sudo apt update && sudo apt install -y nodejs npm git
git clone https://github.com/KarinaFromAespa/post-scheduler.git ~/post-scheduler
cd ~/post-scheduler/backend
npm install
```

### 2. Configure it

Create `backend/.env` (gitignored — never commit it):

```env
# Required
API_KEY=<a long random string, e.g. openssl rand -hex 32>
PUBLIC_BASE_URL=https://<your-domain>
FB_APP_ID=<your Facebook App ID>
FB_APP_SECRET=<your Facebook App Secret>
# Optional — database and object storage (see Configuration)
ORACLE_DB_USER=ADMIN
ORACLE_DB_PASSWORD=<Autonomous DB ADMIN password>
ORACLE_DB_CONNECT_STRING=tcps://adb.<region>.oraclecloud.com:1521/<db>_tp.adb.oraclecloud.com
ORACLE_OBJECT_STORAGE_NAMESPACE=<namespace>
ORACLE_OBJECT_STORAGE_BUCKET=planner-media
ORACLE_OBJECT_STORAGE_REGION=<region>
NEON_POSTGRES_URL=postgres://<user>:<password>@<host>/<db>?sslmode=require
NEON_S3_ENDPOINT=https://<endpoint>
NEON_S3_ACCESS_KEY_ID=<key>
NEON_S3_SECRET_ACCESS_KEY=<secret>
NEON_S3_BUCKET=planner-media-dr
NEON_S3_REGION=<region>
```

### 3. Run it as a service

```sh
sudo tee /etc/systemd/system/facebook-planner-backend.service > /dev/null <<'EOF'
[Unit]
Description=Post Scheduler backend
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/home/ubuntu/post-scheduler/backend
ExecStart=/usr/bin/node server.js
Restart=always
RestartSec=5
EnvironmentFile=/home/ubuntu/post-scheduler/backend/.env

[Install]
WantedBy=multi-user.target
EOF
sudo systemctl daemon-reload
sudo systemctl enable --now facebook-planner-backend
```

### 4. Put HTTPS in front of it (Caddy)

The backend listens on `:25565` (or any port you choose). Caddy terminates TLS and reverse-proxies to it, so the backend itself never needs a certificate.

```sh
# Install Caddy (official repo)
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy
```

`/etc/caddy/Caddyfile`:

```
<your-domain> {
    reverse_proxy localhost:25565
}
```

```sh
sudo systemctl reload caddy
```

Caddy obtains a Let's Encrypt certificate automatically the first time the domain is reachable on port 80, and renews it on its own. Open ports **80** and **443** (and **22** for SSH) in the cloud firewall and the OS firewall; **close the backend's direct port** once Caddy is in front of it, so the API is only reachable through HTTPS.

### 5. Point a domain at the VM

Any domain works. A free DuckDNS subdomain is the simplest:

1. Sign in at [duckdns.org](https://www.duckdns.org), create a subdomain (e.g. `postschedulerapp`).
2. Set its IP to the VM's public IP.
3. Wait a minute, then confirm from the VM: `getent hosts <your-domain>.duckdns.org` should return the VM's IP.

### 6. Wire the clients

- **Android app** — set `planner.backend.url=https://<your-domain>` in `gradle.properties`, and put the same API key in the machine-local `~/.gradle/gradle.properties` as `planner.api.key=<key>` (never in the committed `gradle.properties`). Rebuild and install.
- **Website** — set the Netlify env var `PLANNER_API_KEY` to the same key (the Netlify Function injects it server-side). See the website repo's README.

## Facebook setup

1. Create an app at [developers.facebook.com](https://developers.facebook.com) (use case: "Manage everything on your Page" / Facebook Login for Business).
2. Add **Facebook Login** and set the **Valid OAuth Redirect URIs** to include your website callback, e.g. `https://<your-site>/fb-callback.html`.
3. Set the **Privacy Policy URL** to your hosted policy (the website repo has one at `/privacy-policy.html`, public outside the login).
4. Note the **App ID** and **App Secret** — they go in the backend `.env` (`FB_APP_ID`, `FB_APP_SECRET`) and the app's machine-local `gradle.properties` (`planner.facebook.app.id`, `planner.facebook.client.token`).
5. Permissions the app requests: `public_profile`, `pages_show_list`, `pages_read_engagement`, `pages_manage_posts`, `business_management`, `read_insights`, `pages_read_user_content`. The app works without advanced access, but only accounts with a role on the app (owner, Testers) can log in until advanced access is granted — add other accounts as Testers in App Roles.
6. If your Pages live in a **Business Portfolio**, the person logging in must pick the Pages in the asset picker ("current pages / business integration only") — that selection is what attaches the Pages.

## Configuration

Backend env vars (all in `backend/.env`, gitignored):

| Variable | Required | What it does |
|---|---|---|
| `PORT` | No (default `3000`) | Port the backend listens on. |
| `API_KEY` | Yes (production) | Shared secret. Clients send it as `X-Api-Key`. When unset, the API is open — fine for local dev, never for a public server. |
| `PUBLIC_BASE_URL` | Yes | Public HTTPS address of the backend (e.g. `https://postschedulerapp.duckdns.org`). Media URLs are computed from it, so changing it + restarting switches every post's URLs. |
| `FB_APP_ID` / `FB_APP_SECRET` | Yes (for login) | Used to exchange short-lived Page tokens for long-lived ones (60 days). |
| `FB_PAGE_ID` / `FB_PAGE_TOKEN` | No | Fallback Page used when a post has no `pageId`. Prefer connecting Pages through the app/website instead. |
| `ORACLE_DB_USER` / `ORACLE_DB_PASSWORD` / `ORACLE_DB_CONNECT_STRING` | No | Oracle Autonomous DB (primary queue store). Without these, the queue lives in the local JSON file only. |
| `ORACLE_OBJECT_STORAGE_NAMESPACE` / `ORACLE_OBJECT_STORAGE_BUCKET` / `ORACLE_OBJECT_STORAGE_REGION` | No | Oracle Object Storage (primary media). Without these, media lives on local disk only. |
| `NEON_POSTGRES_URL` | No | Neon Postgres (DR copy of the queue). |
| `NEON_S3_ENDPOINT` / `NEON_S3_ACCESS_KEY_ID` / `NEON_S3_SECRET_ACCESS_KEY` / `NEON_S3_BUCKET` / `NEON_S3_REGION` | No | Neon Object Storage (DR copy of media). |

App `gradle.properties` (committed file keeps placeholders; real values go in the machine-local `~/.gradle/gradle.properties`):

| Property | What it does |
|---|---|
| `planner.backend.url` | Backend base URL (e.g. `https://postschedulerapp.duckdns.org`). |
| `planner.api.key` | The backend API key, sent as `X-Api-Key` on every request. |
| `planner.facebook.app.id` | Facebook App ID. |
| `planner.facebook.client.token` | Facebook client token. |

## API reference

Base URL is the backend address. Every `/api/*` route requires the `X-Api-Key` header when `API_KEY` is set (401 otherwise). `/health`, `/`, and `/uploads/*` are public.

| Method & path | What it does |
|---|---|
| `GET /health` | Health check: `{ok, queuePaused, facebookConfigured, counts}`. |
| `GET /api/queue` | Scheduled posts only. |
| `GET /api/drafts` | Drafts only. |
| `GET /api/posts` | Every post, all statuses, sorted by scheduled time. |
| `POST /api/posts` | Create a post. Body: `type`, `caption`, `scheduledAt` (ISO, null = draft), `status`, `publishNow`, `pageId`, `tempMediaIds` (files already uploaded to temp staging). Returns the post. |
| `PATCH /api/posts/:id` | Edit a scheduled post or draft (caption, time, type, status, pageId, `tempMediaIds`). Validates before mutating; refuses published/publishing posts; scheduling < 2 min ahead is a 400. |
| `DELETE /api/posts/:id` | Delete a post and its media. |
| `POST /api/media/temp` | Upload a file to temp staging (multipart `file`). Returns `{id, filename, mimetype, url}`. Use this the moment a photo is picked, before the post exists. |
| `DELETE /api/media/temp/:id` | Remove a staged file (from disk + both buckets). A missing id returns `{ok:true}`. |
| `DELETE /api/posts/:id/media` | Delete a post's media files. |
| `POST /api/posts/:id/publish-now` | Publish immediately, bypassing the queue. |
| `GET /api/queue/state` | `{paused}`. |
| `POST /api/queue/pause` / `POST /api/queue/resume` | Pause / resume the queue. |
| `GET /api/pages` | Connected Pages (tokens never exposed). |
| `POST /api/pages/connect` | Connect a Page: `{pageId, pageName, accessToken, pictureUrl}`. The token is exchanged for a long-lived one when `FB_APP_ID` + `FB_APP_SECRET` are set. |
| `DELETE /api/pages/:id` | Disconnect a Page: revokes its token at Facebook, deletes it, and moves its scheduled posts to Drafts (time kept). |
| `GET /api/insights?pageId=` | Total approximate earnings for the current month: `{monetized, monetizationCriteria, totalEarningsUsd}`. Just 2 Graph API calls. |

## How the pieces work

- **Publishing** — every 60 seconds the publisher picks up `scheduled` posts with `scheduledAt <= now` (unless paused). A post is marked published only when Facebook returns a post id; without one it is marked failed and its media is kept for retry. Media is deleted only after a confirmed publish.
- **Background upload** — when a photo is picked it is uploaded to temp staging (`POST /api/media/temp`) right away. Saving a post sends the temp ids (`tempMediaIds`) and the backend claims them into the post, so saving never waits on an upload. Removing a picked photo calls `DELETE /api/media/temp/:id`. A file staged longer than 2 hours without being claimed is reclaimed by an hourly sweep, along with any unreferenced file in the uploads directory.
- **Storage** — the in-memory store is the working copy. Every mutation writes the local JSON file synchronously and queues a serialized background write to Oracle (primary) then Neon (DR). The JSON file is the local backup; on startup the backend loads JSON, then Oracle, then Neon (first non-empty wins), then syncs the whole state back to both databases.
- **Tokens** — Page access tokens are stored on the backend (in-memory, JSON, Oracle, Neon) and never returned by any API endpoint. The app never stores a Page token on the phone; the website keeps the user token in `sessionStorage` (cleared when the tab closes). Disconnecting a Page revokes its token at Facebook before deleting it.
- **Insights** — the backend makes exactly 2 Graph API calls (`content_monetization_earnings` + `monetization_approximate_earnings`, current calendar month) and returns only the total. Per-post earnings and engagement were deliberately removed to keep the rate-limit cost near zero.

## Alternatives

You do not need Oracle, Neon, Caddy, or DuckDNS. The backend is written so each piece can be swapped without touching the API the clients use.

| Piece | What we use | Simpler alternative | Other options |
|---|---|---|---|
| Server | Oracle Always Free VM | Any VPS or your own PC (run `npm start`, expose the port) | Railway, Render, Fly.io, a Raspberry Pi |
| Database | Oracle Autonomous DB + Neon (DR) | Just the local JSON file (`data/planner.json`) — no env vars needed; fine for one user | Postgres, MySQL, SQLite, Supabase, Turso |
| Media storage | Oracle Object Storage + Neon S3 | Local disk (`uploads/`) only | S3, Cloudflare R2, Backblaze B2, any S3-compatible store |
| HTTPS / reverse proxy | Caddy + DuckDNS | None for local use; or `ngrok` / Cloudflare Tunnel for a quick public URL | nginx + certbot, Traefik |
| Frontend hosting | Netlify (manual deploy) | Serve the `post-scheduler-web` folder from any static host, or from the backend itself | Vercel, GitHub Pages, Cloudflare Pages |
| Facebook app | Facebook Login for Business | (Required for publishing as a Page — there is no alternative to Facebook's API here.) | — |

To run with **no cloud services at all**: clone this repo, `cd backend && npm install && npm start`, point the app at `http://<your-ip>:3000`, and connect a Page. The queue lives in `data/planner.json` and media in `uploads/`. Back up those two paths and you lose nothing.

## Commands

```sh
# Backend
cd backend
npm install          # install dependencies
npm start            # run on :3000 (or $PORT)
node --check server.js   # syntax check a file

# Android app (from the repo root)
./gradlew assembleDebug        # build debug APK
./gradlew assembleRelease      # release APK (needs a signing config)
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Check the live backend
curl https://<your-domain>/health
```

## Contributing

Contributions are welcome. A few ground rules that keep the project reviewable:

- **Keep it minimal.** Small, focused changes; no unnecessary abstractions, no extra dependencies without a reason.
- **Only keep comments that are necessary.** If the code says it, don't comment it.
- **No secrets in the repo.** API keys, tokens, and passwords live in `.env` and machine-local `gradle.properties` only — never in committed files.
- **Test on a real device / the live site** before opening a PR for anything user-facing.
- **One concern per PR**, with a clear description of what changed and how it was verified.

## License

MIT. See `LICENSE`.
