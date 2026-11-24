package com.cadapp;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ProjConfig {
    public String projection = "utm";
    public double lonCenter = 360.0;
    public double[][] ctrlLonLat = new double[0][2];
    public double[][] ctrlXy = new double[0][2];
    public boolean loaded = false;
    public String sourceName = "";

    public static String getDisplayName(Context ctx, Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) return new File(uri.getPath()).getName();
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        String p = uri.getLastPathSegment();
        if (p != null) { int s = p.lastIndexOf('/'); return s >= 0 ? p.substring(s + 1) : p; }
        return "";
    }

    public static ProjConfig loadFromStream(InputStream is, String name) {
        ProjConfig cfg = new ProjConfig();
        if (is == null) return cfg;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line).append("\n");
            parseText(cfg, sb.toString());
            cfg.loaded = true; cfg.sourceName = name;
        } catch (Exception e) { e.printStackTrace(); }
        return cfg;
    }

    public static ProjConfig loadForDwg(Context ctx, Uri dwgUri) {
        ProjConfig cfg = new ProjConfig();
        String dwgName = getDisplayName(ctx, dwgUri);
        if (dwgName.isEmpty()) return cfg;
        int dot = dwgName.lastIndexOf('.');
        String baseName = dot > 0 ? dwgName.substring(0, dot) : dwgName;
        String projFileName = baseName + ".proj";

        List<File> candidates = new ArrayList<>();
        if ("file".equals(dwgUri.getScheme()) && dwgUri.getPath() != null) {
            File parent = new File(dwgUri.getPath()).getParentFile();
            if (parent != null) candidates.add(new File(parent, projFileName));
        }
        candidates.add(new File("/storage/emulated/0/1hdm", projFileName));
        candidates.add(new File("/storage/emulated/0/Download", projFileName));

        for (File f : candidates) {
            if (f.exists() && f.isFile() && f.canRead()) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
                    StringBuilder sb = new StringBuilder(); String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                    parseText(cfg, sb.toString());
                    cfg.loaded = true; cfg.sourceName = f.getName();
                    return cfg;
                } catch (Exception e) { e.printStackTrace(); }
            }
        }
        return cfg;
    }

    private static void parseText(ProjConfig cfg, String text) {
        List<double[]> ll = new ArrayList<>(), xy = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue;
            String low = line.toLowerCase();
            if (low.contains("gao") || low.contains("高斯")) cfg.projection = "gauss";
            else if (low.contains("utm")) cfg.projection = "utm";

            if (line.contains("=")) {
                String[] kv = line.split("=", 2);
                String k = kv[0].trim().toLowerCase(), v = kv[1].trim();
                if (k.contains("center") || k.contains("lon") || k.contains("子午")) {
                    try { cfg.lonCenter = Double.parseDouble(v); } catch (Exception ignored) {}
                } else if (k.contains("proj")) {
                    cfg.projection = v;
                }
                line = v;
            }
            String[] parts = line.split("[,;\\s\\t]+");
            List<Double> nums = new ArrayList<>();
            for (String pt : parts) {
                try { nums.add(Double.parseDouble(pt)); } catch (Exception ignored) {}
            }
            if (nums.size() == 1 && Math.abs(nums.get(0)) <= 180) {
                cfg.lonCenter = nums.get(0);
            } else if (nums.size() >= 4) {
                // 4个数字：经度, 纬度, X(北), Y(东)
                ll.add(new double[]{nums.get(0), nums.get(1)});
                xy.add(new double[]{nums.get(2), nums.get(3)});
            }
        }
        cfg.ctrlLonLat = ll.toArray(new double[0][2]);
        cfg.ctrlXy = xy.toArray(new double[0][2]);
    }
}
