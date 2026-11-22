package com.cadapp;

public class Projection {
    public static double[] gauss_proj(double L, double B, double lonCenter) {
        double pi = 3.141592653589793238463, p0 = 206264.8062470963551564, e = 0.00669438002290, e1 = 0.00673949677548, b = 6356752.3141, a = 6378137.0;
        B = B * pi / 180; L = L * pi / 180;
        double L_center = (lonCenter >= 359) ? 3 * Math.floor(L * 180 / pi / 3.0 + 0.5) : lonCenter;
        double l = (L * 180 / pi - L_center) * 3600;
        double M0 = a * (1 - e), M2 = 1.5 * e * M0, M4 = 1.25 * e * M2, M6 = 7.0 / 6.0 * e * M4, M8 = 1.125 * e * M6;
        double a0 = M0 + M2 / 2.0 + 3.0 / 8.0 * M4 + 5.0 / 16.0 * M6 + 35.0 / 128.0 * M8;
        double a2 = M2 / 2.0 + M4 / 2.0 + 15.0 / 32.0 * M6 + 7.0 / 16.0 * M8;
        double a4 = M4 / 8.0 + 3.0 / 16.0 * M6 + 7.0 / 32.0 * M8, a6 = M6 / 32.0 + M8 / 16.0, a8 = M8 / 128.0;
        double Xz = a0 * B - a2 / 2.0 * Math.sin(2 * B) + a4 / 4.0 * Math.sin(4 * B) - a6 / 6.0 * Math.sin(6 * B) + a8 / 8.0 * Math.sin(8 * B);
        double c = a * a / b, V = Math.sqrt(1 + e1 * Math.cos(B) * Math.cos(B)), N = c / V, t = Math.tan(B), n = e1 * Math.cos(B) * Math.cos(B);
        double m1 = N * Math.cos(B), m2 = N / 2.0 * Math.sin(B) * Math.cos(B), m3 = N / 6.0 * Math.pow(Math.cos(B), 3) * (1 - t * t + n);
        double m4 = N / 24.0 * Math.sin(B) * Math.pow(Math.cos(B), 3) * (5 - t * t + 9 * n);
        double m5 = N / 120.0 * Math.pow(Math.cos(B), 5) * (5 - 18 * t * t + Math.pow(t, 4) + 14 * n - 58 * n * t * t);
        double m6 = N / 720.0 * Math.sin(B) * Math.pow(Math.cos(B), 5) * (61 - 58 * t * t + Math.pow(t, 4));
        double x = Xz + m2 * l * l / Math.pow(p0, 2) + m4 * Math.pow(l, 4) / Math.pow(p0, 4) + m6 * Math.pow(l, 6) / Math.pow(p0, 6);
        double y = m1 * l / p0 + m3 * Math.pow(l, 3) / Math.pow(p0, 3) + m5 * Math.pow(l, 5) / Math.pow(p0, 5) + 500000;
        return new double[]{x, y, L_center};
    }

    public static double[] utm_proj(double longitude, double latitude) {
        double a = 6378137.0, f = 1 / 298.257223563, e2 = 2 * f - f * f, ep2 = e2 / (1 - e2), k0 = 0.9996;
        int zone = (int) Math.floor((longitude + 180) / 6) + 1;
        double cm = (zone - 1) * 6 - 180 + 3, latR = Math.toRadians(latitude), lonR = Math.toRadians(longitude), cmR = Math.toRadians(cm);
        double N = a / Math.sqrt(1 - e2 * Math.pow(Math.sin(latR), 2)), T = Math.pow(Math.tan(latR), 2), C = ep2 * Math.pow(Math.cos(latR), 2), A = (lonR - cmR) * Math.cos(latR);
        double M = a * ((1 - e2 / 4 - 3 * e2 * e2 / 64 - 5 * Math.pow(e2, 3) / 256) * latR
                - (3 * e2 / 8 + 3 * e2 * e2 / 32 + 45 * Math.pow(e2, 3) / 1024) * Math.sin(2 * latR)
                + (15 * e2 * e2 / 256 + 45 * Math.pow(e2, 3) / 1024) * Math.sin(4 * latR) - (35 * Math.pow(e2, 3) / 3072) * Math.sin(6 * latR));
        double east = k0 * N * (A + (1 - T + C) * Math.pow(A, 3) / 6 + (5 - 18 * T + T * T + 72 * C - 58 * ep2) * Math.pow(A, 5) / 120) + 500000.0;
        double north = k0 * (M + N * Math.tan(latR) * (A * A / 2 + (5 - T + 9 * C + 4 * C * C) * Math.pow(A, 4) / 24 + (61 - 58 * T + T * T + 600 * C - 330 * ep2) * Math.pow(A, 6) / 720));
        if (latitude < 0) north += 10000000.0;
        return new double[]{north, east, zone};
    }
    public static double[] cs4(double[] source, double[] target) {
        if (source == null || target == null || source.length != target.length || source.length < 4 || source.length % 2 != 0) return new double[]{0, 0, 0, 1};
        int n = source.length / 2;
        double sx1 = 0, sy1 = 0, sx2 = 0, sy2 = 0;
        for (int i = 0; i < n; i++) { sx1 += source[2*i]; sy1 += source[2*i+1]; sx2 += target[2*i]; sy2 += target[2*i+1]; }
        double mx1 = sx1 / n, my1 = sy1 / n, mx2 = sx2 / n, my2 = sy2 / n;
        double H11 = 0, B1 = 0, B2 = 0;
        for (int i = 0; i < n; i++) {
            double x1 = source[2*i] - mx1, y1 = source[2*i+1] - my1, x2 = target[2*i] - mx2, y2 = target[2*i+1] - my2;
            H11 += x1 * x1 + y1 * y1; B1 += x1 * x2 + y1 * y2; B2 += x1 * y2 - y1 * x2;
        }
        if (Math.abs(H11 * H11) < 1e-15) return new double[]{0, 0, 0, 1};
        double a = B1 / H11, b = B2 / H11, scale = Math.hypot(a, b), rot = Math.atan2(b, a);
        return new double[]{mx2 - (a * mx1 - b * my1), my2 - (b * mx1 + a * my1), rot, scale};
    }

    public static double[] fourParameterTransform(double x, double y, double dx, double dy, double rot, double scale) {
        return new double[]{scale * (x * Math.cos(rot) - y * Math.sin(rot)) + dx, scale * (x * Math.sin(rot) + y * Math.cos(rot)) + dy};
    }

    public static double[] gps2Xy(double lon, double lat, String proj, double[][] ctrlLonLat, double[][] ctrlXy, double center) {
        String p = proj == null ? "" : proj.toLowerCase();
        boolean isGao = p.contains("gao") || p.contains("高斯");
        if (ctrlLonLat == null || ctrlXy == null || ctrlLonLat.length == 0) {
            double[] raw = isGao ? gauss_proj(lon, lat, center) : utm_proj(lon, lat);
            return new double[]{raw[0], raw[1]};
        }
        int n = Math.min(ctrlLonLat.length, ctrlXy.length);
        double[] src = new double[n * 2], dst = new double[n * 2];
        for (int i = 0; i < n; i++) {
            double[] ls = isGao ? gauss_proj(ctrlLonLat[i][0], ctrlLonLat[i][1], center) : utm_proj(ctrlLonLat[i][0], ctrlLonLat[i][1]);
            src[2*i] = ls[0]; src[2*i+1] = ls[1];
            dst[2*i] = ctrlXy[i][0]; dst[2*i+1] = ctrlXy[i][1];
        }
        double[] p4 = cs4(src, dst);
        double[] cur = isGao ? gauss_proj(lon, lat, center) : utm_proj(lon, lat);
        return fourParameterTransform(cur[0], cur[1], p4[0], p4[1], p4[2], p4[3]);
    }
}
