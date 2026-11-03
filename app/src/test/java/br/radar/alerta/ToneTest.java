package br.radar.alerta;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ToneTest {
    @Test
    public void everySoundIsAudibleAndAboutTheRightLength() {
        for (String id : Tone.IDS) {
            short[] pcm = Tone.pcm(id);
            int peak = 0;
            for (short s : pcm) peak = Math.max(peak, Math.abs(s));
            int ms = Tone.durationMs(pcm);
            assertTrue(id + " peak " + peak, peak > 15_000);          // loud enough to hear in a car
            assertTrue(id + " does not wrap around", peak <= Short.MAX_VALUE);
            assertTrue(id + " length " + ms + " ms", ms >= 500 && ms <= 4_000);
        }
    }

    @Test
    public void sirenIsTwoPointThreeSeconds() {
        assertEquals(2_300, Tone.durationMs(Tone.pcm("siren")), 5); // 2 x (6 x 150 ms + 250 ms)
    }

    @Test
    public void everySoundHasSilenceBetweenItsPartsExceptPing() {
        for (String id : new String[] {"siren", "klaxon", "chirp", "pulse", "doorbell"}) {
            short[] pcm = Tone.pcm(id);
            int zeros = 0;
            for (short s : pcm) if (s == 0) zeros++;
            assertTrue(id + " zeros " + zeros, zeros > pcm.length / 20);
        }
    }

    @Test
    public void unknownIdFallsBackToTheSiren() {
        assertEquals(Tone.pcm("siren").length, Tone.pcm("no-such-sound").length);
    }

    @Test
    public void cameraKindsSoundDifferent() {
        for (String id : Tone.IDS) {
            int one = Tone.pcm(id).length;
            assertEquals(id, one, Tone.pcm(id, CameraStore.Kind.SPEED).length);
            assertEquals(id, one * 2 + 300 * Tone.SAMPLE_RATE / 1000, Tone.pcm(id, CameraStore.Kind.RED_LIGHT).length);
            assertEquals(id, one * 4 / 3, Tone.pcm(id, CameraStore.Kind.AVERAGE).length, 1);
        }
    }
}
