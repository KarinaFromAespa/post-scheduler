# Post Scheduler — backend (Node)

Queue-and-publish backend for the Android app and the website. It owns the queue, the 1-minute publisher, media storage, connected Pages, and Insights. It matches the app's `PlannerApi` and the website's API surface exactly.

## Run

```sh
npm install
npm start
```

That starts it on `http://localhost:3000` (or `$PORT`). With no env vars set it uses a local JSON store (`data/planner.json`, gitignored) and local `uploads/`, and publishing fails honestly with "Facebook Page token not configured" — it never invents a published post id or earnings.

## How it works

- **Queue** — posts are `draft` / `scheduled` / `publishing` / `published` / `failed`. Multiple items can share one time slot; each publishes independently, so one failure never blocks the others.
- **1-minute publisher** — every 60 seconds it picks up `scheduled` posts with `scheduledAt <= now`, unless the queue is paused. Short-notice scheduling (2–10 min ahead) works through this, not Facebook native scheduling (which needs 10+ minutes).
- **Publish Now** — `POST /api/posts/:id/publish-now` publishes immediately, bypassing the queue entirely.
- **Pause / Resume** — `POST /api/queue/pause` holds all scheduled posts without deleting them; `resume` releases them.
- **Publishing** — all four publish calls (`/{pageId}/feed`, `/photos`, `/videos`, `/stories`) are real POSTs to the Graph API. A post is marked published only when Facebook returns a post id; without one it is marked failed and its media is kept for retry. Media is deleted only after a confirmed publish.
- **Background upload (temp staging)** — a photo is uploaded the moment the user picks it, before the post exists: `POST /api/media/temp` (multipart `file`) returns `{id, filename, mimetype, url}`. Saving a post sends those ids as `tempMediaIds` and the backend claims them into the post, so saving never waits on an upload. `DELETE /api/media/temp/:id` removes a staged file the user took off (a missing id returns `{ok:true}`). On an edit, `PATCH` with `tempMediaIds` deletes the old media files and claims the replacements. A file staged longer than 2 hours without being claimed is reclaimed by an hourly sweep, along with any unreferenced file in the uploads directory.
- **Media** — uploaded files go to local `uploads/` (serving cache) and, when configured, to Oracle Object Storage (primary) and Neon Object Storage (DR). Facebook fetches media by URL, so `PUBLIC_BASE_URL` must be the backend's public address — media URLs are computed at DTO time, so changing it + restarting switches every post's URLs.
- **Storage** — the in-memory store is the working copy. Every mutation writes the local JSON file synchronously and queues a serialized background write to Oracle (primary) then Neon (DR). On startup it loads JSON, then Oracle, then Neon (first non-empty wins), then syncs the whole state back to both databases. Temp media is JSON-only and never synced to the databases.
- **Connected Pages** — `POST /api/pages/connect` stores a Page (`pageId`, `pageName`, Page access token, `pictureUrl`). A post's `pageId` picks which Page it publishes as; without one it falls back to the env-configured Page. Short-lived tokens are exchanged for long-lived ones (60 days) when `FB_APP_ID` + `FB_APP_SECRET` are set. `GET /api/pages` lists connected Pages — tokens are never exposed. `DELETE /api/pages/:id` revokes the Page's token at Facebook, deletes the Page, and moves its scheduled posts to Drafts (time kept).
- **Insights** — total approximate earnings for the current month only: `{monetized, monetizationCriteria, totalEarningsUsd}`. Just 2 Graph API calls (`content_monetization_earnings` + `monetization_approximate_earnings`). No per-post data, no likes/comments/shares — deliberately, to keep the rate-limit cost near zero.
- **Auth** — every `/api/*` route requires the `X-Api-Key` header when `API_KEY` is set (401 otherwise). `/health`, `/`, and `/uploads/*` stay public. When `API_KEY` is unset the API is open — fine for local dev, never for a public server.

## Configuration

All in `backend/.env` (gitignored — never commit it):

| Variable | Required | What it does |
|---|---|---|
| `PORT` | No (default `3000`) | Port to listen on. |
| `API_KEY` | Yes (production) | Shared secret, sent as `X-Api-Key`. Generate with `openssl rand -hex 32`. |
| `PUBLIC_BASE_URL` | Yes | Public HTTPS address (e.g. `https://postschedulerapp.duckdns.org`). |
| `FB_APP_ID` / `FB_APP_SECRET` | Yes (for login) | Exchanges short-lived Page tokens for long-lived ones (60 days). |
| `FB_PAGE_ID` / `FB_PAGE_TOKEN` | No | Fallback Page when a post has no `pageId`. |
| `ORACLE_DB_USER` / `ORACLE_DB_PASSWORD` / `ORACLE_DB_CONNECT_STRING` | No | Oracle Autonomous DB (primary queue store). Without these, the queue lives in the local JSON file only. |
| `ORACLE_OBJECT_STORAGE_NAMESPACE` / `ORACLE_OBJECT_STORAGE_BUCKET` / `ORACLE_OBJECT_STORAGE_REGION` | No | Oracle Object Storage (primary media). Without these, media lives on local disk only. |
| `NEON_POSTGRES_URL` | No | Neon Postgres (DR copy of the queue). |
| `NEON_S3_ENDPOINT` / `NEON_S3_ACCESS_KEY_ID` / `NEON_S3_SECRET_ACCESS_KEY` / `NEON_S3_BUCKET` / `NEON_S3_REGION` | No | Neon Object Storage (DR copy of media). |

## API

Every `/api/*` route requires `X-Api-Key` when `API_KEY` is set.

| Method & path | What it does |
|---|---|
| `GET /health` | `{ok, queuePaused, facebookConfigured, counts}`. |
| `GET /api/queue` / `GET /api/drafts` / `GET /api/posts` | Scheduled / drafts / every post. |
| `POST /api/posts` | Create. Body: `type`, `caption`, `scheduledAt` (ISO, null = draft), `status`, `publishNow`, `pageId`, `tempMediaIds`. |
| `PATCH /api/posts/:id` | Edit (caption, time, type, status, pageId, `tempMediaIds`). Validates before mutating; refuses published/publishing; scheduling < 2 min ahead is a 400. |
| `DELETE /api/posts/:id` | Delete a post and its media. |
| `POST /api/media/temp` | Stage a file before the post exists. Returns `{id, filename, mimetype, url}`. |
| `DELETE /api/media/temp/:id` | Remove a staged file. Missing id returns `{ok:true}`. |
| `DELETE /api/posts/:id/media` | Delete a post's media files. |
| `POST /api/posts/:id/publish-now` | Publish immediately. |
| `GET /api/queue/state`, `POST /api/queue/pause`, `POST /api/queue/resume` | Pause state. |
| `GET /api/pages`, `POST /api/pages/connect`, `DELETE /api/pages/:id` | Connected Pages. |
| `GET /api/insights?pageId=` | Total approx earnings this month. |

## Files

| File | What it is |
|---|---|
| `server.js` | Express app: routes, API-key middleware, temp-media sweep, publisher tick. |
| `src/store.js` | In-memory state + JSON file + Oracle/Neon write-through + temp-media staging. |
| `src/media.js` | Multer uploads, local disk cache, Oracle/Neon bucket copies, temp staging. |
| `src/publisher.js` | 1-minute publisher: picks up due posts, publishes, marks success/failure. |
| `src/facebook.js` | Graph API client (v23.0): publish calls, token exchange, revocation, Insights. |
| `src/db/oracle.js` | Oracle Autonomous DB (oracledb Thin mode, pool). |
| `src/db/neon.js` | Neon Postgres (pg Pool). |
| `src/db/mediaStore.js` | Oracle Object Storage (instance principal) + Neon S3. |
| `schema-oracle.sql` / `schema-neon.sql` | Table definitions. |
| `setup-oracle.js` | Creates the Oracle tables (run from the VM — the DB ACL allows only the VM's IP). |
| `migrate-media.js` | One-off media migration helper. |

## Deploy (production)

The setup this project runs on: an Oracle Always Free VM, Caddy for HTTPS, and a free DuckDNS domain. The full steps (VM, Caddy, domain, firewall, systemd service) are in the monorepo root `README.md` under "Server setup (production)". The short version:

```sh
# On the VM
git clone https://github.com/KarinaFromAespa/post-scheduler.git
cd post-scheduler/backend && npm install
# Write backend/.env (see Configuration above), then:
sudo systemctl enable --now facebook-planner-backend   # see root README for the unit file
```

## Swapping the storage

Each store is isolated in one module, so you can swap it without touching the API:

- **Queue store** — `src/store.js`. Replace the Oracle/Neon write-through with Postgres, MySQL, SQLite, or just keep the JSON file (the default with no env vars).
- **Media store** — `src/db/mediaStore.js`. Replace the Oracle/Neon bucket calls with S3, Cloudflare R2, Backblaze B2, or any S3-compatible store — or just local disk (the default).
- **HTTPS / proxy** — Caddy is only a reverse proxy. nginx + certbot, Traefik, or Cloudflare Tunnel all work; the backend itself only needs `PUBLIC_BASE_URL` set to whatever the public address is.
