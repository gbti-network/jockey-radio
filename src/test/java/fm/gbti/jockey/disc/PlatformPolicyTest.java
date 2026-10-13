package fm.gbti.jockey.disc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** YouTube links are refused in every form; other platforms are not affected. */
class PlatformPolicyTest {

	@Test
	void youtubeLinksAreRefused() {
		assertTrue(PlatformPolicy.isRefused("https://www.youtube.com/watch?v=eURPTdaO3ck"));
		assertTrue(PlatformPolicy.isRefused("https://youtube.com/watch?v=eURPTdaO3ck"));
		assertTrue(PlatformPolicy.isRefused("https://m.youtube.com/watch?v=eURPTdaO3ck"));
		assertTrue(PlatformPolicy.isRefused("https://music.youtube.com/watch?v=eURPTdaO3ck"));
		assertTrue(PlatformPolicy.isRefused("https://youtu.be/eURPTdaO3ck"));
		assertTrue(PlatformPolicy.isRefused("https://www.youtube-nocookie.com/embed/eURPTdaO3ck"));
		assertTrue(PlatformPolicy.isRefused("HTTPS://WWW.YOUTUBE.COM/@LofiGirl"));
		assertTrue(PlatformPolicy.isRefused("www.youtube.com/watch?v=eURPTdaO3ck"));
		assertTrue(PlatformPolicy.isRefused("youtu.be/eURPTdaO3ck"));
		assertTrue(PlatformPolicy.isRefused("https://www.youtube.com./watch?v=eURPTdaO3ck"));
	}

	@Test
	void otherPlatformsAndLookalikesAreNot() {
		assertFalse(PlatformPolicy.isRefused("https://www.twitch.tv/theburntpeanut_247"));
		assertFalse(PlatformPolicy.isRefused("https://kick.com/savepointapp"));
		assertFalse(PlatformPolicy.isRefused("https://soundcloud.com/john-digweed/john-digweed-transitions-1138"));
		assertFalse(PlatformPolicy.isRefused("https://notyoutube.com/watch"));
		assertFalse(PlatformPolicy.isRefused("https://youtube.com.example.net/x"));
		assertFalse(PlatformPolicy.isRefused(null));
		assertFalse(PlatformPolicy.isRefused("not a url"));
		assertFalse(PlatformPolicy.isRefused("kick.com/savepointapp"));
	}
}
