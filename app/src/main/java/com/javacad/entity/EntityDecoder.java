package com.javacad.entity;
import com.javacad.bitstream.BitReader;
import java.util.*;

public class EntityDecoder {
    public static class LayerInfo { public String name = "0"; public int color = 7; }
    public static class Base { public int color = 7; public long layerHandle; public String layerName = "0"; }
    public static class Line extends Base { public double sx, sy, ex, ey; }
    public static class Circle extends Base { public double cx, cy, r; }
    public static class Arc extends Base { public double cx, cy, r, a0, a1; }
    public static class Ellipse extends Base { public double cx, cy, mx, my, ratio, a0, a1; }
    public static class LwPoly extends Base {
        public List<List<Double>> pts = new ArrayList<>();
        public List<Double> bulges = new ArrayList<>();
        public boolean closed;
    }
    public static class TextEnt extends Base { public String text = ""; public double x, y, h, rot; public int anchor = 0; }
    public static class DimEnt extends Base {
        public double mx, my, val, p13x, p13y, p14x, p14y, p10x, p10y;
        public String text = "";
    }

    public static LayerInfo decodeLayer(BitReader r, long expectH, long endBit) {
        if (r.readH() != expectH) return null;
        while (true) {
            int len = r.readBS();
            if (len == 0) break;
            r.readH();
            for (int i = 0; i < len; i++) r.readRC();
        }
        r.readBL(); r.readB(); r.readB();
        r.readBS(); r.readBS();
        int idx = r.readBS();
        long rgb = r.readBL();
        r.readRC();
        LayerInfo info = new LayerInfo();
        String n = readTailString(r, endBit);
        if (!n.trim().isEmpty()) info.name = n;
        int c = idx & 0x1FF;
        if ((rgb & 0xFF) > 0) c = (int)(rgb & 0xFF);
        if (c == 0 || c > 255) c = 7;
        
        info.color = c;
        return info;
    }

    public static Base parseHeaderAndLayer(BitReader r, long expectH, boolean shadowLw, long endBit) {
        if (r.readH() != expectH) return null;
        while (true) {
            int len = r.readBS();
            if (len == 0) break;
            r.readH();
            for (int i = 0; i < len; i++) r.readRC();
        }
        if (r.readB() == 1) {
            long g = r.readBL();
            for (long i = 0; i < g; i++) r.readRC();
        }
        int mode = r.readBB();
        if (mode == 1) return null;
        long reactors = r.readBL();
        if (reactors > 100000) return null;
        int xdicMissing = r.readB();
        r.readB();
        int col = r.readColorIndex();
        r.readBD(); r.readBB(); r.readBB(); r.readBB();
        if (shadowLw) r.readRC();
        r.readB(); r.readB(); r.readB();
        r.readBS();
        if (shadowLw) r.readRC();

        long save = r.tellBits();
        long layerH = 0;
        try {
            r.setBitPos(endBit);
            if (mode == 0) r.readHandleRef(expectH);
            for (long i = 0; i < reactors; i++) r.readHandleRef(expectH);
            if (xdicMissing == 0) r.readHandleRef(expectH);
            layerH = r.readHandleRef(expectH);
        } catch (Exception ignored) {}
        r.setBitPos(save);

        Base b = new Base();
        b.color = col;
        b.layerHandle = layerH;
        return b;
    }

    public static String readTailString(BitReader r, long objSizeBit) {
        if (objSizeBit < 40) return "";
        long save = r.tellBits();
        try {
            r.setBitPos(objSizeBit - 1);
            if (r.readB() == 0) return "";
            r.setBitPos(objSizeBit - 17);
            int ds = r.readRS();
            if ((ds & 0x8000) != 0) {
                r.setBitPos(objSizeBit - 33);
                ds = (ds & 0x7FFF) | (r.readRS() << 15);
            }
            long start = objSizeBit - 17 - ds;
            if (start < 0 || ds <= 0 || ds > 100000) return "";
            r.setBitPos(start);
            return r.readTU();
        } catch (Exception e) { return ""; }
        finally { r.setBitPos(save); }
    }

    public static Line decodeLine(BitReader r) {
        Line l = new Line();
        int z0 = r.readB();
        l.sx = r.readRD(); l.ex = r.readDD(l.sx);
        l.sy = r.readRD(); l.ey = r.readDD(l.sy);
        if (z0 == 0) { double z = r.readRD(); r.readDD(z); }
        r.readBT(); r.readBE();
        return l;
    }

    public static Circle decodeCircle(BitReader r) {
        Circle c = new Circle();
        c.cx = r.readBD(); c.cy = r.readBD(); r.readBD();
        c.r = r.readBD();
        r.readBT(); r.readBE();
        return c;
    }

    public static Arc decodeArc(BitReader r) {
        Arc a = new Arc();
        a.cx = r.readBD(); a.cy = r.readBD(); r.readBD();
        a.r = r.readBD();
        r.readBT(); r.readBE();
        a.a0 = r.readBD(); a.a1 = r.readBD();
        return a;
    }

    public static Ellipse decodeEllipse(BitReader r) {
        Ellipse e = new Ellipse();
        e.cx = r.readBD(); e.cy = r.readBD(); r.readBD();
        e.mx = r.readBD(); e.my = r.readBD(); r.readBD();
        r.read3BD();
        e.ratio = r.readBD(); e.a0 = r.readBD(); e.a1 = r.readBD();
        return e;
    }

    public static LwPoly decodeLwPoly(BitReader r) {
        LwPoly lp = new LwPoly();
        int flags = r.readBS();
        if ((flags & 0x04) != 0) r.readBD();
        if ((flags & 0x08) != 0) r.readBD();
        if ((flags & 0x02) != 0) r.readBD();
        if ((flags & 0x01) != 0) r.read3BD();
        int n = (int) r.readBL();
        if (n <= 0 || n > 100000) return lp;
        int nb = ((flags & 0x10) != 0) ? (int) r.readBL() : 0;
        int nid = ((flags & 0x400) != 0) ? (int) r.readBL() : 0;
        int nw = ((flags & 0x20) != 0) ? (int) r.readBL() : 0;
        double x = r.readRD(), y = r.readRD();
        lp.pts.add(List.of(x, y));
        for (int i = 1; i < n; i++) {
            x = r.readDD(x); y = r.readDD(y);
            lp.pts.add(List.of(x, y));
        }
        for (int i = 0; i < nb; i++) lp.bulges.add(r.readBD());
        lp.closed = (flags & 0x201) != 0;
        return lp;
    }

    public static TextEnt decodeText(BitReader r, long endBit) {
        TextEnt t = new TextEnt();
        int df = r.readRC();
        if ((df & 0x01) == 0) r.readRD();
        t.x = r.readRD(); t.y = r.readRD();
        double ax = t.x, ay = t.y;
        boolean hasAlign = (df & 0x02) == 0;
        if (hasAlign) { ax = r.readDD(t.x); ay = r.readDD(t.y); }
        r.readBE(); r.readBT();
        if ((df & 0x04) == 0) r.readRD();
        if ((df & 0x08) == 0) t.rot = r.readRD();
        t.h = r.readRD();
        if ((df & 0x10) == 0) r.readRD();
        if ((df & 0x20) == 0) r.readBS();
        int hAlign = ((df & 0x40) == 0) ? r.readBS() : 0;
        int vAlign = ((df & 0x80) == 0) ? r.readBS() : 0;
        if ((hAlign != 0 || vAlign != 0) && hasAlign) { t.x = ax; t.y = ay; }
        t.anchor = (hAlign == 1 || hAlign == 4) ? 1 : (hAlign == 2 ? 2 : 0);
        t.text = readTailString(r, endBit);
        return t;
    }

    public static TextEnt decodeMText(BitReader r, long endBit) {
        TextEnt t = new TextEnt();
        t.x = r.readBD(); t.y = r.readBD(); r.readBD();
        r.read3BD();
        double dx = r.readBD(), dy = r.readBD(); r.readBD();
        t.rot = Math.atan2(dy, dx);
        r.readBD(); r.readBD();
        t.h = r.readBD();
        int att = r.readBS();
        if (att >= 1 && att <= 9) t.anchor = (att - 1) % 3;
        t.text = readTailString(r, endBit).replaceAll("\\\\[A-Za-z][^;]*;|\\{|\\}", "").replace("\\P", " ");
        return t;
    }

    public static DimEnt decodeDim(BitReader r, int tc, long endBit) {
        DimEnt d = new DimEnt();
        r.readRC(); r.read3BD();
        d.mx = r.readRD(); d.my = r.readRD();
        r.readBD(); r.readRC();
        d.text = readTailString(r, endBit);
        r.readBD(); r.readBD(); r.read3BD(); r.readBD();
        r.readBS(); r.readBS(); r.readBD();
        d.val = r.readBD();
        r.readB(); r.readB(); r.readB();
        r.readRD(); r.readRD();
        if (tc == 0x15 || tc == 0x16) {
            d.p13x = r.readBD(); d.p13y = r.readBD(); r.readBD();
            d.p14x = r.readBD(); d.p14y = r.readBD(); r.readBD();
            d.p10x = r.readBD(); d.p10y = r.readBD(); r.readBD();
        } else if (tc == 0x14 || tc == 0x17) {
            d.p10x = r.readBD(); d.p10y = r.readBD(); r.readBD();
            d.p13x = r.readBD(); d.p13y = r.readBD(); r.readBD();
            d.p14x = r.readBD(); d.p14y = r.readBD(); r.readBD();
        } else if (tc == 0x19) {
            d.p10x = r.readBD(); d.p10y = r.readBD(); r.readBD();
            d.p13x = r.readBD(); d.p13y = r.readBD(); r.readBD();
        } else if (tc == 0x1A) {
            d.p13x = r.readBD(); d.p13y = r.readBD(); r.readBD();
            d.p10x = r.readBD(); d.p10y = r.readBD(); r.readBD();
        }
        if (d.text.trim().isEmpty() || d.text.contains("<>")) {
            String num = String.format("%.2f", d.val);
            d.text = d.text.trim().isEmpty() ? num : d.text.replace("<>", num);
        }
        return d;
    }
}