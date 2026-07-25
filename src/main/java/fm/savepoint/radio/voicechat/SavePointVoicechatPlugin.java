package fm.savepoint.radio.voicechat;

import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent;
import fm.savepoint.radio.SavePointRadioMod;
import fm.savepoint.radio.session.RadioManager;
import net.minecraft.server.MinecraftServer;

/**
 * Simple Voice Chat plugin entrypoint, declared under the {@code "voicechat"}
 * key in fabric.mod.json. SVC scans that Fabric entrypoint and instantiates
 * this class itself, so it is only ever loaded when the voicechat mod is
 * present - it may therefore import SVC API types freely.
 *
 * <p>{@code initialize} only hands over the base {@link VoicechatApi}; the
 * {@link VoicechatServerApi} needed for channels arrives via
 * {@link VoicechatServerStartedEvent} (fired after the Minecraft server is up,
 * i.e. AFTER world load has already resumed radio sessions with null sinks).
 * The started handler therefore also schedules
 * {@link RadioManager#reopenActiveSinks(String)} on the server thread so those
 * sessions upgrade to real voicechat sinks.</p>
 */
public final class SavePointVoicechatPlugin implements VoicechatPlugin {

	@Override
	public String getPluginId() {
		return SavePointRadioMod.MOD_ID;
	}

	@Override
	public void initialize(VoicechatApi api) {
		SavePointRadioMod.LOGGER.info("[jukebox] voicechat plugin initialized id={}", SavePointRadioMod.MOD_ID);
	}

	@Override
	public void registerEvents(EventRegistration registration) {
		registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
		registration.registerEvent(VoicechatServerStoppedEvent.class, this::onServerStopped);
	}

	private void onServerStarted(VoicechatServerStartedEvent event) {
		VoicechatServerApi api = event.getVoicechat();
		boolean categoryOk = registerCategory(api);
		VoiceChatBackend.setServerApi(api, categoryOk);
		SavePointRadioMod.LOGGER.info("[jukebox] voicechat server api ready category={}",
				categoryOk ? VoiceChatBackend.CATEGORY_ID : "unregistered");
		// Sessions resumed during world load are running on null sinks; hop to
		// the server thread (the event's thread is SVC's business) and upgrade.
		MinecraftServer server = RadioManager.get().currentServer();
		if (server != null) {
			server.execute(() -> RadioManager.get().reopenActiveSinks("voicechat-ready"));
		}
	}

	private void onServerStopped(VoicechatServerStoppedEvent event) {
		VoiceChatBackend.clear();
		SavePointRadioMod.LOGGER.info("[jukebox] voicechat server api stopped");
	}

	/**
	 * Registers the "Traveler's Jukebox" volume category so clients get a
	 * dedicated volume slider for radio audio. No icon (SVC renders a default).
	 * Failure is non-fatal: sinks simply skip {@code setCategory}.
	 */
	private static boolean registerCategory(VoicechatServerApi api) {
		try {
			VolumeCategory category = api.volumeCategoryBuilder()
					.setId(VoiceChatBackend.CATEGORY_ID)
					.setName("Traveler's Jukebox")
					.setDescription("Streaming radio jukebox discs")
					.build();
			api.registerVolumeCategory(category);
			return true;
		} catch (Throwable t) {
			SavePointRadioMod.LOGGER.warn("[jukebox] voicechat volume category registration failed", t);
			return false;
		}
	}
}
