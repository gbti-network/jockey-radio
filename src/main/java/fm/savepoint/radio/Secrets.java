package fm.savepoint.radio;

/**
 * One place to mask secrets ({@code token}, {@code streamKey}, now-playing
 * bearer token) for any human-visible surface - dialog text, command output,
 * logs. A secret is only ever shown as {@code ***}; its presence or absence may
 * be revealed, never its value.
 */
public final class Secrets {

	/** The masked placeholder every surface renders in place of a secret value. */
	public static final String MASK = "***";

	private Secrets() {
	}

	/**
	 * {@code (none)} when the secret is unset/blank, otherwise {@link #MASK}.
	 * Never returns the value itself, so it is safe to interpolate into any
	 * shown string.
	 */
	public static String mask(String secret) {
		return (secret == null || secret.isBlank()) ? "(none)" : MASK;
	}
}
