package fm.gbti.jockey.session;

import fm.gbti.jockey.config.RadioConfig;
import fm.gbti.jockey.disc.DiscDefinition;

import java.util.Optional;

/**
 * The single seam that turns an {@link DiscDefinition.SourceType#RTMP_RECEIVE}
 * disc into the ingest URLs a DJ/OBS/relay publishes to. Keeping the URL shape
 * here means a future change to the RTMP path touches one file (mirrors
 * {@link StationEndpoints} for the HLS-pull side).
 *
 * <p>The mod's RTMP listener binds {@link DiscDefinition#rtmpBind}. When it
 * binds {@code 0.0.0.0} (all interfaces) it is reachable on loopback, so the
 * shown local host is {@code 127.0.0.1}; when it binds a specific interface
 * the reachable host is that bind address itself (loopback would NOT reach a
 * listener bound only to, say, {@code 10.0.0.5}), so that is what
 * {@link #displayHost}/{@link #local} report. When the disc binds
 * {@code 0.0.0.0} an admin may also expose it to remote broadcasters; the
 * external ingest form is only offered when a public host is configured via
 * {@link RadioConfig#rtmpPublicHost} (otherwise the caller shows a
 * "set rtmpPublicHost" note instead).</p>
 *
 * <p>The {@link #streamKey() stream key} is a secret: it is legitimately shown
 * to the requesting admin (they need it for OBS) and placed on that admin's
 * clipboard, but it is NEVER logged and NEVER written to item NBT - the same
 * discipline W1-W3 apply.</p>
 */
public final class RtmpEndpoints {

	/** The host reachable on the local machine, independent of the bind address. */
	public static final String LOCAL_HOST = "127.0.0.1";

	/**
	 * A resolvable RTMP ingest target. {@link #server()} is the OBS "Server"
	 * field, {@link #streamKey()} the OBS "Stream Key" field, and
	 * {@link #fullUrl()} the two joined (some tools want a single URL).
	 */
	public record Ingest(String host, int port, String streamKey) {

		/** The OBS "Server" URL: {@code rtmp://<host>:<port>/live}. */
		public String server() {
			return "rtmp://" + host + ":" + port + "/live";
		}

		/** The combined publish URL: {@code rtmp://<host>:<port>/live/<streamKey>}. */
		public String fullUrl() {
			return server() + "/" + streamKey;
		}
	}

	private RtmpEndpoints() {
	}

	/** True when the disc binds all interfaces ({@code 0.0.0.0}). */
	public static boolean bindsAllInterfaces(DiscDefinition def) {
		return "0.0.0.0".equals(def.rtmpBind);
	}

	/**
	 * The host reachable for the local ingest: {@code 127.0.0.1} when the disc
	 * binds all interfaces ({@code 0.0.0.0}) or has no explicit bind, otherwise
	 * the concrete bind interface itself (a listener bound only to a specific
	 * interface is not reachable on loopback, so 127.0.0.1 would be a dead URL).
	 */
	public static String displayHost(DiscDefinition def) {
		String bind = def.rtmpBind;
		if (bind == null || bind.isBlank() || "0.0.0.0".equals(bind.trim())) {
			return LOCAL_HOST;
		}
		return bind.trim();
	}

	/** The local ingest target ({@code <displayHost>:<port>}) reachable on this host. */
	public static Ingest local(DiscDefinition def) {
		return new Ingest(displayHost(def), def.rtmpPort, def.streamKey);
	}

	/**
	 * The external ingest target for remote broadcasters, present only when the
	 * disc binds {@code 0.0.0.0} AND {@link RadioConfig#rtmpPublicHost} is set.
	 * Empty otherwise (bound to a single interface, or no public host configured
	 * - the caller then shows the "set rtmpPublicHost" note).
	 *
	 * <p>{@code rtmpPublicHost} is a bare {@code host} OR {@code host:port}
	 * authority (RadioConfig strips scheme/path but keeps an embedded port, and
	 * the documented form is literally {@code radio.savepoint.fm:1935}). When it
	 * already carries a port, that port is honoured; appending the disc's
	 * {@code rtmpPort} on top would emit a malformed
	 * {@code rtmp://host:pubPort:discPort/live}. Only a portless authority gets
	 * the disc's {@code rtmpPort}.</p>
	 */
	public static Optional<Ingest> external(DiscDefinition def, RadioConfig config) {
		if (!bindsAllInterfaces(def)) {
			return Optional.empty();
		}
		String host = config.rtmpPublicHost;
		if (host == null || host.isBlank()) {
			return Optional.empty();
		}
		host = host.trim();
		int embeddedPort = portSuffix(host);
		if (embeddedPort > 0) {
			String hostPart = host.substring(0, host.lastIndexOf(':'));
			return Optional.of(new Ingest(hostPart, embeddedPort, def.streamKey));
		}
		return Optional.of(new Ingest(host, def.rtmpPort, def.streamKey));
	}

	/**
	 * Extracts a trailing {@code :<port>} from a bare authority, or {@code -1}
	 * when there is no valid 1..65535 numeric port suffix (a portless host, or a
	 * value whose text after the last colon is not a plain in-range port - e.g.
	 * an unbracketed IPv6 literal, which then falls through to the disc port).
	 */
	private static int portSuffix(String authority) {
		int colon = authority.lastIndexOf(':');
		if (colon <= 0 || colon >= authority.length() - 1) {
			return -1;
		}
		String portStr = authority.substring(colon + 1);
		if (portStr.length() > 5) {
			return -1;
		}
		for (int i = 0; i < portStr.length(); i++) {
			if (!Character.isDigit(portStr.charAt(i))) {
				return -1;
			}
		}
		int port = Integer.parseInt(portStr);
		return (port >= 1 && port <= 65535) ? port : -1;
	}
}
