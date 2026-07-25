package fm.gbti.jockey.voicechat;

import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import fm.gbti.jockey.JockeyRadioMod;
import fm.gbti.jockey.session.AudioSink;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Real audio delivery: encodes each 960-sample s16 mono 48&nbsp;kHz frame with
 * a per-sink Opus encoder and sends it over a locational Simple Voice Chat
 * channel anchored at the jukebox.
 *
 * <p>Threading: {@link #accept(short[])} runs on a feed delivery thread at
 * 20&nbsp;ms cadence; {@link #close()} runs on the server thread. Per the
 * {@link AudioSink} contract one in-flight frame may arrive concurrently with
 * or up to one frame period after close - and {@code encoder.close()} frees a
 * native encoder, so encode racing close would be use-after-free. The
 * {@code lock} therefore guards ONLY the native encoder: {@code accept} does
 * its {@code closed}/{@code isClosed} re-check plus {@code encoder.encode()}
 * under it, and {@code close} frees the encoder under it, so the two can never
 * overlap. {@code accept} first checks the volatile {@code closed} flag (a
 * trailing frame drops at the cheap check) and then performs
 * {@code channel.send()} OUTSIDE the lock. That send is a full O(listeners)
 * broadcast - packet serialize/encrypt plus a synchronous UDP write per
 * in-range player - that can block on network I/O, so keeping it out of the
 * lock means {@code close()} on the server thread waits at most one
 * sub-millisecond native encode, never a network broadcast (the 'server thread
 * never blocked' guardrail). A trailing {@code send()} that lands after
 * {@code close()} is harmless: {@code LocationalAudioChannel} holds no
 * per-channel native/socket state that {@code flush()} frees, so the already
 * produced opus packet is just delivered slightly late and client jitter
 * buffers absorb it. The channel has no {@code close()} in the SVC API -
 * {@code flush()} signals end-of-stream to those jitter buffers.</p>
 *
 * <p>Off-thread {@code send()} relies on Simple Voice Chat's
 * {@code threaded_server_support} server-config option. With it enabled,
 * {@code LocationalAudioChannel.send()} reads a volatile UUID snapshot of the
 * in-range players; with it OFF (the SVC default) that path walks the live
 * {@code ServerLevel} player list with an indexed loop, which is only
 * thread-safe on the server thread. When it is off, a player
 * join/leave/dimension-change on the server thread racing a broadcast on the
 * feed thread can throw {@code IndexOutOfBoundsException}/NPE or read torn
 * state on the feed thread. That never touches the server thread, and the
 * {@code try/catch(Throwable)} around {@code send()} is the safety net: it
 * drops that single 20&nbsp;ms frame, logs once, and the next frame self-heals.
 * Operators who want glitch-free off-thread delivery should enable
 * {@code threaded_server_support} in the Simple Voice Chat server config.</p>
 */
final class VoiceChatSink implements AudioSink {

	private final LocationalAudioChannel channel;
	private final RadioOpusEncoder encoder;
	private final String stationKey;
	private final String posDescription;

	/** Guards the native encoder so {@code encode()} never races {@code encoder.close()}. */
	private final Object lock = new Object();
	private volatile boolean closed;
	/** Sent-frame tally; incremented on the feed thread, read on the server thread at close. */
	private final AtomicLong framesSent = new AtomicLong();
	/** Feed-thread only; the first encode/send failure logs, the rest stay quiet. */
	private boolean sendFailureLogged;

	VoiceChatSink(LocationalAudioChannel channel, RadioOpusEncoder encoder, String stationKey, String posDescription) {
		this.channel = channel;
		this.encoder = encoder;
		this.stationKey = stationKey;
		this.posDescription = posDescription;
	}

	@Override
	public void open() {
		JockeyRadioMod.LOGGER.info("[jockey] sink open type=voicechat {} station={}",
				posDescription, stationKey);
	}

	@Override
	public void accept(short[] frame) {
		if (closed) {
			return; // contract-tolerated trailing frame
		}
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
		// Broadcast OUTSIDE the lock: send() is O(listeners) crypto + UDP writes
		// that can block, and close() (server thread) must never wait on it. A
		// trailing send after close() delivers an already-produced packet late,
		// which is harmless (the channel keeps no native/socket state).
		try {
			channel.send(opus);
			framesSent.incrementAndGet();
		} catch (Throwable t) {
			logFailure(t);
		}
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		// Only the native encoder needs mutual exclusion with a concurrent
		// encode(); this is the sole thing close() can block on, and it is a
		// sub-millisecond native call, not a network broadcast.
		synchronized (lock) {
			try {
				if (!encoder.isClosed()) {
					encoder.close();
				}
			} catch (Throwable t) {
				JockeyRadioMod.LOGGER.warn("[jockey] voicechat encoder close failed {} station={}",
						posDescription, stationKey, t);
			}
		}
		// flush() signals end-of-stream to client jitter buffers; it needs no
		// lock now that send() runs lock-free (a racing trailing send is benign).
		try {
			channel.flush();
		} catch (Throwable t) {
			JockeyRadioMod.LOGGER.warn("[jockey] voicechat channel flush failed {} station={}",
					posDescription, stationKey, t);
		}
		JockeyRadioMod.LOGGER.info("[jockey] sink close type=voicechat {} framesSent={}",
				posDescription, framesSent.get());
	}

	/** Logs the first encode/send failure only; the feed thread is the sole caller. */
	private void logFailure(Throwable t) {
		if (!sendFailureLogged) {
			sendFailureLogged = true;
			JockeyRadioMod.LOGGER.warn(
					"[jockey] sink send failed type=voicechat {} station={} (suppressing further send errors)",
					posDescription, stationKey, t);
		}
	}
}
