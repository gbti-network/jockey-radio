package fm.savepoint.radio.voicechat;

import org.concentus.OpusApplication;
import org.concentus.OpusEncoder;
import org.concentus.OpusException;
import org.concentus.OpusSignal;

import java.util.Arrays;

/**
 * Our own music-grade Opus encoder for SavePoint radio channels ONLY, wrapping a
 * bundled pure-Java Concentus {@link OpusEncoder} configured 48&nbsp;kHz / mono /
 * {@code OPUS_APPLICATION_AUDIO} at a config-tunable bitrate.
 *
 * <p><b>Why this exists.</b> SVC's {@code api.createEncoder(AUDIO)} gives no way
 * to raise the bitrate, so the radio was stuck at SVC's voice-tuned default. But
 * {@code AudioChannel.send(byte[])} transmits a RAW Opus packet and the SVC
 * client decodes any bitrate, so we encode the packets ourselves at music-grade
 * bitrate and hand them to the same {@code channel.send(...)}. Nothing else about
 * SVC changes: this class is only ever built inside OUR two sink factories, so no
 * other SVC channel, the player-to-player voice codec, or the SVC server config
 * is touched.</p>
 *
 * <p><b>MTU safety.</b> An SVC voice packet must fit {@value #MTU_BYTES} bytes. A
 * 20&nbsp;ms frame carries {@code bitrate/50/8} bytes, so the MTU-derived ceiling is
 * ~510&nbsp;kbit/s - but the binding limit is lower: Concentus caps the encoder at
 * 300&nbsp;kbit/s mono, so we clamp to {@value #MAX_BITRATE} (~750&nbsp;bytes/frame) and
 * report anything higher instead of letting the library silently reduce it. The encode
 * output buffer is capped at the MTU so Concentus can never emit an oversized packet.
 * 128&nbsp;kbit/s mono is ~320&nbsp;bytes/frame - comfortably safe.</p>
 *
 * <p><b>Lifecycle.</b> Concentus is pure Java and holds no native handle, so there
 * is nothing to free; {@link #close()} merely flips a flag that {@link #isClosed()}
 * reports (matching the SVC encoder's shape the sinks expect). The single owning
 * sink calls {@link #encode(short[])} only under its lock, so the reused
 * {@link #out} scratch buffer needs no extra synchronization.</p>
 */
final class HqOpusEncoder implements RadioOpusEncoder {

	/** 48 kHz: the fixed radio sample rate (frames are 960 samples = 20 ms). */
	static final int SAMPLE_RATE = 48_000;
	/** Mono: the radio mixes to one channel. */
	static final int CHANNELS = 1;
	/** Samples per channel in one 20 ms frame (AbstractFfmpegFeed.FRAME_SAMPLES). */
	static final int FRAME_SAMPLES = 960;
	/** SVC voice-packet MTU; a produced Opus packet must fit under this. */
	static final int MTU_BYTES = 1275;
	/** Floor for a sane music bitrate. */
	static final int MIN_BITRATE = 24_000;
	/**
	 * Ceiling set by the ENCODER, not by the MTU: Concentus's {@code setBitrate} hard-caps
	 * at {@code 300000 * channels}, so anything above this was silently reduced and the knob
	 * did nothing over a third of its former advertised range. 300 kbit/s mono @ 20 ms is
	 * ~750 bytes/frame, still well inside the {@value #MTU_BYTES}-byte MTU (the MTU-derived
	 * ceiling would be ~510 kbit/s, but the encoder never lets us near it).
	 */
	static final int MAX_BITRATE = 300_000;

	private final OpusEncoder encoder;
	/** Reused encode target, MTU-capped; single-sink/under-lock so no sharing. */
	private final byte[] out = new byte[MTU_BYTES];
	private volatile boolean closed;

	private HqOpusEncoder(OpusEncoder encoder) {
		this.encoder = encoder;
	}

	/** Floor for Opus encode complexity (fastest, lowest quality). */
	static final int MIN_COMPLEXITY = 0;
	/** Ceiling for Opus encode complexity (slowest, highest quality). */
	static final int MAX_COMPLEXITY = 10;

	/**
	 * Builds a 48&nbsp;kHz mono music-mode encoder at the given bitrate, clamped
	 * MTU-safe to {@value #MIN_BITRATE}..{@value #MAX_BITRATE}, and the given Opus
	 * encode complexity clamped to {@value #MIN_COMPLEXITY}..{@value #MAX_COMPLEXITY}.
	 * Throws {@link OpusException} if Concentus refuses to initialise - the caller
	 * then falls back to the SVC encoder so audio still works.
	 *
	 * <p>Complexity is the per-frame encode-cost lever: the pure-Java Concentus
	 * encoder at its default (max) complexity can occasionally exceed the 20 ms
	 * frame budget on a slow host, which the paced delivery thread then hears as a
	 * late-frame-then-burst hitch. A middling complexity keeps encode time well
	 * under budget while VBR preserves music quality where bits are cheap.</p>
	 */
	/** Ceiling for the FEC expected-packet-loss hint (percent). */
	static final int MAX_FEC_PACKET_LOSS = 100;

	static HqOpusEncoder create(int bitrate, int complexity, int fecPacketLossPercent) throws OpusException {
		OpusEncoder enc = new OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.OPUS_APPLICATION_AUDIO);
		int clamped = Math.max(MIN_BITRATE, Math.min(MAX_BITRATE, bitrate));
		enc.setBitrate(clamped);
		// Music-mode hint; the source is 192k AAC so treat it as music, not voice.
		enc.setSignalType(OpusSignal.OPUS_SIGNAL_MUSIC);
		// Bound the per-frame encode cost so a slow host stays under the 20 ms frame
		// budget (see class doc). VBR (constrained, no DTX) keeps music quality while
		// respecting the target bitrate; DTX off so a continuous stream never gates.
		int clampedComplexity = Math.max(MIN_COMPLEXITY, Math.min(MAX_COMPLEXITY, complexity));
		enc.setComplexity(clampedComplexity);
		enc.setUseVBR(true);
		enc.setUseConstrainedVBR(true);
		enc.setUseDTX(false);
		// In-band FEC (opt-in, default off): lets the SVC client reconstruct an isolated
		// lost packet from redundancy in the next one (its decoder calls decode_fec on a
		// detected gap), cutting loss-related dropouts for remote players. TRADE-OFF: Opus
		// only carries FEC in SILK/hybrid mode, so enabling it forces the encoder OUT of
		// pristine CELT-only fullband music (TOC config 31) INTO hybrid (config 15, SILK on
		// the low band) - a small fidelity change for loss robustness, plus a little
		// redundancy bitrate. Pointless on a low-loss/LAN path, hence 0 (off) by default;
		// worth it only when serving remote players over a lossy network.
		int loss = Math.max(0, Math.min(MAX_FEC_PACKET_LOSS, fecPacketLossPercent));
		if (loss > 0) {
			enc.setUseInbandFEC(true);
			enc.setPacketLossPercent(loss);
		}
		return new HqOpusEncoder(enc);
	}

	@Override
	public byte[] encode(short[] frame) {
		if (closed) {
			return null;
		}
		// Opus only accepts standard frame sizes; the feed contract guarantees an
		// exact 960-sample frame, so a short array is a contract violation we drop
		// rather than feed Concentus an invalid size.
		if (frame.length < FRAME_SAMPLES) {
			return null;
		}
		int len;
		try {
			len = encoder.encode(frame, 0, FRAME_SAMPLES, out, 0, out.length);
		} catch (OpusException e) {
			// Surface as unchecked so the sink's existing catch(Throwable) drops the
			// single frame and logs once, exactly as it does for the SVC encoder.
			throw new IllegalStateException("hq opus encode failed", e);
		}
		if (len <= 0) {
			return null;
		}
		return Arrays.copyOf(out, len);
	}

	@Override
	public boolean isClosed() {
		return closed;
	}

	@Override
	public void close() {
		// Pure-Java encoder: no native handle to free, just stop accepting frames.
		closed = true;
	}
}
