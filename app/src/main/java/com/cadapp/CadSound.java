package com.cadapp;

import android.media.*;

public class CadSound {
    public static boolean sfxEnabled = true;
    private static volatile boolean bgmRunning = false;
    private static Thread bgmThread;

    public static void playTone(final double[] freqs, final int msEach, final float vol) {
        if (!sfxEnabled) return;
        new Thread(() -> {
            try {
                int sr = 16000, nPer = sr * msEach / 1000, total = nPer * freqs.length;
                short[] buf = new short[total];
                for (int k = 0; k < freqs.length; k++) {
                    for (int i = 0; i < nPer; i++) {
                        double env = Math.min(1.0, i / 80.0) * Math.min(1.0, (nPer - i) / 120.0);
                        buf[k * nPer + i] = (short) (Math.sin(2.0 * Math.PI * freqs[k] * i / sr) * 32767 * vol * env);
                    }
                }
                AudioTrack at = new AudioTrack(AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, total * 2, AudioTrack.MODE_STATIC);
                at.write(buf, 0, total);
                at.play();
                Thread.sleep(msEach * freqs.length + 40);
                at.release();
            } catch (Throwable ignored) {}
        }).start();
    }

    public static void snapEnd() { playTone(new double[]{1320}, 35, 0.25f); }
    public static void snapMid() { playTone(new double[]{990}, 35, 0.22f); }
    public static void confirm() { playTone(new double[]{660, 880}, 45, 0.25f); }
    public static void success() { playTone(new double[]{523.25, 659.25, 783.99}, 55, 0.25f); }

    public static void toggleBgm() { if (bgmRunning) stopBgm(); else startBgm(); }
    public static boolean isBgmPlaying() { return bgmRunning; }

    public static void startBgm() {
        if (bgmRunning) return;
        bgmRunning = true;
        bgmThread = new Thread(() -> {
            int sr = 16000;
            double[] notes = {261.63, 329.63, 392.00, 523.25, 440.00, 392.00, 329.63, 293.66, 329.63, 392.00, 329.63, 261.63};
            int minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack at = new AudioTrack(AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, 4096), AudioTrack.MODE_STREAM);
            try {
                at.play();
                int idx = 0, step = sr * 420 / 1000;
                short[] chunk = new short[step];
                while (bgmRunning) {
                    double f = notes[idx % notes.length];
                    for (int i = 0; i < step; i++) {
                        double env = Math.min(1.0, i / 400.0) * Math.exp(-2.8 * i / step);
                        double wave = 0.75 * Math.sin(2.0 * Math.PI * f * i / sr) + 0.25 * Math.sin(4.0 * Math.PI * f * i / sr);
                        chunk[i] = (short) (wave * 32767 * 0.14 * env);
                    }
                    at.write(chunk, 0, step);
                    idx++;
                }
            } catch (Throwable ignored) {}
            try { at.stop(); at.release(); } catch (Throwable ignored) {}
        });
        bgmThread.start();
    }

    public static void stopBgm() { bgmRunning = false; }
}
