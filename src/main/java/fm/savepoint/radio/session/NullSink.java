package fm.savepoint.radio.session;

import fm.savepoint.radio.SavePointRadioMod;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2 placeholder sink: swallows audio and only logs its lifecycle plus
 * would-be frame statistics. Replaced by the Simple Voice Chat sink in a later
 * phase.
 */
public final class NullSink implements AudioSink {

	private final String description;
	private final AtomicLong framesAccepted = new AtomicLong();
	private volatile boolean open;

	/**
	 * @param description freeform label used in log lines, e.g.
	 *                    {@code "station=pulsefm pos=1,64,2"}
	 */
	public NullSink(String description) {
		this.description = description;
	}

	@Override
	public void open() {
		open = true;
		SavePointRadioMod.LOGGER.info("[radio] sink open {}", description);
	}

	@Override
	public void accept(short[] frame) {
		// Audio arrives in Phase 3; count what we would have played.
		framesAccepted.incrementAndGet();
	}

	@Override
	public void close() {
		open = false;
		SavePointRadioMod.LOGGER.info("[radio] sink close {} framesAccepted={}", description, framesAccepted.get());
	}

	public boolean isOpen() {
		return open;
	}

	@Override
	public boolean isPlaceholder() {
		return true;
	}
}
