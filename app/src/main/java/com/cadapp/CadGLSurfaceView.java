package com.cadapp;
import android.content.*; import android.opengl.*; import android.animation.*; import android.view.*; import android.view.animation.*; import android.widget.Toast;
import java.io.*; import java.nio.*; import java.nio.charset.StandardCharsets; import java.util.*;
import javax.microedition.khronos.egl.EGLConfig; import javax.microedition.khronos.opengles.GL10;

public class CadGLSurfaceView extends GLSurfaceView implements GLSurfaceView.Renderer {
    public static class DrawBatch {
        public int typeId, layerId, startVert, vertCount;
        public DrawBatch(int t, int l, int sv, int vc) { typeId = t; layerId = l; startVert = sv; vertCount = vc; }
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

    public float textScaleMult = 1.0f, enScale = 1.85f, enSpacing = 1.13f, punctSpacing = 0.63f;

    public boolean isDarkMode = true;
    private byte[] cachedDwgBytes = null;
    private byte[] cachedBin = null;
    private volatile boolean isLoading = false;
    public double gcx = 0, gcy = 0;
    public Runnable onViewportChanged = null;
    public boolean pickMode = false;
    public interface OnPointPickedListener { void onPicked(double cadX, double cadY, String snapType, String layerName, float fingerX, float fingerY, boolean touching); }
    public OnPointPickedListener onPointPicked = null;
    private boolean hasFitted = false;
    private float minX = -50, minY = -50, maxX = 50, maxY = 50, baseScale = 1f, userScale = 1f, userTx = 0f, userTy = 0f, lastX, lastY;
    private final float[] mvp = new float[16]; private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private long twoFingerDownTime = 0; private float twoFingerX = 0, twoFingerY = 0; private boolean twoFingerMoved = false; private boolean dragging;
    private HzShxFontTool shxParser;

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
            shxParser = new HzShxFontTool(streams.toArray(new InputStream[0]));
        } catch (Exception e) { shxParser = new HzShxFontTool(); e.printStackTrace(); }

        gestureDetector = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) { if (!pickMode) { zoomAround(2.5f, e.getX(), e.getY()); return true; } return false; }
        });
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float ns = Math.max(1e-6f, Math.min(userScale * d.getScaleFactor(), 1e7f)), f = ns / userScale;
                float fx = d.getFocusX() - getWidth() / 2f, fy = d.getFocusY() - getHeight() / 2f;
                userTx = fx - (fx - userTx) * f; userTy = fy - (fy - userTy) * f; userScale = ns; requestRender(); if (onViewportChanged != null) post(onViewportChanged); return true;
            }
        });
    }

    public void toggleTheme() {
        isDarkMode = !isDarkMode;
        if (cachedDwgBytes != null) {
            isLoading = false;
            new Thread(() -> {
                try {
                    byte[] bin = DwgParser.parseToBinary(cachedDwgBytes, isDarkMode);
                    if (bin != null) { cachedBin = bin; rebuildFromBin(bin, false); }
                } catch (Exception e) { e.printStackTrace(); }
            }).start();
        } else requestRender();
    }

    private ValueAnimator zoomAnim;
    private void zoomAround(float factor, float screenX, float screenY) {
        if (zoomAnim != null) zoomAnim.cancel();
        final float s0 = userScale, tx0 = userTx, ty0 = userTy;
        final float ns = Math.max(1e-6f, Math.min(s0 * factor, 1e7f)), f = ns / s0;
        final float fx = screenX - getWidth() / 2f, fy = screenY - getHeight() / 2f;
        final float ntx = fx - (fx - tx0) * f, nty = fy - (fy - ty0) * f;
        zoomAnim = ValueAnimator.ofFloat(0f, 1f);
        zoomAnim.setDuration(300);
        zoomAnim.setInterpolator(new DecelerateInterpolator(1.8f));
        zoomAnim.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            userScale = s0 + (ns - s0) * t; userTx = tx0 + (ntx - tx0) * t; userTy = ty0 + (nty - ty0) * t;
            requestRender(); if (onViewportChanged != null) post(onViewportChanged);
        });
        zoomAnim.start();
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
        cachedDwgBytes = dwgData;
        new Thread(() -> {
            try {
                byte[] bin = DwgParser.parseToBinary(dwgData, isDarkMode);
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
        double scale = (h / 92.0) * textScaleMult;
        HzShxFontTool.LayoutData layout = shxParser.layoutLocal(txt, scale);
        double ox = (anchor == 1) ? -layout.width() / 2.0 : (anchor == 2) ? -layout.width() : 0.0;
        double cos = Math.cos(rot), sin = Math.sin(rot);
        for (List<HzShxFontTool.Pt> pl : layout.localPolylines) {
            for (int i = 0; i + 1 < pl.size(); i++) {
                HzShxFontTool.Pt p1 = pl.get(i), p2 = pl.get(i + 1);
                double lx1 = ox + p1.x, ly1 = p1.y, lx2 = ox + p2.x, ly2 = p2.y;
                out.add(new float[]{
                    (float)(x + lx1 * cos - ly1 * sin), (float)(y + lx1 * sin + ly1 * cos),
                    (float)(x + lx2 * cos - ly2 * sin), (float)(y + lx2 * sin + ly2 * cos)
                });
                cols.add(col);
            }
        }
    }
    public double[] screenToCad(float sx, float sy) {
        double s = baseScale * userScale;
        double rx = (sx - getWidth() / 2f - userTx) / s;
        double ry = (getHeight() / 2f + userTy - sy) / s;
        return new double[]{gcx + rx, gcy + ry};
    }

    public void pickAtScreen(float sx, float sy, float fingerX, float fingerY, boolean touching) {
        double[] raw = screenToCad(sx, sy);
        double bestX = raw[0], bestY = raw[1];
        String snapType = "自由点", snapLayer = "无";
        double bestDistSq = 42.0 * 42.0;
        double s = baseScale * userScale;
        float hw = getWidth() / 2f, hh = getHeight() / 2f;

        if (activeBuffer != null && vertexCount > 0) {
            for (DrawBatch b : batches) {
                if (b.vertCount <= 0 || b.typeId >= typeVisible.length || b.layerId >= layerVisible.length) continue;
                if (!typeVisible[b.typeId] || !layerVisible[b.layerId]) continue;
                String lName = b.layerId < layerNames.size() ? layerNames.get(b.layerId) : "0";
                int endV = b.startVert + b.vertCount;
                for (int v = b.startVert; v + 1 < endV; v += 2) {
                    int p1 = v * 6, p2 = (v + 1) * 6;
                    float x1 = activeBuffer.get(p1), y1 = activeBuffer.get(p1 + 1);
                    float x2 = activeBuffer.get(p2), y2 = activeBuffer.get(p2 + 1);
                    float sx1 = (float)(hw + userTx + x1 * s), sy1 = (float)(hh + userTy - y1 * s);
                    float sx2 = (float)(hw + userTx + x2 * s), sy2 = (float)(hh + userTy - y2 * s);
                    double d1 = (sx1 - sx)*(sx1 - sx) + (sy1 - sy)*(sy1 - sy);
                    if (d1 < bestDistSq) { bestDistSq = d1; bestX = gcx + x1; bestY = gcy + y1; snapType = "端点"; snapLayer = lName; }
                    double d2 = (sx2 - sx)*(sx2 - sx) + (sy2 - sy)*(sy2 - sy);
                    if (d2 < bestDistSq) { bestDistSq = d2; bestX = gcx + x2; bestY = gcy + y2; snapType = "端点"; snapLayer = lName; }
                    float smx = (sx1 + sx2) * 0.5f, smy = (sy1 + sy2) * 0.5f;
                    double dm = (smx - sx)*(smx - sx) + (smy - sy)*(smy - sy) + 25.0;
                    if (dm < bestDistSq) { bestDistSq = dm; bestX = gcx + (x1 + x2)*0.5; bestY = gcy + (y1 + y2)*0.5; snapType = "中点"; snapLayer = lName; }
                }
            }
        }
        if (onPointPicked != null) onPointPicked.onPicked(bestX, bestY, snapType, snapLayer, fingerX, fingerY, touching);
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
        int vs = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER); GLES20.glShaderSource(vs, VS); GLES20.glCompileShader(vs);
        int fs = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER); GLES20.glShaderSource(fs, FS); GLES20.glCompileShader(fs);
        program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vs); GLES20.glAttachShader(program, fs); GLES20.glLinkProgram(program);
        uMVP = GLES20.glGetUniformLocation(program, "uMVP"); aPos = GLES20.glGetAttribLocation(program, "aPos"); aCol = GLES20.glGetAttribLocation(program, "aCol");
        int[] vbos = new int[1]; GLES20.glGenBuffers(1, vbos, 0); vboId = vbos[0]; if (activeBuffer != null) needReupload = true;
    }

    @Override public void onSurfaceChanged(GL10 gl, int w, int h) { GLES20.glViewport(0, 0, w, h); if (!hasFitted) { fitToScreen(); } }

    @Override public void onDrawFrame(GL10 gl) {
        if (isDarkMode) GLES20.glClearColor(0f, 0f, 0f, 1f);
        else GLES20.glClearColor(1f, 1f, 1f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if (pendingBuffer != null) {
            activeBuffer = pendingBuffer; vertexCount = pendingVertexCount;
            if (pendingBatches != null) { batches.clear(); batches.addAll(pendingBatches); pendingBatches = null; }
            pendingBuffer = null; needReupload = true;
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
        gestureDetector.onTouchEvent(e);
        scaleDetector.onTouchEvent(e);
        float sx = 0, sy = 0; int pc = e.getPointerCount(), up = (e.getActionMasked() == MotionEvent.ACTION_POINTER_UP) ? e.getActionIndex() : -1, vp = 0;
        for (int i = 0; i < pc; i++) { if (i != up) { sx += e.getX(i); sy += e.getY(i); vp++; } }
        float fx = vp > 0 ? sx / vp : 0, fy = vp > 0 ? sy / vp : 0;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                twoFingerDownTime = 0;
                if (pickMode && pc == 1) { pickAtScreen(fx, fy - 240f, fx, fy, true); return true; }
                lastX = fx; lastY = fy; dragging = vp > 0; break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (pc == 2) {
                    twoFingerDownTime = System.currentTimeMillis();
                    twoFingerX = (e.getX(0) + e.getX(1)) * 0.5f;
                    twoFingerY = (e.getY(0) + e.getY(1)) * 0.5f;
                    twoFingerMoved = false;
                } else twoFingerDownTime = 0;
                lastX = fx; lastY = fy; dragging = vp > 0; break;
            case MotionEvent.ACTION_POINTER_UP:
                if (pc == 2 && twoFingerDownTime > 0 && !twoFingerMoved && (System.currentTimeMillis() - twoFingerDownTime) < 260) {
                    zoomAround(0.4f, twoFingerX, twoFingerY);
                }
                twoFingerDownTime = 0;
                lastX = fx; lastY = fy; dragging = vp > 0; break;
            case MotionEvent.ACTION_MOVE:
                if (twoFingerDownTime > 0 && pc >= 2) {
                    float cx = (e.getX(0) + e.getX(1)) * 0.5f, cy = (e.getY(0) + e.getY(1)) * 0.5f;
                    if (Math.hypot(cx - twoFingerX, cy - twoFingerY) > 30f) twoFingerMoved = true;
                }
                if (pickMode && pc == 1 && !scaleDetector.isInProgress()) { pickAtScreen(fx, fy - 240f, fx, fy, true); return true; }
                if (dragging && !scaleDetector.isInProgress()) { userTx += fx - lastX; userTy += fy - lastY; requestRender(); if (onViewportChanged != null) post(onViewportChanged); } lastX = fx; lastY = fy; break;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                if (pickMode && pc == 1) { pickAtScreen(fx, fy - 240f, fx, fy, false); }
                dragging = false; twoFingerDownTime = 0; break;
        }
        return true;
    }
}
