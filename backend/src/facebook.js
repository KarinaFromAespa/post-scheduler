const GRAPH = 'https://graph.facebook.com/v23.0';
const store = require('./store');

function envConfigured() {
  return Boolean(process.env.FB_PAGE_TOKEN && process.env.FB_PAGE_ID);
}

// Resolve the Page + token for a post: the post's connected Page first,
// then the env-configured Page.
function pageFor(post) {
  if (post && post.pageId) {
    const page = store.getPage(post.pageId);
    if (page && page.accessToken) return { id: page.id, token: page.accessToken };
  }
  if (envConfigured()) return { id: process.env.FB_PAGE_ID, token: process.env.FB_PAGE_TOKEN };
  return null;
}

function configured() {
  return store.allPages().length > 0 || envConfigured();
}

// Graph call. GET puts params in the query string; POST sends them as a
// form-encoded body (what the Graph API expects for publishing). The old
// code did fetch(url) for everything, so every publish went out as a GET.
async function graph(path, { params = {}, method = 'GET', token } = {}) {
  const url = new URL(GRAPH + path);
  let body = null;
  if (method === 'GET') {
    for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);
  } else {
    body = new URLSearchParams(params);
  }
  if (token) url.searchParams.set('access_token', token);
  // No timeout means a hung Facebook connection sticks the publisher on
  // one post forever (the video-story poll only checks its deadline after
  // a fetch returns). 30s is generous for a Graph call.
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), 30 * 1000);
  let res;
  try {
    res = await fetch(url, {
      method,
      ...(body ? { body, headers: { 'Content-Type': 'application/x-www-form-urlencoded' } } : {}),
      signal: ctrl.signal,
    });
  } catch (e) {
    if (e.name === 'AbortError') throw new Error('Facebook API timed out (30s)');
    throw e;
  } finally {
    clearTimeout(timer);
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data?.error?.message || `Facebook API ${res.status}`);
  return data;
}

// Revoke a Page access token at Facebook: DELETE /{page-id}/permissions
// with the Page token removes every permission the Page granted to the
// app, which invalidates the token so it can never publish again. Used
// when a Page is disconnected. Best-effort — the caller still removes the
// Page locally even if this fails (e.g. the token already expired).
async function revokePageToken(pageId, token) {
  await graph(`/${pageId}/permissions`, { method: 'DELETE', token });
}

// A Page's profile picture URL, via the Page token. One Graph call, no
// rate-limit concern (it is the picture field on the Page, not an insights
// metric). Returns null when the picture cannot be read.
async function pagePictureUrl(pageId, token) {
  try {
    const d = await graph(`/${pageId}`, { params: { fields: 'picture.type(large)' }, token });
    const url = d.picture?.data?.url;
    return (typeof url === 'string' && url) ? url : null;
  } catch { return null; }
}

async function publishPost(post, mediaFiles) {
  const page = pageFor(post);
  if (!page) {
    return { ok: false, reason: 'No Facebook Page connected (connect a Page from the website, or set FB_PAGE_TOKEN and FB_PAGE_ID)' };
  }
  if (post.type === 'story') return publishStory(post, mediaFiles, page);
  const params = { message: post.caption };
  if (mediaFiles.length === 0) {
    const body = await graph(`/${page.id}/feed`, { params, method: 'POST', token: page.token });
    return { ok: true, facebookPostId: body.id };
  }
  if (post.type === 'reel' || mediaFiles[0].mimetype.startsWith('video/')) {
    const body = await graph(`/${page.id}/videos`, {
      params: { ...params, file_url: mediaFiles[0].url, description: post.caption },
      method: 'POST', token: page.token,
    });
    return { ok: true, facebookPostId: body.id };
  }
  const attached = [];
  for (const f of mediaFiles) {
    const body = await graph(`/${page.id}/photos`, {
      params: { url: f.url, published: 'false' }, method: 'POST', token: page.token,
    });
    attached.push({ media_fbid: body.id });
  }
  const body = await graph(`/${page.id}/feed`, {
    params: { ...params, attached_media: JSON.stringify(attached) },
    method: 'POST', token: page.token,
  });
  return { ok: true, facebookPostId: body.id };
}

async function publishStory(post, mediaFiles, page) {
  if (mediaFiles.length === 0) {
    return { ok: false, reason: 'Story needs one photo or video' };
  }
  if (mediaFiles[0].mimetype.startsWith('video/')) {
    return publishVideoStory(mediaFiles[0], page);
  }
  if (!mediaFiles[0].mimetype.startsWith('image/')) {
    return { ok: false, reason: 'Story needs one photo or video' };
  }
  const body = await graph(`/${page.id}/stories`, {
    params: { photo_url: mediaFiles[0].url },
    method: 'POST', token: page.token,
  });
  return { ok: true, facebookPostId: body.id };
}

// Video stories need a three-phase upload: open a session, upload the
// file to the upload URL Facebook returns, then publish once it has
// finished processing. The video bytes are uploaded directly (resumable
// protocol, Offset header) — the file_url shortcut left the video
// unprocessed on Facebook's side: the upload reported success but
// processing_phase stayed "not_started" forever, so the story timed out.
async function publishVideoStory(file, page) {
  const start = await graph(`/${page.id}/video_stories`, {
    params: { upload_phase: 'start' }, method: 'POST', token: page.token,
  });
  const videoId = start.video_id;
  const uploadUrl = start.upload_url;
  if (!videoId || !uploadUrl) throw new Error('Facebook did not start a video story upload session');
  // Read the video bytes from our own public URL, then send them to
  // Facebook. A hung read or upload must not stick the story in
  // "publishing" forever, so both have timeouts.
  const fileRes = await fetch(file.url, { signal: AbortSignal.timeout(60 * 1000) });
  if (!fileRes.ok) throw new Error(`Could not read the story video (${fileRes.status})`);
  const bytes = Buffer.from(await fileRes.arrayBuffer());
  const uploadCtrl = new AbortController();
  const uploadTimer = setTimeout(() => uploadCtrl.abort(), 120 * 1000);
  let uploadRes;
  try {
    uploadRes = await fetch(uploadUrl, {
      method: 'POST',
      headers: {
        Authorization: `OAuth ${page.token}`,
        Offset: '0',
        'Content-Length': String(bytes.length),
        'X-Entity-Length': String(bytes.length),
        'Content-Type': file.mimetype || 'video/mp4',
      },
      body: bytes,
      signal: uploadCtrl.signal,
      duplex: 'half',
    });
  } catch (e) {
    if (e.name === 'AbortError') throw new Error('Video story upload timed out (120s)');
    throw e;
  } finally {
    clearTimeout(uploadTimer);
  }
  const uploadBody = await uploadRes.json().catch(() => ({}));
  if (!uploadRes.ok) {
    throw new Error(uploadBody?.error?.message || uploadBody?.debug_info?.message || `Video story upload failed (${uploadRes.status})`);
  }
  // Wait for Facebook to finish processing the video — publishing before
  // that fails. Poll the video status, giving up after 90 seconds.
  const deadline = Date.now() + 90 * 1000;
  for (;;) {
    const status = await graph(`/${videoId}`, {
      params: { fields: 'status' }, token: page.token,
    });
    const phase = status?.status?.processing_phase?.status;
    if (phase === 'complete') break;
    if (phase === 'error') throw new Error('Facebook failed to process the story video');
    if (Date.now() > deadline) throw new Error('Timed out waiting for Facebook to process the story video');
    await new Promise((resolve) => setTimeout(resolve, 3000));
  }
  const finish = await graph(`/${page.id}/video_stories`, {
    params: { upload_phase: 'finish', video_id: videoId }, method: 'POST', token: page.token,
  });
  return { ok: true, facebookPostId: finish.post_id };
}

// Exchange a short-lived Page token for a long-lived one (60 days). Needs
// FB_APP_ID + FB_APP_SECRET as backend env vars; without them the token is
// stored as-is and the caller is told it is short-lived.
async function exchangeLongLivedToken(shortToken) {
  const appId = process.env.FB_APP_ID;
  const appSecret = process.env.FB_APP_SECRET;
  if (!appId || !appSecret) return { token: shortToken, longLived: false };
  const data = await graph('/oauth/access_token', {
    params: {
      grant_type: 'fb_exchange_token',
      client_id: appId,
      client_secret: appSecret,
      fb_exchange_token: shortToken,
    },
  });
  return { token: data.access_token, longLived: true };
}

// Read one day's earnings value from an insights metric. The two
// monetization metrics report on different scales:
// - content_monetization_earnings: micro-units, the value is an object
//   { amount, currency } — amount / 1e6 = dollars.
// - monetization_approximate_earnings: a bare number, already in dollars.
function earningsAmount(value) {
  if (typeof value === 'number') return value;
  if (value && typeof value.amount === 'number') return value.amount / 1e6;
  if (value && typeof value.value === 'number') return value.value / 1e6;
  return 0;
}

// Total approx earnings for the current calendar month, nothing else.
// Just 2 Graph API calls (the two monetization metrics on the /insights
// edge) — no per-post reads, no shares/likes/comments. The total is NOT
// intertwined with the per-post data, so this costs almost no rate limit.
// These are insights METRICS (Graph v23.0) on the /insights edge — not
// Page fields. Reading them needs the read_insights permission on the Page
// token; without it the edge returns empty data instead of an error.
//
// pageId selects which Page the total is for. Without it the first
// connected Page is used. The app/website pass the currently selected
// Page, so switching Pages shows that Page's earnings instead of the
// first Page's.
async function insights(pageId) {
  if (!configured()) {
    return {
      monetized: false,
      monetizationCriteria: 'Not enrolled in Content Monetization. Check Meta Business Suite > Monetization > View Page eligibility. Earnings show here once the Page is enrolled.',
      totalEarningsUsd: null,
    };
  }
  const pages = store.allPages();
  const found = pageId ? pages.find((p) => p.id === pageId) : null;
  const first = found || pages[0];
  const page = first
    ? { id: first.id, token: first.accessToken }
    : { id: process.env.FB_PAGE_ID, token: process.env.FB_PAGE_TOKEN };

  let total = null;
  let monetized = false;
  const now = new Date();
  const since = Math.floor(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), 1) / 1000);
  const until = Math.floor(Date.now() / 1000);
  for (const metric of ['content_monetization_earnings', 'monetization_approximate_earnings']) {
    try {
      const res = await graph(`/${page.id}/insights`, {
        params: { metric, period: 'day', since, until },
        token: page.token,
      });
      const days = res.data?.[0]?.values || [];
      if (days.length > 0) monetized = true;
      for (const d of days) {
        total = (total ?? 0) + earningsAmount(d.value);
      }
    } catch { /* try the other metric */ }
  }
  if (monetized) total = total ?? 0;
  return {
    monetized,
    monetizationCriteria: monetized
      ? null
      : 'No Content Monetization earnings found this month. If the Page is enrolled, check that the Page token has the read_insights permission (re-connect the Page after adding it) and see Meta Business Suite > Monetization > View Page eligibility.',
    totalEarningsUsd: total,
  };
}

module.exports = { configured, publishPost, insights, exchangeLongLivedToken, pagePictureUrl, revokePageToken };
