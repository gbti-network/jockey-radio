package fm.savepoint.radio.mixin;

import fm.savepoint.radio.SavePointRadioMod;
import fm.savepoint.radio.dialog.DialogRouter;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes {@code savepoint_radio:*} custom-click actions (the {@code /savepoint}
 * dialog buttons) into {@link DialogRouter}.
 *
 * <p>{@code handleCustomClickAction} is defined only on the common listener, so
 * this mixin targets {@link ServerCommonPacketListenerImpl}. The injection point
 * is the {@code MinecraftServer.handleCustomClickAction} call rather than
 * {@code HEAD}: vanilla's {@code ensureRunningOnSameThread} reschedules onto the
 * main packet-processor thread first, so injecting at {@code HEAD} would fire on
 * the netty thread <em>and</em> again after reschedule. At the invoke point
 * execution is guaranteed on the main server thread, which satisfies the
 * server-thread-only guardrail for registry writes.</p>
 *
 * <p>Only our namespace is intercepted and cancelled (skipping vanilla's
 * harmless debug log); every other namespace falls through untouched. The whole
 * body is wrapped so a handler bug can never drop the connection.</p>
 */
@Mixin(ServerCommonPacketListenerImpl.class)
public abstract class CustomClickActionMixin {

	@Inject(
			method = "handleCustomClickAction",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/MinecraftServer;handleCustomClickAction(Lnet/minecraft/resources/Identifier;Ljava/util/Optional;)V"),
			cancellable = true)
	private void savepoint_radio$onCustomClickAction(ServerboundCustomClickActionPacket packet, CallbackInfo ci) {
		if (!SavePointRadioMod.MOD_ID.equals(packet.id().getNamespace())) {
			// Not ours: let vanilla run (it only debug-logs).
			return;
		}
		try {
			// Custom clicks are only handled during play; the instanceof also
			// excludes the configuration-phase listener (which has no player).
			if ((Object) this instanceof ServerGamePacketListenerImpl game) {
				ServerPlayer player = game.player;
				if (player != null) {
					DialogRouter.handle(player, packet.id(), packet.payload());
				}
			}
		} catch (Throwable t) {
			SavePointRadioMod.LOGGER.error("[radio] dialog custom-click handler failed", t);
		}
		// Always cancel for our namespace so vanilla's server call is skipped.
		ci.cancel();
	}
}
