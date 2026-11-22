package com.cadapp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ShxFontParser {
    public static class Glyph {
        public List<float[]> strokes;
        public float advance;
        public Glyph(List<float[]> strokes, float advance) { this.strokes = strokes; this.advance = advance; }
    }
    private final Map<String, Glyph> fontMap = new HashMap<>();
    private boolean isBigFont = false;

    public ShxFontParser(InputStream... streams) {
        for (InputStream is : streams) if (is != null) {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                int nRead; byte[] data = new byte[16384];
                while ((nRead = is.read(data, 0, data.length)) != -1) buffer.write(data, 0, nRead);
                parseFontData(buffer.toByteArray()); is.close();
            } catch (Exception e) { e.printStackTrace(); }
        }
    }

    private void parseFontData(byte[] data) {
        if (data.length < 128) return;
        int headerEnd = -1;
        for (int i = 0; i < data.length - 2; i++) if ((data[i] & 0xFF) == 0x0D && (data[i + 1] & 0xFF) == 0x0A && (data[i + 2] & 0xFF) == 0x1A) { headerEnd = i + 3; break; }
        if (headerEnd == -1) return;
        this.isBigFont = new String(data, 0, headerEnd, StandardCharsets.US_ASCII).toLowerCase().contains("bigfont");
        int pos = headerEnd;
        if (this.isBigFont) {
            pos += 2; int count = readInt16(data, pos); pos += 2; int changeNumber = readInt16(data, pos); pos += 2;
            if (count <= 0) return; pos += changeNumber * 4;
            List<int[]> items = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int code = readUint16(data, pos); pos += 2; int length = readUint16(data, pos); pos += 2; int offset = readInt32(data, pos); pos += 4;
                if (code != 0 || length != 0 || offset != 0) items.add(new int[]{code, length, offset});
            }
            for (int[] item : items) {
                int code = item[0], length = item[1], offset = item[2];
                if (offset >= 0 && offset + length <= data.length) {
                    byte[] glyphBytes = new byte[length]; System.arraycopy(data, offset, glyphBytes, 0, length);
                    Glyph glyph = parseShape(splitShapeNameAndBytecode(glyphBytes), false);
                    if (glyph != null && !glyph.strokes.isEmpty()) {
                        String ch = ""; try { ch = new String(new byte[]{(byte) (code >> 8), (byte) code}, "GBK"); } catch (Exception ignored) {}
                        if (!ch.isEmpty()) fontMap.put(ch, glyph);
                    }
                }
            }
        } else {
            pos += 4; int count = readInt16(data, pos); pos += 2; if (count <= 0) return;
            List<int[]> items = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int code = readUint16(data, pos); pos += 2; int length = readUint16(data, pos); pos += 2;
                if (length > 0) items.add(new int[]{code, length});
            }
            for (int[] item : items) {
                int code = item[0], length = item[1];
                if (pos + length <= data.length) {
                    byte[] glyphBytes = new byte[length]; System.arraycopy(data, pos, glyphBytes, 0, length); pos += length;
                    Glyph glyph = parseShape(splitShapeNameAndBytecode(glyphBytes), true);
                    if (glyph != null && !glyph.strokes.isEmpty()) fontMap.put(String.valueOf((char) code), glyph);
                } else break;
            }
        }
    }
    private Glyph parseShape(byte[] data, boolean initialPenDown) {
        List<float[]> polylines = new ArrayList<>(), currentPolyline = new ArrayList<>();
        float x = 0, y = 0, scale = 1.0f; boolean isPenDown = initialPenDown;
        float[][] sp = new float[4][2]; int spIdx = 0;
        if (isPenDown) currentPolyline.add(new float[]{x, y});
        for (int i = 0; i < data.length; i++) {
            int cb = data[i] & 0xFF;
            if (cb <= 0x0F) {
                switch (cb) {
                    case 0: flushPolyline(polylines, currentPolyline); isPenDown = false; break;
                    case 1: if (!isPenDown) currentPolyline.add(new float[]{x, y}); isPenDown = true; break;
                    case 2: isPenDown = false; flushPolyline(polylines, currentPolyline); break;
                    case 3: i++; if (i < data.length) scale /= byteToSByte(data[i]); break;
                    case 4: i++; if (i < data.length) scale *= byteToSByte(data[i]); break;
                    case 5: if (spIdx < 4) { sp[spIdx][0] = x; sp[spIdx][1] = y; spIdx++; } break;
                    case 6: if (spIdx > 0) { spIdx--; x = sp[spIdx][0]; y = sp[spIdx][1]; flushPolyline(polylines, currentPolyline); if (isPenDown) currentPolyline.add(new float[]{x, y}); } break;
                    case 7: i++; break;
                    case 8: if (i + 2 < data.length) { x += byteToSByte(data[++i]) * scale; y += byteToSByte(data[++i]) * scale; if (isPenDown) currentPolyline.add(new float[]{x, y}); } break;
                    case 9: while (i + 2 < data.length) { float dx = byteToSByte(data[++i]), dy = byteToSByte(data[++i]); if (dx == 0 && dy == 0) break; x += dx * scale; y += dy * scale; if (isPenDown) currentPolyline.add(new float[]{x, y}); } break;
                    case 10: if (i + 2 < data.length) {
                        float r = (data[++i] & 0xFF) * scale; int flag = byteToSByte(data[++i]), so = (flag & 0x70) >> 4, oc = flag & 0x07; boolean cw = flag < 0;
                        float sr = (float)(Math.PI / 4.0 * so);
                        List<float[]> pts = tessellateOctant(x - (float)(Math.cos(sr) * r), y - (float)(Math.sin(sr) * r), r, so, oc, cw);
                        if (isPenDown) { if (!currentPolyline.isEmpty()) currentPolyline.remove(currentPolyline.size() - 1); currentPolyline.addAll(pts); }
                        if (!pts.isEmpty()) { float[] last = pts.get(pts.size() - 1); x = last[0]; y = last[1]; }
                    } break;
                    case 11: if (i + 4 < data.length) {
                        int sOff = data[++i] & 0xFF, eOff = data[++i] & 0xFF, hr = data[++i] & 0xFF, lr = data[++i] & 0xFF;
                        float r = ((hr * 255) + lr) * scale; int flag = byteToSByte(data[++i]), so = (flag & 0x70) >> 4, oc = flag & 0x07;
                        if (oc == 0) oc = 8; if (eOff != 0) oc--;
                        float pi4 = (float)(Math.PI / 4.0), span = pi4 * oc, delta = (float)(Math.PI / 18.0), sign = flag < 0 ? -1 : 1;
                        if (flag < 0) { delta = -delta; span = -span; }
                        float sr = pi4 * so + (pi4 * sOff / 256f) * sign, er = pi4 * so + span + (pi4 * eOff / 256f) * sign;
                        float cx = x - r * (float)Math.cos(sr), cy = y - r * (float)Math.sin(sr);
                        if (isPenDown) {
                            float cr = sr; currentPolyline.add(new float[]{cx + r * (float)Math.cos(cr), cy + r * (float)Math.sin(cr)});
                            while (delta > 0 ? cr + delta < er : cr + delta > er) { cr += delta; currentPolyline.add(new float[]{cx + r * (float)Math.cos(cr), cy + r * (float)Math.sin(cr)}); }
                            currentPolyline.add(new float[]{cx + r * (float)Math.cos(er), cy + r * (float)Math.sin(er)});
                        }
                        x = cx + r * (float)Math.cos(er); y = cy + r * (float)Math.sin(er);
                    } break;
                    case 12: if (i + 3 < data.length) {
                        float ex = x + byteToSByte(data[++i]) * scale, ey = y + byteToSByte(data[++i]) * scale, b = byteToSByte(data[++i]);
                        if (isPenDown) { if (b == 0) currentPolyline.add(new float[]{ex, ey}); else { List<float[]> pts = tessellateBulge(x, y, ex, ey, b / 127f); for (int k = 1; k < pts.size(); k++) currentPolyline.add(pts.get(k)); } }
                        x = ex; y = ey;
                    } break;
                    case 13: while (i + 2 < data.length) {
                        float dx = byteToSByte(data[++i]) * scale, dy = byteToSByte(data[++i]) * scale; if (dx == 0 && dy == 0) break;
                        float ex = x + dx, ey = y + dy, b = byteToSByte(data[++i]);
                        if (isPenDown) { if (b == 0) currentPolyline.add(new float[]{ex, ey}); else { List<float[]> pts = tessellateBulge(x, y, ex, ey, b / 127f); for (int k = 1; k < pts.size(); k++) currentPolyline.add(pts.get(k)); } }
                        x = ex; y = ey;
                    } break;
                    case 14: i++; if (i < data.length) {
                        int sc = data[i] & 0xFF;
                        if (sc == 3 || sc == 4) i++; else if (sc == 7) i += (isBigFont ? 2 : 1); else if (sc == 8 || sc == 10) i += 2;
                        else if (sc == 11) i += 5; else if (sc == 12) i += 3;
                        else if (sc == 9) { while (i + 2 < data.length) { if (data[i+1]==0 && data[i+2]==0) { i+=2; break; } i+=2; } }
                        else if (sc == 13) { while (i + 3 < data.length) { if (data[i+1]==0 && data[i+2]==0) { i+=3; break; } i+=3; } }
                    } break;
                }
            } else {
                int len = (cb & 0xF0) >> 4, dir = cb & 0x0F;
                float[] dxs = {1,1,1,0.5f,0,-0.5f,-1,-1,-1,-1,-1,-0.5f,0,0.5f,1,1};
                float[] dys = {0,0.5f,1,1,1,1,1,0.5f,0,-0.5f,-1,-1,-1,-1,-1,-0.5f};
                x += dxs[dir] * len * scale; y += dys[dir] * len * scale;
                if (isPenDown) currentPolyline.add(new float[]{x, y});
            }
        }
        return new Glyph(polylines, x);
    }

    private List<float[]> tessellateOctant(float cx, float cy, float r, int so, int oc, boolean cw) {
        List<float[]> pts = new ArrayList<>();
        float sa = (float)(Math.PI / 4 * so), span = (float)(Math.PI / 4 * (oc == 0 ? 8 : oc)) * (cw ? -1 : 1);
        float inc = Math.abs(span); int segs = Math.max(1, (int)Math.floor(inc / (Math.PI / 18)));
        for (int i = 0; i <= segs; i++) { float a = cw ? sa - inc * i / segs : sa + inc * i / segs; pts.add(new float[]{cx + r * (float)Math.cos(a), cy + r * (float)Math.sin(a)}); }
        return pts;
    }
    private List<float[]> tessellateBulge(float sx, float sy, float ex, float ey, float b) {
        List<float[]> pts = new ArrayList<>(); b = Math.max(-1, Math.min(1, b));
        float dx = ex - sx, dy = ey - sy, D = (float)Math.hypot(dx, dy);
        if (Math.abs(b) * D == 0) { pts.add(new float[]{sx, sy}); pts.add(new float[]{ex, ey}); return pts; }
        boolean cw = b < 0; float th = 4 * (float)Math.atan(Math.abs(b)), r = D / (2 * (float)Math.sin(th / 2));
        float mx = sx + dx / 2, my = sy + dy / 2, len = (float)Math.hypot(-dy, dx);
        float nx = (-dy / len) * Math.abs(r * (float)Math.cos(th / 2)), ny = (dx / len) * Math.abs(r * (float)Math.cos(th / 2));
        float cx = cw ? mx - nx : mx + nx, cy = cw ? my - ny : my + ny;
        float sa = (float)Math.atan2(sy - cy, sx - cx), ea = (float)Math.atan2(ey - cy, ex - cx);
        if (cw && ea >= sa) ea -= 2 * Math.PI; else if (!cw && ea <= sa) ea += 2 * Math.PI;
        float inc = Math.abs(ea - sa); int segs = Math.max(1, (int)Math.floor(inc / (Math.PI / 18)));
        for (int i = 0; i <= segs; i++) { float a = cw ? sa - inc * i / segs : sa + inc * i / segs; pts.add(new float[]{cx + r * (float)Math.cos(a), cy + r * (float)Math.sin(a)}); }
        return pts;
    }
    private void flushPolyline(List<float[]> out, List<float[]> cur) {
        for (int i = 0; i + 1 < cur.size(); i++) { float[] p1 = cur.get(i), p2 = cur.get(i + 1); out.add(new float[]{p1[0], p1[1], p2[0], p2[1]}); }
        cur.clear();
    }
    private byte[] splitShapeNameAndBytecode(byte[] raw) {
        for (int i = 0; i < raw.length - 1; i++) if (raw[i] == 0) { byte[] bc = new byte[raw.length - i - 1]; System.arraycopy(raw, i + 1, bc, 0, bc.length); return bc; }
        return raw;
    }
    public static int byteToSByte(byte v) { return (v & 127) - ((v & 128) != 0 ? 128 : 0); }
    private int readInt16(byte[] d, int p) { return (short)((d[p] & 0xFF) | ((d[p+1] & 0xFF) << 8)); }
    private int readUint16(byte[] d, int p) { return (d[p] & 0xFF) | ((d[p+1] & 0xFF) << 8); }
    private int readInt32(byte[] d, int p) { return (d[p] & 0xFF) | ((d[p+1] & 0xFF) << 8) | ((d[p+2] & 0xFF) << 16) | ((d[p+3] & 0xFF) << 24); }
    public Glyph getGlyph(String str) { return fontMap.get(str); }
}
