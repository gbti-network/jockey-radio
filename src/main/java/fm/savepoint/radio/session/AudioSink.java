package fm.savepoint.radio.session;

/**
 * Receives decoded station audio for a single radio session.
 *
 * <p>Phase 2 only exercises the lifecycle ({@link #open()} / {@link #close()});
 * from Phase 3 onward the owning {@link StationFeed} delivers 960-sample
 * (20&nbsp;ms at 48&nbsp;kHz) signed 16-bit mono PCM frames via
 * {@link #accept(short[])}. Frames may arrive on a feed worker thread, so
 * implementations must be safe to call off the server thread.</p>
 */
public interface AudioSink {

	/** Called once, on the server thread, when the owning session activates. */
	void open();

	/**
	 * Delivers one 960-sample s16 mono PCM frame. May be called from a feed
	 * worker thread. Never called before {@link #open()}. Because delivery
	 * runs on a feed worker thread while removal/close happen on the server
	 * thread, one in-flight frame may still arrive concurrently with or
	 * shortly (up to one 20&nbsp;ms frame period) after {@link #close()} -
	 * implementations MUST tolerate such a trailing call (e.g. check a closed
	 * flag and drop the frame) instead of assuming close() strictly
	 * happens-after the last accept(). The array is owned by the feed
	 * and may be shared between sinks (e.g. the silence frame) - sinks must
	 * not mutate it and must copy if they keep it past the call.
	 */
	void accept(short[] frame);

	/** Called once, on the server thread, when the owning session deactivates. */
	void close();

	/**
	 * True for a placeholder sink ({@link NullSink}) that merely swallows audio
	 * and should be replaced by a real backend (a Simple Voice Chat channel) as
	 * soon as one becomes available. Real sinks return {@code false} so the
	 * voicechat-ready upgrade pass leaves a working channel/encoder untouched
	 * instead of tearing it down and recreating it (which would cost a native
	 * encoder teardown and a one-frame audible gap for no gain). Server thread.
	 */
	default boolean isPlaceholder() {
		return false;
	}
}
