const store = require('./store');
const facebook = require('./facebook');
const media = require('./media');

async function publishOne(post) {
  store.upsertPost({ ...post, status: 'publishing', updatedAt: new Date().toISOString() });
  let lastError = null;
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      const result = await facebook.publishPost(post, media.mediaFor(post));
      if (!result.ok) throw new Error(result.reason);
      // A publish only counts when Facebook returns a post ID. Without one
      // the post stays failed and its media is kept, so a retry can use it —
      // the old code marked any {ok:true} as published and deleted the media.
      if (!result.facebookPostId) {
        throw new Error('Facebook accepted the post but returned no post ID; keeping it queued and its media intact.');
      }
      const done = {
        ...post, status: 'published', facebookPostId: result.facebookPostId,
        failureReason: null, updatedAt: new Date().toISOString(),
      };
      store.upsertPost(done);
      media.deleteMedia(post);
      return done;
    } catch (err) {
      lastError = err;
      // Two more tries before giving up — a short wait in between, growing
      // each time, so a brief Facebook hiccup doesn't fail the post.
      if (attempt < 3) await new Promise((resolve) => setTimeout(resolve, attempt * 5000));
    }
  }
  const failed = {
    ...post, status: 'failed', failureReason: lastError ? lastError.message : 'Unknown error',
    updatedAt: new Date().toISOString(),
  };
  store.upsertPost(failed);
  return failed;
}

function duePosts() {
  if (store.isQueuePaused()) return [];
  const now = Date.now();
  return store.allPosts().filter(
    (p) => p.status === 'scheduled' && p.scheduledAt && Date.parse(p.scheduledAt) <= now,
  );
}

async function runOnce() {
  for (const post of duePosts()) {
    await publishOne(post);
  }
}

function start() {
  setInterval(() => { runOnce().catch(console.error); }, 60 * 1000);
  runOnce().catch(console.error);
}

module.exports = { start, runOnce, publishOne };
