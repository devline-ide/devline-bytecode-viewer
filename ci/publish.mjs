// GitHub Actions only: request an audience-bound OIDC token, then upload exact bytes.
// No GitHub PAT or IDE user session is needed. Never print either temporary token.
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';

const [metadataPath,archivePath]=process.argv.slice(2),origin=process.env.DEVLINE_CATALOG_ORIGIN||'https://devline.ozliginus.com';
const base=new URL(origin);if(base.protocol!=='https:'||base.origin!==origin)throw new Error('Expected HTTPS catalog origin');
const metadata=JSON.parse(await readFile(metadataPath,'utf8')),bytes=await readFile(archivePath);
metadata.sha256=createHash('sha256').update(bytes).digest('hex');metadata.size=bytes.length;
const audience=origin+'/v1/marketplace/ci',tokenUrl=new URL(process.env.ACTIONS_ID_TOKEN_REQUEST_URL);
if(tokenUrl.protocol!=='https:'||!process.env.ACTIONS_ID_TOKEN_REQUEST_TOKEN)throw new Error('GitHub Actions OIDC is required');
tokenUrl.searchParams.set('audience',audience);
const tokenResponse=await fetch(tokenUrl,{headers:{Authorization:`Bearer ${process.env.ACTIONS_ID_TOKEN_REQUEST_TOKEN}`},redirect:'error',signal:AbortSignal.timeout(15000)});
if(!tokenResponse.ok)throw new Error('OIDC request failed');const {value:token}=await tokenResponse.json();
const submissionResponse=await fetch(origin+'/v1/marketplace/ci/releases',{method:'POST',headers:{'Content-Type':'application/json',Authorization:`Bearer ${token}`},body:JSON.stringify(metadata),redirect:'error',signal:AbortSignal.timeout(15000)});
if(!submissionResponse.ok)throw new Error(`Catalog submission failed (${submissionResponse.status})`);
const submission=await submissionResponse.json(),upload=new URL(submission.uploadUrl);
if(upload.origin!==origin||!/^\/v1\/marketplace\/ci\/releases\/[a-f0-9-]{36}\/artifact$/.test(upload.pathname)||upload.search||upload.hash)throw new Error('Unexpected upload destination');
const response=await fetch(upload,{method:'PUT',headers:{'Content-Type':'application/octet-stream',Authorization:`Bearer ${submission.uploadToken}`},body:bytes,redirect:'error',signal:AbortSignal.timeout(90000)});
if(!response.ok)throw new Error(`Catalog upload failed (${response.status})`);
const {release}=await response.json();console.log(JSON.stringify({releaseId:release.id,extensionId:release.extensionId,version:release.version,platform:release.platform,channel:release.channel,status:release.status,sha256:release.sha256,size:release.size}));
