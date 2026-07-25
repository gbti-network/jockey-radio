package fm.gbti.jockey.voicechat;

/**
 * Minimal Opus-encoder abstraction the radio sinks encode against, so a sink can
 * hold either Simple Voice Chat's own {@code de.maxhenkel.voicechat.api.opus.OpusEncoder}
 * (via {@link SvcOpusEncoder}) or our bundled high-bitrate encoder
 * ({@link HqOpusEncoder}) behind the same three-call shape the sinks already use:
 * {@code encode(short[960]) -> byte[]}, {@link #isClosed()}, {@link #close()}.
 *
 * <p>This decouples {@link VoiceChatSink}/{@link MonitorSink} from the concrete
 * encoder type so Phase 2b can swap in a music-grade encoder for OUR channels
 * only, while {@code opusHighQuality=false} keeps the exact SVC encoder and thus
 * zero behavior change. It carries no Simple Voice Chat imports, so it is safe to
 * classload regardless of whether SVC is present (though in practice it is only
 * ever reached through the SVC-guarded sink-creation path).</p>
 *
 * <p>Threading matches the sinks' existing contract: a single sink owns one
 * encoder instance and only ever calls {@link #encode(short[])} under that sink's
 * lock, so implementations may reuse a per-instance scratch buffer without extra
 * synchronization.</p>
 */
interface RadioOpusEncoder {

	/**
	 * Encodes one 960-sample s16 mono 48&nbsp;kHz frame into a single 20&nbsp;ms
	 * Opus packet. Returns {@code null} (or a zero-length array) when nothing
	 * should be sent. May throw on an encode error; the caller drops that one
	 * frame and logs once.
	 */
	byte[] encode(short[] frame);

	/** True once {@link #close()} has run; the sinks re-check this under their lock. */
	boolean isClosed();

	/** Releases any encoder resources. Idempotent; safe to call from close(). */
	void close();
}
