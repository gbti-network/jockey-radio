package fm.gbti.jockey;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/**
 * Admin audit trail for disc registry mutations. Every create/edit/delete/give
 * that goes through the {@code /jocky} dialog router or the commands emits
 * exactly one line so who-did-what is recoverable from the server log:
 *
 * <pre>[jockey] admin &lt;action&gt; disc=&lt;id&gt; by=&lt;name&gt;/&lt;uuid&gt;</pre>
 *
 * <p>Give also records the recipient. Secrets ({@code token}, {@code streamKey})
 * are never part of an audit line - only the disc id, the actor and (for give)
 * the target appear.</p>
 */
public final class RadioAudit {

	private RadioAudit() {
	}

	/** {@code name/uuid} label for a player actor. */
	public static String actor(ServerPlayer player) {
		return player.getName().getString() + "/" + player.getUUID();
	}

	/**
	 * Actor label for a command source: {@code name/uuid} for a player, or the
	 * source's text name (e.g. {@code Server}) for the console/command blocks.
	 */
	public static String actor(CommandSourceStack source) {
		ServerPlayer player = source.getPlayer();
		return player != null ? actor(player) : source.getTextName();
	}

	/** One audit line for a create/edit/delete action. */
	public static void log(String action, String discId, String actor) {
		JockeyRadioMod.LOGGER.info("[jockey] admin {} disc={} by={}", action, discId, actor);
	}

	/** One audit line for a give, recording both the actor and the recipient. */
	public static void logGive(String discId, String actor, String target) {
		JockeyRadioMod.LOGGER.info("[jockey] admin give disc={} by={} target={}", discId, actor, target);
	}
}
