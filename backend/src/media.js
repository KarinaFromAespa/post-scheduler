// Post media. The VM disk keeps a local copy as a serving cache —
// Facebook keeps fetching from the backend's /uploads URL exactly as
// before. The durable copies live in the Oracle bucket (primary) and the
// Neon S3 bucket (DR), so a lost VM loses no media. On startup, any local
// file that is missing is re-downloaded from the Oracle bucket.
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { execFile } = require('child_process');
const multer = require('multer');
const mediaStore = require('./db/mediaStore');

const UPLOAD_DIR = path.join(__dirname, '..', 'uploads');
fs.mkdirSync(UPLOAD_DIR, { recursive: true });

// A video's thumbnail is a still frame pulled out of the file with
// ffmpeg, because the app cannot reliably decode a frame from a remote
// video URL (Coil's VideoFrameDecoder + MediaMetadataRetriever is flaky
// over HTTPS). The poster is a small JPEG next to the video, uploaded
// to both buckets like the video itself, and the DTO carries its URL so
// clients just load a normal image. The website's <video> element shows
// its own first frame, so it never needed this — the poster is for the app.
function makePoster(videoPath) {
  const posterPath = `${videoPath}-poster.jpg`;
  return new Promise((resolve) => {
    execFile('ffmpeg', ['-y', '-i', videoPath, '-ss', '0.5', '-frames:v', '1', '-q:v', '4', posterPath], (err) => {
      if (err) {
        console.error('[media] poster failed:', videoPath, err.message);
        resolve(null);
      } else {
        resolve(posterPath);
      }
    });
  });
}

// If a media entry is a video, generate its poster and store the poster
// alongside it (local file + both buckets). Called after the video is
// saved, for both post media and staged temp media.
async function attachPoster(entry, objectNamePrefix) {
  const mime = entry.path ? detectMime(entry.path) : null;
  const type = (mime && mime !== 'application/octet-stream') ? mime : entry.mimetype;
  if (!type || !type.startsWith('video/')) return;
  const posterPath = await makePoster(entry.path);
  if (!posterPath) return;
  entry.posterPath = posterPath;
  const posterObjectName = `${objectNamePrefix}-poster`;
  entry.posterObjectName = posterObjectName;
  try {
    await mediaStore.uploadToOracle(posterObjectName, posterPath, 'image/jpeg');
  } catch (e) {
    console.error('[media] Oracle poster upload failed:', e.message);
  }
  if (mediaStore.neonConfigured()) {
    try {
      await mediaStore.uploadToNeon(posterObjectName, posterPath, 'image/jpeg');
    } catch (e) {
      console.error('[media] Neon poster upload failed:', e.message);
    }
  }
}

function posterUrl(entry) {
  if (!entry.posterPath) return null;
  const base = publicBaseUrl();
  return `${base}/uploads/${path.basename(entry.posterPath)}`;
}

function deletePoster(entry) {
  if (entry.posterPath) {
    try { fs.unlinkSync(entry.posterPath); } catch { /* already gone */ }
  }
  if (entry.posterObjectName) {
    mediaStore.deleteFromOracle(entry.posterObjectName).catch((e) => {
      console.error('[media] Oracle poster delete failed:', e.message);
    });
    if (mediaStore.neonConfigured()) {
      mediaStore.deleteFromNeon(entry.posterObjectName).catch((e) => {
        console.error('[media] Neon poster delete failed:', e.message);
      });
    }
  }
}

const upload = multer({
  dest: UPLOAD_DIR,
  limits: { fileSize: 200 * 1024 * 1024 },
});

function publicBaseUrl() {
  return (process.env.PUBLIC_BASE_URL || '').replace(/\/$/, '');
}

// Detect a file's real type from its first bytes. Uploads arrive with
// the wrong mimetype when the client sends application/octet-stream
// (the app did that for every file until 2026-11-22), and the stored
// files have no extension, so neither the stored mimetype nor the
// filename can be trusted — the content is the only reliable signal.
// Used for the DTO and for the /uploads Content-Type, so the app and
// the website can tell a photo from a video and show a thumbnail.
function detectMime(filePath) {
  try {
    const fd = fs.openSync(filePath, 'r');
    const buf = Buffer.alloc(12);
    fs.readSync(fd, buf, 0, 12, 0);
    fs.closeSync(fd);
    if (buf[0] === 0xff && buf[1] === 0xd8 && buf[2] === 0xff) return 'image/jpeg';
    if (buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47) return 'image/png';
    if (buf[0] === 0x47 && buf[1] === 0x49 && buf[2] === 0x46) return 'image/gif';
    if (buf.toString('ascii', 0, 4) === 'RIFF' && buf.toString('ascii', 8, 12) === 'WEBP') return 'image/webp';
    if (buf.toString('ascii', 4, 8) === 'ftyp') return 'video/mp4';
  } catch { /* file gone — fall through */ }
  return null;
}

function mediaFor(post) {
  const base = publicBaseUrl();
  return (post.mediaFiles || []).map((f) => {
    const mimetype = (f.mimetype && f.mimetype !== 'application/octet-stream')
      ? f.mimetype
      : (f.path ? detectMime(f.path) : null) || f.mimetype;
    const out = {
      ...f,
      mimetype,
      // Facebook must be able to fetch the file, so the URL is absolute once
      // PUBLIC_BASE_URL is set (the VM's public address). Without it the
      // relative path still works through the website's /uploads proxy.
      url: f.url || `${base}/uploads/${path.basename(f.path)}`,
    };
    const pUrl = posterUrl(f);
    if (pUrl) out.posterUrl = pUrl;
    return out;
  });
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
  await attachPoster(entry, objectName);
  return entry;
}

// Delete one staged file from local disk + both buckets.
function deleteTempMedia(entry) {
  try { fs.unlinkSync(entry.path); } catch { /* already gone */ }
  deletePoster(entry);
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
  await attachPoster(entry, objectName);
  return entry;
}

function deleteMedia(post) {
  for (const f of post.mediaFiles || []) {
    try { fs.unlinkSync(f.path); } catch { /* already gone */ }
    deletePoster(f);
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
    if (f.posterObjectName && f.posterPath) {
      try {
        fs.accessSync(f.posterPath, fs.constants.F_OK);
      } catch {
        try {
          fs.mkdirSync(path.dirname(f.posterPath), { recursive: true });
          await mediaStore.downloadFromOracle(f.posterObjectName, f.posterPath);
          console.log('[media] restored local poster from Oracle bucket:', f.posterObjectName);
        } catch (e) {
          console.error('[media] poster restore failed:', f.posterObjectName, e.message);
        }
      }
    }
  }
}

module.exports = { upload, mediaFor, addMedia, deleteMedia, ensureLocalFiles, addTempMedia, deleteTempMedia, tempMediaUrl, detectMime, attachPoster, posterUrl };
