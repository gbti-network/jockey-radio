# Publishing SavePoint Radio

Release checklist for a Modrinth / Fabric distribution. **This document does not publish
anything — it records the steps.** Do not upload until the owner signs off on the open
decisions below.

---

## Build the release jar

```bash
export JAVA_HOME=$HOME/.jdks/jdk-25.0.3+9
export PATH=$JAVA_HOME/bin:$PATH
./gradlew clean build
```

The distributable jar is the remapped output in **`build/libs/`**:

- `savepoint-radio-<version>.jar` — the mod (upload this)
- `savepoint-radio-<version>-sources.jar` — sources (optional additional file)

`<version>` comes from `mod_version` in `gradle.properties` (currently `0.1.0`).

> On this unobfuscated 26.2 toolchain there is no `remapJar` task, so `build` produces the
> final jar directly via `jar`. The `LICENSE` file at the repo root is bundled into the jar
> by `build.gradle` (`jar { from('LICENSE') }`).

---

## Modrinth project / version fields

Metadata comes from `src/main/resources/fabric.mod.json` (the build expands `${version}`);
mirror it in the Modrinth form:

| Modrinth field        | Value / source                                                            |
| --------------------- | ------------------------------------------------------------------------- |
| Name                  | SavePoint Radio                                                           |
| Summary               | `description` in fabric.mod.json                                          |
| Environment           | Server: **required** · Client: **optional** (`environment: "*"`, ducking) |
| Loaders               | Fabric                                                                    |
| Game versions         | `26.2` — confirm the exact Modrinth game-version label at publish time     |
| Version number        | `mod_version` (e.g. `0.1.0`)                                              |
| Channel               | Release / Beta as appropriate                                            |
| License               | ARR (or MIT — see decision below)                                        |
| Links                 | `contact.homepage` / `sources` / `issues` (see placeholder note)         |

**Dependencies to declare on the Modrinth version:**

| Dependency                              | Type      |
| --------------------------------------- | --------- |
| Fabric API                              | required  |
| Simple Voice Chat                       | optional  |

(ffmpeg is a **host** requirement, not a Modrinth dependency — call it out in the
description/body, not the dependency list.)

**Body:** reuse the [README](README.md). **Do not** include any stream keys, access
tokens, now-playing tokens, or the dev stream key anywhere in the listing.

---

## Open decisions before first publish

1. **License (owner's call).** Currently `ARR` in `fabric.mod.json` with a matching
   All-Rights-Reserved [`LICENSE`](LICENSE). If SavePoint.fm wants a permissive
   open-source release instead, replace `LICENSE` with the standard MIT text and change the
   `fabric.mod.json` `license` field from `"ARR"` to `"MIT"`. Both are valid on Modrinth.

2. **Pack icon (required by Modrinth/ModMenu; asset not yet present).** `fabric.mod.json`
   references **`assets/savepoint_radio/icon.png`**, but that file does **not exist yet**.
   Before release, add an original PNG (128×128 or 256×256 recommended) at
   `src/main/resources/assets/savepoint_radio/icon.png` and rebuild. Until then the
   reference is a documented placeholder — harmless on a dedicated server (no ModMenu), but
   it should be filled in for the public release. Also upload the same image as the
   Modrinth project icon.

3. **Contact URLs are placeholders.** `contact.sources` and `contact.issues` point at
   `https://github.com/savepoint-fm/minecraft-savepoint-radio` — replace with the real
   repository (or remove) before publishing. `contact.homepage` (`https://savepoint.fm`) is
   real.

---

## Pre-flight verification

- [ ] `./gradlew clean build` is green on JDK 25 (CI: `.github/workflows/build.yml`).
- [ ] `build/libs/savepoint-radio-<version>.jar` exists and loads on a clean Fabric 26.2
      server with Fabric API (+ Simple Voice Chat + ffmpeg for a full smoke test).
- [ ] `LICENSE` is present and bundled in the jar.
- [ ] `assets/savepoint_radio/icon.png` exists and the `icon` field resolves.
- [ ] `contact.sources` / `contact.issues` are real URLs.
- [ ] No secrets (tokens, stream keys) appear in the jar, README, or listing.
- [ ] Modrinth version declares Fabric API (required) and Simple Voice Chat (optional).
