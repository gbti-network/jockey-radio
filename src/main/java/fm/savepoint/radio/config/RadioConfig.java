package fm.savepoint.radio.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import fm.savepoint.radio.SavePointRadioMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Gson-backed configuration stored at {@code <configDir>/savepoint_radio.json}.
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
 */
public final class RadioConfig {

	private static final String FILE_NAME = "savepoint_radio.json";

	/** Frames per second at 48 kHz mono in 960-sample (20 ms) frames. */
	private static final int FRAMES_PER_SECOND = 50;
	private static final double DEFAULT_TARGET_BUFFER_SECONDS = 3.0;
	private static final double DEFAULT_MAX_BUFFER_SECONDS = 12.0;
	private static final double MIN_TARGET_BUFFER_SECONDS = 0.5;
	/**
	 * sow-005 Phase A: the target ceiling is raised to 90&nbsp;s so an operator can run
	 * the DEEP-BUFFER profile (start behind the live edge + a deep target so the
	 * ~2.6% source deficit is absorbed gaplessly). The shipped default stays 3&nbsp;s;
	 * this only widens the allowed range. Dev guidance: no benefit past ~90&nbsp;s depth.
	 */
	private static final double MAX_TARGET_BUFFER_SECONDS = 90.0;
	/** Raised to 120&nbsp;s (sow-005 Phase A) so the max cap can keep ~2x headroom over a deep 90 s target. */
	private static final double MAX_MAX_BUFFER_SECONDS = 120.0;
	private static final double MIN_PREROLL_SECONDS = 0.2;
	/**
	 * sow-005 Phase 4: the hysteresis high-water trim (engage point ~2x target in
	 * {@code AbstractFfmpegFeed}) doubles as the sliding-window LAG CAP - it drains a
	 * burst-inflated buffer back to the target once occupancy creeps to high-water. That
	 * deadband only exists when the hard cap sits well above the target. Raising
	 * {@code targetBufferSeconds} toward {@code maxBufferSeconds} (the SOW's deeper-target
	 * guidance) collapses it: high-water pins to {@code maxBufferFrames} and the trim
	 * excess shrinks to ~0, so latency can sit at the full max with no bounded catch-up.
	 * We warn once when the cap gives less than this multiple of target of headroom so the
	 * operator raises {@code maxBufferSeconds} alongside the target (the recommendation is
	 * ~2x, matching the trim's engage point).
	 */
	private static final double LAG_CAP_HEADROOM_WARN_MULTIPLIER = 1.5;
	private static final double LAG_CAP_HEADROOM_RECOMMENDED_MULTIPLIER = 2.0;
	/** The pre-jitter default ring size; an explicit non-600 value migrates. */
	private static final int LEGACY_DEFAULT_BUFFER_FRAMES = 600;

	// --- sow-005 Phase A: HLS start-behind (deep-buffer fast fill) ---
	/**
	 * Default HLS start-behind depth in segments. {@code 15} = ~30&nbsp;s at 2&nbsp;s
	 * segments - a MODERATE default (WSOLA in a later phase will remove the drain and
	 * let latency drop). {@code 0} keeps ffmpeg's default live-edge start.
	 */
	private static final int DEFAULT_HLS_START_BEHIND_SEGMENTS = 15;
	/**
	 * Max HLS start-behind depth. The SavePoint trunk retains 120&nbsp;s (60x2&nbsp;s)
	 * with segment deletion on. Parking {@code -live_start_index -N} puts ffmpeg's start
	 * segment {@code 2N} seconds behind the live edge, so it falls out of the retained
	 * window in {@code 120 - 2N} seconds; requiring the SOW's &gt;=30&nbsp;s deletion
	 * margin gives {@code 120 - 2N >= 30}, i.e. {@code N <= 45}. A higher ceiling (the
	 * former 55, ~10&nbsp;s margin) let a small connect delay push the requested start
	 * segment out of the window -&gt; HTTP 404 at connect, the exact failure this clamp
	 * exists to prevent. Default {@value #DEFAULT_HLS_START_BEHIND_SEGMENTS} is well
	 * inside this.
	 */
	private static final int MAX_HLS_START_BEHIND_SEGMENTS = 45;

	// --- sow-005 Phase 4: buffer-preserving reconnect + sustained-drain re-anchor ---
	/** Reuse the jitter buffer across ffmpeg respawns by default (the D-061 win). */
	private static final boolean DEFAULT_RECONNECT_PRESERVE_BUFFER = true;
	/**
	 * Default re-anchor low watermark (seconds of buffered audio). Sits below the
	 * default target so it only trips on a genuine sustained drain, not jitter.
	 */
	private static final double DEFAULT_REANCHOR_LOW_WATERMARK_SECONDS = 1.0;
	private static final double MIN_REANCHOR_LOW_WATERMARK_SECONDS = 0.2;
	/** Default sustained-drain window before ONE controlled re-anchor fires. */
	private static final double DEFAULT_REANCHOR_SUSTAIN_SECONDS = 8.0;
	private static final double MIN_REANCHOR_SUSTAIN_SECONDS = 1.0;
	private static final double MAX_REANCHOR_SUSTAIN_SECONDS = 120.0;

	// --- sow-005 Phase B: pitch-preserving time-stretch (TEMPO_MATCH) ---
	/**
	 * Default smoothing mode. {@code "tempo_match"} runs the WSOLA time-stretch
	 * stage so a deep buffer against the permanent ~2.6% source deficit never drains
	 * (zero rebuffer pauses at constant latency); {@code "rebuffer"} disables the
	 * stretch entirely and keeps the Phase-A passthrough (the safe rollback).
	 */
	private static final String DEFAULT_SMOOTHING_MODE = "tempo_match";
	/** Default max time-stretch magnitude (percent); a &lt;=3% stretch is inaudible for music. */
	private static final double DEFAULT_MAX_STRETCH_PERCENT = 3.0;
	/** Hard ceiling on the configurable stretch magnitude (percent). */
	private static final double MAX_MAX_STRETCH_PERCENT = 6.0;

	/** Default radio Opus bitrate (bits/s): music-grade 128 kbit/s mono. */
	private static final int DEFAULT_OPUS_BITRATE = 128_000;
	/** Lower bound for a sane music bitrate. */
	private static final int MIN_OPUS_BITRATE = 24_000;
	/**
	 * Upper bound, set by what the ENCODER will actually honour. Concentus hard-caps
	 * {@code setBitrate} at 300 kbit/s mono, so a higher value was accepted here, passed
	 * on, and silently reduced - the knob did nothing above 300 kbit/s and no log line
	 * said so. Clamping at the real ceiling makes the accepted range truthful. (The
	 * MTU-derived ceiling is higher, ~510 kbit/s, but it is never the binding one.)
	 */
	private static final int MAX_OPUS_BITRATE = 300_000;

	/**
	 * Default Opus encode complexity: mid-scale, so the pure-Java encoder stays
	 * well under the 20 ms per-frame budget on a slow host while VBR preserves
	 * music quality. 0 = fastest/lowest, 10 = slowest/highest.
	 */
	private static final int DEFAULT_OPUS_COMPLEXITY = 5;
	/** Lowest Opus complexity (fastest encode). */
	private static final int MIN_OPUS_COMPLEXITY = 0;
	/** Highest Opus complexity (slowest encode). */
	private static final int MAX_OPUS_COMPLEXITY = 10;

	/** Default Opus FEC expected packet-loss percent. 0 = FEC OFF (pristine CELT-only music). */
	private static final int DEFAULT_OPUS_FEC_PACKET_LOSS = 0;
	private static final int MIN_OPUS_FEC_PACKET_LOSS = 0;
	private static final int MAX_OPUS_FEC_PACKET_LOSS = 100;

	/**
	 * Station keys must stay parseable by Brigadier's
	 * {@code StringArgumentType.word()} ({@code /radiodisc give <station>}),
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
	public String defaultStation = "pulsefm";

	/** Feed implementation: {@code "ffmpeg"} (default) or {@code "stub"}. */
	public String ingest = "ffmpeg";
	/** ffmpeg executable: bare name resolved via PATH, or an absolute path. */
	public String ffmpegBinary = "ffmpeg";
	/**
	 * Whether to install the ffmpeg/yt-dlp binaries bundled in the jar on first run and use
	 * them automatically (<b>default {@code true}</b>). See {@link BundledBinaries}: on
	 * linux/amd64 the binaries are extracted to {@code <configDir>/savepoint_radio/bin/},
	 * chmod +x, and used ONLY while {@link #ffmpegBinary}/{@link #ytdlpBinary} are their default
	 * bare names - an explicit path always wins. Set this {@code false} to force the configured
	 * value / PATH even on a bundled build (e.g. to use a system ffmpeg you trust).
	 */
	public boolean useBundledBinaries = true;
	/**
	 * sow-007: yt-dlp executable for PLATFORM discs (bare name via PATH, or an absolute
	 * path). Resolves a YouTube/SoundCloud/Mixcloud/Twitch/Kick page URL to an audio
	 * stream. Only consulted by PLATFORM discs; if it is missing/outdated those discs
	 * log a resolve failure and retry, while every other disc is unaffected. Keep it
	 * updated - platforms change often. Pre-key files load with the default.
	 */
	public String ytdlpBinary = "yt-dlp";
	/**
	 * sow-013: master gate for PLATFORM (third-party link) discs. <b>Default {@code false}.</b>
	 *
	 * <p>Extracting audio from a third-party platform (YouTube / SoundCloud / Mixcloud / Twitch /
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
	 * {@link #resolveBuffering()}). Default 600 (~12 s = the default max cap).
	 */
	public int bufferFrames = 600;
	/**
	 * Steady-state playout latency in seconds (jitter-buffer target). Nullable so
	 * a pre-jitter file (key absent) can be told from an explicit value; absent
	 * uses {@link #DEFAULT_TARGET_BUFFER_SECONDS}. Clamped
	 * {@value #MIN_TARGET_BUFFER_SECONDS}..{@value #MAX_TARGET_BUFFER_SECONDS} on load.
	 */
	public Double targetBufferSeconds;
	/**
	 * Hard cap on buffered audio in seconds (memory bound; absorbs an upstream
	 * reconnect flood). Nullable; absent uses {@link #DEFAULT_MAX_BUFFER_SECONDS}
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
	 * {@value #DEFAULT_HLS_START_BEHIND_SEGMENTS} (~30&nbsp;s at 2&nbsp;s segments, a
	 * moderate profile); {@code 0} keeps ffmpeg's default live-edge start. Clamped
	 * {@code 0..}{@value #MAX_HLS_START_BEHIND_SEGMENTS} on load (the retained window).
	 * HLS only - RTMP receive is a real-time push with no seekable backlog and ignores
	 * this. Pre-key files load with the default.
	 *
	 * <p><b>Recommended DEEP-BUFFER profile for the SavePoint trunk</b> (absorbs the
	 * source deficit gaplessly at the cost of latency; the rare rebuffer becomes a clean
	 * declared "buffering" pause): {@code targetBufferSeconds} ~30, {@code maxBufferSeconds}
	 * ~60, {@code prerollSeconds} ~30, {@code hlsStartBehindSegments} 15. The max cap is
	 * ~2x the target ({@link #LAG_CAP_HEADROOM_RECOMMENDED_MULTIPLIER}) so the high-water
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
	 * drains the buffer. Nullable; absent uses {@value #DEFAULT_REANCHOR_LOW_WATERMARK_SECONDS}.
	 * Clamped {@value #MIN_REANCHOR_LOW_WATERMARK_SECONDS}..{@code targetBufferSeconds}
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
	 * jitter. Nullable; absent uses {@value #DEFAULT_REANCHOR_SUSTAIN_SECONDS}. A value
	 * {@code <= 0} DISABLES the re-anchor entirely (playout then only re-prerolls on a
	 * true underrun, the pre-Phase-4 behaviour); a positive value is clamped
	 * {@value #MIN_REANCHOR_SUSTAIN_SECONDS}..{@value #MAX_REANCHOR_SUSTAIN_SECONDS}.
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
	 * the default {@value #DEFAULT_MAX_STRETCH_PERCENT}% the factor stays in
	 * {@code 0.97..1.03}. A &lt;=3% stretch is inaudible for music with proper
	 * overlap-add. Nullable; absent uses {@value #DEFAULT_MAX_STRETCH_PERCENT}.
	 * Clamped {@code 0..}{@value #MAX_MAX_STRETCH_PERCENT} on load; {@code 0}
	 * effectively disables the stretch (factor pinned to 1.0). Ignored in
	 * {@code "rebuffer"} mode. Pre-key files load with the default.
	 */
	public Double maxStretchPercent;

	// --- Derived jitter-buffer frame counts (not serialized). Populated by
	// resolveBuffering() on every load; safe defaults until then. ---
	private transient int maxBufferFrames = (int) Math.round(DEFAULT_MAX_BUFFER_SECONDS * FRAMES_PER_SECOND);
	private transient int targetFrames = (int) Math.round(DEFAULT_TARGET_BUFFER_SECONDS * FRAMES_PER_SECOND);
	private transient int prerollFrames =
			(int) Math.round(DEFAULT_TARGET_BUFFER_SECONDS * FRAMES_PER_SECOND);
	private transient int reanchorLowWaterFrames =
			(int) Math.round(DEFAULT_REANCHOR_LOW_WATERMARK_SECONDS * FRAMES_PER_SECOND);
	private transient int reanchorSustainFrames =
			(int) Math.round(DEFAULT_REANCHOR_SUSTAIN_SECONDS * FRAMES_PER_SECOND);
	/** Resolved max-stretch magnitude (percent), clamped 0..6 on load. */
	private transient double resolvedMaxStretchPercent = DEFAULT_MAX_STRETCH_PERCENT;

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
	 * {@code 0.0.0.0}. Optional and {@code null} by default: the {@code /savepoint}
	 * disc detail page always shows the local {@code rtmp://127.0.0.1:<port>/live}
	 * ingest, and additionally an external {@code rtmp://<rtmpPublicHost>/live}
	 * form only when this is set and the disc binds all interfaces. A value with
	 * a scheme or path is rejected on load (nulled with a warning) - it is a bare
	 * authority the mod prefixes with {@code rtmp://}.
	 */
	public String rtmpPublicHost = null;
	/**
	 * Op permission level the {@code /savepoint} admin tree requires (0..4;
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
	 * How long (seconds) the {@code /savepoint} "Test" audible monitor plays a
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
	 * {@code config/savepoint_radio/audio/} to {@code FILE} discs over HTTP (so ffmpeg
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
	 * {@value #DEFAULT_OPUS_BITRATE} (128 kbit/s mono ≈ 320 bytes/20 ms frame).
	 * Clamped to {@value #MIN_OPUS_BITRATE}..{@value #MAX_OPUS_BITRATE} on load with a
	 * warning. The upper bound is the encoder's own ceiling (Concentus caps at 300 kbit/s
	 * mono); it also stays well under the ~1275-byte SVC packet MTU, since a 20 ms frame
	 * is {@code bitrate/50/8} bytes. Ignored when {@link #opusHighQuality} is
	 * {@code false}. Pre-key files load with the default.
	 */
	public int opusBitrate = DEFAULT_OPUS_BITRATE;
	/**
	 * Opus encode complexity (0..10) for {@link #opusHighQuality}. Default
	 * {@value #DEFAULT_OPUS_COMPLEXITY}. This is the encode-latency/quality lever:
	 * the bundled pure-Java Concentus encoder at max complexity can occasionally
	 * exceed the 20 ms per-frame budget on a slow host, which the paced delivery
	 * thread hears as a periodic late-frame-then-burst hitch; a lower complexity
	 * keeps encode time under budget (VBR still preserves music quality). Clamped
	 * to {@value #MIN_OPUS_COMPLEXITY}..{@value #MAX_OPUS_COMPLEXITY} on load with a
	 * warning. Ignored when {@link #opusHighQuality} is {@code false}. Pre-key files
	 * load with the default.
	 */
	public int opusComplexity = DEFAULT_OPUS_COMPLEXITY;
	/**
	 * Opus in-band FEC expected packet-loss percent (0..100) for {@link #opusHighQuality}.
	 * Default {@value #DEFAULT_OPUS_FEC_PACKET_LOSS} = <b>FEC OFF</b>. In-band FEC lets the
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
	 * redundancy bitrate. Clamped {@value #MIN_OPUS_FEC_PACKET_LOSS}..{@value #MAX_OPUS_FEC_PACKET_LOSS}
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
				SavePointRadioMod.LOGGER.info("Wrote default config to {}", path);
			} catch (IOException e) {
				SavePointRadioMod.LOGGER.error("Failed to write default config to {}", path, e);
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
			normalizeIngestSettings(loaded);
			// Drop invalid entries entirely so downstream code (commands,
			// tab completion, disc creation) never sees them.
			loaded.stations.entrySet().removeIf(entry -> {
				String key = entry.getKey();
				Station station = entry.getValue();
				if (key == null || !VALID_KEY.matcher(key).matches()) {
					SavePointRadioMod.LOGGER.warn(
							"Ignoring station '{}' in {}: keys must match [A-Za-z0-9_.+-]+ to be usable in /radiodisc",
							key, FILE_NAME);
					return true;
				}
				if (station == null) {
					SavePointRadioMod.LOGGER.warn("Ignoring station '{}' in {}: entry is null", key, FILE_NAME);
					return true;
				}
				if (station.url == null || station.url.isBlank()) {
					SavePointRadioMod.LOGGER.warn("Ignoring station '{}' in {}: no url configured", key, FILE_NAME);
					return true;
				}
				return false;
			});
			instance = loaded;
			SavePointRadioMod.LOGGER.info("Loaded {} station(s) from {}", loaded.stations.size(), path);
		} catch (Exception e) {
			SavePointRadioMod.LOGGER.error(
					"Malformed config at {} - using built-in defaults (file left untouched)", path, e);
			instance = createDefault();
		}
		// Install/resolve the jar-bundled binaries and point ffmpegBinary/ytdlpBinary at them
		// when left at their defaults (in-memory only, so the on-disk file stays portable).
		BundledBinaries.installAndResolve(instance, path.getParent());
		return instance;
	}

	/**
	 * Clamps the Phase 3 ingest settings to usable values. Files that predate
	 * these keys arrive here with the field defaults (Gson leaves absent
	 * fields untouched), so only explicit bad values are corrected.
	 */
	private static void normalizeIngestSettings(RadioConfig loaded) {
		if (loaded.ingest == null || loaded.ingest.isBlank()) {
			loaded.ingest = "ffmpeg";
		} else {
			loaded.ingest = loaded.ingest.trim().toLowerCase(Locale.ROOT);
			if (!loaded.ingest.equals("ffmpeg") && !loaded.ingest.equals("stub")) {
				SavePointRadioMod.LOGGER.warn(
						"Unknown ingest '{}' in {} (expected \"ffmpeg\" or \"stub\") - using \"ffmpeg\"",
						loaded.ingest, FILE_NAME);
				loaded.ingest = "ffmpeg";
			}
		}
		if (loaded.ffmpegBinary == null || loaded.ffmpegBinary.isBlank()) {
			loaded.ffmpegBinary = "ffmpeg";
		}
		if (loaded.bufferFrames < 5 || loaded.bufferFrames > 3000) {
			SavePointRadioMod.LOGGER.warn(
					"bufferFrames {} in {} is outside 5..3000 - using default 600",
					loaded.bufferFrames, FILE_NAME);
			loaded.bufferFrames = 600;
		}
		if (loaded.reconnectBackoffMaxSeconds < 2 || loaded.reconnectBackoffMaxSeconds > 3600) {
			SavePointRadioMod.LOGGER.warn(
					"reconnectBackoffMaxSeconds {} in {} is outside 2..3600 - using default 60",
					loaded.reconnectBackoffMaxSeconds, FILE_NAME);
			loaded.reconnectBackoffMaxSeconds = 60;
		}
		// HLS start-behind depth: 0 (default live-edge) .. the retained window. Clamp
		// with a warning like the other knobs so a too-deep start never seeks past the
		// trunk's deletion horizon (sow-005 Phase A).
		if (loaded.hlsStartBehindSegments < 0
				|| loaded.hlsStartBehindSegments > MAX_HLS_START_BEHIND_SEGMENTS) {
			int clamped = Math.max(0, Math.min(MAX_HLS_START_BEHIND_SEGMENTS, loaded.hlsStartBehindSegments));
			SavePointRadioMod.LOGGER.warn(
					"hlsStartBehindSegments {} in {} is outside 0..{} - using {}",
					loaded.hlsStartBehindSegments, FILE_NAME, MAX_HLS_START_BEHIND_SEGMENTS, clamped);
			loaded.hlsStartBehindSegments = clamped;
		}
		if (loaded.adminPermissionLevel < 0 || loaded.adminPermissionLevel > 4) {
			SavePointRadioMod.LOGGER.warn(
					"adminPermissionLevel {} in {} is outside 0..4 - using default 3",
					loaded.adminPermissionLevel, FILE_NAME);
			loaded.adminPermissionLevel = 3;
		}
		// 0 or negative = disabled. A POSITIVE out-of-range value is clamped into 5..3600
		// like every neighbouring knob, rather than silently turning the feature off: an
		// operator who set 3 hoping for faster track updates got no poller at all, no
		// "now-playing poller enabled" line, and no track title for the whole run.
		if (loaded.nowPlayingPollSeconds < 0) {
			loaded.nowPlayingPollSeconds = 0;
		} else if (loaded.nowPlayingPollSeconds != 0
				&& (loaded.nowPlayingPollSeconds < 5 || loaded.nowPlayingPollSeconds > 3600)) {
			int clamped = Math.max(5, Math.min(3600, loaded.nowPlayingPollSeconds));
			SavePointRadioMod.LOGGER.warn(
					"nowPlayingPollSeconds {} in {} is outside 5..3600 (0 disables) - using {}",
					loaded.nowPlayingPollSeconds, FILE_NAME, clamped);
			loaded.nowPlayingPollSeconds = clamped;
		}
		if (loaded.monitorSeconds < 10 || loaded.monitorSeconds > 600) {
			SavePointRadioMod.LOGGER.warn(
					"monitorSeconds {} in {} is outside 10..600 - using default 90",
					loaded.monitorSeconds, FILE_NAME);
			loaded.monitorSeconds = 90;
		}
		// Now-playing bar gate mode: "beacon" (default) or "always". Warn once on an
		// unknown/blank value and fall back to "beacon" so the gate stays predictable.
		if (loaded.nowPlayingHudMode == null || loaded.nowPlayingHudMode.isBlank()) {
			loaded.nowPlayingHudMode = "beacon";
		} else {
			loaded.nowPlayingHudMode = loaded.nowPlayingHudMode.trim().toLowerCase(Locale.ROOT);
			if (!loaded.nowPlayingHudMode.equals("beacon") && !loaded.nowPlayingHudMode.equals("always")) {
				SavePointRadioMod.LOGGER.warn(
						"Unknown nowPlayingHudMode '{}' in {} (expected \"beacon\" or \"always\") - using \"beacon\"",
						loaded.nowPlayingHudMode, FILE_NAME);
				loaded.nowPlayingHudMode = "beacon";
			}
		}
		// Beacon-gate scan radius: 1..8 per axis (default 4 = 9x9x9). Clamp with a
		// warning like the other knobs so the gate cost stays bounded.
		if (loaded.nowPlayingHudTriggerRadius < 1 || loaded.nowPlayingHudTriggerRadius > 8) {
			int clamped = Math.max(1, Math.min(8, loaded.nowPlayingHudTriggerRadius));
			SavePointRadioMod.LOGGER.warn(
					"nowPlayingHudTriggerRadius {} in {} is outside 1..8 - using {}",
					loaded.nowPlayingHudTriggerRadius, FILE_NAME, clamped);
			loaded.nowPlayingHudTriggerRadius = clamped;
		}
		// rtmpPublicHost is a bare host[:port] authority the mod prefixes with
		// rtmp://; a scheme or path would produce a malformed ingest URL, so reject
		// (null) such a value with one warning. Blank collapses to null (disabled).
		if (loaded.rtmpPublicHost != null) {
			String host = loaded.rtmpPublicHost.trim();
			if (host.isEmpty()) {
				loaded.rtmpPublicHost = null;
			} else if (host.contains("://") || host.contains("/")) {
				SavePointRadioMod.LOGGER.warn(
						"rtmpPublicHost '{}' in {} must be a bare host[:port] (no scheme, no path) - ignoring",
						host, FILE_NAME);
				loaded.rtmpPublicHost = null;
			} else {
				loaded.rtmpPublicHost = host;
			}
		}
		// High-quality Opus bitrate: clamp to what the encoder will honour, with a warning.
		// The ceiling is Concentus's own 300 kbit/s mono cap, not the MTU - above it the
		// library silently reduced the value, so the knob read as effective when it was not.
		// Only consulted when opusHighQuality is on, but clamp regardless so a later toggle
		// stays safe. Pre-key files arrive with the field default.
		if (loaded.opusBitrate < MIN_OPUS_BITRATE || loaded.opusBitrate > MAX_OPUS_BITRATE) {
			int clamped = Math.max(MIN_OPUS_BITRATE, Math.min(MAX_OPUS_BITRATE, loaded.opusBitrate));
			SavePointRadioMod.LOGGER.warn(
					"opusBitrate {} in {} is outside {}..{} (the encoder caps mono at {}) - using {}",
					loaded.opusBitrate, FILE_NAME, MIN_OPUS_BITRATE, MAX_OPUS_BITRATE,
					MAX_OPUS_BITRATE, clamped);
			loaded.opusBitrate = clamped;
		}
		// Opus encode complexity: the encode-latency/quality lever. Clamp 0..10 with
		// a warning, matching the opusBitrate pattern. Only consulted when
		// opusHighQuality is on, but clamp regardless so a later toggle stays safe.
		if (loaded.opusComplexity < MIN_OPUS_COMPLEXITY || loaded.opusComplexity > MAX_OPUS_COMPLEXITY) {
			int clamped = Math.max(MIN_OPUS_COMPLEXITY, Math.min(MAX_OPUS_COMPLEXITY, loaded.opusComplexity));
			SavePointRadioMod.LOGGER.warn(
					"opusComplexity {} in {} is outside {}..{} - using {}",
					loaded.opusComplexity, FILE_NAME, MIN_OPUS_COMPLEXITY, MAX_OPUS_COMPLEXITY, clamped);
			loaded.opusComplexity = clamped;
		}
		// Opus FEC expected packet-loss percent: clamp 0..100 with a warning. 0 = FEC off.
		if (loaded.opusFecPacketLossPercent < MIN_OPUS_FEC_PACKET_LOSS
				|| loaded.opusFecPacketLossPercent > MAX_OPUS_FEC_PACKET_LOSS) {
			int clamped = Math.max(MIN_OPUS_FEC_PACKET_LOSS,
					Math.min(MAX_OPUS_FEC_PACKET_LOSS, loaded.opusFecPacketLossPercent));
			SavePointRadioMod.LOGGER.warn(
					"opusFecPacketLossPercent {} in {} is outside {}..{} - using {}",
					loaded.opusFecPacketLossPercent, FILE_NAME, MIN_OPUS_FEC_PACKET_LOSS,
					MAX_OPUS_FEC_PACKET_LOSS, clamped);
			loaded.opusFecPacketLossPercent = clamped;
		}
		// sow-005 Phase B: smoothing mode - "tempo_match" (default, WSOLA stretch) or
		// "rebuffer" (Phase-A passthrough, safe rollback). Warn once on an unknown/blank
		// value and fall back to the default so the audio path stays predictable.
		if (loaded.smoothingMode == null || loaded.smoothingMode.isBlank()) {
			loaded.smoothingMode = DEFAULT_SMOOTHING_MODE;
		} else {
			loaded.smoothingMode = loaded.smoothingMode.trim().toLowerCase(Locale.ROOT);
			if (!loaded.smoothingMode.equals("tempo_match") && !loaded.smoothingMode.equals("rebuffer")) {
				SavePointRadioMod.LOGGER.warn(
						"Unknown smoothingMode '{}' in {} (expected \"tempo_match\" or \"rebuffer\") - using \"{}\"",
						loaded.smoothingMode, FILE_NAME, DEFAULT_SMOOTHING_MODE);
				loaded.smoothingMode = DEFAULT_SMOOTHING_MODE;
			}
		}
		// sow-005 Phase B: max time-stretch magnitude (percent), clamped 0..6. The feed
		// hard-clamps the servo factor to 1 +/- this/100. Absent uses the default.
		double stretchPct = loaded.maxStretchPercent != null
				? loaded.maxStretchPercent : DEFAULT_MAX_STRETCH_PERCENT;
		double clampedStretchPct = clamp(stretchPct, 0.0, MAX_MAX_STRETCH_PERCENT);
		if (loaded.maxStretchPercent != null && clampedStretchPct != stretchPct) {
			SavePointRadioMod.LOGGER.warn(
					"maxStretchPercent {} in {} is outside 0..{} - using {}",
					stretchPct, FILE_NAME, MAX_MAX_STRETCH_PERCENT, clampedStretchPct);
		}
		loaded.resolvedMaxStretchPercent = clampedStretchPct;
		loaded.resolveBuffering();
	}

	/**
	 * Resolves the jitter-buffer frame counts from the {@code *BufferSeconds}
	 * keys, clamping (and warning once) on out-of-range explicit values and
	 * migrating a legacy explicit {@code bufferFrames} into the max cap when the
	 * new keys are absent. Runs exactly once per {@link #load()} so any warning
	 * or migration line is logged once, not per feed. Idempotent and pure aside
	 * from logging; safe to call on a defaults instance (all keys present).
	 *
	 * <p>Order matters: the target is clamped first ({@value #MIN_TARGET_BUFFER_SECONDS}..{@value
	 * #MAX_TARGET_BUFFER_SECONDS} s), then the max cap (clamped {@code target}..{@value
	 * #MAX_MAX_BUFFER_SECONDS} s so it can never sit below the target), then
	 * the preroll (clamped 0.2 s..max). Rounding to frames is re-ordered after so
	 * {@code preroll,target <= max} and every count is at least one frame.</p>
	 */
	private void resolveBuffering() {
		// --- target: steady-state playout latency ---
		double targetSec = targetBufferSeconds != null ? targetBufferSeconds : DEFAULT_TARGET_BUFFER_SECONDS;
		double clampedTarget = clamp(targetSec, MIN_TARGET_BUFFER_SECONDS, MAX_TARGET_BUFFER_SECONDS);
		if (targetBufferSeconds != null && clampedTarget != targetSec) {
			SavePointRadioMod.LOGGER.warn("targetBufferSeconds {} in {} is outside {}..{} - using {}",
					targetSec, FILE_NAME, MIN_TARGET_BUFFER_SECONDS, MAX_TARGET_BUFFER_SECONDS, clampedTarget);
		}
		targetSec = clampedTarget;

		// --- max cap: explicit key wins, else a migrated legacy bufferFrames,
		// else the default. Migration only when the new key is truly absent. ---
		double maxSec;
		boolean migrated = false;
		if (maxBufferSeconds != null) {
			maxSec = maxBufferSeconds;
		} else if (bufferFrames != LEGACY_DEFAULT_BUFFER_FRAMES) {
			maxSec = bufferFrames / (double) FRAMES_PER_SECOND;
			migrated = true;
		} else {
			maxSec = DEFAULT_MAX_BUFFER_SECONDS;
		}
		double clampedMax = clamp(maxSec, targetSec, MAX_MAX_BUFFER_SECONDS);
		if (maxBufferSeconds != null && clampedMax != maxSec) {
			SavePointRadioMod.LOGGER.warn("maxBufferSeconds {} in {} is outside {}..{} - using {}",
					maxSec, FILE_NAME, targetSec, MAX_MAX_BUFFER_SECONDS, clampedMax);
		}
		maxSec = clampedMax;

		// sow-005 Phase 4 (finding): the high-water trim IS the sliding-window lag cap,
		// but its deadband only exists when the hard cap sits well above the target.
		// Raising targetBufferSeconds toward maxBufferSeconds (the deeper-target guidance)
		// pins high-water to maxBufferFrames and collapses the trim excess to ~0, silently
		// defeating the lag cap - latency then sits at the full max with no bounded
		// catch-up. Warn once (does not fire at the shipped 3s/12s defaults) so the
		// operator raises the cap alongside the target instead of losing the lag bound.
		if (maxSec < LAG_CAP_HEADROOM_WARN_MULTIPLIER * targetSec) {
			SavePointRadioMod.LOGGER.warn(
					"maxBufferSeconds {}s in {} gives little headroom over targetBufferSeconds {}s - the "
							+ "high-water lag cap needs the cap above the target to bound latency; raise "
							+ "maxBufferSeconds to >= ~{}s (~{}x target) so a burst-inflated buffer still "
							+ "trims back to target",
					maxSec, FILE_NAME, targetSec,
					LAG_CAP_HEADROOM_RECOMMENDED_MULTIPLIER * targetSec, LAG_CAP_HEADROOM_RECOMMENDED_MULTIPLIER);
		}

		// --- preroll: explicit key wins, else the target. The preroll is the
		// steady-state resting depth (we never resample, so the buffer cannot grow
		// above it on its own; the trim only walks a burst back DOWN toward target),
		// so defaulting it below target would leave cold-start/post-underrun depth
		// under target and underrun on the first upstream gap. Default preroll ==
		// target keeps the configured target latency actually in the buffer. ---
		double prerollDefault = targetSec;
		double prerollSec = prerollSeconds != null ? prerollSeconds : prerollDefault;
		double clampedPreroll = clamp(prerollSec, MIN_PREROLL_SECONDS, maxSec);
		if (prerollSeconds != null && clampedPreroll != prerollSec) {
			SavePointRadioMod.LOGGER.warn("prerollSeconds {} in {} is outside {}..{} - using {}",
					prerollSec, FILE_NAME, MIN_PREROLL_SECONDS, maxSec, clampedPreroll);
		}
		prerollSec = clampedPreroll;

		int max = (int) Math.round(maxSec * FRAMES_PER_SECOND);
		int target = (int) Math.round(targetSec * FRAMES_PER_SECOND);
		int preroll = (int) Math.round(prerollSec * FRAMES_PER_SECOND);
		// Re-establish invariants after rounding: >=1 frame, target/preroll <= max.
		this.maxBufferFrames = Math.max(1, max);
		this.targetFrames = Math.max(1, Math.min(target, this.maxBufferFrames));
		this.prerollFrames = Math.max(1, Math.min(preroll, this.maxBufferFrames));

		// --- sow-005 Phase 4: sustained-drain re-anchor thresholds ---
		// Low watermark clamps into [0.2 s .. target] and is then forced strictly
		// below the target IN FRAMES, so the re-anchor arms only on a genuine drain
		// past the intended playout depth and never on jitter sitting at target.
		double lowSec = reanchorLowWatermarkSeconds != null
				? reanchorLowWatermarkSeconds : DEFAULT_REANCHOR_LOW_WATERMARK_SECONDS;
		double clampedLow = clamp(lowSec, MIN_REANCHOR_LOW_WATERMARK_SECONDS, targetSec);
		if (reanchorLowWatermarkSeconds != null && clampedLow != lowSec) {
			SavePointRadioMod.LOGGER.warn("reanchorLowWatermarkSeconds {} in {} is outside {}..{} - using {}",
					lowSec, FILE_NAME, MIN_REANCHOR_LOW_WATERMARK_SECONDS, targetSec, clampedLow);
		}
		lowSec = clampedLow;

		// Sustain <= 0 DISABLES the re-anchor (sustainFrames = 0); a positive value
		// clamps into [1 s .. 120 s].
		double sustainSec = reanchorSustainSeconds != null
				? reanchorSustainSeconds : DEFAULT_REANCHOR_SUSTAIN_SECONDS;
		int sustainFrames;
		if (sustainSec <= 0.0) {
			sustainFrames = 0;
		} else {
			double clampedSustain = clamp(sustainSec, MIN_REANCHOR_SUSTAIN_SECONDS, MAX_REANCHOR_SUSTAIN_SECONDS);
			if (reanchorSustainSeconds != null && clampedSustain != sustainSec) {
				SavePointRadioMod.LOGGER.warn("reanchorSustainSeconds {} in {} is outside {}..{} - using {}",
						sustainSec, FILE_NAME, MIN_REANCHOR_SUSTAIN_SECONDS, MAX_REANCHOR_SUSTAIN_SECONDS, clampedSustain);
			}
			sustainFrames = Math.max(1, (int) Math.round(clampedSustain * FRAMES_PER_SECOND));
		}
		int low = (int) Math.round(lowSec * FRAMES_PER_SECOND);
		// Floor the low water strictly below BOTH the target AND the preroll depth. A
		// re-anchor always rebuilds the cushion to prerollFrames (the delivery loop drops
		// back to pre-roll on fire), so if the low water sat at/above prerollFrames the
		// rebuild would land at/below its own arming threshold and the re-anchor could
		// re-fire once per sustain window instead of once per genuine drain episode.
		// prerollSeconds is an independent operator knob (clamped only to [0.2s..max]), so
		// a low-latency preroll below reanchorLowWatermarkSeconds is reachable; forcing
		// low < preroll here keeps the "fires ONCE per drain episode" guarantee for any
		// config. Warn once when the configured low water would have sat at/above the
		// preroll depth so the operator sees the interaction (sow-005 Phase 4, finding).
		if (sustainFrames > 0 && low >= this.prerollFrames) {
			SavePointRadioMod.LOGGER.warn(
					"reanchorLowWatermarkSeconds {}s in {} ({} frames) is at/above prerollSeconds ({} frames) - "
							+ "a re-anchor rebuilds only to the preroll depth, so the low water is forced below it "
							+ "to keep the re-anchor firing once per drain episode; lower reanchorLowWatermarkSeconds "
							+ "or raise prerollSeconds",
					lowSec, FILE_NAME, low, this.prerollFrames);
		}
		this.reanchorLowWaterFrames = Math.max(1,
				Math.min(low, Math.max(1, Math.min(this.targetFrames - 1, this.prerollFrames - 1))));
		this.reanchorSustainFrames = sustainFrames;

		if (migrated) {
			SavePointRadioMod.LOGGER.info(
					"Migrated legacy bufferFrames={} in {} to maxBufferSeconds={}s ({} frames); set "
							+ "targetBufferSeconds/maxBufferSeconds/prerollSeconds to tune the jitter buffer",
					bufferFrames, FILE_NAME, maxSec, this.maxBufferFrames);
		}
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}

	private static RadioConfig createDefault() {
		RadioConfig config = new RadioConfig();
		config.stations.put("pulsefm", new Station(
				"PulseFM",
				"https://savepoint.fm/radio/broadcast/35/trunk.m3u8",
				null,
				32));
		config.defaultStation = "pulsefm";
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
		config.resolveBuffering();
		return config;
	}

	private RadioConfig() {
	}
}
