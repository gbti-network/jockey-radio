package fm.savepoint.radio.voicechat;

import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel;
import fm.savepoint.radio.SavePointRadioMod;
import fm.savepoint.radio.session.MonitorChannel;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Audio delivery for the on-demand admin monitor: encodes each 960-sample s16
 * mono 48&nbsp;kHz frame with a per-sink Opus encoder and sends it over a
 * NON-POSITIONAL {@link StaticAudioChannel} bound to exactly one admin's voice
 * chat connection, so only that admin hears the feed - no jukebox, no locational
 * falloff. Alongside delivery it maintains a smoothed RMS {@link #level()} the
 * {@code MonitorService} boss-bar tick updater reads for its level meter.
 *
 * <p>Threading mirrors {@code VoiceChatSink} exactly: {@link #accept(short[])}
 * runs on a feed delivery thread at 20&nbsp;ms cadence and {@link #close()} on
 * the server thread. The {@code lock} guards ONLY the native encoder - the
 * closed re-check plus {@code encoder.encode()} happen under it, and
 * {@code close()} frees the encoder under it, so encode can never race the
 * native free (use-after-free protection). {@code channel.send()} runs OUTSIDE
 * the lock because it can block on a UDP write, and {@code close()} on the server
 * thread must never wait on the network; a trailing {@code send()} that lands
 * after {@code close()} just delivers an already-produced opus packet slightly
 * late, which client jitter buffers absorb (the SVC channel has no
 * {@code close()} - {@code flush()} signals end-of-stream). The RMS is computed
 * from the frame outside the lock (it touches no native state) and published
 * through the volatile {@code level}; the sink thread is its only writer.</p>
 *
 * <p>The channel is bound to a single connection, so {@code send()} is a
 * one-recipient write rather than the O(listeners) broadcast the locational sink
 * does - but the same {@code try/catch(Throwable)} still drops a single failed
 * 20&nbsp;ms frame and self-heals on the next one.</p>
 */
final class MonitorSink implements MonitorChannel {

	/** Attack/release coefficients for the level EMA (faster to rise than fall). */
	private static final float LEVEL_ATTACK = 0.5f;
	private static final float LEVEL_RELEASE = 0.15f;

	private final StaticAudioChannel channel;
	private final RadioOpusEncoder encoder;
	private final String discLabel;

	/** Guards the native encoder so {@code encode()} never races {@code encoder.close()}. */
	private final Object lock = new Object();
	private volatile boolean closed;
	/** Smoothed RMS 0..1; written only on the feed thread, read on the server thread. */
	private volatile float level;
	/** Sent-frame tally; incremented on the feed thread, read on the server thread at close. */
	private final AtomicLong framesSent = new AtomicLong();
	/** Feed-thread only; the first encode/send failure logs, the rest stay quiet. */
	private boolean sendFailureLogged;

	MonitorSink(StaticAudioChannel channel, RadioOpusEncoder encoder, String discLabel) {
		this.channel = channel;
		this.encoder = encoder;
		this.discLabel = discLabel;
	}

	@Override
	public void open() {
		SavePointRadioMod.LOGGER.info("[radio] monitor sink open station={}", discLabel);
	}

	@Override
	public void accept(short[] frame) {
		if (closed) {
			return; // contract-tolerated trailing frame
		}
		// Level meter first: a pure read of the frame with no native state, so it
		// stays outside the encoder lock and updates even if the encode below drops.
		updateLevel(frame);
		byte[] opus;
		// Encode under the lock: the re-check plus encode() must be atomic with
		// respect to close()'s encoder.close() (use-after-free protection).
		synchronized (lock) {
			if (closed || encoder.isClosed() || channel.isClosed()) {
				return;
			}
			try {
				opus = encoder.encode(frame);
			} catch (Throwable t) {
				logFailure(t);
				return;
			}
		}
		if (opus == null || opus.length == 0) {
			return;
		}
		// Send OUTSIDE the lock: it can block on a UDP write and close() (server
		// thread) must never wait on it. A trailing send after close() delivers an
		// already-produced packet late, which is harmless.
		try {
			channel.send(opus);
			framesSent.incrementAndGet();
		} catch (Throwable t) {
			logFailure(t);
		}
	}

	@Override
	public float level() {
		return level;
	}

	/**
	 * Recomputes the smoothed RMS from one frame. Exponential moving average with a
	 * faster attack than release so the meter rises promptly on a transient yet
	 * settles gently. The feed thread is the only writer, so the volatile
	 * read-modify-write needs no lock.
	 */
	private void updateLevel(short[] frame) {
		if (frame.length == 0) {
			return;
		}
		double sum = 0.0;
		for (short sample : frame) {
			double value = sample;
			sum += value * value;
		}
		float rms = (float) (Math.sqrt(sum / frame.length) / 32768.0);
		float prev = level;
		float coeff = rms > prev ? LEVEL_ATTACK : LEVEL_RELEASE;
		float next = prev + coeff * (rms - prev);
		if (next < 0f) {
			next = 0f;
		} else if (next > 1f) {
			next = 1f;
		}
		level = next;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		level = 0f; // let the meter fall to zero on teardown
		// Only the native encoder needs mutual exclusion with a concurrent
		// encode(); this is the sole thing close() can block on, a sub-millisecond
		// native call, never a network send.
		synchronized (lock) {
			try {
				if (!encoder.isClosed()) {
					encoder.close();
				}
			} catch (Throwable t) {
				SavePointRadioMod.LOGGER.warn("[radio] monitor encoder close failed station={}", discLabel, t);
			}
		}
		// flush() signals end-of-stream to the client jitter buffer; a racing
		// trailing send is benign, so it needs no lock.
		try {
			channel.flush();
		} catch (Throwable t) {
			SavePointRadioMod.LOGGER.warn("[radio] monitor channel flush failed station={}", discLabel, t);
		}
		SavePointRadioMod.LOGGER.info("[radio] monitor sink close station={} framesSent={}",
				discLabel, framesSent.get());
	}

	/** Logs the first encode/send failure only; the feed thread is the sole caller. */
	private void logFailure(Throwable t) {
		if (!sendFailureLogged) {
			sendFailureLogged = true;
			SavePointRadioMod.LOGGER.warn(
					"[radio] monitor sink send failed station={} (suppressing further send errors)", discLabel, t);
		}
	}
}
