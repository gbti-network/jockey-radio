#!/usr/bin/env node
// Traveler's Jukebox release automation. Bumps the version + build number, builds the mod jar
// LOCALLY (the fat jar bundles the ffmpeg/yt-dlp binaries from .product/, which CI cannot see),
// tags + pushes, and publishes a GitHub Release with the jar attached.
//
//   npm run release            # patch bump  (0.1.0 -> 0.1.1)
//   npm run release:minor      # minor bump  (0.1.0 -> 0.2.0)
//   npm run release:major      # major bump  (0.1.0 -> 1.0.0)
//   npm run release -- 0.4.0   # explicit version
//   npm run release:dry        # print the plan, change nothing
//   npm run release:build      # bump + build + commit + push, but DO NOT create the GH release
//
// Zero dependencies (Node built-ins only), so no `npm install` is needed.

import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { createInterface } from 'node:readline';
import { homedir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const IS_WIN = process.platform === 'win32';
const GRADLEW = IS_WIN ? path.join(ROOT, 'gradlew.bat') : path.join(ROOT, 'gradlew');
const ARCHIVE_BASE = 'travelers-jukebox';

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
  if (!process.stdin.isTTY) die('not a TTY - re-run with --yes to skip the confirmation prompt');
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
  die(`unknown bump "${spec}" - use patch | minor | major | x.y.z`);
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

// ---- main ------------------------------------------------------------------
const undo = []; // rollback actions, run in reverse on failure
async function main() {
  process.chdir(ROOT);
  if (!existsSync(GRADLEW)) die(`gradle wrapper not found at ${GRADLEW}`);

  // Prereqs -------------------------------------------------------------------
  step('checking prerequisites');
  const auth = tryRun('gh', ['auth', 'status']);
  let ghEnv = { ...process.env };
  if (auth.code !== 0) {
    // Fall back to the PAT in .env so a fresh machine can still publish.
    const envPath = path.join(ROOT, '.env');
    const pat = existsSync(envPath) && (readFileSync(envPath, 'utf8').match(/^GITHUB_PAT=(.+)$/m) || [])[1];
    if (pat) { ghEnv.GH_TOKEN = pat.trim(); if (!DRY) warn('gh not logged in - using GITHUB_PAT from .env'); }
    else if (!DRY && !NO_RELEASE) die('gh CLI is not authenticated. Run `gh auth login` (or set GITHUB_PAT in .env).');
  }
  const dirty = gitOk('status', '--porcelain').out;
  // A dry run only previews, so a dirty tree is fine (and expected). A real release requires clean.
  if (dirty && !DRY) die('working tree has uncommitted changes - commit or stash them first:\n' + dirty);
  const branch = git('rev-parse', '--abbrev-ref', 'HEAD');

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

  // Guard: tag must not already exist ----------------------------------------
  if (gitOk('rev-parse', tag).code === 0) die(`git tag ${tag} already exists - bump to a different version`);

  // Plan ---------------------------------------------------------------------
  log('');
  log(C.bold('  Release plan'));
  log(`    branch        ${branch}`);
  log(`    version       ${C.dim(pkgVersion)} → ${C.green(newVersion)}`);
  log(`    build number  ${C.dim(props.build_number)} → ${C.green(newBuild)}`);
  log(`    jar           ${jarName}`);
  log(`    git tag       ${tag}`);
  log(`    GitHub release${NO_RELEASE ? C.yellow('  (skipped: --no-release)') : `  ${title}`}`);
  log('');
  if (DRY) { log(C.yellow('dry run - no changes made.')); return; }
  if (!(await confirm(`Proceed with release ${C.bold(tag)}?  [y/N]`))) die('aborted.');

  // 1. Bump version files -----------------------------------------------------
  step('updating version files');
  writePkgVersion(newVersion);
  writeProps({ mod_version: newVersion, build_number: newBuild });
  undo.push(() => { writePkgVersion(pkgVersion); writeProps({ mod_version: props.mod_version, build_number: props.build_number }); });

  // 2. Build the jar ----------------------------------------------------------
  step('building the mod jar (local fat jar with bundled binaries)');
  const javaHome = resolveJavaHome();
  if (javaHome) log(C.dim(`    JAVA_HOME=${javaHome}`));
  else warn('no JDK 25 found via JAVA_HOME or ~/.jdks - relying on the Gradle toolchain');
  const buildEnv = javaHome ? { ...process.env, JAVA_HOME: javaHome } : process.env;
  const build = spawnSync(GRADLEW, ['clean', 'build'], { cwd: ROOT, stdio: 'inherit', env: buildEnv, shell: false });
  if (build.status !== 0) throw new Error('gradle build failed');
  if (!existsSync(jarPath)) throw new Error(`expected jar not found: ${jarPath}`);
  const jarMb = (readFileSync(jarPath).length / 1e6).toFixed(1);
  if (Number(jarMb) < 5) warn(`jar is only ${jarMb} MB - looks like a THIN jar (no .product/ binaries bundled)`);
  else log(C.dim(`    built ${jarName} (${jarMb} MB)`));

  // 3. Commit, tag, push ------------------------------------------------------
  step('committing, tagging and pushing');
  git('add', 'package.json', 'gradle.properties');
  // Author identity comes from the repo config (gbtilabs); message carries no trailers.
  git('commit', '-m', `Release ${tag} (build ${newBuild})`);
  undo.push(() => gitOk('reset', '--hard', 'HEAD~1'));
  git('tag', '-a', tag, '-m', `Traveler's Jukebox ${title}`);
  undo.push(() => gitOk('tag', '-d', tag));
  git('push', 'origin', branch);
  git('push', 'origin', tag);
  undo.push(() => gitOk('push', 'origin', ':refs/tags/' + tag)); // delete remote tag on rollback

  // 4. GitHub Release ---------------------------------------------------------
  if (NO_RELEASE) {
    log('');
    log(C.green('✓ ') + `pushed ${tag}. GitHub release skipped (--no-release).`);
    return;
  }
  step('creating the GitHub release and attaching the jar');
  const rel = spawnSync('gh', [
    'release', 'create', tag, jarPath,
    '--title', title,
    '--generate-notes',
    '--latest',
    '--verify-tag',
  ], { cwd: ROOT, stdio: 'inherit', env: ghEnv, shell: false });
  if (rel.status !== 0) throw new Error('gh release create failed');

  const url = tryRun('gh', ['release', 'view', tag, '--json', 'url', '--jq', '.url'], { env: ghEnv }).out;
  log('');
  log(C.green('✓ ') + C.bold(`released ${title}`));
  if (url) log('  ' + url);
}

main().catch((e) => {
  console.error(C.red('\n✗ release failed: ') + (e.message || e));
  if (undo.length) {
    warn('rolling back...');
    for (const fn of undo.reverse()) { try { fn(); } catch { /* best effort */ } }
    warn('rolled back local version bump / commit / tag. If a GitHub release was created, delete it manually.');
  }
  process.exit(1);
});
