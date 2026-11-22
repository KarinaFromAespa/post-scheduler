// One-off backfill: generate poster JPEGs for video media that was
// uploaded before posters existed (the 3 video stories). Run once on
// the VM after the poster code deploys, then delete this file.
// Usage: node backfill-posters.js   (run from the backend dir)
require('dotenv').config();
const fs = require('fs');
const path = require('path');
const media = require('./src/media');
const store = require('./src/store');

(async () => {
  await store.init();
  let fixed = 0;
  for (const post of store.allPosts()) {
    let changed = false;
    for (const f of post.mediaFiles || []) {
      const mime = (f.mimetype && f.mimetype !== 'application/octet-stream')
        ? f.mimetype
        : (f.path ? media.detectMime(f.path) : null) || f.mimetype;
      if (!mime || !mime.startsWith('video/')) continue;
      if (f.posterPath) continue;
      if (!f.path || !fs.existsSync(f.path)) {
        console.log('skip, file missing:', post.id, f.filename);
        continue;
      }
      await media.attachPoster(f, f.objectName || `${post.id}/${Date.now()}`);
      if (f.posterPath) {
        changed = true;
        fixed += 1;
        console.log('poster made for', post.id, f.filename, '->', f.posterPath);
      }
    }
    if (changed) {
      // Persist the updated mediaFiles entry. upsertPost writes the
      // JSON file and queues the Oracle/Neon background write.
      store.upsertPost(post);
    }
  }
  console.log('done, posters made:', fixed);
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
