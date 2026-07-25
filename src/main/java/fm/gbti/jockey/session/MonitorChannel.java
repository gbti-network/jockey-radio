package fm.gbti.jockey.session;

/**
 * An {@link AudioSink} that also reports a live signal level, delivered by the
 * voicechat backend for the on-demand admin monitor. Kept in the
 * {@code session} package (referencing no Simple Voice Chat API type) so
 * {@link MonitorService} - which never touches SVC directly - can hold and read
 * it, while the concrete implementation
 * ({@code fm.gbti.jockey.voicechat.MonitorSink}) lives behind the
 * {@code isModLoaded} guard like every other SVC-typed class.
 *
 * <p>The level is written on the feed delivery thread (in {@link #accept}) into
 * a volatile and read on the server thread by the boss-bar tick updater, so a
 * reader always sees a recent, internally consistent value without any lock.</p>
 */
public interface MonitorChannel extends AudioSink {

	/**
	 * Smoothed RMS of the most recent audio frame as a {@code 0..1} fraction of
	 * full scale ({@code sqrt(mean(sample^2))/32768}, exponentially smoothed).
	 * Drops back toward {@code 0} on silence and after {@link #close()}. Safe on
	 * any thread (one volatile read).
	 */
	float level();
}
