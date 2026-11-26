package com.cadapp;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.*;
import android.view.*;
import java.util.*;

public class MusicBoxDialog {
    private static final double[] SCALE = {261.63, 293.66, 329.63, 392.00, 440.00, 523.25, 587.33, 659.25, 783.99, 880.00};
    private static final int[] COLS = {0xFF00E5FF, 0xFF76FF03, 0xFFFFEA00, 0xFFFF4081, 0xFFE040FB, 0xFF18FFFF};

    static class Orb {
        float x, y, vx, vy, r; int col, note; long lastHit;
        List<float[]> trail = new ArrayList<>();
    }

    public static void show(Context ctx) {
        final List<Orb> orbs = new ArrayList<>();
        final Random rnd = new Random();
        final float[] grav = {0f, 3.5f};
        final SensorManager sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        final Sensor acc = sm != null ? sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;

        View canvasView = new View(ctx) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                for (int i = 0; i < 9; i++) addOrb(200 + i * 65, 300 + (i % 3) * 120, i);
            }
            void addOrb(float x, float y, int idx) {
                if (orbs.size() > 24) orbs.remove(0);
                Orb o = new Orb();
                o.x = x; o.y = y; o.vx = (rnd.nextFloat() - 0.5f) * 14f; o.vy = (rnd.nextFloat() - 0.5f) * 14f;
                o.r = 16f + rnd.nextInt(10); o.col = COLS[idx % COLS.length]; o.note = idx % SCALE.length;
                orbs.add(o);
            }
            @Override protected void onDraw(Canvas c) {
                int w = getWidth(), h = getHeight();
                c.drawColor(0xFF0A0E17);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2f); p.setColor(0x2200E5FF);
                for (int g = 80; g < Math.max(w, h); g += 80) { c.drawLine(g, 0, g, h, p); c.drawLine(0, g, w, g, p); }
                float barY = h * 0.52f, barX1 = w * 0.22f, barX2 = w * 0.78f;
                p.setStrokeWidth(6f); p.setColor(0xAA00E5FF);
                c.drawLine(barX1, barY, barX2, barY, p);

                long now = System.currentTimeMillis();
                for (Orb o : orbs) {
                    o.vx += grav[0] * 0.28f; o.vy += grav[1] * 0.28f;
                    o.vx *= 0.994f; o.vy *= 0.994f;
                    o.x += o.vx; o.y += o.vy;
                    boolean hit = false;
                    if (o.x < o.r) { o.x = o.r; o.vx = Math.abs(o.vx) * 0.92f; hit = Math.abs(o.vx) > 3.5f; }
                    else if (o.x > w - o.r) { o.x = w - o.r; o.vx = -Math.abs(o.vx) * 0.92f; hit = Math.abs(o.vx) > 3.5f; }
                    if (o.y < o.r) { o.y = o.r; o.vy = Math.abs(o.vy) * 0.92f; hit = Math.abs(o.vy) > 3.5f; }
                    else if (o.y > h - o.r) { o.y = h - o.r; o.vy = -Math.abs(o.vy) * 0.92f; hit = Math.abs(o.vy) > 3.5f; }
                    if (o.x >= barX1 && o.x <= barX2 && Math.abs(o.y - barY) < o.r + 4f) {
                        o.vy = (o.y < barY) ? -Math.abs(o.vy) * 0.96f - 2f : Math.abs(o.vy) * 0.96f + 2f;
                        o.y = (o.y < barY) ? barY - o.r - 5f : barY + o.r + 5f;
                        hit = true;
                    }
                    if (hit && now - o.lastHit > 160) {
                        o.lastHit = now;
                        int ni = Math.min(SCALE.length - 1, Math.max(0, (int) ((o.x / Math.max(1f, w)) * SCALE.length)));
                        CadSound.playTone(new double[]{SCALE[ni]}, 95, 0.20f);
                    }
                    o.trail.add(new float[]{o.x, o.y});
                    if (o.trail.size() > 10) o.trail.remove(0);
                    p.setStyle(Paint.Style.FILL);
                    for (int t = 0; t < o.trail.size(); t++) {
                        int alpha = (int) (130f * t / o.trail.size());
                        p.setColor((alpha << 24) | (o.col & 0x00FFFFFF));
                        c.drawCircle(o.trail.get(t)[0], o.trail.get(t)[1], o.r * (0.3f + 0.6f * t / o.trail.size()), p);
                    }
                    p.setColor(o.col); c.drawCircle(o.x, o.y, o.r, p);
                    p.setColor(0xFFFFFFFF); c.drawCircle(o.x - o.r * 0.3f, o.y - o.r * 0.3f, o.r * 0.25f, p);
                }
                postInvalidateOnAnimation();
            }
            @Override public boolean onTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    addOrb(e.getX(), e.getY(), rnd.nextInt(10));
                    int ni = Math.min(SCALE.length - 1, Math.max(0, (int) ((e.getX() / Math.max(1f, getWidth())) * SCALE.length)));
                    CadSound.playTone(new double[]{SCALE[ni]}, 80, 0.18f);
                }
                return true;
            }
        };

        final SensorEventListener sl = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent ev) { grav[0] = -ev.values[0]; grav[1] = ev.values[1]; }
            @Override public void onAccuracyChanged(Sensor s, int a) {}
        };
        if (sm != null && acc != null) sm.registerListener(sl, acc, SensorManager.SENSOR_DELAY_GAME);

        AlertDialog dlg = new AlertDialog.Builder(ctx)
            .setTitle("🌌 重力感应光点音律盒 (倾斜/触摸演奏)")
            .setView(canvasView)
            .setNeutralButton("摇一摇", null)
            .setPositiveButton("关闭", null)
            .create();
        dlg.setOnDismissListener(d -> { if (sm != null) sm.unregisterListener(sl); });
        dlg.show();
        if (dlg.getWindow() != null) dlg.getWindow().setLayout(-1, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.72f));
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            for (Orb o : orbs) { o.vx = (rnd.nextFloat() - 0.5f) * 32f; o.vy = -12f - rnd.nextFloat() * 16f; }
        });
    }
}
