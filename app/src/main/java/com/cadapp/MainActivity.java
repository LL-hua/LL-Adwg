package com.cadapp;
import android.Manifest; import android.app.*; import android.content.Intent; import android.content.pm.PackageManager;
import android.location.*; import android.net.Uri; import android.os.*; import android.provider.Settings; import android.view.Gravity; import android.widget.*;
import java.io.*; import java.util.*;

public class MainActivity extends Activity {
    private CadGLSurfaceView glView;
    private LocationOverlayView locOverlay;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private ProjConfig projConfig = new ProjConfig();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable debounceRun;

    private double[] gpsToCadXY(double lat, double lon) {
        double[] xy = Projection.gps2Xy(lon, lat, projConfig.projection, projConfig.ctrlLonLat, projConfig.ctrlXy, projConfig.lonCenter);
        if (xy == null || xy.length < 2) return null;
        return new double[]{xy[1], xy[0]};
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
        }
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL);
        Button btnOpen = new Button(this); btnOpen.setText("打开");
        btnOpen.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            i.setType("*/*");
            startActivityForResult(i, 1001);
        });
        Button btnType = new Button(this); btnType.setText("图元"); btnType.setOnClickListener(v -> showTypeDialog());
        Button btnLayer = new Button(this); btnLayer.setText("图层"); btnLayer.setOnClickListener(v -> showLayerDialog());
        Button btnFont = new Button(this); btnFont.setText("字体"); btnFont.setOnClickListener(v -> showFontTuningDialog());
        Button btnFit = new Button(this); btnFit.setText("居中"); btnFit.setOnClickListener(v -> glView.fitToScreen());
        Button btnLoc = new Button(this); btnLoc.setText("定位");
        btnLoc.setOnClickListener(v -> {
            checkLocationPermission();
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            if (lm != null && !lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                Toast.makeText(this, "请先开启手机定位服务", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                return;
            }
            if (locOverlay.hasLocation()) glView.panToCad(locOverlay.getCadX(), locOverlay.getCadY());
            else Toast.makeText(this, "正在等待卫星定位信号...", Toast.LENGTH_SHORT).show();
        });

        bar.addView(btnOpen, new LinearLayout.LayoutParams(0, -2, 1f)); bar.addView(btnType, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(btnLayer, new LinearLayout.LayoutParams(0, -2, 1f)); bar.addView(btnFont, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(btnFit, new LinearLayout.LayoutParams(0, -2, 1f)); bar.addView(btnLoc, new LinearLayout.LayoutParams(0, -2, 1f));

        FrameLayout stack = new FrameLayout(this);
        glView = new CadGLSurfaceView(this);
        locOverlay = new LocationOverlayView(this, glView);
        glView.onViewportChanged = () -> locOverlay.invalidate();
        stack.addView(glView, new LinearLayout.LayoutParams(-1, -1));
        stack.addView(locOverlay, new LinearLayout.LayoutParams(-1, -1));

        root.addView(bar); root.addView(stack, new LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root);
        checkLocationPermission();

        // 启动时自动打开上次选择的图纸
        String lastUri = getSharedPreferences("cad_cfg", MODE_PRIVATE).getString("last_dwg_uri", null);
        if (lastUri != null && !lastUri.isEmpty()) {
            openDwgUri(Uri.parse(lastUri), true);
        }
    }

    private void checkLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 2001);
                return;
            }
        }
        startGpsTracking();
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == 2001 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startGpsTracking();
    }

    private void startGpsTracking() {
        if (locationManager == null) locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null || locationListener != null) return;
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location loc) {
                double[] xy = gpsToCadXY(loc.getLatitude(), loc.getLongitude());
                if (xy != null && xy.length >= 2) {
                    float bearing = loc.hasBearing() ? loc.getBearing() : 0f;
                    locOverlay.updateLocation(xy[0], xy[1], bearing, String.format("N: %.3f\nE: %.3f\n海拔: %.2f m", xy[1], xy[0], loc.getAltitude()));
                }
            }
            @Override public void onStatusChanged(String p, int s, Bundle e) {}
            @Override public void onProviderEnabled(String p) {}
            @Override public void onProviderDisabled(String p) {}
        };
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener);
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, locationListener);
        } catch (SecurityException ignored) {}
    }
    private interface SliderCb { void onVal(float v); }
    private void addSlider(LinearLayout p, String name, float min, float max, float cur, SliderCb cb) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, 16, 0, 16);
        TextView tvL = new TextView(this); tvL.setText(name); tvL.setWidth(220); row.addView(tvL);
        SeekBar sb = new SeekBar(this); sb.setMax(200); sb.setProgress((int)(((cur - min) / (max - min)) * 200));
        row.addView(sb, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView tvV = new TextView(this); tvV.setText(String.format("%.2f", cur)); tvV.setWidth(110); tvV.setGravity(Gravity.RIGHT); row.addView(tvV);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int prog, boolean fromUser) {
                float v = min + (max - min) * (prog / 200f); tvV.setText(String.format("%.2f", v));
                if (fromUser) { if (debounceRun != null) handler.removeCallbacks(debounceRun); debounceRun = () -> cb.onVal(v); handler.postDelayed(debounceRun, 80); }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {} @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        p.addView(row);
    }

    private void showFontTuningDialog() {
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(48, 32, 48, 16);
        addSlider(box, "整体字高倍率:", 0.2f, 3.0f, glView.textScaleMult, v -> glView.updateTextParams(v, glView.enScale, glView.enSpacing, glView.punctSpacing));
        addSlider(box, "英文字高比例:", 0.5f, 3.0f, glView.enScale, v -> glView.updateTextParams(glView.textScaleMult, v, glView.enSpacing, glView.punctSpacing));
        addSlider(box, "字符间距倍率:", 0.5f, 2.5f, glView.enSpacing, v -> glView.updateTextParams(glView.textScaleMult, glView.enScale, v, glView.punctSpacing));
        addSlider(box, "标点压缩比例:", 0.1f, 1.5f, glView.punctSpacing, v -> glView.updateTextParams(glView.textScaleMult, glView.enScale, glView.enSpacing, v));
        new AlertDialog.Builder(this).setTitle("SHX 字体实时微调 (自动保存)").setView(box)
            .setNeutralButton("恢复默认", (d, w) -> glView.updateTextParams(1.0f, 1.85f, 1.13f, 0.63f)).setPositiveButton("完成", null).show();
    }

    private void showTypeDialog() {
        List<Integer> valid = new ArrayList<>();
        for (int i = 0; i < glView.typeNames.size(); i++) if (glView.typeCounts.get(i) > 0) valid.add(i);
        if (valid.isEmpty()) return; int n = valid.size(); String[] labels = new String[n]; boolean[] checked = new boolean[n];
        for (int i = 0; i < n; i++) { int idx = valid.get(i); labels[i] = glView.typeNames.get(idx) + " (" + glView.typeCounts.get(idx) + "线)"; checked[i] = glView.typeVisible[idx]; }
        new AlertDialog.Builder(this).setTitle("图元类型开关 (0ms秒切)").setMultiChoiceItems(labels, checked, (d, w, isC) -> { glView.typeVisible[valid.get(w)] = isC; glView.requestRender(); }).setPositiveButton("完成", null).show();
    }

    private void showLayerDialog() {
        List<Integer> valid = new ArrayList<>();
        for (int i = 0; i < glView.layerNames.size(); i++) if (glView.layerCounts.get(i) > 0) valid.add(i);
        if (valid.isEmpty()) return; int n = valid.size(); String[] labels = new String[n]; boolean[] checked = new boolean[n];
        for (int i = 0; i < n; i++) { int idx = valid.get(i); labels[i] = glView.layerNames.get(idx) + " (" + glView.layerCounts.get(idx) + "线)"; checked[i] = glView.layerVisible[idx]; }
        new AlertDialog.Builder(this).setTitle("图层管理开关 (0ms秒切)").setMultiChoiceItems(labels, checked, (d, w, isC) -> { glView.layerVisible[valid.get(w)] = isC; glView.requestRender(); })
            .setNeutralButton("全显", (d, w) -> { Arrays.fill(glView.layerVisible, true); glView.requestRender(); })
            .setNegativeButton("全隐", (d, w) -> { Arrays.fill(glView.layerVisible, false); glView.requestRender(); })
            .setPositiveButton("完成", null).show();
    }

    @Override protected void onResume() { super.onResume(); glView.onResume(); }
    @Override protected void onPause() { super.onPause(); glView.onPause(); }
    @Override protected void onDestroy() { super.onDestroy(); if (locationManager != null && locationListener != null) try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {} }

    private void openDwgUri(final Uri uri, final boolean isAuto) {
        new Thread(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                if (is == null) return;
                byte[] buf = new byte[8192]; int len; while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
                final byte[] bytes = bos.toByteArray();
                final ProjConfig cfg = ProjConfig.loadForDwg(MainActivity.this, uri);
                runOnUiThread(() -> {
                    projConfig = cfg;
                    if (!isAuto) {
                        if (cfg.loaded) Toast.makeText(MainActivity.this, "已加载投影: " + cfg.sourceName + " (" + cfg.ctrlXy.length + "点)", Toast.LENGTH_SHORT).show();
                        else Toast.makeText(MainActivity.this, "未找到同名.proj，使用默认UTM", Toast.LENGTH_SHORT).show();
                    }
                    glView.loadDwgAsync(bytes);
                    try {
                        Location last = locationManager != null ? locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) : null;
                        if (last == null && locationManager != null) last = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                        if (last != null && locationListener != null) locationListener.onLocationChanged(last);
                    } catch (SecurityException ignored) {}
                });
            } catch (Exception e) { e.printStackTrace(); }
        }).start();
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1001 && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            getSharedPreferences("cad_cfg", MODE_PRIVATE).edit().putString("last_dwg_uri", uri.toString()).apply();
            openDwgUri(uri, false);
        }
    }
}
