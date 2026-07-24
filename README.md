# SavePoint Radio

A **server-side Fabric mod** that turns an ordinary music disc into a **live radio
receiver**. Drop the disc into any jukebox and it streams a live audio feed —
positionally, through [Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat) —
so players standing near the jukebox hear the station as if it were playing in the world.

- **Minecraft:** 26.2 (unobfuscated) · **Loader:** Fabric 0.19.3+ · **Java:** 25
- **Mod id:** `savepoint_radio` · **Environment:** server (client side is optional)

---

## What it is

SavePoint Radio adds **no new item** and needs **no resource pack**. A radio disc is a
real vanilla music disc (`music_disc_13` by default, or any disc item an admin picks)
whose `jukebox_playable` component points at a bundled data-driven jukebox song,
`savepoint_radio:radio`. That song references a sound event
(`savepoint_radio:radio_stream`) that has **no `sounds.json` entry anywhere**, so:

- A **vanilla client** resolves no sound and simply plays silence — the disc "works"
  (it goes in the jukebox, shows its name, spins) and **no client is ever kicked**.
- The **actual audio** is delivered out-of-band by the server through the Simple Voice
  Chat plugin API, as a **locational** channel anchored at the jukebox block. Players
  with a Simple Voice Chat client within the disc's radius hear it, spatialised.

The station/disc identity travels in the disc's `custom_data` component
(`{"savepoint_radio": {"station": "<key>"}}` for a config station, or
`{"savepoint_radio": {"disc": "<discId>"}}` for an admin registry disc). **Secrets**
(HLS access tokens, RTMP stream keys) live only in the server-side config/registry and
**never enter item NBT**.

### How a session works

Sessions track **disc presence**, not vanilla play state — while a radio disc sits in a
jukebox the session is live, even after vanilla stops the 5-minute song timer. Jukebox
mixins (`JukeboxBlockEntityMixin`) and Fabric block-entity/lifecycle events drive the
state machine in `RadioManager`:

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
- **[ffmpeg](https://ffmpeg.org/) on the host `PATH`** (or an absolute path configured via
  `ffmpegBinary`). ffmpeg is what pulls/decodes the audio. Without it the mod still loads
  and sessions still track, but **no audio streams** — see
  [`docs/HOSTING.md`](docs/HOSTING.md).
- **[Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat)** (server side) to
  actually deliver audio. It is a **suggested** dependency, not a hard one: without it the
  discs still work as (silent) sessions.

### Players (to HEAR the radio)
- **Fabric Loader + Fabric API + the Simple Voice Chat client mod.** That is all a player
  needs to hear a station. The SavePoint Radio mod itself is **not** required on the client.

### Client (optional)
- Installing the SavePoint Radio jar **client-side** is optional and adds exactly one
  thing: **music ducking** (Minecraft's own background music quiets down near a playing
  radio jukebox). See [Music ducking](#music-ducking-client-optional). Vanilla / SVC-only
  clients hear the radio perfectly without it.

---

## Install

1. Install Fabric Loader 0.19.3+ for Minecraft 26.2 on your server.
2. Drop **Fabric API**, **Simple Voice Chat** (server), and the **SavePoint Radio** jar
   into the server's `mods/` folder.
3. Ensure **ffmpeg** is installed and on the `PATH` (verify with `ffmpeg -version`).
4. Start the server once. It writes default config to `config/savepoint_radio.json`
   (a demo `pulsefm` station) and creates `config/savepoint_radio/discs.json` on first
   admin disc save.
5. As an operator, run `/savepoint` to open the admin UI, or `/radiodisc give` for the
   built-in config station.

Building from source:

```bash
export JAVA_HOME=$HOME/.jdks/jdk-25.0.3+9
export PATH=$JAVA_HOME/bin:$PATH
./gradlew build          # remapped jar lands in build/libs/
./gradlew deployMod      # copies the jar into .server/mods (local dev server)
```

---

## Disc source types

Every disc streams from one of two source backends.

### 1. HLS pull (SavePoint station / any HLS)

ffmpeg pulls an HLS trunk playlist and decodes it to PCM. This is the SavePoint station
path (`StationEndpoints` builds
`https://<host>/radio/broadcast/<stationId>/trunk.m3u8`, or the Direct-Stream-Access
`/api/public/stations/<id>/stream/trunk.m3u8` shape when `useRawBroadcastPath` is turned
off). An optional access **token** is appended as `?token=…` and is redacted from every
log line. Config stations may also carry a fully-formed URL (with an inline token).

### 2. RTMP receive (DJ / OBS / relay pushes in)

The mod runs an ffmpeg RTMP **listener** (`ffmpeg -listen 1 -i rtmp://<bind>:<port>/live/<key>`)
and a broadcaster (OBS, a relay, a DJ rig) publishes into it. Each RTMP disc needs **one
distinct port**, and serves **one publisher at a time**. The **stream key** gates who may
publish and is never logged or placed in item NBT.

> **⚠️ RTMP port collision — do not use 1935.**
> 1935 is the standard RTMP port, but on a SavePoint host the local SavePoint server
> already owns it, so a mod listener there can never bind (ffmpeg loops
> `Address already in use`, receives 0 frames, stays silent). The mod **rejects port
> 1935** at disc-validation time with a clear message. **Use `1936`** (the known-free
> port) or another free port ≥ 1024 instead.

The `/savepoint` disc detail page shows the local ingest
(`rtmp://127.0.0.1:<port>/live` + stream key) and, when the disc binds `0.0.0.0` **and**
`rtmpPublicHost` is configured, an external ingest form for remote broadcasters. See
[`docs/HOSTING.md`](docs/HOSTING.md) for firewall/exposure notes.

---

## Admin UI and commands

### `/savepoint` — dialog admin UI (op level 3 by default)

Running `/savepoint` as a player opens a vanilla **dialog UI** (no client mod required —
it uses vanilla `server.dialog.*`) to create, browse, edit, give, **test**, and delete
radio discs. The permission gate is `adminPermissionLevel` (default `3` =
`LEVEL_ADMINS`) and is re-checked server-side on every dialog submit. Connection info
(RTMP ingest URL, stream key) is copied to the requesting admin's clipboard client-side
and is serialized only into that one admin's dialog packet — never logged.

Console / headless subcommands (no dialog needed):

| Command                              | Purpose                                   |
| ------------------------------------ | ----------------------------------------- |
| `/savepoint give <discId> [player]`  | Give an admin registry disc               |
| `/savepoint list`                    | List all registry discs (secrets masked)  |
| `/savepoint reload`                  | Reload `discs.json` from disk             |

### `/radiodisc` — config-station discs & diagnostics (op level 2)

| Command                                  | Purpose                                          |
| ---------------------------------------- | ------------------------------------------------ |
| `/radiodisc give [station] [player]`     | Give a disc for a **config** station (defaults to `defaultStation` / self) |
| `/radiodisc sessions`                    | List tracked sessions + live feed stats          |
| `/radiodisc diag`                        | Per-feed diagnostics (frames, drops, buffer occupancy, reconnects, …) |

---

## Configuration

### `config/savepoint_radio.json` (server)

```jsonc
{
  "stations": {
    "pulsefm": {
      "displayName": "PulseFM",
      "url": "https://savepoint.fm/radio/broadcast/35/trunk.m3u8",
      "token": null,
      "radiusBlocks": 32
    }
  },
  "defaultStation": "pulsefm",

  "ingest": "ffmpeg",            // "ffmpeg" (default) or "stub" (track sessions, no audio)
  "ffmpegBinary": "ffmpeg",      // bare name (PATH) or absolute path

  "targetBufferSeconds": 3.0,    // steady-state playout latency (jitter buffer target)
  "maxBufferSeconds": 12.0,      // hard cap on buffered audio (memory bound)
  "prerollSeconds": 3.0,         // fill required before (re)starting playout; defaults to target

  "reconnectBackoffMaxSeconds": 60,
  "useRawBroadcastPath": true,   // raw /radio/broadcast path vs Direct Stream Access
  "rtmpPublicHost": null,        // bare host[:port] for external RTMP ingest (no scheme/path)
  "adminPermissionLevel": 3,     // op level required by /savepoint (0..4)
  "nowPlayingPollSeconds": 0,    // 0 = LIVE indicator disabled; else 5..3600
  "monitorSeconds": 90,          // /savepoint "Test" audible-monitor auto-stop (10..600)
  "nowPlayingHud": true,         // server-side NOW-PLAYING boss bar on/off
  "nowPlayingHudMode": "beacon"  // "beacon" (needs a trigger block) or "always"
}
```

Key knobs:

- **`targetBufferSeconds` / `maxBufferSeconds` / `prerollSeconds`** — the jitter buffer.
  Higher target = smoother playback across upstream hiccups but more latency; lower =
  snappier but more prone to underruns. Frame counts are derived at 50 frames/s. See
  [`docs/HOSTING.md`](docs/HOSTING.md) for the latency-vs-smoothness trade-off. (A legacy
  `bufferFrames` key is still accepted and migrated into `maxBufferSeconds`.)
- **`ingest`** — set to `"stub"` for a fully headless run (no ffmpeg spawns, sessions
  still track). Handy for testing.
- **`nowPlayingHudMode`** — in `"beacon"` mode the NOW-PLAYING boss bar only shows while a
  trigger block (**lantern, sea lantern, lava, redstone block, or redstone torch**) sits
  in the 3×3×3 around the jukebox, so a station only announces itself when its owner
  places a "beacon" nearby. `"always"` shows the bar for every playing disc in range.
- **`rtmpPublicHost`** — a bare `host` or `host:port` authority (no scheme, no path) that
  an external DJ/OBS uses to reach an RTMP disc bound to `0.0.0.0`. A value with a scheme
  or path is rejected on load.

Values are validated and clamped on load; an unknown or out-of-range value logs a warning
and falls back to the default. A malformed file is logged and replaced **in memory** by
defaults (the broken file on disk is left untouched) so the server keeps running.

### `config/savepoint_radio/discs.json` (server)

The admin disc registry, written/read by the `/savepoint` UI. Each entry is a
`DiscDefinition` (id, display name, colour, base disc item, source type + fields, radius).
Hand-editable; validated on load with bad entries dropped and logged. Written atomically.

### `config/savepoint_radio_client.json` (client only — music ducking)

Only read on a physical client that has the mod installed. Defaults:

```jsonc
{
  "enabled": true,
  "musicFloor": 0.15,            // music volume multiplier at innerRadius (0.01..1.0)
  "innerRadius": 4,              // at/below this distance, music is at musicFloor
  "outerRadius": 24,             // at/beyond this distance, no ducking
  "requireVoicechatConnected": true  // only duck while connected to the SVC server
}
```

---

## Features and how to enable them

### Positional audio (core)
Automatic whenever Simple Voice Chat is installed server-side and a player with the SVC
client is within a playing disc's `radiusBlocks`. Audio is a locational Opus channel
encoded in **AUDIO** (music-optimised) mode, anchored at the jukebox block, tagged with a
"SavePoint Radio" volume category so players get a dedicated volume slider.

### NOW-PLAYING boss bar (server-side, opt-in gate)
A vanilla server→client boss bar naming the station (and its live track when available)
shows to players inside a playing disc's radius — **no client mod required**. On by
default (`nowPlayingHud: true`). In the default **`beacon`** mode it appears only when a
trigger block is near the jukebox; set `nowPlayingHudMode` to `"always"` to always show
it, or `nowPlayingHud: false` to disable it entirely. A player inside two discs' radii
sees only the **nearest** one's bar.

### LIVE now-playing indicator (opt-in, needs a token)
Off by default (`nowPlayingPollSeconds: 0`). When set to a positive interval (5..3600 s)
the poller GETs each HLS disc's
`/api/public/stations/<id>/now-playing` endpoint and records the live flag + current
track/artist/segment, which the disc-detail dialog and boss bar then show. Needs a
now-playing bearer token (`nowPlayingToken` on the disc, falling back to `token`). See
[`docs/HOSTING.md`](docs/HOSTING.md) for the enable steps.

### Audible monitor — `/savepoint` → "Test" (op-only)
Lets an admin **privately hear** a disc's feed without a jukebox: it plays into the
admin's own voice chat connection on a non-positional channel with a boss-bar level meter,
then auto-stops after `monitorSeconds` (default 90). Requires the admin to have a
connected SVC client.

### Music ducking (client, optional)
If the mod is installed **client-side**, Minecraft's own background music smoothly ducks
to `musicFloor` as the player approaches a playing SavePoint jukebox (detected from the
vanilla jukebox level events, no extra packets). Configurable and, by default, only active
while the player is actually connected to the SVC server. Fails open to full music on any
error. Purely local; the dedicated server neither reads nor needs this.

---

## Testing

Session state-machine behaviour is currently verified with a **manual test matrix** driven
over RCON (`tools/rcon.py`) against the local dev server, using `ingest: "stub"` so no
ffmpeg or SVC is needed:

| # | Action                                            | Expected (`/radiodisc sessions`)                  |
| - | ------------------------------------------------- | ------------------------------------------------- |
| 1 | Insert a radio disc into a jukebox                | session count **+1**, state `ACTIVE`; `feeds` +1  |
| 2 | Eject the disc (empty the jukebox)                | session count **−1** (stop reason `ejected`)      |
| 3 | Replace with a **different** radio disc           | one session stops (`replaced`), one starts        |
| 4 | Break the jukebox block                           | session **−1** (stop reason `broken`)             |
| 5 | Unload the chunk (move far away)                  | session state `SUSPENDED` (`unload`), count same  |
| 6 | Reload the chunk with the disc still in           | session back to `ACTIVE` (resume)                 |
| 7 | Insert a **vanilla** (non-radio) disc             | no session created                                |
| 8 | `/stop` the server                                | all sessions suspended, feed executor torn down   |

**Automated gametests are a deferred follow-up.** The mod is well-seamed for a stub-feed
`RadioManager.getSessionCount` lifecycle test (public assertion hooks exist:
`getSessionCount()`, `getSessions()`, `sessionForDisc()`, `FeedRegistry.feedCount()`), but
the **gametest API is not currently on the classpath** — fabric-api `0.155.2+26.2` ships no
gametest module and `build.gradle` declares no `fabric-gametest-api-v1` dependency — so
wiring a gametest would first require adding that dependency. The reason it is deferred, not
included: this is a **first-test spike** on a nonstandard toolchain — an unobfuscated 26.2
build with **no `remapJar`** and Loom `1.17-SNAPSHOT` on Java 25, with **no existing test
scaffolding or run configs**. Standing up a headless gametest server is exactly where the
risk to the (additive) build and the running dev server concentrates. The recommended
fast-follow is a small spike that first proves a `runGametest` task works on this
toolchain, then lands the stub-ingest lifecycle test.

---

## Credits & license

- Audio delivery uses the **[Simple Voice Chat](https://modrinth.com/mod/simple-voice-chat)**
  plugin API by Max Henkel (compiled against `voicechat-api`; none of its code is bundled).
- **License:** All Rights Reserved (see [`LICENSE`](LICENSE)). The `fabric.mod.json`
  `license` field is `ARR`. See [`MODRINTH.md`](MODRINTH.md) if the owner elects MIT
  instead.

For hosting/ops details (ffmpeg, firewalls, SVC tuning, buffering) see
[`docs/HOSTING.md`](docs/HOSTING.md). For release steps see [`MODRINTH.md`](MODRINTH.md).
