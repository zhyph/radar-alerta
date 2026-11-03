package br.radar.alerta;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

/** Plays the siren, ducking other audio (music, Maps voice) while it sounds. */
final class AlertSound {
    private static final AudioAttributes ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    private final AudioManager audioManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioTrack track;
    private String trackId = "";
    private int durationMs;
    private AudioFocusRequest focus;

    AlertSound(Context context) {
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    /** (Re)builds the audio track when a different sound is requested. */
    private void load(String id) {
        if (id.equals(trackId) && track != null) return;
        if (track != null) track.release();
        short[] pcm = Tone.pcm(id);
        durationMs = Tone.durationMs(pcm);
        track = new AudioTrack.Builder()
                .setAudioAttributes(ATTRS)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(Tone.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2)
                .build();
        track.write(pcm, 0, pcm.length);
        trackId = id;
    }

    void play(String id) {
        load(id);
        abandonFocus();
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(ATTRS).build();
        audioManager.requestAudioFocus(focus);
        track.stop();
        track.reloadStaticData();
        track.play();
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::abandonFocus, durationMs + 200L);
    }

    void release() {
        handler.removeCallbacksAndMessages(null);
        if (track != null) track.release();
        abandonFocus();
    }

    private void abandonFocus() {
        if (focus != null) {
            audioManager.abandonAudioFocusRequest(focus);
            focus = null;
        }
    }
}
