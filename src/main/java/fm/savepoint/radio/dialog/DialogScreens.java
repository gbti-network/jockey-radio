package fm.savepoint.radio.dialog;

import net.minecraft.core.Holder;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.network.protocol.common.ClientboundClearDialogPacket;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.level.ServerPlayer;

/**
 * Thin transport helper for the {@code /jukebox} dialog UI.
 *
 * <p>A dialog is pushed to a single player as an <em>inline</em>
 * {@link Holder#direct(Object) direct} holder - it never needs a registry entry
 * (the {@code Dialog.STREAM_CODEC} encodes a direct holder as {@code VarInt 0}
 * followed by the dialog itself). Every send here targets exactly one player's
 * connection and must run on the server thread.</p>
 */
public final class DialogScreens {

	/** Shows {@code dialog} to {@code player}, replacing any open dialog. */
	public static void send(ServerPlayer player, Dialog dialog) {
		player.connection.send(new ClientboundShowDialogPacket(Holder.direct(dialog)));
	}

	/** Clears any open dialog on the player's client. */
	public static void clear(ServerPlayer player) {
		player.connection.send(ClientboundClearDialogPacket.INSTANCE);
	}

	private DialogScreens() {
	}
}
