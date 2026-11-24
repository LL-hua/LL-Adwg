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

    private boolean hasPicked = false;
    private boolean hasMeasureA = false;
    private double measAX = 0, measAY = 0;
    private double pickCadX = 0, pickCadY = 0;
    private String pickSnapType = "";
    private float fingerX = 0, fingerY = 0; private boolean isPickingTouch = false;

    private final Paint haloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint snapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public LocationOverlayView(Context c, CadGLSurfaceView cadView) {
        super(c);
        this.cadView = cadView;
        setBackgroundColor(Color.TRANSPARENT);
        haloPaint.setColor(0x331E88E5); haloPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(0xFF1E88E5); dotPaint.setStyle(Paint.Style.FILL);
        borderPaint.setColor(Color.WHITE); borderPaint.setStyle(Paint.Style.STROKE); borderPaint.setStrokeWidth(5f);
        textPaint.setColor(Color.WHITE); textPaint.setTextSize(32f);
        bgPaint.setColor(0xB8000000); bgPaint.setStyle(Paint.Style.FILL);
        snapPaint.setStyle(Paint.Style.STROKE); snapPaint.setStrokeWidth(4.5f);
        guidePaint.setColor(0xCCFF9800); guidePaint.setStyle(Paint.Style.STROKE); guidePaint.setStrokeWidth(4f);
        guidePaint.setPathEffect(new DashPathEffect(new float[]{18f, 12f}, 0f));
    }

    public void updateLocation(double cadX, double cadY, float bearingDeg, String label) {
        this.curCadX = cadX; this.curCadY = cadY; this.bearing = bearingDeg;
        this.infoText = label != null ? label : String.format("N: %.3f\nE: %.3f\n海拔: --", cadY, cadX);
        this.hasLocation = true;
        postInvalidate();
    }

    public void setPickedPoint(double cadX, double cadY, String snapType, float fx, float fy, boolean touching) {
        this.pickCadX = cadX; this.pickCadY = cadY; this.pickSnapType = snapType;
        this.fingerX = fx; this.fingerY = fy; this.isPickingTouch = touching;
        this.hasPicked = true;
        postInvalidate();
    }

    public void clearPickedPoint() { this.hasPicked = false; postInvalidate(); }
    public void setMeasureStart(double x, double y) { this.measAX = x; this.measAY = y; this.hasMeasureA = true; postInvalidate(); }
    public void clearMeasure() { this.hasMeasureA = false; this.hasPicked = false; postInvalidate(); }
    public boolean hasLocation() { return hasLocation; }
    public double getCadX() { return curCadX; }
    public double getCadY() { return curCadY; }

    @Override public boolean onTouchEvent(MotionEvent event) { return false; }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (cadView == null) return;

        if (hasMeasureA) {
            float[] ap = cadView.cadToScreen(measAX, measAY);
            snapPaint.setColor(0xFFFFEB3B);
            canvas.drawCircle(ap[0], ap[1], 16f, snapPaint);
            if (hasPicked) {
                float[] bp = cadView.cadToScreen(pickCadX, pickCadY);
                guidePaint.setColor(0xFF00E5FF);
                canvas.drawLine(ap[0], ap[1], bp[0], bp[1], guidePaint);
                guidePaint.setColor(0xCCFF9800);
            }
        }
        if (hasPicked) {
            float[] pp = cadView.cadToScreen(pickCadX, pickCadY);
            float px = pp[0], py = pp[1];
            if (hasLocation) {
                float[] lp = cadView.cadToScreen(curCadX, curCadY);
                canvas.drawLine(lp[0], lp[1], px, py, guidePaint);
            }
            if (isPickingTouch) {
                snapPaint.setColor(0xCCFFEB3B);
                canvas.drawLine(fingerX, fingerY, px, py + 28f, snapPaint);
                canvas.drawCircle(fingerX, fingerY, 14f, snapPaint);
                Path tip = new Path();
                tip.moveTo(px, py + 24f); tip.lineTo(px - 10f, py + 42f); tip.lineTo(px + 10f, py + 42f); tip.close();
                canvas.drawPath(tip, snapPaint);
                snapPaint.setColor(0x55FFFFFF);
                canvas.drawCircle(px, py, 46f, snapPaint);
            }
            if ("端点".equals(pickSnapType)) {
                snapPaint.setColor(0xFF00E676);
                canvas.drawRect(px - 18f, py - 18f, px + 18f, py + 18f, snapPaint);
            } else if ("中点".equals(pickSnapType)) {
                snapPaint.setColor(0xFF00E5FF);
                Path tri = new Path();
                tri.moveTo(px, py - 22f); tri.lineTo(px - 20f, py + 16f); tri.lineTo(px + 20f, py + 16f); tri.close();
                canvas.drawPath(tri, snapPaint);
            } else {
                snapPaint.setColor(Color.WHITE);
                canvas.drawLine(px - 22f, py, px + 22f, py, snapPaint);
                canvas.drawLine(px, py - 22f, px, py + 22f, snapPaint);
            }
        }

        if (!hasLocation) return;
        float[] pt = cadView.cadToScreen(curCadX, curCadY);
        float sx = pt[0], sy = pt[1];
        canvas.drawCircle(sx, sy, 48f, haloPaint);
        canvas.save(); canvas.translate(sx, sy); canvas.rotate(bearing);
        Path arrow = new Path(); arrow.moveTo(0f, -38f); arrow.lineTo(-14f, -14f); arrow.lineTo(14f, -14f); arrow.close();
        canvas.drawPath(arrow, dotPaint); canvas.restore();
        canvas.drawCircle(sx, sy, 18f, dotPaint); canvas.drawCircle(sx, sy, 18f, borderPaint);

        if (!infoText.isEmpty()) {
            String[] lines = infoText.split("\n");
            float maxW = 0f;
            for (String ln : lines) maxW = Math.max(maxW, textPaint.measureText(ln));
            float lineH = 40f, padX = 20f, padY = 14f, boxH = lines.length * lineH + padY * 2, top = sy + 30f;
            RectF box = new RectF(sx - maxW / 2f - padX, top, sx + maxW / 2f + padX, top + boxH);
            canvas.drawRoundRect(box, 14f, 14f, bgPaint);
            for (int i = 0; i < lines.length; i++) {
                canvas.drawText(lines[i], sx - maxW / 2f, top + padY + (i + 1) * lineH - 8f, textPaint);
            }
        }
    }
}
