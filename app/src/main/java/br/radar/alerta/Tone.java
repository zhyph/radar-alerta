package br.radar.alerta;

import java.io.ByteArrayOutputStream;

/** Generates the alert sounds as raw PCM so there are no audio assets and they can be unit-tested. */
final class Tone {
    static final int SAMPLE_RATE = 44_100;

    /** Selectable sounds, in the order shown in settings. */
    static final String[] IDS = {"siren", "klaxon", "chirp", "pulse", "doorbell", "ping"};
    static final String DEFAULT = "siren";

    private static final int FADE = 4 * SAMPLE_RATE / 1000; // 4 ms ramp: no clicks at starts and ends

    private Tone() { }

    static short[] pcm(String id) {
        Builder b = new Builder();
        switch (id) {
            case "klaxon":   // three low, harsh blasts
                for (int i = 0; i < 3; i++) {
                    b.square(480, 330, 1.0).silence(110);
                    b.square(360, 330, 1.0).silence(140);
                }
                break;
            case "chirp":    // rising radar-detector sweeps
                for (int i = 0; i < 4; i++) b.sweep(900, 3200, 220).silence(120);
                break;
            case "pulse":    // rapid piercing beeps
                for (int i = 0; i < 10; i++) b.square(2800, 70, 1.0).silence(60);
                break;
            case "doorbell": // loud two-tone, twice
                for (int i = 0; i < 2; i++) b.sine(988, 380, 1.0).sine(740, 520, 1.0).silence(180);
                break;
            case "ping":     // one soft ping, for a gentle option
                b.sine(1320, 160, 0.9).silence(80).sine(1760, 420, 0.7);
                break;
            default:         // "siren": alternating 2400 / 1600 Hz beeps, played twice
                for (int burst = 0; burst < 2; burst++) {
                    for (int i = 0; i < 6; i++) b.square(i % 2 == 0 ? 2400 : 1600, 110, 1.0).silence(40);
                    b.silence(250);
                }
        }
        return b.build();
    }

    /**
     * The alert for one camera kind, so they can be told apart by ear: speed cameras play the chosen
     * sound once, red-light cameras play it twice, average-speed sections play it lower and slower.
     */
    static short[] pcm(String id, CameraStore.Kind kind) {
        short[] base = pcm(id);
        if (kind == CameraStore.Kind.RED_LIGHT) {
            int gap = 300 * SAMPLE_RATE / 1000;
            short[] twice = new short[base.length * 2 + gap];
            System.arraycopy(base, 0, twice, 0, base.length);
            System.arraycopy(base, 0, twice, base.length + gap, base.length);
            return twice;
        }
        if (kind == CameraStore.Kind.AVERAGE) {
            short[] slow = new short[base.length * 4 / 3];   // resampled at 0.75x: lower pitch, longer
            for (int i = 0; i < slow.length; i++) slow[i] = base[(int) Math.min(base.length - 1, i * 3L / 4)];
            return slow;
        }
        return base;
    }

    static int durationMs(short[] pcm) {
        return (int) (pcm.length * 1000L / SAMPLE_RATE);
    }

    /** Appends tones to a growing buffer. */
    private static final class Builder {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Builder silence(int ms) {
            for (int i = 0; i < ms * SAMPLE_RATE / 1000; i++) put(0);
            return this;
        }

        /** Sine wave. */
        Builder sine(double hz, int ms, double gain) {
            return wave(hz, hz, ms, gain, 1.0);
        }

        /** Overdriven sine, which sounds like a harsh square wave and cuts through road noise. */
        Builder square(double hz, int ms, double gain) {
            return wave(hz, hz, ms, gain, 1.8);
        }

        /** Frequency sweep from hz0 to hz1. */
        Builder sweep(double hz0, double hz1, int ms) {
            return wave(hz0, hz1, ms, 1.0, 1.4);
        }

        private Builder wave(double hz0, double hz1, int ms, double gain, double drive) {
            int n = ms * SAMPLE_RATE / 1000;
            double phase = 0;
            for (int s = 0; s < n; s++) {
                double hz = hz0 + (hz1 - hz0) * s / n;
                phase += 2 * Math.PI * hz / SAMPLE_RATE;
                double v = Math.max(-1, Math.min(1, Math.sin(phase) * drive));
                double env = Math.min(1.0, Math.min(s, n - 1 - s) / (double) FADE);
                put((int) (v * env * gain * 30_000));
            }
            return this;
        }

        private void put(int sample) {
            bytes.write(sample & 0xFF);
            bytes.write((sample >> 8) & 0xFF);
        }

        short[] build() {
            byte[] b = bytes.toByteArray();
            short[] pcm = new short[b.length / 2];
            for (int i = 0; i < pcm.length; i++) pcm[i] = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
            return pcm;
        }
    }
}
