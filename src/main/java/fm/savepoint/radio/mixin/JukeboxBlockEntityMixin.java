package fm.savepoint.radio.mixin;

import fm.savepoint.radio.SavePointRadioMod;
import fm.savepoint.radio.session.RadioManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Disc-presence hooks: these five injections are the source of truth for
 * radio session lifecycles (see {@link RadioManager}).
 *
 * <ul>
 *   <li>{@code setTheItem} TAIL - disc inserted, ejected or replaced (hoppers
 *       and {@code popOutTheItem} funnel through here via
 *       {@code splitTheItem}).</li>
 *   <li>{@code setSongItemWithoutPlaying} TAIL - defensive catch-all for
 *       modded callers only. Vanilla 26.2 never calls this method:
 *       {@code loadAdditional} writes the item field directly and drives the
 *       song player through a lambda. The authoritative world-load/restart
 *       resume path is Fabric's {@code BLOCK_ENTITY_LOAD} plus the deferred
 *       end-of-tick disc check in {@link RadioManager}.</li>
 *   <li>{@code loadAdditional} TAIL - in-place NBT reloads
 *       ({@code /data merge|remove block}) bypass {@code setTheItem} and write
 *       the item field directly; reconcile afterwards. On the normal
 *       chunk-load path the block entity has no level yet, so the
 *       {@code ServerLevel} guard makes this a no-op there.</li>
 *   <li>{@code preRemoveSideEffects} HEAD - block break, before vanilla pops
 *       the disc out, so the stop reason is {@code broken} not
 *       {@code ejected}.</li>
 *   <li>{@code setRemoved} HEAD - defensive backstop for removal paths that
 *       skip {@code preRemoveSideEffects}. Chunk unloads also call
 *       {@code setRemoved}, so the manager only stops the session when the
 *       block is actually gone.</li>
 * </ul>
 *
 * <p>Every body is wrapped in try/catch: a radio bug must never break vanilla
 * jukeboxes. All hooks act server-side only.</p>
 */
@Mixin(JukeboxBlockEntity.class)
public abstract class JukeboxBlockEntityMixin {

	@Inject(method = "setTheItem", at = @At("TAIL"))
	private void travelers_jukebox$afterSetTheItem(ItemStack stack, CallbackInfo ci) {
		try {
			JukeboxBlockEntity self = (JukeboxBlockEntity) (Object) this;
			if (self.getLevel() instanceof ServerLevel level) {
				RadioManager.get().onJukeboxItemChanged(level, self.getBlockPos(), self.getTheItem());
			}
		} catch (Throwable t) {
			SavePointRadioMod.LOGGER.error("[jukebox] setTheItem hook failed", t);
		}
	}

	@Inject(method = "setSongItemWithoutPlaying", at = @At("TAIL"))
	private void travelers_jukebox$afterSetSongItemWithoutPlaying(ItemStack stack, CallbackInfo ci) {
		try {
			JukeboxBlockEntity self = (JukeboxBlockEntity) (Object) this;
			if (self.getLevel() instanceof ServerLevel level) {
				RadioManager.get().onJukeboxItemChanged(level, self.getBlockPos(), self.getTheItem());
			}
		} catch (Throwable t) {
			SavePointRadioMod.LOGGER.error("[jukebox] setSongItemWithoutPlaying hook failed", t);
		}
	}

	@Inject(method = "loadAdditional", at = @At("TAIL"))
	private void travelers_jukebox$afterLoadAdditional(ValueInput input, CallbackInfo ci) {
		try {
			JukeboxBlockEntity self = (JukeboxBlockEntity) (Object) this;
			if (self.getLevel() instanceof ServerLevel level) {
				RadioManager.get().onJukeboxItemChanged(level, self.getBlockPos(), self.getTheItem());
			}
		} catch (Throwable t) {
			SavePointRadioMod.LOGGER.error("[jukebox] loadAdditional hook failed", t);
		}
	}

	@Inject(method = "preRemoveSideEffects", at = @At("HEAD"))
	private void travelers_jukebox$beforeRemoveSideEffects(BlockPos pos, BlockState state, CallbackInfo ci) {
		try {
			JukeboxBlockEntity self = (JukeboxBlockEntity) (Object) this;
			if (self.getLevel() instanceof ServerLevel level) {
				RadioManager.get().onJukeboxRemoved(level, pos);
			}
		} catch (Throwable t) {
			SavePointRadioMod.LOGGER.error("[jukebox] preRemoveSideEffects hook failed", t);
		}
	}

	@Inject(method = "setRemoved", at = @At("HEAD"))
	private void travelers_jukebox$onSetRemoved(CallbackInfo ci) {
		try {
			JukeboxBlockEntity self = (JukeboxBlockEntity) (Object) this;
			if (self.getLevel() instanceof ServerLevel level) {
				RadioManager.get().onJukeboxSetRemoved(level, self.getBlockPos());
			}
		} catch (Throwable t) {
			SavePointRadioMod.LOGGER.error("[jukebox] setRemoved hook failed", t);
		}
	}
}
