#!/usr/bin/env node
// Jockey Radio release automation. Bumps the version + build number, builds the mod jar
// LOCALLY (the fat jar bundles the ffmpeg/yt-dlp binaries from .product/, which CI cannot see),
// tags + pushes, and publishes a GitHub Release with the jar attached.
//
// Order matters, because a push cannot be taken back. Every local step (version bump, build,
// commit, tag) runs first. Then the release is created as a DRAFT and every asset is uploaded
// to it, then the branch and tag go up in one atomic push, and only then is the draft
// published. A failure before the push deletes the draft and undoes the local steps; a failure
// after it leaves the remote alone and says exactly what is left to do by hand.
//
//   npm run release            # patch bump  (0.1.0 -> 0.1.1)
//   npm run release:minor      # minor bump  (0.1.0 -> 0.2.0)
//   npm run release:major      # major bump  (0.1.0 -> 1.0.0)
//   npm run release -- 0.4.0   # explicit version
//   npm run release:dry        # print the plan and run the checks, change nothing
//   npm run release:build      # bump + build + commit + push, but DO NOT create the GH release
//
// A release must carry the bundled binaries (the zero-touch install depends on them) and each one
// must match the SHA-256 the bundled notice lists. A THIN jar (built without .product/) is
// refused unless --allow-thin is passed; a partial set or a hash the notice does not list is
// always refused, because the notice that ships would then be wrong.
//
// The notice also promises that every release carries the corresponding source of the bundled
// ffmpeg: the FFmpeg source at the commit it was built from, and the BtbN FFmpeg-Builds scripts
// that built it. Both are attached to the release from .product/sources/, named as GitHub's
// archive download names them (FFmpeg-<commit>.tar.gz, FFmpeg-Builds-<commit>.tar.gz; .tar.xz
// and .zip also work). A missing archive stops the release before anything changes, and the
// error prints the download commands. Update SOURCE_ARCHIVES whenever the bundled ffmpeg changes.
//
// JOCKEY_RELEASE_API_BASE overrides https://api.github.com for the REST path (used when the gh
// CLI is not logged in), so the flow can be exercised against a local stand-in.
//
// Zero dependencies (Node built-ins only), so no `npm install` is needed.

import { execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { inflateRawSync } from 'node:zlib';
import { createInterface } from 'node:readline';
import { homedir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const IS_WIN = process.platform === 'win32';
const GRADLEW = IS_WIN ? path.join(ROOT, 'gradlew.bat') : path.join(ROOT, 'gradlew');
const ARCHIVE_BASE = 'jockey-radio';
const API_BASE = (process.env.JOCKEY_RELEASE_API_BASE || 'https://api.github.com').replace(/\/+$/, '');

const C = { // tiny ANSI helpers; degrade to plain text where color is unwanted
  dim: (s) => `\x1b[2m${s}\x1b[0m`, bold: (s) => `\x1b[1m${s}\x1b[0m`,
  green: (s) => `\x1b[32m${s}\x1b[0m`, red: (s) => `\x1b[31m${s}\x1b[0m`,
  yellow: (s) => `\x1b[33m${s}\x1b[0m`, cyan: (s) => `\x1b[36m${s}\x1b[0m`,
};
const log = (m) => console.log(m);
const step = (m) => console.log(C.cyan('▸ ') + m);
const warn = (m) => console.log(C.yellow('! ') + m);
const die = (m) => { console.error(C.red('✗ ') + m); process.exit(1); };

// ---- arg parsing -----------------------------------------------------------
const args = process.argv.slice(2);
const flags = new Set(args.filter((a) => a.startsWith('--')));
const positionals = args.filter((a) => !a.startsWith('--'));
const DRY = flags.has('--dry-run');
const NO_RELEASE = flags.has('--no-release');
const ASSUME_YES = flags.has('--yes');
const ALLOW_THIN = flags.has('--allow-thin');
// Last positional wins, so `release:minor -- 1.0.0` lets an explicit version override the default.
const bumpSpec = positionals.length ? positionals[positionals.length - 1] : 'patch';

// ---- small shell helpers ---------------------------------------------------
function run(cmd, cmdArgs, opts = {}) {
  return execFileSync(cmd, cmdArgs, { cwd: ROOT, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], ...opts }).trim();
}
function tryRun(cmd, cmdArgs, opts = {}) {
  const r = spawnSync(cmd, cmdArgs, { cwd: ROOT, encoding: 'utf8', ...opts });
  return { code: r.status ?? 1, out: (r.stdout || '').trim(), err: (r.stderr || '').trim() };
}
function git(...a) { return run('git', a); }
function gitOk(...a) { return tryRun('git', a); }

async function confirm(question) {
  if (ASSUME_YES) return true;
  if (!process.stdin.isTTY) die('not a TTY; re-run with --yes to skip the confirmation prompt');
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  const answer = await new Promise((res) => rl.question(question + ' ', res));
  rl.close();
  return /^y(es)?$/i.test(answer.trim());
}

// ---- version files ---------------------------------------------------------
function readProps() {
  const raw = readFileSync(path.join(ROOT, 'gradle.properties'), 'utf8');
  const get = (k) => (raw.match(new RegExp(`^${k}=(.*)$`, 'm')) || [])[1]?.trim();
  return { raw, mod_version: get('mod_version'), build_number: get('build_number') };
}
function writeProps(mutate) {
  const p = path.join(ROOT, 'gradle.properties');
  let raw = readFileSync(p, 'utf8');
  for (const [k, v] of Object.entries(mutate)) {
    raw = raw.replace(new RegExp(`^${k}=.*$`, 'm'), `${k}=${v}`);
  }
  writeFileSync(p, raw);
}
function readPkgVersion() {
  return JSON.parse(readFileSync(path.join(ROOT, 'package.json'), 'utf8')).version;
}
function writePkgVersion(v) {
  const p = path.join(ROOT, 'package.json');
  const pkg = JSON.parse(readFileSync(p, 'utf8'));
  pkg.version = v;
  writeFileSync(p, JSON.stringify(pkg, null, 2) + '\n');
}

function bumpSemver(current, spec) {
  if (/^\d+\.\d+\.\d+$/.test(spec)) return spec; // explicit x.y.z
  const m = current.match(/^(\d+)\.(\d+)\.(\d+)$/);
  if (!m) die(`current version "${current}" is not a plain x.y.z semver`);
  let [maj, min, pat] = m.slice(1).map(Number);
  if (spec === 'major') return `${maj + 1}.0.0`;
  if (spec === 'minor') return `${maj}.${min + 1}.0`;
  if (spec === 'patch') return `${maj}.${min}.${pat + 1}`;
  die(`unknown bump "${spec}"; use patch | minor | major | x.y.z`);
}

// ---- toolchain: locate a JDK 25 so the gradle build never fails on JAVA_HOME
function resolveJavaHome() {
  const isJdk25 = (home) => {
    if (!home) return false;
    const r = tryRun(path.join(home, 'bin', IS_WIN ? 'java.exe' : 'java'), ['-version']);
    return /version "25/.test(r.err) || /version "25/.test(r.out);
  };
  if (isJdk25(process.env.JAVA_HOME)) return process.env.JAVA_HOME;
  // The build-jdk25-location gotcha: non-login shells don't export JAVA_HOME; the JDK lives in ~/.jdks.
  const jdksDir = path.join(homedir(), '.jdks');
  if (existsSync(jdksDir)) {
    const cand = readdirSync(jdksDir).filter((d) => d.startsWith('jdk-25')).sort().reverse();
    for (const d of cand) {
      const home = path.join(jdksDir, d);
      if (isJdk25(home)) return home;
    }
  }
  return null;
}

function resolveWslBuild() {
  if (!IS_WIN) return null;
  const distro = process.env.JOCKEY_RELEASE_WSL_DISTRO || 'Ubuntu';
  let javaHome = process.env.JOCKEY_RELEASE_WSL_JAVA_HOME || '';
  if (!javaHome) {
    const probe = tryRun('wsl.exe', [
      '-d', distro, '--', 'bash', '-lc',
      'j=$(find "$HOME/.jdks" -maxdepth 3 -type f -name javac -path "*/jdk-25*/bin/javac" -print -quit 2>/dev/null); [ -n "$j" ] && dirname "$(dirname "$j")"',
    ]);
    if (probe.code !== 0 || !probe.out) return null;
    javaHome = probe.out;
  }
  const drivePath = ROOT.match(/^([A-Za-z]):[\\/](.*)$/);
  if (!drivePath) return null;
  const root = `/mnt/${drivePath[1].toLowerCase()}/${drivePath[2].replaceAll('\\', '/')}`;
  return { distro, javaHome, root };
}

function runBuild(javaHome, wslBuild) {
  if (javaHome) {
    log(C.dim(`    JAVA_HOME=${javaHome}`));
    return spawnSync(GRADLEW, ['clean', 'build'], {
      cwd: ROOT, stdio: 'inherit', env: { ...process.env, JAVA_HOME: javaHome }, shell: false,
    });
  }
  if (wslBuild) {
    log(C.dim(`    WSL ${wslBuild.distro}, JAVA_HOME=${wslBuild.javaHome}`));
    const quote = (value) => `'${value.replaceAll("'", "'\\''")}'`;
    const command = `cd ${quote(wslBuild.root)} && export JAVA_HOME=${quote(wslBuild.javaHome)} && ./gradlew clean build`;
    return spawnSync('wsl.exe', [
      '-d', wslBuild.distro, '--', 'bash', '-lc', command,
    ], { cwd: ROOT, stdio: 'inherit', shell: false });
  }
  warn('no JDK 25 found via JAVA_HOME, ~/.jdks, or WSL; relying on the Gradle toolchain');
  return spawnSync(GRADLEW, ['clean', 'build'], { cwd: ROOT, stdio: 'inherit', shell: false });
}

// ---- jar contents: the bundled binaries and the notice that lists their hashes
// Jar resource names, as config/BundledBinaries extracts them.
const BUNDLED = [
  'jockey_radio/bin/linux-amd64/ffmpeg',
  'jockey_radio/bin/linux-amd64/yt-dlp',
  'jockey_radio/bin/linux-amd64/deno',
  'jockey_radio/bin/windows-amd64/ffmpeg.exe',
  'jockey_radio/bin/windows-amd64/yt-dlp.exe',
  'jockey_radio/bin/windows-amd64/deno.exe',
];
const NOTICE_ENTRY = 'jockey_radio/THIRD-PARTY-NOTICES.txt';

// The corresponding source attached to every release (see the header). The commits must match
// the ones the bundled notice names; inspectBundle checks that.
const SOURCES_DIR = path.join(ROOT, '.product', 'sources');
const SOURCE_ARCHIVES = [
  { what: 'FFmpeg source', repo: 'FFmpeg/FFmpeg', base: 'FFmpeg', commit: '7e93014c5bbb61e7c22afebb478bf92f7a08d59a' },
  { what: 'BtbN FFmpeg-Builds scripts', repo: 'BtbN/FFmpeg-Builds', base: 'FFmpeg-Builds', commit: '63ac3265b0789ea16c7b1f8351cd2ad195a28722' },
];

// The archive on disk for each SOURCE_ARCHIVES entry: { found: [paths], missing: [entries] }.
function findSourceArchives() {
  const names = existsSync(SOURCES_DIR) ? readdirSync(SOURCES_DIR) : [];
  const found = [];
  const missing = [];
  for (const s of SOURCE_ARCHIVES) {
    const hits = names.filter((n) => [`${s.base}-${s.commit}.tar.gz`, `${s.base}-${s.commit}.tar.xz`, `${s.base}-${s.commit}.zip`].includes(n));
    if (hits.length === 1) found.push(path.join(SOURCES_DIR, hits[0]));
    else if (hits.length === 0) missing.push(s);
    else die(`${s.what}: more than one archive in .product/sources/ (${hits.join(', ')}); keep one`);
  }
  return { found, missing };
}

function sourcesHelp(missing) {
  return missing.map((s) => `\n    ${s.what} (${s.repo} at ${s.commit}):`
    + `\n      curl -fL -o .product/sources/${s.base}-${s.commit}.tar.gz https://github.com/${s.repo}/archive/${s.commit}.tar.gz`).join('');
}

// A minimal zip reader (central directory, stored and deflated entries), enough for our own jar.
function readJar(file) {
  const buf = readFileSync(file);
  let eocd = -1;
  for (let i = buf.length - 22; i >= Math.max(0, buf.length - 22 - 0xffff); i--) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error(`${path.basename(file)} is not a zip file`);
  const count = buf.readUInt16LE(eocd + 10);
  let p = buf.readUInt32LE(eocd + 16);
  if (count === 0xffff || p === 0xffffffff) throw new Error(`${path.basename(file)} is a zip64 archive, which this check does not read`);
  const entries = new Map();
  for (let n = 0; n < count; n++) {
    if (buf.readUInt32LE(p) !== 0x02014b50) throw new Error(`${path.basename(file)} has a corrupt central directory`);
    const nameLen = buf.readUInt16LE(p + 28);
    entries.set(buf.toString('utf8', p + 46, p + 46 + nameLen), {
      method: buf.readUInt16LE(p + 10), size: buf.readUInt32LE(p + 20), offset: buf.readUInt32LE(p + 42),
    });
    p += 46 + nameLen + buf.readUInt16LE(p + 30) + buf.readUInt16LE(p + 32);
  }
  const read = (name) => {
    const e = entries.get(name);
    if (!e) return null;
    const start = e.offset + 30 + buf.readUInt16LE(e.offset + 26) + buf.readUInt16LE(e.offset + 28);
    const data = buf.subarray(start, start + e.size);
    if (e.method === 0) return data;
    if (e.method === 8) return inflateRawSync(data);
    throw new Error(`${name} uses zip method ${e.method}, which this check does not read`);
  };
  return { has: (name) => entries.has(name), read };
}

// What is wrong with the jar's bundled set: { thin } when it has no binaries at all, else the
// list of problems (a missing binary or notice, or a hash the notice does not list).
function inspectBundle(jarPath) {
  const jar = readJar(jarPath);
  const present = BUNDLED.filter((n) => jar.has(n));
  if (present.length === 0) return { thin: true, problems: [] };
  const problems = BUNDLED.filter((n) => !jar.has(n)).map((n) => `missing ${n}`);
  const notice = jar.read(NOTICE_ENTRY);
  if (!notice) return { thin: false, problems: [...problems, `missing ${NOTICE_ENTRY}`] };
  const listed = notice.toString('utf8');
  for (const n of present) {
    const sha = createHash('sha256').update(jar.read(n)).digest('hex');
    if (!listed.includes(sha)) problems.push(`${n} has SHA-256 ${sha}, which the bundled notice does not list`);
  }
  for (const s of SOURCE_ARCHIVES) {
    if (!listed.includes(s.commit)) problems.push(`the bundled notice does not name ${s.repo} commit ${s.commit}; update SOURCE_ARCHIVES in scripts/release.mjs`);
  }
  return { thin: false, problems };
}

// ---- publishers: the same four steps through the gh CLI or the REST API ----
// createDraft makes a draft release (GitHub creates no tag for a draft, and uses the tag we push
// once it is published); upload attaches one file; publish turns draft off and marks it latest;
// deleteDraft removes the draft and never touches a tag.

function ghPublisher(env) {
  const gh = (a) => spawnSync('gh', a, { cwd: ROOT, env, shell: false, encoding: 'utf8' });
  const fail = (what, r) => new Error(`${what} failed: ${(r.stderr || r.stdout || '').trim()}`);
  let tag = null;
  return {
    name: 'gh CLI',
    async createDraft({ tag: t, title, branch }) {
      const r = gh(['release', 'create', t, '--draft', '--title', title, '--generate-notes', '--target', branch]);
      if (r.status !== 0) throw fail('gh release create --draft', r);
      tag = t;
      return { url: (r.stdout || '').trim() };
    },
    async upload(file) {
      const r = gh(['release', 'upload', tag, file]);
      if (r.status !== 0) throw fail(`gh release upload ${path.basename(file)}`, r);
    },
    async publish() {
      const r = gh(['release', 'edit', tag, '--draft=false', '--latest']);
      if (r.status !== 0) throw fail('gh release edit --draft=false', r);
      return (gh(['release', 'view', tag, '--json', 'url', '--jq', '.url']).stdout || '').trim();
    },
    async deleteDraft() {
      if (!tag) return;
      const r = gh(['release', 'delete', tag, '--yes']);
      if (r.status !== 0) throw fail('gh release delete', r);
    },
  };
}

function apiPublisher(token) {
  if (!token) die('GitHub CLI is unavailable and GITHUB_PAT is not set in .env');
  const remote = git('remote', 'get-url', 'origin');
  const match = remote.match(/github\.com[/:]([^/]+)\/([^/.]+)(?:\.git)?$/);
  if (!match) die(`cannot derive GitHub repository from origin: ${remote}`);
  const [, owner, repo] = match;
  const headers = {
    Authorization: `Bearer ${token}`,
    Accept: 'application/vnd.github+json',
    'X-GitHub-Api-Version': '2022-11-28',
    'User-Agent': 'jockey-radio-release',
  };
  const call = async (method, url, body, type = 'application/json') => {
    const res = await fetch(url, { method, headers: { ...headers, 'Content-Type': type }, body });
    if (!res.ok) throw new Error(`GitHub API ${method} ${new URL(url).pathname} failed (${res.status}): ${await res.text()}`);
    return res.status === 204 ? null : res.json();
  };
  const releases = `${API_BASE}/repos/${owner}/${repo}/releases`;
  let release = null;
  return {
    name: 'GitHub REST API',
    async createDraft({ tag, title, branch }) {
      release = await call('POST', releases, JSON.stringify({
        tag_name: tag, target_commitish: branch, name: title, generate_release_notes: true,
        draft: true, prerelease: false,
      }));
      return { url: release.html_url };
    },
    async upload(file) {
      const name = path.basename(file);
      const url = release.upload_url.replace(/\{.*$/, '') + `?name=${encodeURIComponent(name)}`;
      await call('POST', url, readFileSync(file), contentType(name));
    },
    async publish() {
      const done = await call('PATCH', `${releases}/${release.id}`, JSON.stringify({ draft: false, make_latest: 'true' }));
      return done.html_url;
    },
    async deleteDraft() {
      if (!release) return;
      await call('DELETE', `${releases}/${release.id}`);
    },
  };
}

function contentType(name) {
  if (name.endsWith('.jar')) return 'application/java-archive';
  if (name.endsWith('.tar.gz')) return 'application/gzip';
  if (name.endsWith('.tar.xz')) return 'application/x-xz';
  if (name.endsWith('.zip')) return 'application/zip';
  return 'application/octet-stream';
}

// ---- working tree ----------------------------------------------------------
// Paths that feed the build or the release. An untracked file under one of these would change
// what is built without being in the tagged commit, so the release could not be rebuilt from its
// tag; an untracked file anywhere else (a screenshot in the root, notes) cannot reach the jar.
const BUILD_INPUTS = ['src/', 'gradle/', 'scripts/', 'build.gradle', 'settings.gradle', 'gradle.properties',
  'gradlew', 'gradlew.bat', 'LICENSE', 'package.json'];
const isBuildInput = (f) => BUILD_INPUTS.some((p) => (p.endsWith('/') ? f.startsWith(p) : f === p));

// { blocking: [...], ignored: [...] }. Every change to a tracked file blocks, wherever it is.
function treeState() {
  const r = spawnSync('git', ['status', '--porcelain=v1', '-z', '--untracked-files=all'], { cwd: ROOT, encoding: 'utf8' });
  if (r.status !== 0) die(`git status failed: ${(r.stderr || '').trim()}`);
  const parts = (r.stdout || '').split('\0').filter(Boolean);
  const blocking = [];
  const ignored = [];
  for (let i = 0; i < parts.length; i++) {
    const code = parts[i].slice(0, 2);
    const file = parts[i].slice(3);
    if (code[0] === 'R' || code[0] === 'C') i++; // the next entry is the rename's source path
    if (code === '??' && !isBuildInput(file)) ignored.push(file);
    else blocking.push(parts[i]);
  }
  return { blocking, ignored };
}

// ---- remote checks ---------------------------------------------------------
// Read-only. They run against the push URL, the place the release will actually go.
function remoteRef(pushUrl, ref) {
  const r = gitOk('ls-remote', pushUrl, ref);
  if (r.code !== 0) die(`cannot reach ${pushUrl}: ${r.err}`);
  return (r.out.split(/\s+/)[0] || '').trim();
}

// ---- main ------------------------------------------------------------------
const undo = []; // local rollback actions, run in reverse while nothing has left the machine
const remote = { draft: null, publisher: null, pushed: false, tag: '', branch: '' };

async function main() {
  process.chdir(ROOT);
  if (!existsSync(GRADLEW)) die(`gradle wrapper not found at ${GRADLEW}`);

  // Prereqs -------------------------------------------------------------------
  step('checking prerequisites');
  const auth = tryRun('gh', ['auth', 'status']);
  const hasGh = auth.code === 0;
  let ghEnv = { ...process.env };
  let githubToken = '';
  if (auth.code !== 0) {
    // Fall back to the PAT in .env so a fresh machine can still publish.
    const envPath = path.join(ROOT, '.env');
    const pat = existsSync(envPath) && (readFileSync(envPath, 'utf8').match(/^GITHUB_PAT=(.+)$/m) || [])[1];
    if (pat) {
      githubToken = pat.trim();
      ghEnv.GH_TOKEN = githubToken;
      if (!DRY) warn('gh unavailable or not logged in; using GITHUB_PAT from .env');
    }
    else if (!DRY && !NO_RELEASE) die('gh CLI is not authenticated. Run `gh auth login` (or set GITHUB_PAT in .env).');
  }
  const tree = treeState();
  const listed = (files) => files.slice(0, 20).map((f) => '\n    ' + f).join('') + (files.length > 20 ? `\n    (and ${files.length - 20} more)` : '');
  // A dry run only previews, so a dirty tree is reported, not fatal. A real release requires clean.
  if (tree.blocking.length) {
    const why = 'the working tree has changes that would not be in the release commit; commit or stash them first:' + listed(tree.blocking);
    if (DRY) warn(why); else die(why);
  }
  if (tree.ignored.length) warn(`ignoring ${tree.ignored.length} untracked file(s) outside the build inputs (they cannot reach the jar):` + listed(tree.ignored));
  const branch = git('rev-parse', '--abbrev-ref', 'HEAD');
  if (branch === 'HEAD') die('detached HEAD: check out the branch to release from');
  const pushUrl = git('remote', 'get-url', '--push', 'origin');
  // Chosen now, so a missing token or an unreadable origin stops the run before anything changes.
  const publisher = NO_RELEASE || (DRY && !hasGh && !githubToken) ? null
    : hasGh ? ghPublisher(ghEnv) : apiPublisher(githubToken);

  // Compute the new version + build number -----------------------------------
  const props = readProps();
  const pkgVersion = readPkgVersion();
  if (pkgVersion !== props.mod_version) {
    warn(`package.json (${pkgVersion}) and gradle.properties mod_version (${props.mod_version}) disagree; using package.json`);
  }
  const newVersion = bumpSemver(pkgVersion, bumpSpec);
  const newBuild = String(Number(props.build_number || '0') + 1);
  const fullVersion = `${newVersion}+build.${newBuild}`;
  const tag = `v${newVersion}`;
  const jarName = `${ARCHIVE_BASE}-${fullVersion}.jar`;
  const jarPath = path.join(ROOT, 'build', 'libs', jarName);
  const title = `${tag} (build ${newBuild})`;

  // Guards: the tag is new everywhere, and the push will fast-forward ---------
  if (gitOk('rev-parse', '-q', '--verify', `refs/tags/${tag}`).code === 0) die(`git tag ${tag} already exists locally; bump to a different version`);
  if (remoteRef(pushUrl, `refs/tags/${tag}`)) die(`tag ${tag} already exists on origin; bump to a different version`);
  const remoteHead = remoteRef(pushUrl, `refs/heads/${branch}`);
  if (remoteHead && gitOk('merge-base', '--is-ancestor', remoteHead, 'HEAD').code !== 0) {
    die(`origin/${branch} has commits this checkout lacks. Pull first, so the push cannot be rejected halfway.`);
  }
  // The ffmpeg source the notice promises. With --allow-thin it may be absent, but only if the jar
  // then turns out thin (no ffmpeg ships, so there is no source to offer); that is checked after the build.
  const sources = NO_RELEASE ? { found: [], missing: [] } : findSourceArchives();
  if (sources.missing.length && !ALLOW_THIN) {
    die('the release must attach the ffmpeg source the notice promises, and .product/sources/ lacks:' + sourcesHelp(sources.missing));
  }

  // Plan ---------------------------------------------------------------------
  log('');
  log(C.bold('  Release plan'));
  log(`    branch        ${branch}`);
  log(`    version       ${C.dim(pkgVersion)} → ${C.green(newVersion)}`);
  log(`    build number  ${C.dim(props.build_number)} → ${C.green(newBuild)}`);
  log(`    jar           ${jarName}`);
  if (!NO_RELEASE) {
    log(`    source        ${sources.found.length ? sources.found.map((f) => path.basename(f)).join(', ') : C.yellow('none (allowed only if the jar is thin)')}`);
  }
  log(`    git tag       ${tag}`);
  log(`    GitHub release${NO_RELEASE ? C.yellow('  (skipped: --no-release)') : `  ${title} via ${publisher ? publisher.name : 'nothing (no gh login or GITHUB_PAT)'}`}`);
  log(`    order         bump, build, commit, tag${NO_RELEASE ? '' : ', draft + upload'}, atomic push${NO_RELEASE ? '' : ', publish'}`);
  log('');
  if (DRY) { log(C.yellow('dry run: checks passed, no changes made.')); return; }
  if (!(await confirm(`Proceed with release ${C.bold(tag)}?  [y/N]`))) die('aborted.');

  // 1. Bump version files -----------------------------------------------------
  step('updating version files');
  writePkgVersion(newVersion);
  writeProps({ mod_version: newVersion, build_number: newBuild });
  undo.push(['restored package.json and gradle.properties', () => {
    writePkgVersion(pkgVersion); writeProps({ mod_version: props.mod_version, build_number: props.build_number });
  }]);

  // 2. Build the jar ----------------------------------------------------------
  step('building the mod jar (local fat jar with bundled binaries)');
  const javaHome = resolveJavaHome();
  const build = runBuild(javaHome, resolveWslBuild());
  if (build.status !== 0) throw new Error('gradle build failed');
  if (!existsSync(jarPath)) throw new Error(`expected jar not found: ${jarPath}`);
  const jarMb = (readFileSync(jarPath).length / 1e6).toFixed(1);
  log(C.dim(`    built ${jarName} (${jarMb} MB)`));
  const bundle = inspectBundle(jarPath);
  if (bundle.thin) {
    const why = 'the jar is THIN: it bundles no binaries (.product/ was missing at build time)';
    if (NO_RELEASE || ALLOW_THIN) warn(why + (NO_RELEASE ? '' : '; releasing it because of --allow-thin'));
    else throw new Error(`${why}. Rebuild with .product/ in place, or pass --allow-thin to release it anyway.`);
  } else if (bundle.problems.length) {
    const list = bundle.problems.map((p) => '\n    ' + p).join('');
    if (NO_RELEASE) warn('the bundled set does not match its notice:' + list);
    else throw new Error('the bundled set does not match its notice, so the notice that ships would be wrong:' + list);
  } else {
    log(C.dim(`    bundles ${BUNDLED.length} binaries, each listed by SHA-256 in the bundled notice`));
  }
  if (!NO_RELEASE && !bundle.thin && sources.missing.length) {
    throw new Error('the jar bundles ffmpeg, so the release must attach its source, and .product/sources/ lacks:' + sourcesHelp(sources.missing));
  }
  const assets = [jarPath, ...(bundle.thin ? [] : sources.found)];

  // 3. Commit and tag, locally ------------------------------------------------
  step('committing and tagging locally');
  git('add', 'package.json', 'gradle.properties');
  // Author identity comes from the repo config (gbtilabs); message carries no trailers.
  git('commit', '-m', `Release ${tag} (build ${newBuild})`);
  // --keep, not --hard: it refuses rather than discard anything that changed since.
  undo.push(['removed the release commit', () => git('reset', '--keep', 'HEAD~1')]);
  git('tag', '-a', tag, '-m', `Jockey Radio ${title}`);
  undo.push([`deleted the local tag ${tag}`, () => git('tag', '-d', tag)]);

  // Everything local has passed. From here on, things leave the machine. --------
  remote.tag = tag;
  remote.branch = branch;

  // 4. Draft release with every asset ------------------------------------------
  if (!NO_RELEASE) {
    remote.publisher = publisher;
    step(`creating a draft release via ${publisher.name}`);
    remote.draft = await publisher.createDraft({ tag, title, branch });
    for (const file of assets) {
      step(`uploading ${path.basename(file)}`);
      await publisher.upload(file);
    }
  }

  // 5. One atomic push: the branch and the tag land together or not at all ------
  step(`pushing ${branch} and ${tag} (atomic)`);
  git('push', '--atomic', 'origin', branch, `refs/tags/${tag}`);
  remote.pushed = true;

  if (NO_RELEASE) {
    log('');
    log(C.green('✓ ') + `pushed ${tag}. GitHub release skipped (--no-release).`);
    return;
  }

  // 6. Publish the draft --------------------------------------------------------
  step('publishing the release');
  const url = await remote.publisher.publish();
  log('');
  log(C.green('✓ ') + C.bold(`released ${title}`));
  if (url) log('  ' + url);
}

// What a failure leaves behind, said plainly. Before the push, the draft is deleted and the local
// steps are undone; after it, nothing is touched, because origin already has the commit and tag.
async function recover(e) {
  console.error(C.red('\n✗ release failed: ') + (e.message || e));
  if (remote.pushed) {
    warn(`origin already has ${remote.branch} at the release commit and the tag ${remote.tag}; nothing was rolled back.`);
    if (remote.draft) {
      warn(`the draft release holds every asset${remote.draft.url ? `: ${remote.draft.url}` : ''}`);
      warn(`publish it by hand: gh release edit ${remote.tag} --draft=false --latest (or in the GitHub web UI)`);
    }
    return;
  }
  if (remote.draft) {
    try {
      await remote.publisher.deleteDraft();
      warn('deleted the draft release; nothing was pushed.');
    } catch (err) {
      warn(`could not delete the draft release (${err.message}). Delete it by hand${remote.draft.url ? `: ${remote.draft.url}` : ''}`);
    }
  } else if (remote.tag) {
    warn('nothing was pushed and no release was created.');
  }
  for (const [what, fn] of undo.reverse()) {
    try { fn(); warn(what); } catch (err) { warn(`could not undo (${what}): ${err.message}`); }
  }
}

main().catch(async (e) => { await recover(e); process.exit(1); });
