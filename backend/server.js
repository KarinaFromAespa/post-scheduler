require('dotenv').config();
const crypto = require('crypto');
const express = require('express');
const path = require('path');
const store = require('./src/store');
const media = require('./src/media');
const publisher = require('./src/publisher');
const facebook = require('./src/facebook');

const app = express();
app.use(express.json({ limit: '1mb' }));
app.use('/uploads', express.static(path.join(__dirname, 'uploads')));

// API key auth: every /api/* call must send X-Api-Key matching the
// API_KEY env var. /uploads stays public (Facebook fetches media from it
// with no key) and /health + / stay public for monitoring. When no
// API_KEY is set the check is skipped, so a missing env var can't lock
// the backend out — set API_KEY on the VM to enforce it.
const API_KEY = process.env.API_KEY || null;
app.use('/api', (req, res, next) => {
  if (!API_KEY) return next();
  const supplied = req.headers['x-api-key'];
  if (typeof supplied === 'string' && supplied.length > 0) {
    const a = Buffer.from(supplied);
    const b = Buffer.from(API_KEY);
    if (a.length === b.length && crypto.timingSafeEqual(a, b)) return next();
  }
  return res.status(401).json({ error: 'API key required' });
});

const PORT = Number(process.env.PORT || 3000);
const TYPES = new Set(['post', 'reel', 'story']);
const STATUSES = new Set(['draft', 'scheduled', 'publishing', 'published', 'failed']);

function toDto(p) {
  return {
    id: p.id, type: p.type, caption: p.caption, hashtags: p.hashtags || null,
    scheduledAt: p.scheduledAt || null, status: p.status,
    failureReason: p.failureReason || null, facebookPostId: p.facebookPostId || null,
    pageId: p.pageId || null,
    media: media.mediaFor(p).map((f) => ({ url: f.url, mimetype: f.mimetype, filename: f.filename })),
  };
}

function toPageDto(p) {
  return { id: p.id, name: p.name, connectedAt: p.connectedAt || null, pictureUrl: p.pictureUrl || null };
}

app.get('/health', (req, res) => res.json({
  ok: true, queuePaused: store.isQueuePaused(),
  facebookConfigured: facebook.configured(),
  counts: { queued: store.allPosts().filter((p) => p.status === 'scheduled').length },
}));

app.get('/', (req, res) => res.json({ name: 'facebook-post-planner-backend', version: '1.0.0' }));

app.get('/api/queue', (req, res) => res.json(
  store.allPosts().filter((p) => p.status === 'scheduled')
    .sort((a, b) => Date.parse(a.scheduledAt || 0) - Date.parse(b.scheduledAt || 0)).map(toDto),
));

// Every post, all statuses. The app syncs this (not just /api/queue) so
// it learns when a scheduled post publishes or fails: /api/queue only
// returns scheduled posts, so a published one drops out of it and the
// app kept showing it as scheduled forever.
app.get('/api/posts', (req, res) => res.json(
  store.allPosts()
    .sort((a, b) => Date.parse(a.scheduledAt || 0) - Date.parse(b.scheduledAt || 0)).map(toDto),
));

app.get('/api/drafts', (req, res) => res.json(
  store.allPosts().filter((p) => p.status === 'draft').map(toDto),
));

app.post('/api/posts', (req, res) => {
  const { type, caption, hashtags, scheduledAt, status, publishNow, pageId } = req.body || {};
  if (!TYPES.has(type)) return res.status(400).json({ error: 'type must be post, reel or story' });
  // Caption may be empty (stories have none; a reel or post can be media-only).
  // The clients still require text OR media before they send anything.
  if (typeof caption !== 'string' || caption.length > 1024) {
    return res.status(400).json({ error: 'caption must be a string, max 1024 chars' });
  }
  if (scheduledAt && Number.isNaN(Date.parse(scheduledAt))) {
    return res.status(400).json({ error: 'scheduledAt must be ISO-8601' });
  }
  const post = {
    id: crypto.randomUUID(), type, caption: caption.trim(),
    hashtags: hashtags || null, scheduledAt: scheduledAt || null,
    status: publishNow ? 'publishing' : (STATUSES.has(status) ? status : (scheduledAt ? 'scheduled' : 'draft')),
    failureReason: null, facebookPostId: null, mediaFiles: [],
    pageId: pageId || null,
    createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
  };
  store.upsertPost(post);
  if (publishNow) {
    publisher.publishOne(post).then((done) => res.json(toDto(done))).catch((e) => res.status(500).json({ error: e.message }));
  } else {
    res.status(201).json(toDto(post));
  }
});

app.get('/api/posts/:id', (req, res) => {
  const post = store.getPost(req.params.id);
  if (!post) return res.status(404).json({ error: 'not found' });
  res.json(toDto(post));
});

app.delete('/api/posts/:id', (req, res) => {
  const post = store.getPost(req.params.id);
  if (!post) return res.status(404).json({ error: 'not found' });
  media.deleteMedia(post);
  store.deletePost(req.params.id);
  res.json({ ok: true });
});

app.post('/api/posts/:id/media', media.upload.single('file'), async (req, res) => {
  const post = store.getPost(req.params.id);
  if (!post) return res.status(404).json({ error: 'not found' });
  if (!req.file) return res.status(400).json({ error: 'file is required' });
  const entry = await media.addMedia(post, req.file);
  post.mediaFiles = [...(post.mediaFiles || []), entry];
  post.updatedAt = new Date().toISOString();
  store.upsertPost(post);
  res.json(toDto(post));
});

// Replace the media of a post (used when editing: clear the old photos so
// the client can upload replacements). Deletes the files from disk.
app.delete('/api/posts/:id/media', (req, res) => {
  const post = store.getPost(req.params.id);
  if (!post) return res.status(404).json({ error: 'not found' });
  if (post.status === 'published' || post.status === 'publishing') {
    return res.status(400).json({ error: 'a published post cannot be edited' });
  }
  media.deleteMedia(post);
  post.mediaFiles = [];
  post.updatedAt = new Date().toISOString();
  store.upsertPost(post);
  res.json(toDto(post));
});

// Edit a scheduled post or draft: caption, hashtags, time/date, type and
// Page. A post that already went out (published/publishing) cannot be edited.
app.patch('/api/posts/:id', (req, res) => {
  const post = store.getPost(req.params.id);
  if (!post) return res.status(404).json({ error: 'not found' });
  if (post.status === 'published' || post.status === 'publishing') {
    return res.status(400).json({ error: 'a published post cannot be edited' });
  }
  const { type, caption, hashtags, scheduledAt, status, pageId } = req.body || {};
  if (type !== undefined && !TYPES.has(type)) {
    return res.status(400).json({ error: 'type must be post, reel or story' });
  }
  if (caption !== undefined && (typeof caption !== 'string' || caption.length > 1024)) {
    return res.status(400).json({ error: 'caption must be a string, max 1024 chars' });
  }
  if (scheduledAt !== undefined && scheduledAt !== null && Number.isNaN(Date.parse(scheduledAt))) {
    return res.status(400).json({ error: 'scheduledAt must be ISO-8601' });
  }
  if (status !== undefined && !['draft', 'scheduled'].includes(status)) {
    return res.status(400).json({ error: 'status must be draft or scheduled' });
  }
  // Work out the new status/time FIRST and validate them before touching
  // the post — the store hands out a reference to the live post, so a
  // rejected PATCH that already mutated it would corrupt it even though
  // it returns 400 (the draft would vanish and reappear as scheduled).
  const nextStatus = status !== undefined ? status : post.status;
  const nextScheduledAt = scheduledAt !== undefined ? (scheduledAt || null) : post.scheduledAt;
  // A scheduled post needs a time; a draft may KEEP a planned time (the
  // user picked it when creating the draft) — it just doesn't publish
  // until the status becomes scheduled. A past time can't be scheduled.
  const effectiveStatus = nextStatus === 'scheduled' && !nextScheduledAt ? 'draft' : nextStatus;
  if (effectiveStatus === 'scheduled' && Date.parse(nextScheduledAt) < Date.now() + 2 * 60 * 1000) {
    return res.status(400).json({ error: 'Pick a time at least 2 minutes ahead.' });
  }
  if (type !== undefined) post.type = type;
  if (caption !== undefined) post.caption = caption.trim();
  if (hashtags !== undefined) post.hashtags = hashtags || null;
  if (scheduledAt !== undefined) post.scheduledAt = scheduledAt || null;
  if (pageId !== undefined) post.pageId = pageId || null;
  if (status !== undefined) post.status = status;
  if (post.status === 'scheduled' && !post.scheduledAt) post.status = 'draft';
  post.failureReason = null;
  post.failureReason = null;
  post.updatedAt = new Date().toISOString();
  store.upsertPost(post);
  res.json(toDto(post));
});

app.post('/api/posts/:id/publish-now', async (req, res) => {
  const post = store.getPost(req.params.id);
  if (!post) return res.status(404).json({ error: 'not found' });
  const done = await publisher.publishOne(post);
  res.json(toDto(done));
});

app.get('/api/queue/state', (req, res) => res.json({ paused: store.isQueuePaused() }));
app.post('/api/queue/pause', (req, res) => res.json({ paused: store.setQueuePaused(true) }));
app.post('/api/queue/resume', (req, res) => res.json({ paused: store.setQueuePaused(false) }));

app.get('/api/pages', async (req, res) => {
  const pages = store.allPages();
  // Fill in a missing profile picture for pages connected before the
  // picture was stored (e.g. connected with the old app/website build).
  // One Graph call per page, once — after it is stored, no more fetches.
  for (const p of pages) {
    if (!p.pictureUrl && p.accessToken) {
      const url = await facebook.pagePictureUrl(p.id, p.accessToken);
      if (url) store.upsertPage({ ...p, pictureUrl: url });
    }
  }
  res.json(store.allPages().map(toPageDto));
});

app.post('/api/pages/connect', async (req, res) => {
  const { pageId, pageName, accessToken, pictureUrl } = req.body || {};
  if (!pageId || typeof pageId !== 'string') return res.status(400).json({ error: 'pageId is required' });
  if (!accessToken || typeof accessToken !== 'string') return res.status(400).json({ error: 'accessToken is required' });
  try {
    // Short-lived Page tokens (~1-2h) are exchanged for long-lived ones
    // (60 days) when FB_APP_ID + FB_APP_SECRET are set as backend env vars.
    // Without them the token is stored as-is and reported as short-lived.
    const { token, longLived } = await facebook.exchangeLongLivedToken(accessToken);
    // The picture URL is fetched here when the client did not send one,
    // so a Page always gets its picture even if it was connected from an
    // older app/website build.
    let url = pictureUrl || null;
    if (!url) url = await facebook.pagePictureUrl(pageId, token);
    const page = store.upsertPage({
      id: pageId, name: pageName || pageId, accessToken: token,
      pictureUrl: url,
      longLived, connectedAt: new Date().toISOString(),
    });
    res.status(201).json({ ...toPageDto(page), longLived });
  } catch (e) {
    res.status(400).json({ error: e.message });
  }
});

app.delete('/api/pages/:id', async (req, res) => {
  // Destroy the Page access token at Facebook first, so it can never
  // publish again — just deleting our copy would leave a live token
  // behind. Best-effort: the Page is removed locally even if the revoke
  // fails (e.g. the token already expired).
  const page = store.getPage(req.params.id);
  if (page && page.accessToken) {
    try { await facebook.revokePageToken(page.id, page.accessToken); } catch (e) { console.error('[pages] revoke token failed:', e.message); }
  }
  store.deletePage(req.params.id);
  store.draftScheduledPostsForPage(req.params.id);
  res.json({ ok: true });
});

app.get('/api/insights', async (req, res) => {
  res.json(await facebook.insights(req.query.pageId || null));
});

async function start() {
  await store.init();
  // Restore any local media file that is missing (e.g. after a VM rebuild)
  // from the Oracle bucket, so /uploads keeps serving it.
  for (const post of store.allPosts()) {
    await media.ensureLocalFiles(post);
  }
  app.listen(PORT, () => {
    console.log(`facebook-post-planner-backend listening on :${PORT}`);
    console.log(`facebook configured: ${facebook.configured()}`);
    publisher.start();
  });
}

start().catch((e) => {
  console.error('backend failed to start:', e);
  process.exit(1);
});
