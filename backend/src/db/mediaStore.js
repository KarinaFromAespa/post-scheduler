// Object storage for post media: Oracle bucket (primary) + Neon S3 (DR).
// The VM disk keeps a local copy as a serving cache — the bucket is the
// durable store, so a lost VM loses nothing. Facebook keeps fetching from
// the backend's /uploads URL exactly as before; no PAR is needed for that.
const fs = require('fs');
const { Readable } = require('stream');
const common = require('oci-common');
const os = require('oci-objectstorage');
const { S3Client, PutObjectCommand, DeleteObjectCommand, GetObjectCommand } = require('@aws-sdk/client-s3');

const OCI_NAMESPACE = process.env.ORACLE_OBJECT_STORAGE_NAMESPACE || 'axfqbctrodeb';
const OCI_BUCKET = process.env.ORACLE_OBJECT_STORAGE_BUCKET || 'planner-media';
const OCI_REGION = process.env.ORACLE_OBJECT_STORAGE_REGION || 'ap-hyderabad-1';
const NEON_BUCKET = process.env.NEON_S3_BUCKET || 'planner-media-dr';

function oracleConfigured() {
  return true; // instance principal: no key to configure
}

function neonConfigured() {
  return Boolean(process.env.NEON_S3_ENDPOINT && process.env.NEON_S3_ACCESS_KEY_ID
    && process.env.NEON_S3_SECRET_ACCESS_KEY);
}

let ociClientPromise = null;
function getOciClient() {
  if (!ociClientPromise) {
    ociClientPromise = (async () => {
      const provider = await new common.InstancePrincipalsAuthenticationDetailsProviderBuilder().build();
      const client = new os.ObjectStorageClient({ authenticationDetailsProvider: provider });
      client.region = common.Region.fromRegionId(OCI_REGION);
      return client;
    })();
  }
  return ociClientPromise;
}

let s3Client = null;
function getS3Client() {
  if (!s3Client) {
    s3Client = new S3Client({
      endpoint: process.env.NEON_S3_ENDPOINT,
      region: process.env.NEON_S3_REGION || 'ap-southeast-1',
      credentials: {
        accessKeyId: process.env.NEON_S3_ACCESS_KEY_ID,
        secretAccessKey: process.env.NEON_S3_SECRET_ACCESS_KEY,
      },
      forcePathStyle: true,
    });
  }
  return s3Client;
}

async function uploadToOracle(objectName, filePath, mimetype) {
  const stat = fs.statSync(filePath);
  const client = await getOciClient();
  await client.putObject({
    namespaceName: OCI_NAMESPACE,
    bucketName: OCI_BUCKET,
    objectName,
    putObjectBody: fs.createReadStream(filePath),
    contentType: mimetype,
    contentLength: stat.size,
  });
}

async function uploadToNeon(objectName, filePath, mimetype) {
  await getS3Client().send(new PutObjectCommand({
    Bucket: NEON_BUCKET,
    Key: objectName,
    Body: fs.createReadStream(filePath),
    ContentType: mimetype,
  }));
}

async function deleteFromOracle(objectName) {
  const client = await getOciClient();
  await client.deleteObject({
    namespaceName: OCI_NAMESPACE, bucketName: OCI_BUCKET, objectName,
  });
}

async function deleteFromNeon(objectName) {
  await getS3Client().send(new DeleteObjectCommand({ Bucket: NEON_BUCKET, Key: objectName }));
}

// Re-download a bucket object to the local cache (startup recovery when a
// local file is missing, e.g. after the VM was rebuilt).
async function downloadFromOracle(objectName, destPath) {
  const client = await getOciClient();
  const res = await client.getObject({
    namespaceName: OCI_NAMESPACE, bucketName: OCI_BUCKET, objectName,
  });
  await new Promise((resolve, reject) => {
    const out = fs.createWriteStream(destPath);
    const src = Readable.fromWeb(res.value);
    src.pipe(out);
    out.on('finish', resolve);
    out.on('error', reject);
    src.on('error', reject);
  });
}

module.exports = {
  oracleConfigured, neonConfigured,
  uploadToOracle, uploadToNeon, deleteFromOracle, deleteFromNeon, downloadFromOracle,
};
