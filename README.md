# Jockey Radio

A **server-side Fabric mod** that turns an ordinary music disc into a **live radio
receiver**. Drop the disc into any jukebox and it streams a live audio feed,
positionally, through [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat),
so players standing near the jukebox hear the station as if it were playing in the world.

- **Minecraft:** 26.2 (unobfuscated) · **Loader:** Fabric 0.19.3+ · **Java:** 25
- **Mod id:** `jockey_radio` · **Environment:** server (client side is optional)
- **Publisher:** GBTI · Featured broadcasting integration: **SavePoint** (optional, not required)

---

## What it is

Jockey Radio adds **no new item** and needs **no resource pack**. A radio disc is a
real vanilla music disc (`music_disc_13` by default, or any disc item an admin picks)
whose `jukebox_playable` component points at a bundled data-driven jukebox song,
`jockey_radio:radio`. That song references a sound event (`jockey_radio:radio_stream`)
that has **no `sounds.json` entry anywhere**, so:

- A **vanilla client** resolves no sound and simply plays silence. The disc "works"
  (it goes in the jukebox, shows its name, spins) and **no client is ever kicked**.
- The **actual audio** is delivered out-of-band by the server through the Simple Voice
  Chat plugin API, as a **locational** channel anchored at the jukebox block. Players
  with a Simple Voice Chat client within the disc's radius hear it, spatialised.

The disc identity travels in the disc's `custom_data` component
(`{"jockey_radio": {"station": "<key>"}}` for a config station, or
`{"jockey_radio": {"disc": "<discId>"}}` for an admin registry disc). **Secrets**
(HLS access tokens, RTMP stream keys) live only in the server-side config/registry and
**never enter item NBT**. Discs made on earlier builds (the `travelers_jukebox` and
`savepoint_radio` eras) still resolve through a legacy-namespace fallback, and
`/jockey heal` re-stamps them into the current namespace in place.

### How a session works

Sessions track **disc presence**, not vanilla play state: while a radio disc sits in a
jukebox the session is live, even after vanilla stops the song timer. Jukebox mixins
(`JukeboxBlockEntityMixin`) and Fabric block-entity/lifecycle events drive the state
machine in `RadioManager`:

| Event                                  | Result                              |
| -------------------------------------- | ----------------------------------- |
| Radio disc inserted                    | session **starts** (ACTIVE), feed acquired |
| Disc ejected / replaced                | session **stops** (`ejected` / `replaced`) |
| Jukebox block broken                   | session **stops** (`broken`)        |
| Chunk unload                           | session **suspends** (`unload`)     |
| Chunk reload with disc still present   | session **resumes**                 |
| Server stop                            | all sessions suspended, feeds torn down |

Feeds are **ref-counted and shared**: two jukeboxes playing the same disc share one
ffmpeg process and one audio feed (`FeedRegistry`); the last jukebox leaving stops it.

---

## Requirements

### Server (required)
- **Java 25**
- **Fabric Loader** `>= 0.19.3` and **Fabric API** (`0.155.2+26.2` is the build target).
  Required modules: `fabric-command-api-v2`, `fabric-lifecycle-events-v1`,
  `fabric-networking-api-v1`, `fabric-resource-loader-v1` (installing the full Fabric API
  satisfies all of these).
- **[Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat)** (server side) to
  actually deliver audio. It is a **suggested** dependency, not a hard one: without it the
  discs still work as (silent) sessions.
- **ffmpeg** (and, for platform links, **yt-dlp**). On linux/amd64 hosts these are
  **bundled in the jar** and self-extract on first run (`useBundledBinaries`, default on),
  so nothing needs installing. On other platforms, put `ffmpeg` on the `PATH` (or set
  `ffmpegBinary`); without it the mod still loads and sessions still track, but no audio
  streams.

### Players (to HEAR the radio)
- **Fabric Loader + Fabric API + the Simple Voice Chat client mod.** That is all a player
  needs to hear a station. The Jockey Radio mod itself is **not** required on the client.

### Client (optional)
- Installing the Jockey Radio jar **client-side** is optional and adds exactly one thing:
  **music ducking** (Minecraft's own background music quiets down near a playing radio
  jukebox). Vanilla / SVC-only clients hear the radio perfectly without it.

---

## Install

1. Install Fabric Loader 0.19.3+ for Minecraft 26.2 on your server.
2. Drop **Fabric API**, **Simple Voice Chat** (server), and the **Jockey Radio** jar into
   the server's `mods/` folder.
3. On linux/amd64 the bundled ffmpeg/yt-dlp self-install on first start. Elsewhere, ensure
   `ffmpeg` is on the `PATH` (verify with `ffmpeg -version`).
4. Start the server once. It writes default config to `config/jockey_radio.json` (no
   stations by default) and creates `config/jockey_radio/discs.json` on first admin disc
   save.
5. As an operator, run `/jockey` to open the admin UI and create your first disc.

Building from source:

```bash
export JAVA_HOME=$HOME/.jdks/jdk-25.0.3+9
export PATH=$JAVA_HOME/bin:$PATH
./gradlew build          # jar lands in build/libs/ (unobfuscated 26.2: no remapJar)
./gradlew deployMod      # copies the jar into .server/mods (local dev server)
```

---

## Disc source types

A disc streams from one of four backends:

### 1. HLS pull (SavePoint station / any HLS)

ffmpeg pulls an HLS trunk playlist and decodes it to PCM. This is the SavePoint station
path (`StationEndpoints` builds `https://<host>/radio/broadcast/<stationId>/trunk.m3u8`,
or the Direct-Stream-Access `/api/public/stations/<id>/stream/trunk.m3u8` shape when
`useRawBroadcastPath` is off), and also plays any other m3u8 / icecast / http stream. An
optional access **token** is appended as `?token=…` and is redacted from every log line.

### 2. RTMP receive (DJ / OBS / relay pushes in)

The mod runs an ffmpeg RTMP **listener** (`ffmpeg -listen 1 -i rtmp://<bind>:<port>/live/<key>`)
and a broadcaster (OBS, a relay, a DJ rig) publishes into it. Each RTMP disc needs **one
distinct port** and serves **one publisher at a time**. The **stream key** gates who may
publish and is never logged or placed in item NBT.

> **RTMP port collision - do not use 1935.**
> 1935 is the standard RTMP port, but on a SavePoint host the local SavePoint server
> already owns it, so a mod listener there can never bind. The mod **rejects port 1935**
> at disc-validation time. **Use `1936`** (the known-free port) or another free port
> >= 1024 instead.

### 3. File / direct URL (looping)

A direct audio URL (mp3/ogg/etc.) or a file dropped in `config/jockey_radio/audio/`, served
locally and looped. Good for ambient beds, event music, or a fixed track.

### 4. Platform links (SoundCloud / Twitch / Kick / Mixcloud / ...)

yt-dlp resolves a supported platform link to a playable stream, which ffmpeg then decodes.
A bundled `deno` provides the JS runtime some platforms' extractors need. **Gated behind
`enablePlatformLinks` (default off):** enabling it is an explicit acceptance that you hold
the rights to rebroadcast the linked content on your server. Note that some platforms
block datacenter IPs, so a link that resolves from a home connection may fail on a hosted
server.

---

## Admin UI and commands

### `/jockey` - dialog admin UI (op level 3 by default)

Running `/jockey` as a player opens a vanilla **dialog UI** (no client mod required, it
uses vanilla `server.dialog.*`) to create, browse, edit, give, **test**, and delete radio
discs. The permission gate is `adminPermissionLevel` (default `3` = `LEVEL_ADMINS`) and is
re-checked server-side on every dialog submit. Connection info (RTMP ingest URL, stream
key) is serialized only into that one admin's dialog packet, never logged.

Console / headless subcommands (all under `/jockey`):

| Command                              | Purpose                                          |
| ------------------------------------ | ------------------------------------------------ |
| `/jockey give <discId> [player]`      | Give an admin registry disc                      |
| `/jockey station [key] [player]`      | Give a disc for a **config** station             |
| `/jockey list`                        | List all registry discs (secrets masked)         |
| `/jockey reload`                      | Reload `discs.json` from disk                    |
| `/jockey sessions`                    | List tracked sessions + live feed stats          |
| `/jockey diag`                        | Per-feed diagnostics (frames, drops, buffer, reconnects, ...) |
| `/jockey heal`                        | Re-stamp placed discs from the registry (fixes discs made on an earlier build) |

---

## Configuration

### `config/jockey_radio.json` (server)

A representative subset (values are validated and clamped on load; unknown/out-of-range
values log a warning and fall back to the default):

```jsonc
{
  "stations": {},                 // config stations (empty by default); admin discs live in discs.json
  "defaultStation": null,

  "ingest": "ffmpeg",             // "ffmpeg" (default) or "stub" (track sessions, no audio)
  "useBundledBinaries": true,     // self-extract the bundled ffmpeg/yt-dlp/deno (linux/amd64)
  "ffmpegBinary": "ffmpeg",       // bare name (PATH) or absolute path, when not bundled
  "ytdlpBinary": "yt-dlp",        // for platform links, when not bundled
  "enablePlatformLinks": false,   // gate SoundCloud/Twitch/Kick/... (rights acceptance)

  "useRawBroadcastPath": true,    // raw /radio/broadcast path vs Direct Stream Access
  "rtmpPublicHost": null,         // bare host[:port] for external RTMP ingest (no scheme/path)
  "adminPermissionLevel": 3,      // op level required by /jockey (0..4)
  "nowPlayingPollSeconds": 15,    // 0 disables the LIVE indicator; else 5..3600
  "monitorSeconds": 90,           // /jockey "Test" audible-monitor auto-stop (10..600)
  "nowPlayingHud": true,          // server-side NOW-PLAYING boss bar on/off
  "nowPlayingHudMode": "beacon",  // "beacon" (needs a trigger block) or "always"
  "suppressGameMusic": true,      // duck vanilla music near a playing jukebox
  "opusHighQuality": true         // music-grade Opus encoder for radio channels
}
```

- **`nowPlayingHudMode`** - in `"beacon"` mode the NOW-PLAYING boss bar only shows while a
  trigger block (lantern, sea lantern, lava, redstone block, or redstone torch) sits in the
  3x3x3 around the jukebox. `"always"` shows the bar for every playing disc in range.
- **`rtmpPublicHost`** - a bare `host` or `host:port` authority (no scheme, no path) that
  an external DJ/OBS uses to reach an RTMP disc bound to `0.0.0.0`.

A malformed file is logged and replaced **in memory** by defaults (the broken file on disk
is left untouched) so the server keeps running.

### `config/jockey_radio/discs.json` (server)

The admin disc registry, written/read by the `/jockey` UI. Each entry is a `DiscDefinition`
(id, display name, colour, base disc item, source type + fields, radius). Hand-editable;
validated on load with bad entries dropped and logged. Written atomically. Uploaded audio
files for FILE discs live alongside in `config/jockey_radio/audio/`.

### `config/jockey_radio_client.json` (client only - music ducking)

Only read on a physical client that has the mod installed. Ducks Minecraft's own music
toward `musicFloor` as the player approaches a playing jukebox (inner/outer radius,
optionally only while connected to the SVC server).

---

## Features

- **Positional audio (core)** - a locational Opus channel encoded in AUDIO (music) mode,
  anchored at the jukebox, tagged with a **"Jockey Radio"** volume category so players get a
  dedicated volume slider.
- **NOW-PLAYING boss bar** - a vanilla server->client boss bar naming the station (and its
  live track when available) inside a playing disc's radius, no client mod required. On by
  default; `beacon` mode gates it behind a nearby trigger block.
- **LIVE now-playing indicator** - polls each HLS disc's `/api/public/stations/<id>/now-playing`
  endpoint for the live flag + current track (needs a now-playing token).
- **Audible monitor** (`/jockey` -> "Test") - lets an admin privately hear a disc's feed
  without a jukebox, auto-stopping after `monitorSeconds`.
- **Music ducking (client, optional)** - if the mod is installed client-side, vanilla music
  smoothly ducks near a playing jukebox. Purely local; the dedicated server neither reads
  nor needs it.

---

## Credits & license

- Audio delivery uses the **[Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat)**
  plugin API by Max Henkel (compiled against `voicechat-api`; none of its code is bundled).
- Bundled binaries: **ffmpeg** (BtbN LGPL build), **yt-dlp** (Unlicense), **deno** (MIT).
  See `THIRD-PARTY-NOTICES.txt` in the jar.
- **Jockey Radio** is a **GBTI** project, with **SavePoint** as an optional featured
  broadcasting integration. It is not affiliated with or endorsed by Mojang, Microsoft,
  SoundCloud, Twitch, Kick, or Modrinth.
- **License:** All Rights Reserved (see [`LICENSE`](LICENSE)); the `fabric.mod.json`
  `license` field is `ARR`.

For release steps see [`MODRINTH.md`](MODRINTH.md).
