package fm.savepoint.radio.session;

/**
 * One live upstream connection to a station, shared by every session tuned to
 * it. Obtained exclusively through {@link FeedRegistry#acquire(FeedSource)} so a
 * station is fetched and decoded once no matter how many jukeboxes play it.
 *
 * <p>The real pipeline is {@link AbstractFfmpegFeed} and its subclasses
 * {@link HlsPullFeed} / {@link RtmpReceiveFeed}: an ffmpeg subprocess fetching
 * and decoding on mod-owned daemon executors, never the server thread. The
 * {@link StubStationFeed} remains for {@code "ingest": "stub"} configs and as
 * the fallback when ffmpeg is not runnable.</p>
 */
public interface StationFeed {

	/** Config key of the station this feed serves. */
	String stationKey();

	/** Starts the upstream fetch/decode pipeline. Called once by the registry. */
	void start();

	/** Stops the pipeline and releases upstream resources. Called once by the registry. */
	void stop();

	/** Registers a sink to receive this feed's frames. */
	void addSink(AudioSink sink);

	/** Unregisters a previously added sink. */
	void removeSink(AudioSink sink);

	/**
	 * One-line stats/diagnostics summary for {@code /jukeboxdisc sessions}.
	 * Must be safe to call from any thread.
	 */
	String getStatsLine();

	/**
	 * A richer multi-field diagnostics line for {@code /jukeboxdisc diag} (sow-005
	 * Phase 0): the rolling micro-glitch rate, effective bitrate, buffer-occupancy
	 * summary and redacted ffmpeg arg-shape. Defaults to {@link #getStatsLine()}
	 * so feeds without extra instrumentation (the stub) still print something.
	 * Safe to call from any thread.
	 */
	default String getDiagLine() {
		return getStatsLine();
	}

	/**
	 * Frames decoded from upstream so far - {@code 0} until the first frame
	 * arrives. Lets the "Test feed" probe tell a bound-but-idle RTMP listener
	 * ({@code 0}) from one actively receiving audio ({@code > 0}). Safe on any
	 * thread; feeds that never stream (the stub) leave the default {@code 0}.
	 */
	default long framesRead() {
		return 0L;
	}

	/**
	 * Declared PLAYOUT phase (sow-005 Phase A): {@code "streaming"} once the delivery
	 * loop is emitting real frames, {@code "buffering"} while pre-rolling / re-anchor
	 * rebuffering / initial-connecting, or {@code "idle"} when no live source is
	 * present. Lets {@code NowPlayingHud} declare a buffering pause distinctly from a
	 * bound-but-silent feed. Safe on any thread; feeds that never stream (the stub)
	 * leave the default {@code "idle"}.
	 */
	default String playoutState() {
		return "idle";
	}

	/**
	 * sow-006/sow-012: whether this feed carries a TRULY LIVE source - a live SavePoint HLS station, an
	 * RTMP publisher, or a platform stream yt-dlp reports {@code is_live=true} (Twitch/Kick live). A
	 * finite FILE or a non-live platform VOD (a SoundCloud track) is {@code false}. The now-playing HUD uses
	 * this to show "on air" only for live sources. Default {@code false} (the stub and finite feeds). Any
	 * thread.
	 */
	default boolean isLive() {
		return false;
	}
}
