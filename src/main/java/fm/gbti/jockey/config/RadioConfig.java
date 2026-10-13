package fm.gbti.jockey.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fm.gbti.jockey.JockeyRadioMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static fm.gbti.jockey.config.ConfigLimits.*;

/**
 * Gson-backed configuration stored at {@code <configDir>/jockey_radio.json}.
 *
 * <pre>{@code
 * {
 *   "stations": {
 *     "pulsefm": {
 *       "displayName": "PulseFM",
 *       "url": "https://savepoint.fm/radio/broadcast/35/trunk.m3u8",
 *       "token": null,
 *       "radiusBlocks": 32
 *     }
 *   },
 *   "defaultStation": "pulsefm",
 *   "ingest": "ffmpeg",
 *   "ffmpegBinary": "ffmpeg",
 *   "bufferFrames": 600,
 *   "targetBufferSeconds": 3.0,
 *   "maxBufferSeconds": 12.0,
 *   "prerollSeconds": 3.0,
 *   "reconnectBackoffMaxSeconds": 60,
 *   "hlsStartBehindSegments": 15,
 *   "reconnectPreserveBuffer": true,
 *   "reanchorLowWatermarkSeconds": 1.0,
 *   "reanchorSustainSeconds": 8.0
 * }
 * }</pre>
 *
 * <p>The Phase 3 ingest keys are optional - files written before Phase 3 load
 * unchanged and pick up the defaults ({@code ingest} of {@code "stub"} keeps
 * the no-audio placeholder feeds; anything else means ffmpeg).</p>
 *
 * <p>The jitter-buffer keys ({@code targetBufferSeconds}, {@code maxBufferSeconds},
 * {@code prerollSeconds}) are likewise optional and absent from pre-jitter files.
 * They express the feed's target-latency playout in seconds; the frame counts are
 * derived at {@code 50} frames/second. A pre-jitter file that carried an explicit
 * non-default {@code bufferFrames} has it migrated to {@code maxBufferSeconds}
 * (logged once) so the old ring size becomes the new hard cap.</p>
 *
 * <p>Defaults and bounds live in {@link ConfigLimits}; the load-time clamps in
 * {@link ConfigNormalizer}.</p>
 */
public final class RadioConfig {

	/**
	 * Station keys must stay parseable by Brigadier's
	 * {@code StringArgumentType.word()} ({@code /jockey give <station>}),
	 * which only accepts these characters.
	 */
	private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_.+-]+");
	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.serializeNulls()
			.disableHtmlEscaping()
			.create();

	private static RadioConfig instance;

	public Map<String, Station> stations = new LinkedHashMap<>();
	public String defaultStation = null;

	/** Feed implementation: {@code "ffmpeg"} (default) or {@code "stub"}. */
	public String ingest = "ffmpeg";
	/** ffmpeg executable: bare name resolved via PATH, or an absolute path. */
	public String ffmpegBinary = "ffmpeg";
	/**
	 * Whether to install the ffmpeg/yt-dlp binaries bundled in the jar on first run and use
	 * them automatically (<b>default {@code true}</b>). See {@link BundledBinaries}: on
	 * linux/amd64 the binaries are extracted to {@code <configDir>/jockey_radio/bin/},
	 * chmod +x, and used ONLY while {@link #ffmpegBinary}/{@link #ytdlpBinary} are their default
	 * bare names - an explicit path always wins. Set this {@code false} to force the configured
	 * value / PATH even on a bundled build (e.g. to use a system ffmpeg you trust).
	 */
	public boolean useBundledBinaries = true;
	/**
	 * sow-007: yt-dlp executable for PLATFORM discs (bare name via PATH, or an absolute
	 * path). Resolves a SoundCloud/Mixcloud/Bandcamp/Twitch/Kick page URL to an audio
	 * stream. Only consulted by PLATFORM discs; if it is missing/outdated those discs
	 * log a resolve failure and retry, while every other disc is unaffected. Keep it
	 * updated - platforms change often. Pre-key files load with the default.
	 */
	public String ytdlpBinary = "yt-dlp";
	/**
	 * Extra command-line arguments passed to yt-dlp before the URL, for working around a platform's
	 * server-side blocking (datacenter-IP bot detection, geo-restriction) without a code change.
	 * Default empty. Each element is ONE argument (do not put a flag and its value in the same string).
	 * Examples:
	 * <ul>
	 *   <li>Authenticated session: {@code ["--cookies", "cookies.txt"]} (upload a cookies.txt exported
	 *       from a logged-in browser).</li>
	 *   <li>Route through a residential IP: {@code ["--proxy", "http://user:pass@host:port"]}.</li>
	 * </ul>
	 * YouTube stays off whatever is passed here (see {@code PlatformPolicy}). Only consulted by
	 * PLATFORM discs.
	 */
	public java.util.List<String> ytdlpExtraArgs = new java.util.ArrayList<>();
	/**
	 * sow-013: master gate for PLATFORM (third-party link) discs. <b>Default {@code false}.</b>
	 *
	 * <p>Extracting audio from a third-party platform (SoundCloud / Mixcloud / Bandcamp / Twitch /
	 * Kick) and rebroadcasting it to an audience is assessed as high-risk on BOTH terms-of-service
	 * and copyright grounds — see {@code .data/ops/compliance-ops/}. The feature is fully implemented
	 * and supported, but ships inaccessible: turning this on is a deliberate act by the server
	 * operator, and that act is the signal that they intend to use it responsibly and accept the
	 * risk for their own server.</p>
	 *
	 * <p>While {@code false}: the platform source type is not offered in the admin UI, and an existing
	 * platform disc in {@code discs.json} does not resolve or spawn a feed — it fails soft with an
	 * explanatory log line rather than crashing a session. Every other source type (HLS, RTMP, FILE)
	 * is unaffected. Pre-key config files load with the default, so an upgrade disables it.</p>
	 */
	public boolean enablePlatformLinks = false;
	/**
	 * Legacy ring-buffer size in 20 ms frames, kept for back-compat and as a
	 * migration source: the jitter buffer now derives its frame counts from the
	 * {@code *BufferSeconds} keys below. A pre-jitter file that explicitly set
	 * {@code bufferFrames} to a non-default value has it honored as
	 * {@code maxBufferSeconds} when the new keys are absent (see
	 * {@link ConfigNormalizer#resolveBuffering}). Default 600 (~12 s = the default max cap).
	 */
	public int bufferFrames = 600;
	/**
	 * Steady-state playout latency in seconds (jitter-buffer target). Nullable so
	 * a pre-jitter file (key absent) can be told from an explicit value; absent
	 * uses {@link ConfigLimits#DEFAULT_TARGET_BUFFER_SECONDS}. Clamped
	 * {@value ConfigLimits#MIN_TARGET_BUFFER_SECONDS}..{@value ConfigLimits#MAX_TARGET_BUFFER_SECONDS} on load.
	 */
	public Double targetBufferSeconds;
	/**
	 * Hard cap on buffered audio in seconds (memory bound; absorbs an upstream
	 * reconnect flood). Nullable; absent uses {@link ConfigLimits#DEFAULT_MAX_BUFFER_SECONDS}
	 * (or a migrated {@code bufferFrames}). Clamped {@code targetBufferSeconds}..{@value
	 * #MAX_MAX_BUFFER_SECONDS}.
	 *
	 * <p>Keep this ABOVE the target with headroom (aim for ~2x
	 * {@code targetBufferSeconds}): the high-water hysteresis trim doubles as the
	 * sliding-window lag cap, and it only has a deadband to work in when the cap sits
	 * well above the target. Raising {@code targetBufferSeconds} toward this value
	 * collapses that deadband and defeats the lag cap, so raise this key
	 * proportionally whenever you raise the target - a too-tight cap is warned once on
	 * load (sow-005 Phase 4).</p>
	 */
	public Double maxBufferSeconds;
	/**
	 * Fill (seconds) required before (re)starting playout after a start or an
	 * underrun. Nullable; absent uses {@code targetBufferSeconds}. Clamped
	 * 0.2..{@code maxBufferSeconds}.
	 *
	 * <p>The preroll IS the steady-state playout depth: once playing, the delivery
	 * loop drains exactly one frame per input frame, so a real-time upstream holds
	 * the buffer at whatever depth it pre-rolled to, and it can only rise above
	 * that on an upstream burst (never on its own, since we never resample). The
	 * live-edge trim only walks a burst-inflated depth back DOWN toward the target;
	 * nothing walks it up from a lower preroll. So the default must equal the target
	 * (not a smaller cap), or the cold-start and post-underrun resting depth would
	 * sit below {@code targetBufferSeconds} and underrun on the very first upstream
	 * gap the target was sized to bridge.</p>
	 */
	public Double prerollSeconds;
	/** Reconnect backoff cap: 2,4,8,... seconds up to this value. */
	public int reconnectBackoffMaxSeconds = 60;

	/**
	 * How many HLS segments behind the live edge ffmpeg starts on connect
	 * ({@code -live_start_index -<N>}, sow-005 Phase A). A positive value grabs the
	 * trunk's retained backlog on connect so the deep jitter buffer fills fast, matching
	 * how every healthy trunk consumer (browser/Discord/Kick/YT) buffers BEHIND the live
	 * edge and rides the permanent ~2.6% source deficit gaplessly. Default
	 * {@value ConfigLimits#DEFAULT_HLS_START_BEHIND_SEGMENTS} (~30&nbsp;s at 2&nbsp;s segments, a
	 * moderate profile); {@code 0} keeps ffmpeg's default live-edge start. Clamped
	 * {@code 0..}{@value ConfigLimits#MAX_HLS_START_BEHIND_SEGMENTS} on load (the retained window).
	 * HLS only - RTMP receive is a real-time push with no seekable backlog and ignores
	 * this. Pre-key files load with the default.
	 *
	 * <p><b>Recommended DEEP-BUFFER profile for the SavePoint trunk</b> (absorbs the
	 * source deficit gaplessly at the cost of latency; the rare rebuffer becomes a clean
	 * declared "buffering" pause): {@code targetBufferSeconds} ~30, {@code maxBufferSeconds}
	 * ~60, {@code prerollSeconds} ~30, {@code hlsStartBehindSegments} 15. The max cap is
	 * ~2x the target ({@link ConfigLimits#LAG_CAP_HEADROOM_RECOMMENDED_MULTIPLIER}) so the high-water
	 * hysteresis trim keeps its full ~1x-target deadband; a tighter ~45 cap would pin
	 * high-water to the cap and halve that deadband (the trim still functions, but the
	 * lag-cap headroom shrinks). Do NOT lower these as global defaults for other
	 * deployments - set them per profile.</p>
	 */
	public int hlsStartBehindSegments = DEFAULT_HLS_START_BEHIND_SEGMENTS;

	/**
	 * Whether an ffmpeg respawn REUSES the jitter buffer instead of flushing +
	 * re-prerolling from empty (sow-005 Phase 4, the dev's D-061 win). Default
	 * {@code true}: on reconnect the retained frames keep feeding playout while the
	 * new ffmpeg process refills the buffer in the background, so a gap shorter than
	 * the buffered depth (e.g. SavePoint's ~2&nbsp;s RTMP publisher restart) is
	 * covered silently with no per-reconnect blip; a genuinely empty/below-cushion
	 * buffer (cold start or a long outage) still re-prerolls naturally on the
	 * delivery-side underrun. This default REPRODUCES the pre-Phase-4 behaviour, where a
	 * respawn never cleared the buffer. Set {@code false} to opt into a NEW
	 * flush-and-re-preroll mode: flush the buffer on every respawn so playout always
	 * re-prerolls from empty (this is not a restoration of any prior behaviour - the
	 * pre-Phase-4 code never flushed on respawn). Pre-key files load with the default
	 * (Gson leaves the absent field {@code true}).
	 */
	public boolean reconnectPreserveBuffer = DEFAULT_RECONNECT_PRESERVE_BUFFER;
	/**
	 * Low-watermark (seconds of buffered audio) for the sustained-drain re-anchor
	 * (sow-005 Phase 4). When the buffer stays below this depth continuously for
	 * {@link #reanchorSustainSeconds}, the delivery loop performs ONE controlled
	 * re-anchor - a single clean playout pause that rebuilds the target cushion -
	 * instead of a cluster of ragged micro-underruns as the source's ~3% deficit
	 * drains the buffer. Nullable; absent uses {@value ConfigLimits#DEFAULT_REANCHOR_LOW_WATERMARK_SECONDS}.
	 * Clamped {@value ConfigLimits#MIN_REANCHOR_LOW_WATERMARK_SECONDS}..{@code targetBufferSeconds}
	 * (and forced strictly below BOTH the target AND the preroll depth in frames) so it
	 * only trips on a genuine drain (never on ordinary jitter around target) and a
	 * re-anchor - which rebuilds the cushion to the preroll depth - always lands above
	 * its own arming threshold and fires once per drain episode.
	 */
	public Double reanchorLowWatermarkSeconds;
	/**
	 * How long (seconds) the buffer must stay below {@link #reanchorLowWatermarkSeconds}
	 * before ONE controlled re-anchor fires (sow-005 Phase 4). A sustain window (not a
	 * one-off dip) is what distinguishes the slow 3%-deficit drain from momentary
	 * jitter. Nullable; absent uses {@value ConfigLimits#DEFAULT_REANCHOR_SUSTAIN_SECONDS}. A value
	 * {@code <= 0} DISABLES the re-anchor entirely (playout then only re-prerolls on a
	 * true underrun, the pre-Phase-4 behaviour); a positive value is clamped
	 * {@value ConfigLimits#MIN_REANCHOR_SUSTAIN_SECONDS}..{@value ConfigLimits#MAX_REANCHOR_SUSTAIN_SECONDS}.
	 */
	public Double reanchorSustainSeconds;

	/**
	 * Smoothing mode for the delivery loop (sow-005 Phase B). {@code "tempo_match"}
	 * (default) inserts a pitch-preserving WSOLA time-stretch stage between the
	 * jitter buffer and the sinks: it stretches the content by a slowly-adapted
	 * factor (driven by an occupancy servo toward {@code targetBufferSeconds}) so
	 * content consumed per wall-second matches content produced, holding buffer
	 * occupancy CONSTANT against the trunk's permanent ~2.6% deficit - no rebuffer
	 * pauses, fixed latency, pitch preserved (overlap-add, never resample).
	 * {@code "rebuffer"} disables the stretch and keeps the exact Phase-A passthrough
	 * (drain one frame per 20&nbsp;ms, silence + re-preroll on drain) - the safe
	 * rollback with zero residual effect. Unknown/blank values load as the default
	 * (warned once). Pre-key files load with the default.
	 */
	public String smoothingMode = DEFAULT_SMOOTHING_MODE;
	/**
	 * Maximum time-stretch magnitude in percent for {@link #smoothingMode}
	 * {@code "tempo_match"} (sow-005 Phase B). The occupancy servo's stretch factor
	 * is hard-clamped to {@code 1 +/- maxStretchPercent/100} (output/input), so at
	 * the default {@value ConfigLimits#DEFAULT_MAX_STRETCH_PERCENT}% the factor stays in
	 * {@code 0.97..1.03}. A &lt;=3% stretch is inaudible for music with proper
	 * overlap-add. Nullable; absent uses {@value ConfigLimits#DEFAULT_MAX_STRETCH_PERCENT}.
	 * Clamped {@code 0..}{@value ConfigLimits#MAX_MAX_STRETCH_PERCENT} on load; {@code 0}
	 * effectively disables the stretch (factor pinned to 1.0). Ignored in
	 * {@code "rebuffer"} mode. Pre-key files load with the default.
	 */
	public Double maxStretchPercent;

	// --- Derived jitter-buffer frame counts (not serialized). Populated by
	// resolveBuffering() on every load; safe defaults until then. ---
	transient int maxBufferFrames = (int) Math.round(DEFAULT_MAX_BUFFER_SECONDS * FRAMES_PER_SECOND);
	transient int targetFrames = (int) Math.round(DEFAULT_TARGET_BUFFER_SECONDS * FRAMES_PER_SECOND);
	transient int prerollFrames =
			(int) Math.round(DEFAULT_TARGET_BUFFER_SECONDS * FRAMES_PER_SECOND);
	transient int reanchorLowWaterFrames =
			(int) Math.round(DEFAULT_REANCHOR_LOW_WATERMARK_SECONDS * FRAMES_PER_SECOND);
	transient int reanchorSustainFrames =
			(int) Math.round(DEFAULT_REANCHOR_SUSTAIN_SECONDS * FRAMES_PER_SECOND);
	/** Resolved max-stretch magnitude (percent), clamped 0..6 on load. */
	transient double resolvedMaxStretchPercent = DEFAULT_MAX_STRETCH_PERCENT;

	/**
	 * HLS trunk URL shape for admin discs (see {@code StationEndpoints}). While
	 * {@code true} (default for now) the mod builds the raw
	 * {@code /radio/broadcast/<id>/trunk.m3u8} path, which is the only one live
	 * today; flip to {@code false} once Direct Stream Access
	 * ({@code /api/public/stations/<id>/stream/trunk.m3u8}) is deployed.
	 */
	public boolean useRawBroadcastPath = true;
	/**
	 * Public host (bare {@code host} or {@code host:port} authority, no scheme,
	 * no path) an external DJ/OBS uses to reach an RTMP-receive disc that binds
	 * {@code 0.0.0.0}. Optional and {@code null} by default: the {@code /jockey}
	 * disc detail page always shows the local {@code rtmp://127.0.0.1:<port>/live}
	 * ingest, and additionally an external {@code rtmp://<rtmpPublicHost>/live}
	 * form only when this is set and the disc binds all interfaces. A value with
	 * a scheme or path is rejected on load (nulled with a warning) - it is a bare
	 * authority the mod prefixes with {@code rtmp://}.
	 */
	public String rtmpPublicHost = null;
	/**
	 * Op permission level the {@code /jockey} admin tree requires (0..4;
	 * default 3 = {@code Commands.LEVEL_ADMINS}). Clamped into range on load.
	 */
	public int adminPermissionLevel = 3;
	/**
	 * Poll interval (seconds) for the now-playing LIVE / track indicator. Default
	 * {@code 15}; set to {@code 0} to disable it entirely - no poller thread starts
	 * and nothing is logged. When positive it is clamped to {@code 5..3600}; the
	 * {@code NowPlayingPoller} then GETs the now-playing endpoint of each active disc
	 * that resolves one (derived from the disc's stream URL, or an explicit
	 * now-playing URL) on a daemon thread and records the live flag, broadcast source
	 * and current track title/artist/segment on the session. Discs with no derivable
	 * endpoint (generic non-SavePoint streams) make no outbound request, so enabling
	 * this is safe by default. Nothing in the audio path depends on it.
	 */
	public int nowPlayingPollSeconds = 15;
	/**
	 * How long (seconds) the {@code /jockey} "Test" audible monitor plays a
	 * disc's feed to the admin before it auto-stops. Default 90; clamped 10..600
	 * on load. Bounds the transient feed lease and boss bar a monitor holds so a
	 * forgotten monitor never streams indefinitely.
	 */
	public int monitorSeconds = 90;
	/**
	 * Whether the server shows an on-screen NOW-PLAYING boss bar to players
	 * standing within a playing disc's audible radius (a vanilla server→client
	 * boss bar, so every player sees it with no client mod). Default {@code true};
	 * set {@code false} to disable the bar entirely - {@code NowPlayingHud} then
	 * shows nothing and holds no bars. Pre-key files load with the default.
	 */
	public boolean nowPlayingHud = true;
	/**
	 * Gate mode for the now-playing boss bar when {@link #nowPlayingHud} is on:
	 * {@code "beacon"} (default) shows the bar only while a "beacon" trigger block
	 * (any lantern - lantern/soul/copper variants -, sea lantern, lava, redstone
	 * block, or redstone torch) sits within {@link #nowPlayingHudTriggerRadius} of
	 * the jukebox (default 4 = a 9x9x9 cube), so a station only announces itself
	 * when the owner places one nearby; {@code "always"} shows the bar for every playing disc in
	 * range (the pre-mode behaviour). {@link #nowPlayingHud} {@code false} still
	 * suppresses all bars regardless of this value. Unknown/blank values load as
	 * {@code "beacon"} (warned once). Pre-key files load with the default.
	 */
	public String nowPlayingHudMode = "beacon";
	/**
	 * Cube "radius" (in blocks, per axis) the {@code "beacon"} gate scans around a
	 * jukebox for a trigger block (lantern of any kind, sea lantern, lava, redstone
	 * block, or redstone torch). Default {@code 4} = a 9x9x9 cube; clamped to
	 * {@code 1..8} on load with a warning. Ignored unless {@link #nowPlayingHudMode}
	 * is {@code "beacon"}. Pre-key files load with the default.
	 */
	public int nowPlayingHudTriggerRadius = 4;
	/**
	 * Whether the server mutes each vanilla client's own background game music
	 * while the player stands within a playing SavePoint disc's audible radius, so
	 * Minecraft's own music never fights the streamed radio. Implemented purely
	 * server-side: {@code MusicSuppressor} periodically sends each in-range player a
	 * {@code ClientboundStopSoundPacket} for the {@code MUSIC} sound category, which
	 * works on a 100% vanilla client with no client mod. By the owner's
	 * no-client-mod choice this is a HARD on/off (near-radio game music simply off,
	 * resuming naturally once the player leaves the radius), NOT a smooth fade/duck.
	 * Default {@code true}; set {@code false} to leave game music untouched -
	 * {@code MusicSuppressor} then does nothing. Pre-key files load with the default.
	 */
	public boolean suppressGameMusic = true;
	/**
	 * Diagnostics: when {@code true}, every feed appends a per-interval CSV row of
	 * telemetry (buffer occupancy, playout/connection state, WSOLA stretch, and
	 * delta counters for delivered/silence/underruns/drops/re-anchors/reconnects)
	 * to {@link #feedDiagnosticsFile} for offline broadcast-quality analysis.
	 * Off by default (writes nothing); a testing aid, not a production feature.
	 * Handled by {@code FeedDiagnostics} on a dedicated IO thread, so it never adds
	 * jitter to the delivery loop. Pre-key files load with the default.
	 */
	public boolean feedDiagnostics = false;
	/**
	 * Whether each active feed writes its cumulative health snapshot to the server
	 * log every 30 seconds. Off by default so production servers do not receive a
	 * continuous INFO stream; enable temporarily when diagnosing a feed. This is
	 * independent of {@link #feedDiagnostics}, which controls the CSV recorder.
	 * Warnings and errors remain enabled regardless.
	 */
	public boolean feedStatsLogging = false;
	/**
	 * RTMP-receive idle keep-alive (sow-008). When {@code true} (default), every
	 * RTMP-receive disc's ffmpeg listener is held bound and consuming 24/7 via a
	 * permanent feed anchor - independent of players/sessions - so a station's push
	 * endpoint never disappears on an idle server (which would read as "the RTMP
	 * connection died"). Set {@code false} to revert to on-demand listeners (up only
	 * while a jukebox session is active). HLS-pull discs are unaffected (pull-based).
	 */
	public boolean rtmpKeepAlive = true;
	/**
	 * sow-009: TCP port for the internal loopback audio server that serves files from
	 * {@code config/jockey_radio/audio/} to {@code FILE} discs over HTTP (so ffmpeg
	 * never needs {@code file:}). Bound to {@code 127.0.0.1} only. Default 24462;
	 * {@code 0} disables the server (direct-HTTP-link FILE discs still work). If the port
	 * is busy the server logs and disables itself. Pre-key files load with the default.
	 */
	public int localAudioServerPort = 24462;
	/**
	 * Diagnostics sample interval in seconds. Clamped 1..60; the effective
	 * granularity is ~1s because the dump piggybacks the once-per-second occupancy
	 * sampler. Only meaningful when {@link #feedDiagnostics} is on.
	 */
	public double feedDiagnosticsIntervalSeconds = 1.0;
	/**
	 * Diagnostics output file, relative to the server working directory or an
	 * absolute path. {@code null} (default) uses {@code diagnostics/feed-diagnostics.csv}.
	 * TRUNCATED on each server start so a run's data stands alone. Only meaningful
	 * when {@link #feedDiagnostics} is on.
	 */
	public String feedDiagnosticsFile = null;
	/**
	 * Whether OUR radio SVC channels encode with the mod's own high-bitrate Opus
	 * encoder ({@code HqOpusEncoder}, bundled Concentus) instead of SVC's
	 * voice-tuned {@code api.createEncoder(AUDIO)}. Default {@code true}: the radio
	 * then streams music-grade audio at {@link #opusBitrate}. Set {@code false} to
	 * use the SVC encoder exactly as before (zero behavior change). This ONLY ever
	 * affects channels the mod itself creates - never player-to-player voice or any
	 * other SVC channel. If the high-quality encoder fails to initialise at runtime
	 * the sink falls back to the SVC encoder automatically (logged once). Pre-key
	 * files load with the default.
	 */
	public boolean opusHighQuality = true;
	/**
	 * Target bitrate (bits/s) for {@link #opusHighQuality}. Default
	 * {@value ConfigLimits#DEFAULT_OPUS_BITRATE} (128 kbit/s mono ≈ 320 bytes/20 ms frame).
	 * Clamped to {@value ConfigLimits#MIN_OPUS_BITRATE}..{@value ConfigLimits#MAX_OPUS_BITRATE} on load with a
	 * warning. The upper bound is the encoder's own ceiling (Concentus caps at 300 kbit/s
	 * mono); it also stays well under the ~1275-byte SVC packet MTU, since a 20 ms frame
	 * is {@code bitrate/50/8} bytes. Ignored when {@link #opusHighQuality} is
	 * {@code false}. Pre-key files load with the default.
	 */
	public int opusBitrate = DEFAULT_OPUS_BITRATE;
	/**
	 * Opus encode complexity (0..10) for {@link #opusHighQuality}. Default
	 * {@value ConfigLimits#DEFAULT_OPUS_COMPLEXITY}. This is the encode-latency/quality lever:
	 * the bundled pure-Java Concentus encoder at max complexity can occasionally
	 * exceed the 20 ms per-frame budget on a slow host, which the paced delivery
	 * thread hears as a periodic late-frame-then-burst hitch; a lower complexity
	 * keeps encode time under budget (VBR still preserves music quality). Clamped
	 * to {@value ConfigLimits#MIN_OPUS_COMPLEXITY}..{@value ConfigLimits#MAX_OPUS_COMPLEXITY} on load with a
	 * warning. Ignored when {@link #opusHighQuality} is {@code false}. Pre-key files
	 * load with the default.
	 */
	public int opusComplexity = DEFAULT_OPUS_COMPLEXITY;
	/**
	 * Opus in-band FEC expected packet-loss percent (0..100) for {@link #opusHighQuality}.
	 * Default {@value ConfigLimits#DEFAULT_OPUS_FEC_PACKET_LOSS} = <b>FEC OFF</b>. In-band FEC lets the
	 * SVC client reconstruct an isolated lost packet from redundancy in the next one
	 * (SVC's decoder invokes {@code decode_fec} when it detects a gap), which reduces
	 * loss-related music dropouts for <b>remote players over the internet</b>.
	 *
	 * <p><b>Trade-off (why it is off by default):</b> Opus only carries FEC in SILK/hybrid
	 * mode, so any value &gt; 0 forces the encoder out of pristine <i>CELT-only</i> fullband
	 * music (TOC config 31) into <i>hybrid</i> (config 15, SILK on the low band) - a small
	 * music-fidelity change for loss robustness. On a LAN / co-located / low-loss path it is
	 * pure downside (quality change, no loss to recover), so leave it 0. Raise it (e.g. 10-20)
	 * only when serving remote players who report loss-related glitches. It also adds a little
	 * redundancy bitrate. Clamped {@value ConfigLimits#MIN_OPUS_FEC_PACKET_LOSS}..{@value ConfigLimits#MAX_OPUS_FEC_PACKET_LOSS}
	 * on load. Ignored when {@link #opusHighQuality} is {@code false}. Pre-key files load with the default.
	 */
	public int opusFecPacketLossPercent = DEFAULT_OPUS_FEC_PACKET_LOSS;

	/** Hard cap on buffered frames (drop-oldest overflow bound). */
	public int maxBufferFrames() {
		return maxBufferFrames;
	}

	/** Steady-state playout depth the live-edge trim walks back toward. */
	public int targetFrames() {
		return targetFrames;
	}

	/** Fill required before (re)starting playout after start/underrun. */
	public int prerollFrames() {
		return prerollFrames;
	}

	/**
	 * Buffered depth (frames) below which the sustained-drain re-anchor arms; forced
	 * strictly below both {@link #targetFrames()} (so ordinary jitter around target never
	 * arms it) and {@link #prerollFrames()} (so a re-anchor always rebuilds to a depth
	 * above its own arming threshold and fires once per drain episode) (sow-005 Phase 4).
	 */
	public int reanchorLowWaterFrames() {
		return reanchorLowWaterFrames;
	}

	/**
	 * Consecutive below-low-water delivery ticks (20&nbsp;ms each) required before ONE
	 * controlled re-anchor fires, or {@code 0} when the re-anchor is disabled
	 * (sow-005 Phase 4).
	 */
	public int reanchorSustainFrames() {
		return reanchorSustainFrames;
	}

	/**
	 * Normalized smoothing mode ({@code "tempo_match"} or {@code "rebuffer"}),
	 * resolved on load (sow-005 Phase B).
	 */
	public String smoothingMode() {
		return smoothingMode;
	}

	/**
	 * Max time-stretch magnitude in percent for TEMPO_MATCH, clamped 0..6 on load
	 * (sow-005 Phase B). The feed hard-clamps the servo's factor to
	 * {@code 1 +/- this/100}.
	 */
	public double maxStretchPercent() {
		return resolvedMaxStretchPercent;
	}

	/** A single configured radio station. */
	public static final class Station {
		public String displayName;
		public String url;
		public String token;
		public int radiusBlocks = 32;

		public Station() {
		}

		public Station(String displayName, String url, String token, int radiusBlocks) {
			this.displayName = displayName;
			this.url = url;
			this.token = token;
			this.radiusBlocks = radiusBlocks;
		}
	}

	/** Returns the loaded config, loading it first if necessary. */
	public static RadioConfig get() {
		if (instance == null) {
			load();
		}
		return instance;
	}

	/**
	 * Loads the config from disk, writing the default file if missing.
	 * A malformed file is logged and replaced (in memory only) by defaults so
	 * the server keeps running; the broken file on disk is left untouched.
	 */
	public static RadioConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);

		if (Files.notExists(path)) {
			RadioConfig defaults = createDefault();
			try {
				// Write to a sibling temp file, then move into place, so a crash
				// or full disk mid-write can never leave a truncated config behind.
				Files.createDirectories(path.getParent());
				Path tmp = path.resolveSibling(FILE_NAME + ".tmp");
				Files.writeString(tmp, GSON.toJson(defaults));
				try {
					Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				} catch (AtomicMoveNotSupportedException e) {
					Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
				}
				JockeyRadioMod.LOGGER.info("Wrote default config to {}", path);
			} catch (IOException e) {
				JockeyRadioMod.LOGGER.error("Failed to write default config to {}", path, e);
			}
			instance = defaults;
			// First-run install of the jar-bundled binaries so a zero-touch drop-in works.
			BundledBinaries.installAndResolve(instance, path.getParent());
			return instance;
		}

		try (Reader reader = Files.newBufferedReader(path)) {
			RadioConfig loaded = GSON.fromJson(reader, RadioConfig.class);
			if (loaded == null) {
				throw new IllegalStateException("config file is empty");
			}
			if (loaded.stations == null) {
				loaded.stations = new LinkedHashMap<>();
			}
			if (loaded.defaultStation == null && !loaded.stations.isEmpty()) {
				loaded.defaultStation = loaded.stations.keySet().iterator().next();
			}
			ConfigNormalizer.normalizeIngestSettings(loaded);
			// Drop invalid entries entirely so downstream code (commands,
			// tab completion, disc creation) never sees them.
			loaded.stations.entrySet().removeIf(entry -> {
				String key = entry.getKey();
				Station station = entry.getValue();
				if (key == null || !VALID_KEY.matcher(key).matches()) {
					JockeyRadioMod.LOGGER.warn(
							"Ignoring station '{}' in {}: keys must match [A-Za-z0-9_.+-]+ to be usable in /jockey",
							key, FILE_NAME);
					return true;
				}
				if (station == null) {
					JockeyRadioMod.LOGGER.warn("Ignoring station '{}' in {}: entry is null", key, FILE_NAME);
					return true;
				}
				if (station.url == null || station.url.isBlank()) {
					JockeyRadioMod.LOGGER.warn("Ignoring station '{}' in {}: no url configured", key, FILE_NAME);
					return true;
				}
				return false;
			});
			instance = loaded;
			JockeyRadioMod.LOGGER.info("Loaded {} station(s) from {}", loaded.stations.size(), path);
		} catch (Exception e) {
			JockeyRadioMod.LOGGER.error(
					"Malformed config at {}; using built-in defaults (file left untouched)", path, e);
			instance = createDefault();
		}
		// Install/resolve the jar-bundled binaries and point ffmpegBinary/ytdlpBinary at them
		// when left at their defaults (in-memory only, so the on-disk file stays portable).
		BundledBinaries.installAndResolve(instance, path.getParent());
		return instance;
	}

	/** Package-private so the default-file contract can be pinned by unit tests. */
	static RadioConfig createDefault() {
		RadioConfig config = new RadioConfig();
		// Jockey Radio is standalone: a fresh config ships with NO stations, so nothing
		// implies an external broadcasting service is required. Operators add their own config
		// stations (SavePoint or any m3u8) or, more commonly, create discs via /jockey. See
		// docs for an example SavePoint station block.
		// Write the jitter-buffer keys explicitly so a freshly generated file shows
		// them (and so a reload sees them as present, not migrated defaults).
		config.targetBufferSeconds = DEFAULT_TARGET_BUFFER_SECONDS;
		config.maxBufferSeconds = DEFAULT_MAX_BUFFER_SECONDS;
		config.prerollSeconds = DEFAULT_TARGET_BUFFER_SECONDS;
		config.hlsStartBehindSegments = DEFAULT_HLS_START_BEHIND_SEGMENTS;
		config.reconnectPreserveBuffer = DEFAULT_RECONNECT_PRESERVE_BUFFER;
		config.reanchorLowWatermarkSeconds = DEFAULT_REANCHOR_LOW_WATERMARK_SECONDS;
		config.reanchorSustainSeconds = DEFAULT_REANCHOR_SUSTAIN_SECONDS;
		config.smoothingMode = DEFAULT_SMOOTHING_MODE;
		config.maxStretchPercent = DEFAULT_MAX_STRETCH_PERCENT;
		config.resolvedMaxStretchPercent = DEFAULT_MAX_STRETCH_PERCENT;
		ConfigNormalizer.resolveBuffering(config);
		return config;
	}

	private RadioConfig() {
	}
}
