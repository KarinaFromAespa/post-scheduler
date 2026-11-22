// Queue + Pages store.
// Working copy is in-memory (the publisher's 1-minute tick reads it, so it
// never touches a database). Every mutation is written synchronously to the
// local JSON file (the always-available backup) and queued to the databases
// in the background, in order: Oracle Autonomous DB first (primary), then
// Neon Postgres (DR copy). A failed DB write is logged and never blocks the
// API — the JSON file still holds the data, and the next startup re-syncs
// the whole state to both databases to heal any missed write.
const fs = require('fs');
const path = require('path');
const oracleDb = require('./db/oracle');
const neonDb = require('./db/neon');

const DATA_FILE = path.join(__dirname, '..', 'data', 'planner.json');

function emptyState() {
  return { posts: [], queuePaused: false, pages: [], tempMedia: [] };
}

function loadJson() {
  try {
    const state = JSON.parse(fs.readFileSync(DATA_FILE, 'utf8'));
    if (!Array.isArray(state.posts)) state.posts = [];
    if (typeof state.queuePaused !== 'boolean') state.queuePaused = false;
    if (!Array.isArray(state.pages)) state.pages = [];
    // Temp media is staging for background uploads; JSON-only, never
    // synced to Oracle/Neon — it is short-lived and the sweep reclaims it.
    if (!Array.isArray(state.tempMedia)) state.tempMedia = [];
    return state;
  } catch {
    return emptyState();
  }
}

function saveJson(state) {
  fs.mkdirSync(path.dirname(DATA_FILE), { recursive: true });
  const tmp = DATA_FILE + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(state, null, 2));
  fs.renameSync(tmp, DATA_FILE);
}

let state = loadJson();

// Serialized background DB write chain — keeps mutations in order so a
// later write can never be overwritten by an earlier one finishing late.
let writeChain = Promise.resolve();
function queueDb(fn) {
  writeChain = writeChain.then(fn).catch((e) => {
    console.error('[store] DB write failed:', e.message);
  });
  return writeChain;
}

async function dbUpsertPost(post) {
  if (oracleDb.configured()) {
    try { await oracleDb.upsertPost(post); } catch (e) { console.error('[store] Oracle upsertPost failed:', e.message); }
  }
  if (neonDb.configured()) {
    try { await neonDb.upsertPost(post); } catch (e) { console.error('[store] Neon upsertPost failed:', e.message); }
  }
}

async function dbDeletePost(id) {
  if (oracleDb.configured()) {
    try { await oracleDb.deletePost(id); } catch (e) { console.error('[store] Oracle deletePost failed:', e.message); }
  }
  if (neonDb.configured()) {
    try { await neonDb.deletePost(id); } catch (e) { console.error('[store] Neon deletePost failed:', e.message); }
  }
}

async function dbUpsertPage(page) {
  if (oracleDb.configured()) {
    try { await oracleDb.upsertPage(page); } catch (e) { console.error('[store] Oracle upsertPage failed:', e.message); }
  }
  if (neonDb.configured()) {
    try { await neonDb.upsertPage(page); } catch (e) { console.error('[store] Neon upsertPage failed:', e.message); }
  }
}

async function dbDeletePage(id) {
  if (oracleDb.configured()) {
    try { await oracleDb.deletePage(id); } catch (e) { console.error('[store] Oracle deletePage failed:', e.message); }
  }
  if (neonDb.configured()) {
    try { await neonDb.deletePage(id); } catch (e) { console.error('[store] Neon deletePage failed:', e.message); }
  }
}

async function dbSetPaused(paused) {
  if (oracleDb.configured()) {
    try { await oracleDb.setSetting('queuePaused', String(paused)); } catch (e) { console.error('[store] Oracle setSetting failed:', e.message); }
  }
  if (neonDb.configured()) {
    try { await neonDb.setSetting('queuePaused', String(paused)); } catch (e) { console.error('[store] Neon setSetting failed:', e.message); }
  }
}

// Startup: if the local JSON is empty (fresh VM / restore), rebuild the
// state from the Oracle primary, else the Neon DR copy. Then push the whole
// state to both databases in the background to heal any missed write.
async function init() {
  const jsonEmpty = state.posts.length === 0 && state.pages.length === 0;
  if (jsonEmpty) {
    let loaded = null;
    if (oracleDb.configured()) {
      try { loaded = await oracleDb.getAll(); } catch (e) { console.error('[store] Oracle load failed:', e.message); }
    }
    if ((!loaded || (loaded.posts.length === 0 && loaded.pages.length === 0)) && neonDb.configured()) {
      try { loaded = await neonDb.getAll(); } catch (e) { console.error('[store] Neon load failed:', e.message); }
    }
    if (loaded && (loaded.posts.length > 0 || loaded.pages.length > 0)) {
      // Temp media is JSON-only, so keep the local staging as-is.
      state = { posts: loaded.posts, queuePaused: loaded.queuePaused, pages: loaded.pages, tempMedia: state.tempMedia || [] };
      saveJson(state);
    }
  }
  queueDb(async () => {
    for (const p of state.posts) await dbUpsertPost(p);
    for (const pg of state.pages) await dbUpsertPage(pg);
    await dbSetPaused(state.queuePaused);
  });
}

module.exports = {
  init,
  allPosts: () => state.posts,
  getPost: (id) => state.posts.find((p) => p.id === id),
  upsertPost: (post) => {
    const i = state.posts.findIndex((p) => p.id === post.id);
    if (i >= 0) state.posts[i] = post; else state.posts.push(post);
    saveJson(state);
    queueDb(() => dbUpsertPost(post));
    return post;
  },
  deletePost: (id) => {
    state.posts = state.posts.filter((p) => p.id !== id);
    saveJson(state);
    queueDb(() => dbDeletePost(id));
  },
  isQueuePaused: () => state.queuePaused,
  setQueuePaused: (paused) => {
    state.queuePaused = paused;
    saveJson(state);
    queueDb(() => dbSetPaused(paused));
    return paused;
  },
  // Connected Pages (from POST /api/pages/connect). Tokens live here so the
  // publisher can post as the Page the user picked; never log or expose them.
  allPages: () => state.pages,
  getPage: (id) => state.pages.find((p) => p.id === id),
  upsertPage: (page) => {
    const i = state.pages.findIndex((p) => p.id === page.id);
    if (i >= 0) state.pages[i] = page; else state.pages.push(page);
    saveJson(state);
    queueDb(() => dbUpsertPage(page));
    return page;
  },
  deletePage: (id) => {
    state.pages = state.pages.filter((p) => p.id !== id);
    saveJson(state);
    queueDb(() => dbDeletePage(id));
  },
  // When a Page is disconnected, its scheduled posts can never publish
  // (no token). Move them to draft so they leave the queue instead of
  // sitting there as scheduled forever; the user can re-schedule them
  // from Drafts if the Page is re-connected. The planned time is kept.
  draftScheduledPostsForPage: (pageId) => {
    const changed = [];
    for (const p of state.posts) {
      if (p.pageId === pageId && p.status === 'scheduled') {
        p.status = 'draft';
        changed.push(p);
      }
    }
    if (changed.length > 0) {
      saveJson(state);
      queueDb(async () => { for (const p of changed) await dbUpsertPost(p); });
    }
  },
  // Temp media staging. A picked file is uploaded before the post exists;
  // it waits here until the post is saved (claim) or the user removes it
  // (delete) or it expires (sweep). JSON-only, never Oracle/Neon.
  allTempMedia: () => state.tempMedia,
  getTempMedia: (id) => state.tempMedia.find((t) => t.id === id),
  addTempMedia: (entry) => {
    state.tempMedia.push(entry);
    saveJson(state);
    return entry;
  },
  removeTempMedia: (id) => {
    state.tempMedia = state.tempMedia.filter((t) => t.id !== id);
    saveJson(state);
  },
  claimTempMedia: (ids) => {
    const claimed = [];
    const claimedIds = new Set();
    for (const id of ids || []) {
      const t = state.tempMedia.find((x) => x.id === id);
      if (!t) continue;
      claimed.push({ path: t.path, filename: t.filename, mimetype: t.mimetype, objectName: t.objectName });
      claimedIds.add(t.id);
    }
    if (claimedIds.size > 0) {
      state.tempMedia = state.tempMedia.filter((t) => !claimedIds.has(t.id));
      saveJson(state);
    }
    return claimed;
  },
};
