// Post media. The VM disk keeps a local copy as a serving cache —
// Facebook keeps fetching from the backend's /uploads URL exactly as
// before. The durable copies live in the Oracle bucket (primary) and the
// Neon S3 bucket (DR), so a lost VM loses no media. On startup, any local
// file that is missing is re-downloaded from the Oracle bucket.
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const multer = require('multer');
const mediaStore = require('./db/mediaStore');

const UPLOAD_DIR = path.join(__dirname, '..', 'uploads');
fs.mkdirSync(UPLOAD_DIR, { recursive: true });

const upload = multer({
  dest: UPLOAD_DIR,
  limits: { fileSize: 200 * 1024 * 1024 },
});

function publicBaseUrl() {
  return (process.env.PUBLIC_BASE_URL || '').replace(/\/$/, '');
}

function mediaFor(post) {
  const base = publicBaseUrl();
  return (post.mediaFiles || []).map((f) => ({
    ...f,
    // Facebook must be able to fetch the file, so the URL is absolute once
    // PUBLIC_BASE_URL is set (the VM's public address). Without it the
    // relative path still works through the website's /uploads proxy.
    url: f.url || `${base}/uploads/${path.basename(f.path)}`,
  }));
}

function objectNameFor(postId) {
  return `${postId}/${crypto.randomUUID()}`;
}

// Temp (staging) media: a picked file, uploaded before the post exists.
// Same durable copies as a post's media (Oracle + Neon + local disk),
// under a temp/ key so it is never confused with a post's file.
function tempObjectName() {
  return `temp/${crypto.randomUUID()}`;
}

async function addTempMedia(file) {
  const objectName = tempObjectName();
  const entry = {
    id: crypto.randomUUID(),
    path: file.path, filename: file.originalname, mimetype: file.mimetype,
    objectName, createdAt: new Date().toISOString(),
  };
  try {
    await mediaStore.uploadToOracle(objectName, file.path, file.mimetype);
  } catch (e) {
    console.error('[media] Oracle bucket upload failed:', e.message);
  }
  if (mediaStore.neonConfigured()) {
    try {
      await mediaStore.uploadToNeon(objectName, file.path, file.mimetype);
    } catch (e) {
      console.error('[media] Neon S3 upload failed:', e.message);
    }
  }
  return entry;
}

// Delete one staged file from local disk + both buckets.
function deleteTempMedia(entry) {
  try { fs.unlinkSync(entry.path); } catch { /* already gone */ }
  if (entry.objectName) {
    mediaStore.deleteFromOracle(entry.objectName).catch((e) => {
      console.error('[media] Oracle bucket delete failed:', e.message);
    });
    if (mediaStore.neonConfigured()) {
      mediaStore.deleteFromNeon(entry.objectName).catch((e) => {
        console.error('[media] Neon S3 delete failed:', e.message);
      });
    }
  }
}

// Temp-media URL for the client preview (the staged file is served
// from /uploads, same as a post's media).
function tempMediaUrl(entry) {
  const base = publicBaseUrl();
  return `${base}/uploads/${path.basename(entry.path)}`;
}

// After multer saves a temp file, copy it to the Oracle bucket (primary)
// and the Neon S3 bucket (DR), then return the mediaFiles entry. The local
// file stays as the serving cache. Bucket uploads are best-effort: a
// failed upload is logged and the local file still serves the post.
async function addMedia(post, file) {
  const objectName = objectNameFor(post.id);
  const entry = {
    path: file.path, filename: file.originalname, mimetype: file.mimetype,
    objectName,
  };
  try {
    await mediaStore.uploadToOracle(objectName, file.path, file.mimetype);
  } catch (e) {
    console.error('[media] Oracle bucket upload failed:', e.message);
  }
  if (mediaStore.neonConfigured()) {
    try {
      await mediaStore.uploadToNeon(objectName, file.path, file.mimetype);
    } catch (e) {
      console.error('[media] Neon S3 upload failed:', e.message);
    }
  }
  return entry;
}

function deleteMedia(post) {
  for (const f of post.mediaFiles || []) {
    try { fs.unlinkSync(f.path); } catch { /* already gone */ }
    if (f.objectName) {
      mediaStore.deleteFromOracle(f.objectName).catch((e) => {
        console.error('[media] Oracle bucket delete failed:', e.message);
      });
      if (mediaStore.neonConfigured()) {
        mediaStore.deleteFromNeon(f.objectName).catch((e) => {
          console.error('[media] Neon S3 delete failed:', e.message);
        });
      }
    }
  }
}

// Re-download any local file that is missing (e.g. after the VM was
// rebuilt) from the Oracle bucket, so /uploads keeps serving it.
async function ensureLocalFiles(post) {
  for (const f of post.mediaFiles || []) {
    if (!f.objectName) continue;
    try {
      fs.accessSync(f.path, fs.constants.F_OK);
    } catch {
      try {
        fs.mkdirSync(path.dirname(f.path), { recursive: true });
        await mediaStore.downloadFromOracle(f.objectName, f.path);
        console.log('[media] restored local file from Oracle bucket:', f.objectName);
      } catch (e) {
        console.error('[media] restore from Oracle bucket failed:', f.objectName, e.message);
      }
    }
  }
}

module.exports = { upload, mediaFor, addMedia, deleteMedia, ensureLocalFiles, addTempMedia, deleteTempMedia, tempMediaUrl };
