package com.cadapp;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.util.*;

public class ScriptOverlayView extends View {
    public static class Shape {
        public int type;
        public double x1, y1, x2, y2, r;
        public String label;
        public int color;
        public Shape(int t, double x1, double y1, double x2, double y2, double r, String lb, int col) {
            this.type = t; this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
            this.r = r; this.label = lb; this.color = col;
        }
    }
    private final CadGLSurfaceView glView;
    private final List<Shape> shapes = Collections.synchronizedList(new ArrayList<>());
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG), pFill = new Paint(Paint.ANTI_ALIAS_FLAG), pText = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ScriptOverlayView(Context ctx, CadGLSurfaceView gl) {
        super(ctx); this.glView = gl;
        pLine.setStyle(Paint.Style.STROKE); pLine.setStrokeWidth(3.5f);
        pFill.setStyle(Paint.Style.FILL);
        pText.setTextSize(32f); pText.setTypeface(Typeface.DEFAULT_BOLD);
    }
    public void setShapes(List<Shape> list) { shapes.clear(); if (list != null) shapes.addAll(list); postInvalidate(); }
    public void clearShapes() { shapes.clear(); postInvalidate(); }
    public boolean hasShapes() { return !shapes.isEmpty(); }

    @Override protected void onDraw(Canvas canvas) {
        if (shapes.isEmpty() || glView == null) return;
        synchronized (shapes) {
            for (Shape s : shapes) {
                pLine.setColor(s.color); pFill.setColor(s.color); pText.setColor(s.color);
                float[] p1 = glView.cadToScreen(s.x1, s.y1);
                if (s.type == 1) { float[] p2 = glView.cadToScreen(s.x2, s.y2); canvas.drawLine(p1[0], p1[1], p2[0], p2[1], pLine); }
                else if (s.type == 2) { float[] pr = glView.cadToScreen(s.x1 + s.r, s.y1); float rad = Math.abs(pr[0] - p1[0]); if (rad > 1f) canvas.drawCircle(p1[0], p1[1], rad, pLine); }
                else if (s.type == 3) { canvas.drawCircle(p1[0], p1[1], 10f, pFill); canvas.drawCircle(p1[0], p1[1], 16f, pLine); if (s.label != null && !s.label.isEmpty()) canvas.drawText(s.label, p1[0] + 22f, p1[1] + 10f, pText); }
                else if (s.type == 4 && s.label != null) canvas.drawText(s.label, p1[0], p1[1], pText);
            }
        }
    }
}
