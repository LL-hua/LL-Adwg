package com.cadapp;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.view.View;

public class LocationOverlayView extends View {
    private CadGLSurfaceView cadView;
    private boolean hasLocation = false;
    private double curCadX = 0, curCadY = 0;
    private float bearing = 0f;
    private String infoText = "";

    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LocationOverlayView(Context c, CadGLSurfaceView cadView) {
        super(c);
        this.cadView = cadView;
        setBackgroundColor(Color.TRANSPARENT);
        haloPaint.setColor(0x331E88E5); haloPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(0xFF1E88E5); dotPaint.setStyle(Paint.Style.FILL);
        borderPaint.setColor(Color.WHITE); borderPaint.setStyle(Paint.Style.STROKE); borderPaint.setStrokeWidth(5f);
        textPaint.setColor(Color.WHITE); textPaint.setTextSize(32f);
        bgPaint.setColor(0xB8000000); bgPaint.setStyle(Paint.Style.FILL);
    }

    public void updateLocation(double cadX, double cadY, float bearingDeg, String label) {
        this.curCadX = cadX; this.curCadY = cadY; this.bearing = bearingDeg;
        this.infoText = label != null ? label : String.format("N: %.3f\nE: %.3f\n海拔: --", cadY, cadX);
        this.hasLocation = true;
        postInvalidate();
    }

    public boolean hasLocation() { return hasLocation; }
    public double getCadX() { return curCadX; }
    public double getCadY() { return curCadY; }

    @Override public boolean onTouchEvent(MotionEvent event) { return false; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!hasLocation || cadView == null) return;

        float[] pt = cadView.cadToScreen(curCadX, curCadY);
        float sx = pt[0], sy = pt[1];

        canvas.drawCircle(sx, sy, 48f, haloPaint);

        canvas.save();
        canvas.translate(sx, sy);
        canvas.rotate(bearing);
        Path arrow = new Path();
        arrow.moveTo(0f, -38f); arrow.lineTo(-14f, -14f); arrow.lineTo(14f, -14f); arrow.close();
        canvas.drawPath(arrow, dotPaint);
        canvas.restore();

        canvas.drawCircle(sx, sy, 18f, dotPaint);
        canvas.drawCircle(sx, sy, 18f, borderPaint);

        if (!infoText.isEmpty()) {
            String[] lines = infoText.split("\n");
            float maxW = 0f;
            for (String ln : lines) maxW = Math.max(maxW, textPaint.measureText(ln));
            float lineH = 40f, padX = 20f, padY = 14f;
            float boxH = lines.length * lineH + padY * 2;
            float top = sy + 30f;
            RectF box = new RectF(sx - maxW / 2f - padX, top, sx + maxW / 2f + padX, top + boxH);
            canvas.drawRoundRect(box, 14f, 14f, bgPaint);
            for (int i = 0; i < lines.length; i++) {
                canvas.drawText(lines[i], sx - maxW / 2f, top + padY + (i + 1) * lineH - 8f, textPaint);
            }
        }
    }
}
