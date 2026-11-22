package com.cadapp;
import android.content.*; import android.opengl.*; import android.view.*; import android.widget.Toast;
import java.io.*; import java.nio.*; import java.nio.charset.StandardCharsets; import java.util.*;
import javax.microedition.khronos.egl.EGLConfig; import javax.microedition.khronos.opengles.GL10;

public class CadGLSurfaceView extends GLSurfaceView implements GLSurfaceView.Renderer {
    public static class DrawBatch {
        public int typeId, layerId, startVert, vertCount;
        public DrawBatch(int t, int l, int sv, int vc) { typeId = t; layerId = l; startVert = sv; vertCount = vc; }
    }
    private static class LabelItem {
        float x, y, h, rot; int col, anchor, lid; String txt;
        LabelItem(float x, float y, float h, float rot, int col, int anchor, int lid, String txt) {
            this.x=x; this.y=y; this.h=h; this.rot=rot; this.col=col; this.anchor=anchor; this.lid=lid; this.txt=txt;
        }
    }

    private static final String VS = "uniform mat4 uMVP; attribute vec2 aPos; attribute vec4 aCol; varying vec4 vCol; void main() { gl_Position = uMVP * vec4(aPos, 0.0, 1.0); vCol = aCol; }";
    private static final String FS = "precision mediump float; varying vec4 vCol; void main() { gl_FragColor = vCol; }";
    private int program, uMVP, aPos, aCol, vboId = 0, vertexCount = 0;
    private volatile FloatBuffer pendingBuffer = null, activeBuffer = null;
    private volatile boolean needReupload = false; private volatile int pendingVertexCount = 0;
    private volatile List<DrawBatch> pendingBatches = null;

    public final List<DrawBatch> batches = new ArrayList<>();
    public final List<String> typeNames = new ArrayList<>(), layerNames = new ArrayList<>();
    public final List<Integer> typeCounts = new ArrayList<>(), layerCounts = new ArrayList<>();
    public boolean[] typeVisible = new boolean[0], layerVisible = new boolean[0];

    // 字体微调参数（自动读取本地保存的配置）
    public float textScaleMult = 1.0f, enScale = 1.85f, enSpacing = 1.13f, punctSpacing = 0.63f;

    private byte[] cachedBin = null;
    private volatile boolean isLoading = false;
    public double gcx = 0, gcy = 0;
    public Runnable onViewportChanged = null;
    private boolean hasFitted = false;
    private float minX = -50, minY = -50, maxX = 50, maxY = 50, baseScale = 1f, userScale = 1f, userTx = 0f, userTy = 0f, lastX, lastY;
    private final float[] mvp = new float[16]; private final ScaleGestureDetector scaleDetector; private boolean dragging; private ShxFontParser shxParser;

    public CadGLSurfaceView(Context c) {
        super(c); setEGLContextClientVersion(2); setPreserveEGLContextOnPause(true); setRenderer(this); setRenderMode(RENDERMODE_WHEN_DIRTY);
        SharedPreferences sp = c.getSharedPreferences("cad_cfg", Context.MODE_PRIVATE);
        textScaleMult = sp.getFloat("txt_scale", 1.0f);
        enScale = sp.getFloat("txt_en_scale", 1.85f);
        enSpacing = sp.getFloat("txt_en_spacing", 1.13f);
        punctSpacing = sp.getFloat("txt_punct", 0.63f);

        try {
            String[] files = c.getAssets().list(""); List<InputStream> streams = new ArrayList<>();
            if (files != null) for (String f : files) if (f.toLowerCase().endsWith(".shx")) streams.add(c.getAssets().open(f));
            File dir = new File("/storage/emulated/0/1hdm");
            if (dir.exists() && dir.listFiles() != null) for (File f : dir.listFiles()) if (f.isFile() && f.getName().toLowerCase().endsWith(".shx")) streams.add(new FileInputStream(f));
            if (!streams.isEmpty()) shxParser = new ShxFontParser(streams.toArray(new InputStream[0]));
        } catch (Exception e) { e.printStackTrace(); }

        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float ns = Math.max(1e-6f, Math.min(userScale * d.getScaleFactor(), 1e7f)), f = ns / userScale;
                float fx = d.getFocusX() - getWidth() / 2f, fy = d.getFocusY() - getHeight() / 2f;
                userTx = fx - (fx - userTx) * f; userTy = fy - (fy - userTy) * f; userScale = ns; requestRender(); if (onViewportChanged != null) post(onViewportChanged); return true;
            }
        });
    }

    public void updateTextParams(float scaleM, float enS, float enSp, float punctSp) {
        this.textScaleMult = scaleM; this.enScale = enS; this.enSpacing = enSp; this.punctSpacing = punctSp;
        getContext().getSharedPreferences("cad_cfg", Context.MODE_PRIVATE).edit()
            .putFloat("txt_scale", scaleM).putFloat("txt_en_scale", enS)
            .putFloat("txt_en_spacing", enSp).putFloat("txt_punct", punctSp).apply();
        if (cachedBin != null) rebuildFromBin(cachedBin, false);
    }
    public void loadDwgAsync(final byte[] dwgData) {
        if (isLoading) return; isLoading = true;
        new Thread(() -> {
            try {
                byte[] bin = DwgParser.parseToBinary(dwgData);
                if (bin == null || bin.length < 44) { post(() -> Toast.makeText(getContext(), "解析返回空", Toast.LENGTH_LONG).show()); return; }
                cachedBin = bin; rebuildFromBin(bin, true);
            } catch (Throwable t) { post(() -> Toast.makeText(getContext(), "异常: " + t.getMessage(), Toast.LENGTH_LONG).show()); }
            finally { isLoading = false; }
        }).start();
    }

    private void rebuildFromBin(final byte[] bin, final boolean resetCamera) {
        new Thread(() -> {
            long t0 = System.currentTimeMillis();
            try {
                ByteBuffer bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN);
                gcx = bb.getDouble(); gcy = bb.getDouble();
                int strokeCount = bb.getInt(), labelCount = bb.getInt();
                minX = bb.getFloat(); minY = bb.getFloat(); maxX = bb.getFloat(); maxY = bb.getFloat();
                int dictLen = bb.getInt(), typeNum = bb.get() & 0xFF;
                List<String> tNames = new ArrayList<>();
                for (int i = 0; i < typeNum; i++) { int nl = bb.get() & 0xFF; byte[] nb = new byte[nl]; bb.get(nb); tNames.add(new String(nb, StandardCharsets.UTF_8)); }
                int layerNum = bb.getShort() & 0xFFFF; List<String> lNames = new ArrayList<>();
                for (int i = 0; i < layerNum; i++) { int nl = bb.get() & 0xFF; byte[] nb = new byte[nl]; bb.get(nb); lNames.add(new String(nb, StandardCharsets.UTF_8)); }
                int strokesStart = 44 + dictLen; int[] tCnt = new int[typeNum], lCnt = new int[layerNum];
                Map<Integer, Integer> bucketCnt = new LinkedHashMap<>();
                for (int i = 0; i < strokeCount; i++) {
                    int off = strokesStart + i * 23, tid = bin[off + 20] & 0xFF, lid = (bin[off + 21] & 0xFF) | ((bin[off + 22] & 0xFF) << 8);
                    if (tid < typeNum && lid < layerNum) { tCnt[tid]++; lCnt[lid]++; int k = (tid << 16) | lid; bucketCnt.put(k, bucketCnt.getOrDefault(k, 0) + 1); }
                }
                List<float[]> textStrokes = new ArrayList<>(); List<Integer> textColors = new ArrayList<>(), textLayers = new ArrayList<>();
                int labelOffset = strokesStart + strokeCount * 23;
                if (labelCount > 0 && labelOffset < bin.length) {
                    bb.position(labelOffset);
                    for (int i = 0; i < labelCount && bb.remaining() >= 25; i++) {
                        float lx = bb.getFloat(), ly = bb.getFloat(), lh = bb.getFloat(), rot = bb.getFloat();
                        int col = bb.getInt(), anchor = bb.get() & 0xFF, lid = bb.getShort() & 0xFFFF, tlen = bb.getShort() & 0xFFFF;
                        if (bb.remaining() < tlen) break; byte[] tb = new byte[tlen]; bb.get(tb);
                        if (shxParser != null && lid < layerNum) {
                            int before = textStrokes.size();
                            buildShxStrokes(new String(tb, StandardCharsets.UTF_8), lx, ly, lh, rot, anchor, col, textStrokes, textColors);
                            int added = textStrokes.size() - before;
                            for (int k = 0; k < added; k++) textLayers.add(lid);
                            tCnt[0] += added; lCnt[lid] += added; bucketCnt.put(lid, bucketCnt.getOrDefault(lid, 0) + added);
                        }
                    }
                }
                Map<Integer, Integer> writePos = new HashMap<>(); List<DrawBatch> newBatches = new ArrayList<>(); int curStroke = 0;
                for (Map.Entry<Integer, Integer> en : bucketCnt.entrySet()) { int k = en.getKey(), cnt = en.getValue(); writePos.put(k, curStroke); newBatches.add(new DrawBatch(k >>> 16, k & 0xFFFF, curStroke * 2, cnt * 2)); curStroke += cnt; }
                int totalVerts = curStroke * 2; FloatBuffer fb = ByteBuffer.allocateDirect(Math.max(24, totalVerts * 24)).order(ByteOrder.nativeOrder()).asFloatBuffer();
                for (int i = 0; i < textStrokes.size(); i++) {
                    float[] s = textStrokes.get(i); int col = textColors.get(i), k = textLayers.get(i), idx = writePos.get(k); writePos.put(k, idx + 1);
                    float r = ((col >> 16) & 0xFF) / 255f, g = ((col >> 8) & 0xFF) / 255f, b = (col & 0xFF) / 255f; int p = idx * 12;
                    fb.put(p, s[0]); fb.put(p+1, s[1]); fb.put(p+2, r); fb.put(p+3, g); fb.put(p+4, b); fb.put(p+5, 1f);
                    fb.put(p+6, s[2]); fb.put(p+7, s[3]); fb.put(p+8, r); fb.put(p+9, g); fb.put(p+10, b); fb.put(p+11, 1f);
                }
                bb.position(strokesStart);
                for (int i = 0; i < strokeCount; i++) {
                    float x1 = bb.getFloat(), y1 = bb.getFloat(), x2 = bb.getFloat(), y2 = bb.getFloat(); int col = bb.getInt(), tid = bb.get() & 0xFF, lid = bb.getShort() & 0xFFFF;
                    if (tid >= typeNum || lid >= layerNum) continue;
                    int k = (tid << 16) | lid, idx = writePos.get(k); writePos.put(k, idx + 1);
                    float r = ((col >> 16) & 0xFF) / 255f, g = ((col >> 8) & 0xFF) / 255f, b = (col & 0xFF) / 255f; int p = idx * 12;
                    fb.put(p, x1); fb.put(p+1, y1); fb.put(p+2, r); fb.put(p+3, g); fb.put(p+4, b); fb.put(p+5, 1f);
                    fb.put(p+6, x2); fb.put(p+7, y2); fb.put(p+8, r); fb.put(p+9, g); fb.put(p+10, b); fb.put(p+11, 1f);
                }
                fb.position(0); pendingBuffer = fb; pendingVertexCount = totalVerts; pendingBatches = newBatches;
                long dt = System.currentTimeMillis() - t0;
                post(() -> {
                    typeNames.clear(); typeNames.addAll(tNames); typeCounts.clear(); for (int v : tCnt) typeCounts.add(v);
                    if (typeVisible.length != typeNum) { typeVisible = new boolean[typeNum]; Arrays.fill(typeVisible, true); }
                    layerNames.clear(); layerNames.addAll(lNames); layerCounts.clear(); for (int v : lCnt) layerCounts.add(v);
                    if (layerVisible.length != layerNum) { layerVisible = new boolean[layerNum]; Arrays.fill(layerVisible, true); }
                    if (resetCamera) { hasFitted = false; Toast.makeText(getContext(), "GPU就绪: " + (totalVerts/2) + "线段 (" + dt + "ms)", Toast.LENGTH_SHORT).show(); fitToScreen(); }
                    else requestRender(); if (onViewportChanged != null) post(onViewportChanged);
                });
            } catch (Throwable t) { t.printStackTrace(); }
        }).start();
    }

    private void buildShxStrokes(String txt, float x, float y, float h, float rot, int anchor, int col, List<float[]> out, List<Integer> cols) {
        if (txt == null || txt.isEmpty() || shxParser == null) return;
        if (Math.abs(rot) > 6.2832f) rot = (float) Math.toRadians(rot);
        float baseScale = (h / 100.0f) * textScaleMult, totalW = 0f;
        for (int i = 0; i < txt.length(); i++) {
            char ch = txt.charAt(i); if (ch == ' ') { totalW += h * 0.5f * textScaleMult; continue; }
            ShxFontParser.Glyph g = shxParser.getGlyph(String.valueOf(ch)); if (g == null || g.strokes.isEmpty()) continue;
            float curScale = (ch > 127) ? baseScale : (baseScale * enScale), adv = g.advance * curScale * enSpacing;
            if (ch == '.' || ch == '+' || ch == '-') adv *= punctSpacing; if (adv < h * 0.05f * textScaleMult) adv = h * 0.5f * textScaleMult;
            totalW += adv;
        }
        float ox = (anchor == 1) ? -totalW / 2f : (anchor == 2) ? -totalW : 0f, cos = (float) Math.cos(rot), sin = (float) Math.sin(rot), curX = ox;
        for (int i = 0; i < txt.length(); i++) {
            char ch = txt.charAt(i); if (ch == ' ') { curX += h * 0.5f * textScaleMult; continue; }
            ShxFontParser.Glyph g = shxParser.getGlyph(String.valueOf(ch)); if (g == null || g.strokes.isEmpty()) continue;
            float curScale = (ch > 127) ? baseScale : (baseScale * enScale);
            for (float[] st : g.strokes) {
                float sx1 = curX + st[0] * curScale, sy1 = st[1] * curScale, sx2 = curX + st[2] * curScale, sy2 = st[3] * curScale;
                out.add(new float[]{x + (sx1 * cos - sy1 * sin), y + (sx1 * sin + sy1 * cos), x + (sx2 * cos - sy2 * sin), y + (sx2 * sin + sy2 * cos)}); cols.add(col);
            }
            float adv = g.advance * curScale * enSpacing; if (ch == '.' || ch == '+' || ch == '-') adv *= punctSpacing;
            if (adv < h * 0.05f * textScaleMult) adv = h * 0.5f * textScaleMult; curX += adv;
        }
    }

    public float[] cadToScreen(double cadX, double cadY) {
        float scale = baseScale * userScale;
        float sx = getWidth() / 2f + userTx + (float)((cadX - gcx) * scale);
        float sy = getHeight() / 2f + userTy - (float)((cadY - gcy) * scale);
        return new float[]{sx, sy};
    }

    public void panToCad(double cadX, double cadY) {
        float scale = baseScale * userScale;
        userTx = -(float)((cadX - gcx) * scale);
        userTy = (float)((cadY - gcy) * scale);
        requestRender();
        if (onViewportChanged != null) post(onViewportChanged);
    }

    public void fitToScreen() {
        float w = maxX - minX, h = maxY - minY; if (w <= 0 || h <= 0 || getWidth() == 0) return;
        baseScale = Math.min(getWidth() / w, getHeight() / h) * 0.9f; hasFitted = true; userScale = 1f; userTx = 0f; userTy = 0f; requestRender(); if (onViewportChanged != null) post(onViewportChanged);
    }
    @Override public void onSurfaceCreated(GL10 gl, EGLConfig c) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        int vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER); GLES20.glShaderSource(vs, VS); GLES20.glCompileShader(vs);
        int fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER); GLES20.glShaderSource(fs, FS); GLES20.glCompileShader(fs);
        program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs); GLES20.glLinkProgram(program);
        uMVP = GLES20.glGetUniformLocation(program, "uMVP"); aPos = GLES20.glGetAttribLocation(program, "aPos"); aCol = GLES20.glGetAttribLocation(program, "aCol");
        int[] vbos = new int[1]; GLES20.glGenBuffers(1, vbos, 0); vboId = vbos[0]; if (activeBuffer != null) needReupload = true;
    }
    @Override public void onSurfaceChanged(GL10 gl, int w, int h) { GLES20.glViewport(0, 0, w, h); if (!hasFitted) { fitToScreen(); } }
    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (pendingBuffer != null) {
            activeBuffer = pendingBuffer;
            vertexCount = pendingVertexCount;
            if (pendingBatches != null) { batches.clear(); batches.addAll(pendingBatches); pendingBatches = null; }
            pendingBuffer = null;
            needReupload = true;
        }
        if (needReupload && activeBuffer != null && vertexCount > 0) {
            activeBuffer.position(0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertexCount * 24, activeBuffer, GLES20.GL_STATIC_DRAW);
            needReupload = false;
        }
        if (vertexCount == 0) return;
        float s = baseScale * userScale, hw = getWidth() / 2f, hh = getHeight() / 2f;
        Matrix.setIdentityM(mvp, 0); Matrix.orthoM(mvp, 0, (-hw - userTx) / s, (hw - userTx) / s, (-hh + userTy) / s, (hh + userTy) / s, -1f, 1f);
        GLES20.glUseProgram(program); GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vboId);
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 24, 0);
        GLES20.glEnableVertexAttribArray(aCol); GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, 24, 8);
        for (DrawBatch b : batches) if (b.vertCount > 0 && b.typeId < typeVisible.length && b.layerId < layerVisible.length && typeVisible[b.typeId] && layerVisible[b.layerId]) GLES20.glDrawArrays(GLES20.GL_LINES, b.startVert, b.vertCount);
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e);
        float sx = 0, sy = 0; int pc = e.getPointerCount(), up = (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP) ? e.getActionIndex() : -1, vp = 0;
        for (int i = 0; i < pc; i++) { if (i != up) { sx += e.getX(i); sy += e.getY(i); vp++; } }
        float fx = vp > 0 ? sx / vp : 0, fy = vp > 0 ? sy / vp : 0;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: case MotionEvent.ACTION_POINTER_DOWN: case MotionEvent.ACTION_POINTER_UP: lastX = fx; lastY = fy; dragging = vp > 0; break;
            case MotionEvent.ACTION_MOVE: if (dragging && !scaleDetector.isInProgress()) { userTx += fx - lastX; userTy += fy - lastY; requestRender(); if (onViewportChanged != null) post(onViewportChanged); } lastX = fx; lastY = fy; break;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL: dragging = false; break;
        }
        return true;
    }
}
