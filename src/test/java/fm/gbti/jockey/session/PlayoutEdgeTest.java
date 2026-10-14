package fm.gbti.jockey.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Playout starts and stops without a click: the delivered stream never jumps between two
 * adjacent samples by more than a smooth waveform does, at the resume after pre-roll or at the
 * underrun when the source runs dry. Runs the real delivery loop on a sine wave.
 */
class PlayoutEdgeTest {

	private static final int SAMPLES = AbstractFfmpegFeed.FRAME_SAMPLES;
	private static final double AMPLITUDE = 12_000;
	/** 110.25 Hz: one second of it ends a quarter-cycle in, so the last sample sits at a peak. */
	private static final double HZ = 110.25;
	/** A 110 Hz sine at this amplitude moves at most ~175 per sample; a click is thousands. */
	private static final int MAX_STEP = 400;

	@Test
	@DisplayName("passthrough playout has no step at the resume or at the underrun")
	void passthroughEdgesAreSmooth() throws Exception {
		assertSmooth(play("rebuffer", 0.0));
	}

	@Test
	@DisplayName("time-stretched playout has no step at the resume or at the underrun")
	void timeStretchEdgesAreSmooth() throws Exception {
		assertSmooth(play("tempo_match", 4.0));
	}

	@Test
	@DisplayName("the fade-out ends a frame at zero and the decay tail starts at the held sample")
	void fadesMeetTheirEnds() {
		short[] loud = new short[SAMPLES];
		java.util.Arrays.fill(loud, (short) 10_000);
		short[] out = FeedDeliveryLoop.fadeOut(loud);
		assertEquals(0, out[SAMPLES - 1]);
		assertTrue(Math.abs(out[0] - 10_000) < 20, "the fade-out starts below the frame it fades");
		assertEquals(10_000, loud[0], "the delivered frame was changed in place");

		short[] tail = FeedDeliveryLoop.decayTail((short) -9_000);
		assertTrue(Math.abs(tail[0] + 9_000) < 60, "the tail does not start at the held sample");
		for (int i = FeedDeliveryLoop.DECAY_TAIL_SAMPLES; i < SAMPLES; i++) {
			assertEquals(0, tail[i], "the tail is not silent after its decay");
		}
	}

	private static void assertSmooth(short[] stream) {
		int worst = 0;
		int at = -1;
		boolean heard = false;
		for (int i = 1; i < stream.length; i++) {
			heard |= stream[i] != 0;
			int step = Math.abs(stream[i] - stream[i - 1]);
			if (step > worst) {
				worst = step;
				at = i;
			}
		}
		assertTrue(heard, "nothing was played");
		assertTrue(worst <= MAX_STEP, "a " + worst + "-step click at sample " + at + " (frame " + at / SAMPLES + ")");
	}

	/** Plays one second of sine through a real delivery loop, then lets it underrun. */
	private static short[] play(String mode, double stretchPercent) throws Exception {
		SilentFeed feed = new SilentFeed(mode, stretchPercent);
		synchronized (feed.bufferLock) {
			for (int f = 0; f < 50; f++) {
				short[] frame = new short[SAMPLES];
				for (int i = 0; i < SAMPLES; i++) {
					long n = (long) f * SAMPLES + i;
					frame[i] = (short) Math.round(AMPLITUDE * Math.sin(2 * Math.PI * HZ * n / 48_000.0));
				}
				feed.buffer.addLast(frame);
			}
		}
		List<short[]> heard = new ArrayList<>();
		feed.addSink(new AudioSink() {
			@Override
			public void open() {
			}

			@Override
			public void accept(short[] frame) {
				synchronized (heard) {
					heard.add(frame.clone());
				}
			}

			@Override
			public void close() {
			}
		});
		feed.running = true;
		Thread delivery = new Thread(new FeedDeliveryLoop(feed)::runDeliveryLoop, "test-delivery");
		delivery.start();
		Thread.sleep(1_600); // 50 frames of audio, the underrun, and some silence after it
		feed.running = false;
		delivery.join(2_000);
		synchronized (heard) {
			short[] stream = new short[heard.size() * SAMPLES];
			for (int f = 0; f < heard.size(); f++) {
				System.arraycopy(heard.get(f), 0, stream, f * SAMPLES, SAMPLES);
			}
			return stream;
		}
	}

	/** A feed with no ingest: the test fills its buffer and runs only the delivery loop. */
	private static final class SilentFeed extends AbstractFfmpegFeed {
		SilentFeed(String mode, double stretchPercent) {
			super("test:edges", "ffmpeg", 600, 60, 50, 5, true, 1, 0, mode, stretchPercent);
		}

		@Override
		protected String[] buildCommand() {
			return new String[] {"ffmpeg"};
		}

		@Override
		protected String startLogDetail() {
			return "edges test";
		}
	}
}
