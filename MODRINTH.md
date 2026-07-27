# Publishing Jockey Radio

Release checklist for a Modrinth / Fabric distribution. **This document does not publish
anything - it records the steps.** The listing copy, headings, and gallery plan live in
`.data/ops/branding-ops/branding-ops.md` (local, off-repo).

---

## Build the release jar

```bash
export JAVA_HOME=$HOME/.jdks/jdk-25.0.3+9
export PATH=$JAVA_HOME/bin:$PATH
./gradlew clean build
```

The distributable jar is in **`build/libs/`**:

- `jockey-radio-<version>.jar` - the mod (upload this)
- `jockey-radio-<version>-sources.jar` - sources (optional additional file)

`<version>` comes from `mod_version` in `gradle.properties` (currently `0.1.3`); the full
jar name is `mod_version+build.<n>` where `npm run release` bumps the build number.

> On this unobfuscated 26.2 toolchain there is no `remapJar` task, so `build` produces the
> final jar directly via `jar`. The `LICENSE` file at the repo root is bundled into the jar
> by `build.gradle`. On linux/amd64 the jar also bundles ffmpeg/yt-dlp/deno (from `.product/`)
> for a zero-install drop-in; a build without those present is a THIN jar (falls back to a
> configured/PATH ffmpeg).

---

## Modrinth project / version fields

Metadata comes from `src/main/resources/fabric.mod.json` (the build expands `${version}`);
mirror it in the Modrinth form:

| Modrinth field  | Value / source                                                            |
| --------------- | ------------------------------------------------------------------------- |
| Name            | **Jockey Radio**                                                           |
| Summary         | `description` in fabric.mod.json                                          |
| Publisher       | GBTI                                                                       |
| Environment     | Server: **required** · Client: **unsupported** (`environment: "server"`)   |
| Loaders         | Fabric                                                                    |
| Game versions   | `26.2` - confirm the exact Modrinth game-version label at publish time    |
| Version number  | `0.1.3+build.4`                                                          |
| Channel         | Release / Beta as appropriate                                            |
| License         | ARR                                                                       |
| Links           | `contact.homepage` / `sources` / `issues` (github.com/gbti-network/jockey-radio) |

**Dependencies to declare on the Modrinth version:**

| Dependency          | Type      |
| ------------------- | --------- |
| Fabric API          | required  |
| Simple Voice Chat   | required  |

(ffmpeg/yt-dlp are **bundled** on linux/amd64, so they are not Modrinth dependencies. Note
the bundling and the host fallback in the description.)

**Body:** reuse the [README](README.md), with the gallery/headings from branding-ops.
**Do not** include any stream keys, access tokens, or now-playing tokens anywhere in the
listing.

**Icon:** upload `src/main/resources/assets/jockey_radio/icon.png` (the Jockey Radio broadcast
mark) as the Modrinth project icon.

---

## Settled (previously open) decisions

1. **License:** `ARR`, © 2026 GBTI (matching `LICENSE` and the `fabric.mod.json` `license`
   field). SavePoint is credited as the optional integration, not the owner.
2. **Icon:** present at `assets/jockey_radio/icon.png` (256x256), generated from
   `.product/logo/Jockey Radio.svg`; the full size set is in `.product/logo/exports/`.
3. **Contact URLs:** `sources` / `issues` point at `github.com/gbti-network/jockey-radio`;
   `homepage` is currently the same repo (swap in a product domain if/when one exists).

---

## Pre-flight verification

- [ ] `./gradlew clean build` is green on JDK 25 (CI: `.github/workflows/build.yml`).
- [ ] `build/libs/jockey-radio-<version>.jar` loads on a clean Fabric 26.2 server with Fabric
      API (+ Simple Voice Chat + ffmpeg for a full smoke test).
- [ ] `LICENSE` is present and bundled in the jar.
- [ ] `assets/jockey_radio/icon.png` exists and the `icon` field resolves.
- [ ] jar `fabric.mod.json` reads `id: jockey_radio`, `name: Jockey Radio`, `authors: [GBTI]`.
- [ ] No secrets (tokens, stream keys) appear in the jar, README, or listing.
- [ ] Modrinth version declares Fabric API (required) and Simple Voice Chat (required).
- [ ] README / listing carry no stale "SavePoint Radio" or "Traveler's Jukebox" identity.
