package com.cadapp;

import com.javacad.bitstream.BitReader;
import com.javacad.container.R2004Container;
import com.javacad.entity.EntityDecoder;
import com.javacad.entity.EntityDecoder.*;
import com.javacad.objrec.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class DwgParser {
    private static class Stroke {
        double x1, y1, x2, y2; int col; byte tid; short lid;
        Stroke(double x1, double y1, double x2, double y2, int col, byte tid, short lid) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2; this.col = col; this.tid = tid; this.lid = lid;
        }
    }
    private static class Label {
        double x, y, h, rot; int col; byte anchor; short lid; String txt;
        Label(double x, double y, double h, double rot, int col, byte anchor, short lid, String txt) {
            this.x = x; this.y = y; this.h = h; this.rot = rot; this.col = col; this.anchor = anchor; this.lid = lid; this.txt = txt;
        }
    }

    private static int aciToRgb(int c) {
        if ((c & 0x1000000) != 0) return 0xFF000000 | (c & 0xFFFFFF);
        int rgb = 0xFFFFFF;
        if (c == 1) rgb = 0xFF0000; else if (c == 2) rgb = 0xFFFF00; else if (c == 3) rgb = 0x00FF00;
        else if (c == 4) rgb = 0x00FFFF; else if (c == 5) rgb = 0x4080FF; else if (c == 6) rgb = 0xFF00FF;
        else if (c == 8) rgb = 0x808080; else if (c == 9) rgb = 0xC0C0C0; else if (c == 30) rgb = 0xFF8000;
        else if (c == 100) rgb = 0x00A854; else if (c == 126) rgb = 0x008080;
        if (((rgb >> 16) & 0xFF) < 40 && ((rgb >> 8) & 0xFF) < 40 && (rgb & 0xFF) < 40) return 0xFFFFFFFF;
        return 0xFF000000 | rgb;
    }

    private static void addBulgeSeg(List<Stroke> out, double x1, double y1, double x2, double y2, double bg, int col, byte tid, short lid) {
        double dx = x2 - x1, dy = y2 - y1, chord = Math.hypot(dx, dy);
        if (Math.abs(bg) < 1e-6 || chord < 1e-9) { out.add(new Stroke(x1, y1, x2, y2, col, tid, lid)); return; }
        double theta = 4.0 * Math.atan(bg);
        double r = chord / (2.0 * Math.sin(Math.abs(theta) / 2.0));
        double dist = Math.sqrt(Math.max(0.0, r * r - chord * chord / 4.0));
        if (Math.abs(bg) > 1.0) dist = -dist;
        double nx = -dy / chord, ny = dx / chord;
        if (bg < 0) { nx = -nx; ny = -ny; }
        double cx = (x1 + x2) / 2.0 + nx * dist, cy = (y1 + y2) / 2.0 + ny * dist;
        double a0 = Math.atan2(y1 - cy, x1 - cx);
        int segs = Math.max(8, (int)(Math.abs(theta) / (1.5 * Math.PI / 180.0)) + 1);
        double px = x1, py = y1;
        for (int k = 1; k <= segs; k++) {
            double ang = a0 + theta * k / segs;
            double qx = cx + Math.abs(r) * Math.cos(ang), qy = cy + Math.abs(r) * Math.sin(ang);
            out.add(new Stroke(px, py, qx, qy, col, tid, lid)); px = qx; py = qy;
        }
    }
    public static byte[] parseToBinary(byte[] dwgData) {
        try {
            Map<String, byte[]> secs = R2004Container.getSections(dwgData, "AcDb:Handles", "AcDb:AcDbObjects");
            List<ObjectRecord.ObjectRef> refs = HandlesParser.parseObjectMapHandles(secs.get("AcDb:Handles"));
            byte[] objData = secs.get("AcDb:AcDbObjects");

            Map<Long, LayerInfo> layers = new HashMap<>();
            List<Base> ents = new ArrayList<>(refs.size());
            for (ObjectRecord.ObjectRef ref : refs) {
                try {
                    ObjectRecord rec = ObjectRecord.parseObjectRecord(objData, ref, true);
                    int tc = ObjectRecord.parseObjHeader(rec).typeCode;
                    long endBit = rec.bodyBitOffset + rec.handleSizeFieldBits + (long) rec.size * 8 - rec.handleStreamSizeBits;
                    if (tc == 0x33) {
                        BitReader r = rec.bodyBitStream(); r.readUMC(); r.readOT();
                        LayerInfo li = EntityDecoder.decodeLayer(r, ref.handle, endBit);
                        if (li != null) layers.put(ref.handle, li);
                        continue;
                    }
                    boolean isDim = (tc >= 0x14 && tc <= 0x1A);
                    if (tc != 0x13 && tc != 0x12 && tc != 0x11 && tc != 0x4D && tc != 0x23 && tc != 0x01 && tc != 0x2C && !isDim) continue;
                    for (boolean v : Arrays.asList(true, false)) {
                        BitReader r = rec.bodyBitStream(); r.readUMC(); r.readOT();
                        Base hdr = EntityDecoder.parseHeaderAndLayer(r, ref.handle, v, endBit);
                        if (hdr == null) continue;
                        Base ent = null;
                        if (tc == 0x13) ent = EntityDecoder.decodeLine(r);
                        else if (tc == 0x12) ent = EntityDecoder.decodeCircle(r);
                        else if (tc == 0x11) ent = EntityDecoder.decodeArc(r);
                        else if (tc == 0x23) ent = EntityDecoder.decodeEllipse(r);
                        else if (tc == 0x4D) ent = EntityDecoder.decodeLwPoly(r);
                        else if (tc == 0x01) ent = EntityDecoder.decodeText(r, endBit);
                        else if (tc == 0x2C) ent = EntityDecoder.decodeMText(r, endBit);
                        else ent = EntityDecoder.decodeDim(r, tc, endBit);
                        if (ent != null) { ent.color = hdr.color; ent.layerHandle = hdr.layerHandle; ents.add(ent); }
                        break;
                    }
                } catch (Exception ignored) {}
            }

            List<String> typeNames = Arrays.asList("TEXT", "LINE", "LWPOLYLINE", "CIRCLE", "ARC", "ELLIPSE", "DIMENSION");
            Map<Long, Short> layerMap = new LinkedHashMap<>();
            List<String> layerNames = new ArrayList<>();
            List<Stroke> strokes = new ArrayList<>(ents.size() * 2);
            List<Label> labels = new ArrayList<>();

            for (Base e : ents) {
                LayerInfo li = layers.get(e.layerHandle);
                int c = (e.color == 256 && li != null) ? li.color : e.color;
                int rgb = aciToRgb(c);
                short lid = layerMap.computeIfAbsent(e.layerHandle, h -> {
                    layerNames.add(li != null && !li.name.trim().isEmpty() ? li.name : "0");
                    return (short) (layerNames.size() - 1);
                });
                if (e instanceof Line) {
                    Line l = (Line) e; strokes.add(new Stroke(l.sx, l.sy, l.ex, l.ey, rgb, (byte) 1, lid));
                } else if (e instanceof LwPoly && ((LwPoly) e).pts.size() >= 2) {
                    LwPoly lp = (LwPoly) e; int n = lp.pts.size(), segs = lp.closed ? n : n - 1;
                    for (int i = 0; i < segs; i++) {
                        List<Double> p1 = lp.pts.get(i), p2 = lp.pts.get((i + 1) % n);
                        addBulgeSeg(strokes, p1.get(0), p1.get(1), p2.get(0), p2.get(1), i < lp.bulges.size() ? lp.bulges.get(i) : 0.0, rgb, (byte) 2, lid);
                    }
                } else if (e instanceof Circle && ((Circle) e).r > 0) {
                    Circle ci = (Circle) e; double px = ci.cx + ci.r, py = ci.cy;
                    for (int i = 1; i <= 240; i++) {
                        double a = 2 * Math.PI * i / 240, qx = ci.cx + ci.r * Math.cos(a), qy = ci.cy + ci.r * Math.sin(a);
                        strokes.add(new Stroke(px, py, qx, qy, rgb, (byte) 3, lid)); px = qx; py = qy;
                    }
                } else if (e instanceof Arc && ((Arc) e).r > 0) {
                    Arc ar = (Arc) e;
                    double span = (ar.a1 - ar.a0) % (2.0 * Math.PI);
                    if (span <= 0) span += 2.0 * Math.PI;
                    int segs = Math.max(12, Math.min(720, (int)(span / (Math.PI / 90.0)) + 1));
                    double px = ar.cx + ar.r * Math.cos(ar.a0), py = ar.cy + ar.r * Math.sin(ar.a0);
                    for (int i = 1; i <= segs; i++) {
                        double a = ar.a0 + span * i / segs, qx = ar.cx + ar.r * Math.cos(a), qy = ar.cy + ar.r * Math.sin(a);
                        strokes.add(new Stroke(px, py, qx, qy, rgb, (byte) 4, lid)); px = qx; py = qy;
                    }
                } else if (e instanceof Ellipse) {
                    Ellipse el = (Ellipse) e; double rx = Math.hypot(el.mx, el.my), ry = rx * el.ratio, ang = Math.atan2(el.my, el.mx), px = 0, py = 0;
                    for (int i = 0; i <= 36; i++) {
                        double t = el.a0 + (el.a1 - el.a0) * i / 36, lx = rx * Math.cos(t), ly = ry * Math.sin(t);
                        double qx = el.cx + lx * Math.cos(ang) - ly * Math.sin(ang), qy = el.cy + lx * Math.sin(ang) + ly * Math.cos(ang);
                        if (i > 0) strokes.add(new Stroke(px, py, qx, qy, rgb, (byte) 5, lid)); px = qx; py = qy;
                    }
                } else if (e instanceof TextEnt && !((TextEnt) e).text.trim().isEmpty()) {
                    TextEnt t = (TextEnt) e; labels.add(new Label(t.x, t.y, t.h > 0 ? t.h : 2.5, t.rot, rgb, (byte) t.anchor, lid, t.text.trim()));
                } else if (false && e instanceof DimEnt) {
                    DimEnt d = (DimEnt) e;
                    if (!d.text.trim().isEmpty()) labels.add(new Label(d.mx, d.my, 2.5, 0, rgb, (byte) 1, lid, d.text.trim()));
                }
            }

            double minX = 1e30, minY = 1e30, maxX = -1e30, maxY = -1e30;
            List<Double> xs = new ArrayList<>(strokes.size()), ys = new ArrayList<>(strokes.size());
            for (Stroke s : strokes) {
                xs.add(s.x1); ys.add(s.y1);
                minX = Math.min(minX, Math.min(s.x1, s.x2)); maxX = Math.max(maxX, Math.max(s.x1, s.x2));
                minY = Math.min(minY, Math.min(s.y1, s.y2)); maxY = Math.max(maxY, Math.max(s.y1, s.y2));
            }
            double gcx = 0, gcy = 0;
            if (!xs.isEmpty()) {
                Collections.sort(xs); Collections.sort(ys);
                gcx = xs.get(xs.size() / 2); gcy = ys.get(ys.size() / 2);
            } else { minX = 0; minY = 0; maxX = 100; maxY = 100; }

            ByteBuffer dict = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN);
            dict.put((byte) typeNames.size());
            for (String tn : typeNames) { byte[] b = tn.getBytes(StandardCharsets.UTF_8); dict.put((byte) b.length); dict.put(b); }
            dict.putShort((short) layerNames.size());
            for (String ln : layerNames) { byte[] b = ln.getBytes(StandardCharsets.UTF_8); int l = Math.min(255, b.length); dict.put((byte) l); dict.put(b, 0, l); }
            int dictLen = dict.position();

            int labelBytes = 0;
            List<Object> lbUtf8 = new ArrayList<>(labels.size());
            for (Label lb : labels) { byte[] b = lb.txt.getBytes(StandardCharsets.UTF_8); lbUtf8.add(b); labelBytes += 25 + Math.min(65535, b.length); }

            ByteBuffer out = ByteBuffer.allocate(44 + dictLen + strokes.size() * 23 + labelBytes).order(ByteOrder.LITTLE_ENDIAN);
            out.putDouble(gcx); out.putDouble(gcy); out.putInt(strokes.size()); out.putInt(labels.size());
            out.putFloat((float)(minX - gcx)); out.putFloat((float)(minY - gcy));
            out.putFloat((float)(maxX - gcx)); out.putFloat((float)(maxY - gcy));
            out.putInt(dictLen); out.put(dict.array(), 0, dictLen);

            for (Stroke s : strokes) {
                out.putFloat((float)(s.x1 - gcx)); out.putFloat((float)(s.y1 - gcy));
                out.putFloat((float)(s.x2 - gcx)); out.putFloat((float)(s.y2 - gcy));
                out.putInt(s.col); out.put(s.tid); out.putShort(s.lid);
            }
            for (int i = 0; i < labels.size(); i++) {
                Label lb = labels.get(i); byte[] b = (byte[]) lbUtf8.get(i); int l = Math.min(65535, b.length);
                out.putFloat((float)(lb.x - gcx)); out.putFloat((float)(lb.y - gcy));
                out.putFloat((float) lb.h); out.putFloat((float) lb.rot);
                out.putInt(lb.col); out.put(lb.anchor); out.putShort(lb.lid);
                out.putShort((short) l); out.put(b, 0, l);
            }
            return out.array();
        } catch (Throwable t) { t.printStackTrace(); return null; }
    }
}
