package com.cadapp;

import android.media.*;

public class CadSound {
    public static boolean sfxEnabled = true;
    public static int bgmMode = 0; // 0:关, 1:五声音阶, 2:超级玛丽
    private static volatile int bgmToken = 0;

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
                at.write(buf, 0, total); at.play(); Thread.sleep(msEach * freqs.length + 40); at.release();
            } catch (Throwable ignored) {}
        }).start();
    }

    public static void snapEnd() { playTone(new double[]{1320}, 35, 0.25f); }
    public static void snapMid() { playTone(new double[]{990}, 35, 0.22f); }
    public static void confirm() { playTone(new double[]{660, 880}, 45, 0.25f); }
    public static void success() { playTone(new double[]{987.77, 1318.51}, 65, 0.24f); } // 吃金币 B5->E6

    public static void setBgmMode(int mode) {
        bgmMode = mode % 3;
        if (bgmMode == 0) stopBgm(); else startBgm();
    }
    public static boolean isBgmPlaying() { return bgmMode != 0; }
    public static void stopBgm() { bgmToken++; }

    public static void startBgm() {
        if (bgmMode == 0) return;
        final int mode = bgmMode, myTok = ++bgmToken;
        new Thread(() -> {
            int sr = 16000;
            double[] pent = {261.63, 329.63, 392.00, 523.25, 440.00, 392.00, 329.63, 293.66, 329.63, 392.00, 329.63, 261.63};
            double[][] mario = {
                {659.25,130},{659.25,130},{0,130},{659.25,130},{0,130},{523.25,130},{659.25,130},{0,130},{783.99,260},{0,260},{392.00,260},{0,260},
                {523.25,200},{0,130},{392.00,200},{0,130},{329.63,200},{0,130},{440.00,150},{0,60},{493.88,150},{0,60},{466.16,130},{440.00,200},{0,60},
                {392.00,160},{659.25,160},{783.99,160},{880.00,200},{0,60},{698.46,130},{783.99,130},{0,130},{659.25,150},{0,60},{523.25,130},{587.33,130},{493.88,220},{0,200}
            };
            int minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            AudioTrack at = new AudioTrack(AudioManager.STREAM_MUSIC, sr, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf, 4096), AudioTrack.MODE_STREAM);
            try {
                at.play(); int idx = 0;
                while (bgmToken == myTok) {
                    double f = (mode == 2) ? mario[idx % mario.length][0] : pent[idx % pent.length];
                    int ms = (mode == 2) ? (int) mario[idx % mario.length][1] : 420;
                    int step = sr * ms / 1000;
                    short[] chunk = new short[step];
                    for (int i = 0; i < step; i++) {
                        double env = Math.min(1.0, i / 120.0) * Math.exp((mode == 2 ? -1.8 : -2.8) * i / step);
                        double w = (f <= 0) ? 0 : (mode == 2
                                ? (Math.sin(2.0 * Math.PI * f * i / sr) >= 0 ? 0.65 : -0.65)
                                : (0.75 * Math.sin(2.0 * Math.PI * f * i / sr) + 0.25 * Math.sin(4.0 * Math.PI * f * i / sr)));
                        chunk[i] = (short) (w * 32767 * (mode == 2 ? 0.11 : 0.14) * env);
                    }
                    at.write(chunk, 0, step); idx++;
                }
            } catch (Throwable ignored) {}
            try { at.stop(); at.release(); } catch (Throwable ignored) {}
        }).start();
    }
}
