package fm.gbti.jockey.voicechat;

import de.maxhenkel.voicechat.api.opus.OpusEncoder;

/**
 * {@link RadioOpusEncoder} adapter over Simple Voice Chat's own
 * {@link OpusEncoder} (the one {@code api.createEncoder(OpusEncoderMode.AUDIO)}
 * returns). Every call delegates straight through, so a sink backed by this
 * behaves exactly as the pre-Phase-2b code did - this is the path taken when
 * {@code opusHighQuality=false} or when the high-quality encoder fails to
 * initialise and the sink falls back to the SVC encoder.
 *
 * <p>Holds the native SVC encoder handle, so {@link #close()} frees it; the
 * owning sink already guards {@link #encode(short[])} against {@link #close()}
 * with its lock (use-after-free protection).</p>
 */
final class SvcOpusEncoder implements RadioOpusEncoder {

	private final OpusEncoder delegate;

	SvcOpusEncoder(OpusEncoder delegate) {
		this.delegate = delegate;
	}

	@Override
	public byte[] encode(short[] frame) {
		return delegate.encode(frame);
	}

	@Override
	public boolean isClosed() {
		return delegate.isClosed();
	}

	@Override
	public void close() {
		if (!delegate.isClosed()) {
			delegate.close();
		}
	}
}
