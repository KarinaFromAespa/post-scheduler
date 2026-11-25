// Oracle Autonomous Database (plannerdb) — primary durable store.
// Thin mode: no Oracle Client / wallet needed. One-way TLS via the
// TCPS connect string + ADMIN user + password from env.
const oracledb = require('oracledb');

// The caption column is a CLOB (Facebook allows 63,206 chars, far past
// VARCHAR2's 4000-byte ceiling). Fetch CLOBs as plain strings so the
// row readers below keep getting JS strings, not Lob streams.
oracledb.fetchAsString = [oracledb.CLOB];

let pool = null;

function configured() {
  return Boolean(process.env.ORACLE_DB_CONNECT_STRING && process.env.ORACLE_DB_PASSWORD);
}

async function getPool() {
  if (pool) return pool;
  if (!configured()) throw new Error('Oracle DB is not configured (ORACLE_DB_CONNECT_STRING / ORACLE_DB_PASSWORD)');
  pool = await oracledb.createPool({
    user: process.env.ORACLE_DB_USER || 'ADMIN',
    password: process.env.ORACLE_DB_PASSWORD,
    connectString: process.env.ORACLE_DB_CONNECT_STRING,
    poolMin: 1,
    poolMax: 2,
    poolIncrement: 1,
  });
  return pool;
}

// Run fn with a Connection checked out of the pool, then release it. A Pool
// itself has no .execute() — only a Connection does.
async function withConnection(fn) {
  const p = await getPool();
  const conn = await p.getConnection();
  try {
    return await fn(conn);
  } finally {
    await conn.close();
  }
}

function toIso(v) {
  if (!v) return null;
  return v instanceof Date ? v.toISOString() : new Date(v).toISOString();
}

function rowToPost(r) {
  return {
    id: r.ID, type: r.TYPE, caption: r.CAPTION || '',
    hashtags: r.HASHTAGS || null, scheduledAt: toIso(r.SCHEDULED_AT),
    status: r.STATUS, failureReason: r.FAILURE_REASON || null,
    facebookPostId: r.FACEBOOK_POST_ID || null, pageId: r.PAGE_ID || null,
    mediaFiles: JSON.parse(r.MEDIA_FILES || '[]'),
    createdAt: toIso(r.CREATED_AT), updatedAt: toIso(r.UPDATED_AT),
  };
}

function rowToPage(r) {
  return {
    id: r.ID, name: r.NAME, accessToken: r.ACCESS_TOKEN,
    pictureUrl: r.PICTURE_URL || null, longLived: r.LONG_LIVED === 1,
    connectedAt: toIso(r.CONNECTED_AT),
  };
}

function postBinds(p) {
  return {
    id: p.id, type: p.type, caption: p.caption || '',
    hashtags: p.hashtags || null,
    scheduled_at: p.scheduledAt ? new Date(p.scheduledAt) : null,
    status: p.status, failure_reason: p.failureReason || null,
    facebook_post_id: p.facebookPostId || null, page_id: p.pageId || null,
    media_files: JSON.stringify(p.mediaFiles || []),
    created_at: p.createdAt ? new Date(p.createdAt) : new Date(),
    updated_at: p.updatedAt ? new Date(p.updatedAt) : new Date(),
  };
}

async function upsertPost(post) {
  const b = postBinds(post);
  await withConnection((conn) => conn.execute(
    `MERGE INTO posts dst USING (SELECT :id AS id FROM dual) src ON (dst.id = src.id)
     WHEN MATCHED THEN UPDATE SET type=:type, caption=:caption, hashtags=:hashtags,
       scheduled_at=:scheduled_at, status=:status, failure_reason=:failure_reason,
       facebook_post_id=:facebook_post_id, page_id=:page_id, media_files=:media_files,
       created_at=:created_at, updated_at=:updated_at
     WHEN NOT MATCHED THEN INSERT (id, type, caption, hashtags, scheduled_at, status,
       failure_reason, facebook_post_id, page_id, media_files, created_at, updated_at)
       VALUES (:id, :type, :caption, :hashtags, :scheduled_at, :status,
       :failure_reason, :facebook_post_id, :page_id, :media_files, :created_at, :updated_at)`,
    b, { autoCommit: true },
  ));
}

async function deletePost(id) {
  await withConnection((conn) => conn.execute(
    'DELETE FROM posts WHERE id = :id', { id }, { autoCommit: true },
  ));
}

async function upsertPage(page) {
  await withConnection((conn) => conn.execute(
    `MERGE INTO pages dst USING (SELECT :id AS id FROM dual) src ON (dst.id = src.id)
     WHEN MATCHED THEN UPDATE SET name=:name, access_token=:access_token,
       picture_url=:picture_url, long_lived=:long_lived, connected_at=:connected_at
     WHEN NOT MATCHED THEN INSERT (id, name, access_token, picture_url, long_lived, connected_at)
       VALUES (:id, :name, :access_token, :picture_url, :long_lived, :connected_at)`,
    {
      id: page.id, name: page.name, access_token: page.accessToken,
      picture_url: page.pictureUrl || null, long_lived: page.longLived ? 1 : 0,
      connected_at: page.connectedAt ? new Date(page.connectedAt) : null,
    },
    { autoCommit: true },
  ));
}

async function deletePage(id) {
  await withConnection((conn) => conn.execute(
    'DELETE FROM pages WHERE id = :id', { id }, { autoCommit: true },
  ));
}

async function setSetting(key, value) {
  await withConnection((conn) => conn.execute(
    `MERGE INTO settings dst USING (SELECT :key AS key FROM dual) src ON (dst.key = src.key)
     WHEN MATCHED THEN UPDATE SET value=:value
     WHEN NOT MATCHED THEN INSERT (key, value) VALUES (:key, :value)`,
    { key, value }, { autoCommit: true },
  ));
}

// Whole-state read, used on startup when the local JSON file is empty
// (fresh VM / restore) so the queue can be rebuilt from the primary DB.
async function getAll() {
  return withConnection(async (conn) => {
    const posts = await conn.execute(
      'SELECT id, type, caption, hashtags, scheduled_at, status, failure_reason, facebook_post_id, page_id, media_files, created_at, updated_at FROM posts',
      [], { outFormat: oracledb.OUT_FORMAT_OBJECT },
    );
    const pages = await conn.execute(
      'SELECT id, name, access_token, picture_url, long_lived, connected_at FROM pages',
      [], { outFormat: oracledb.OUT_FORMAT_OBJECT },
    );
    const settings = await conn.execute(
      'SELECT key, value FROM settings', [], { outFormat: oracledb.OUT_FORMAT_OBJECT },
    );
    const queuePaused = (settings.rows.find((r) => r.KEY === 'queuePaused') || {}).VALUE === 'true';
    return {
      posts: posts.rows.map(rowToPost),
      pages: pages.rows.map(rowToPage),
      queuePaused,
    };
  });
}

module.exports = { configured, upsertPost, deletePost, upsertPage, deletePage, setSetting, getAll };
