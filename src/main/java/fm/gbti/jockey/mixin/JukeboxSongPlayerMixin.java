package fm.gbti.jockey.mixin;

import fm.gbti.jockey.JockeyRadioMod;
import fm.gbti.jockey.disc.RadioDiscFactory;
import fm.gbti.jockey.session.RadioManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.JukeboxSongPlayer;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects the song-elapsed case: vanilla's {@code JukeboxSongPlayer.tick}
 * calls {@code stop} when the song passes {@code length_in_seconds} while the
 * disc is still in the jukebox. Radio sessions are keyed to disc presence, so
 * the session must survive - this hook only emits the keepalive log line.
 *
 * <p>The TAIL injection sits after the {@code song == null} early return, so
 * it fires only when a song actually stopped. Eject and break paths clear the
 * jukebox item before {@code stop} runs, so the disc-still-present check in
 * {@link RadioManager#onVanillaSongStopped} filters them out naturally.</p>
 *
 * <p>The {@code tick} HEAD injection keeps the note-particle animation infinite
 * for a live radio disc ONLY: vanilla stops a song (and its particles) once
 * {@code ticksSinceSongStarted} passes {@code length_in_seconds}, but a radio is
 * a continuous stream, so for our {@link RadioDiscFactory#RADIO_SONG_KEY} song we
 * loop the counter back to {@code 0} just before it reaches the length, so
 * {@code hasFinished} (which triggers at {@code lengthInTicks + 20}) never fires
 * and {@code stop} is never called. The counter stays bounded in
 * {@code [0, lengthInTicks)}, so a persisted value is always restorable by
 * {@code setSongWithoutPlaying} after a restart. Gated strictly on the radio
 * song key, so vanilla discs are 100% unaffected; we never call
 * {@code play}/{@code stop} from here (no re-toast, no re-sound).</p>
 */
@Mixin(JukeboxSongPlayer.class)
public abstract class JukeboxSongPlayerMixin {

	@Shadow
	@Final
	private BlockPos blockPos;

	@Shadow
	private long ticksSinceSongStarted;

	@Shadow
	private Holder<JukeboxSong> song;

	@Inject(method = "tick", at = @At("HEAD"))
	private void jockey_radio$beforeTick(LevelAccessor level, BlockState state, CallbackInfo ci) {
		try {
			Holder<JukeboxSong> current = this.song;
			if (!RadioDiscFactory.isRadioSong(current)) {
				// not a radio disc (or no song loaded): vanilla behaviour untouched. isRadioSong
				// matches BOTH the current song and the pre-rename legacy song, so discs crafted
				// before the rename get the same infinite-particle handling.
				return;
			}
			long lengthInTicks = current.value().lengthInTicks();
			// sow-012: a repeat-off finite disc (FILE / non-live VOD) that played to its end. The radio
			// song is an hour long, so merely NOT looping the counter would leave the note particles +
			// comparator "playing" for up to ~1h after the sound stopped. Instead DRIVE the counter past
			// vanilla's +20 finish threshold so vanilla's tick calls stop() now → particles stop →
			// afterStop → onVanillaSongStopped. One-shot: once vanilla stops, song is null and this hook
			// returns early. The session stays parked-but-silent (isFinished latched on the session).
			if (level instanceof ServerLevel serverLevel
					&& RadioManager.get().sessionFinishedAt(serverLevel, this.blockPos)) {
				if (this.ticksSinceSongStarted < lengthInTicks) {
					this.ticksSinceSongStarted = lengthInTicks + 40L;
				}
				return;
			}
			// A live / repeat-on radio disc: loop the counter before the +20 finish threshold so vanilla
			// never times it out - particles emit forever. Bounded to [0, lengthInTicks) is reload-safe.
			if (this.ticksSinceSongStarted >= lengthInTicks) {
				this.ticksSinceSongStarted = 0L;
			}
		} catch (Throwable t) {
			JockeyRadioMod.LOGGER.error("[jockey] JukeboxSongPlayer.tick hook failed", t);
		}
	}

	@Inject(method = "stop", at = @At("TAIL"))
	private void jockey_radio$afterStop(LevelAccessor level, BlockState state, CallbackInfo ci) {
		try {
			if (level instanceof ServerLevel serverLevel) {
				RadioManager.get().onVanillaSongStopped(serverLevel, blockPos);
			}
		} catch (Throwable t) {
			JockeyRadioMod.LOGGER.error("[jockey] JukeboxSongPlayer.stop hook failed", t);
		}
	}
}
