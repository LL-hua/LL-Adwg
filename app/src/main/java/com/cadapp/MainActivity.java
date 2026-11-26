package com.cadapp;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;

public class MainActivity extends Activity {
    private CadGLSurfaceView glView;
    private LocationOverlayView locOverlay;
    private ScriptOverlayView scriptOverlay;
    private LinearLayout pickCard;
    private TextView tvPickInfo;
    private String lastCopyCoords = "";
    private double lastPickX = Double.NaN, lastPickY = Double.NaN;
    private LocationManager locationManager;
    private LocationListener locationListener;
    private ProjConfig projConfig = new ProjConfig();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable debounceRun;

    private String curSnap = "", curSnapLayer = "";
    private double lastSoundX = Double.NaN, lastSoundY = Double.NaN;
    private TextView btnPick, btnDist;
    private Button btnCardAction;
    private boolean distMode = false;
    private double measStartX = Double.NaN, measStartY = Double.NaN;

    private void updatePickCardText(double cx, double cy, String snap, String layer) {
        if (!snap.isEmpty()) { curSnap = snap; curSnapLayer = layer; }
        lastCopyCoords = String.format(Locale.US, "%.3f, %.3f", cy, cx);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("🎯 %s · 图层: %s\n", curSnap, curSnapLayer));
        sb.append(String.format(Locale.US, "N: %.3f   E: %.3f", cy, cx));
        if (locOverlay.hasLocation()) {
            double dx = cx - locOverlay.getCadX(), dy = cy - locOverlay.getCadY();
            double dist = Math.hypot(dx, dy);
            double az = Math.toDegrees(Math.atan2(dx, dy));
            if (az < 0) az += 360.0;
            sb.append(String.format(Locale.US, "\n📏 距当前位置: %.3f m  (方位 %.1f°)", dist, az));
        }
        tvPickInfo.setText(sb.toString());
    }

    private double[] gpsToCadXY(double lat, double lon) {
        double[] xy = Projection.gps2Xy(lon, lat, projConfig.projection, projConfig.ctrlLonLat, projConfig.ctrlXy, projConfig.lonCenter);
        if (xy == null || xy.length < 2) return null;
        return new double[]{xy[1], xy[0]};
    }

    private GradientDrawable makeBg(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, radiusDp, getResources().getDisplayMetrics()));
        return d;
    }

    private TextView makeToolBtn(String emoji, String text) {
        TextView tv = new TextView(this);
        tv.setText(emoji + "\n" + text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, 24, 0, 24);
        tv.setLineSpacing(6f, 1f);
        return tv;
    }

    // 【新增】动态修改状态栏和导航栏颜色，适配黑白主题
    private void updateSystemBarColor(boolean isDark) {
        Window window = getWindow();
        View decor = window.getDecorView();
        if (isDark) {
            // 黑底：状态栏图标变白
            window.setStatusBarColor(Color.BLACK);
            window.setNavigationBarColor(Color.BLACK);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            // 白底：状态栏图标变黑
            window.setStatusBarColor(Color.WHITE);
            window.setNavigationBarColor(Color.WHITE);
            decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 保留顶部标题栏，仅去除 ActionBar
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
        }

        FrameLayout root = new FrameLayout(this);

        glView = new CadGLSurfaceView(this);
        locOverlay = new LocationOverlayView(this, glView);
        scriptOverlay = new ScriptOverlayView(this, glView);
        glView.onViewportChanged = () -> { locOverlay.invalidate(); scriptOverlay.invalidate(); };
        root.addView(glView, new FrameLayout.LayoutParams(-1, -1));
        root.addView(locOverlay, new FrameLayout.LayoutParams(-1, -1));
        root.addView(scriptOverlay, new FrameLayout.LayoutParams(-1, -1));

        // 初始化系统栏颜色
        updateSystemBarColor(glView.isDarkMode);

        LinearLayout menuBtnBox = new LinearLayout(this);
        menuBtnBox.setOrientation(LinearLayout.VERTICAL);
        menuBtnBox.setBackground(makeBg(0xCC222222, 8f));
        menuBtnBox.setElevation(10f);
        
        TextView btnMenu = new TextView(this);
        btnMenu.setText("☰");
        btnMenu.setTextColor(0xFFFFFFFF);
        btnMenu.setTextSize(22f);
        btnMenu.setGravity(android.view.Gravity.CENTER);
        btnMenu.setOnClickListener(v -> showTopMenu(v));
        menuBtnBox.addView(btnMenu, new LinearLayout.LayoutParams(110, 110));

        FrameLayout.LayoutParams menuLp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        menuLp.setMargins(0, 0, 48, 240);
        root.addView(menuBtnBox, menuLp);

        LinearLayout bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setBackground(makeBg(0xCC222222, 16f));
        bottomBar.setElevation(10f);
        bottomBar.setPadding(16, 8, 16, 8);

        TextView btnOpen = makeToolBtn("📁", "打开");
        btnOpen.setOnClickListener(v -> showOpenPicker());

        TextView btnLayer = makeToolBtn("🗂", "图层");
        btnLayer.setOnClickListener(v -> showLayerDialog());

        btnPick = makeToolBtn("🎯", "查点");
        btnPick.setOnClickListener(v -> {
            if (distMode) { distMode = false; btnDist.setTextColor(0xFFFFFFFF); locOverlay.clearMeasure(); measStartX = Double.NaN; }
            glView.pickMode = !glView.pickMode;
            btnPick.setTextColor(glView.pickMode ? 0xFF2196F3 : 0xFFFFFFFF);
            if (!glView.pickMode) {
                locOverlay.clearPickedPoint();
                pickCard.setVisibility(View.GONE);
                lastPickX = Double.NaN;
            } else {
                btnCardAction.setText("复制");
                Toast.makeText(this, "查点模式已开启", Toast.LENGTH_SHORT).show();
            }
        });

        btnDist = makeToolBtn("📏", "测距");
        btnDist.setOnClickListener(v -> {
            if (glView.pickMode && !distMode) { glView.pickMode = false; btnPick.setTextColor(0xFFFFFFFF); }
            distMode = !distMode;
            glView.pickMode = distMode;
            btnDist.setTextColor(distMode ? 0xFF2196F3 : 0xFFFFFFFF);
            locOverlay.clearMeasure();
            measStartX = Double.NaN;
            pickCard.setVisibility(View.GONE);
            if (distMode) {
                btnCardAction.setText("重测");
                Toast.makeText(this, "测距已开启：拖动选取第 1 点", Toast.LENGTH_SHORT).show();
            }
        });

        TextView btnLoc = makeToolBtn("🧭", "定位");
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

        int weight = 1;
        bottomBar.addView(btnOpen, new LinearLayout.LayoutParams(0, -2, weight));
        bottomBar.addView(btnLayer, new LinearLayout.LayoutParams(0, -2, weight));
        bottomBar.addView(btnPick, new LinearLayout.LayoutParams(0, -2, weight));
        bottomBar.addView(btnDist, new LinearLayout.LayoutParams(0, -2, weight));
        bottomBar.addView(btnLoc, new LinearLayout.LayoutParams(0, -2, weight));

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        barLp.setMargins(48, 0, 48, 48); 
        root.addView(bottomBar, barLp);

        pickCard = new LinearLayout(this);
        pickCard.setOrientation(LinearLayout.HORIZONTAL);
        pickCard.setBackground(makeBg(0xE6181818, 12f));
        pickCard.setElevation(8f);
        pickCard.setPadding(32, 24, 32, 24);
        pickCard.setGravity(Gravity.CENTER_VERTICAL);
        pickCard.setVisibility(View.GONE);
        
        tvPickInfo = new TextView(this);
        tvPickInfo.setTextColor(0xFFFFFFFF);
        tvPickInfo.setTextSize(14f);
        pickCard.addView(tvPickInfo, new LinearLayout.LayoutParams(0, -2, 1f));
        
        btnCardAction = new Button(this);
        btnCardAction.setText("复制");
        btnCardAction.setTextColor(Color.BLACK);
        btnCardAction.setBackgroundColor(Color.WHITE);
        btnCardAction.setOnClickListener(v -> {
            if (distMode) {
                measStartX = Double.NaN;
                locOverlay.clearMeasure();
                pickCard.setVisibility(View.GONE);
                Toast.makeText(this, "已清零，请重新选取第 1 点", Toast.LENGTH_SHORT).show();
                return;
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null && !lastCopyCoords.isEmpty()) {
                cm.setPrimaryClip(ClipData.newPlainText("CAD_XY", lastCopyCoords)); CadSound.success();
                Toast.makeText(this, "已复制: " + lastCopyCoords, Toast.LENGTH_SHORT).show();
            }
        });
        pickCard.addView(btnCardAction, new LinearLayout.LayoutParams(-2, -2));
        
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        cardLp.setMargins(48, 0, 48, 200);
        root.addView(pickCard, cardLp);

        bottomBar.post(() -> {
            int gap2mm = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, 2.0f, getResources().getDisplayMetrics()));
            int topOfBar = 48 + bottomBar.getHeight() + gap2mm;
            menuLp.setMargins(0, 0, 48, topOfBar);
            menuBtnBox.setLayoutParams(menuLp);
            cardLp.setMargins(48, 0, 48, topOfBar);
            pickCard.setLayoutParams(cardLp);
        });

        glView.onPointPicked = (cx, cy, snap, layer, fx, fy, touching) -> runOnUiThread(() -> {
            locOverlay.setPickedPoint(cx, cy, snap, fx, fy, touching);
            if ((cx != lastSoundX || cy != lastSoundY) && ("端点".equals(snap) || "中点".equals(snap))) { lastSoundX = cx; lastSoundY = cy; if ("端点".equals(snap)) CadSound.snapEnd(); else CadSound.snapMid(); }
            if (distMode) {
                if (Double.isNaN(measStartX)) {
                    tvPickInfo.setText(String.format(Locale.US, "📍 起点(A) · %s\nN: %.3f   E: %.3f\n松开手指锁定第 1 点", snap, cy, cx));
                    pickCard.setVisibility(View.VISIBLE);
                    if (!touching) {
                        measStartX = cx; measStartY = cy; CadSound.confirm();
                        locOverlay.setMeasureStart(cx, cy);
                        Toast.makeText(this, "已锁定起点，请拖动选取第 2 点", Toast.LENGTH_SHORT).show();
                    }
                } else {
                    double dE = cx - measStartX, dN = cy - measStartY;
                    double dist = Math.hypot(dE, dN);
                    double az = Math.toDegrees(Math.atan2(dE, dN));
                    if (az < 0) az += 360.0;
                    tvPickInfo.setText(String.format(Locale.US, "📏 平距: %.3f m  (方位 %.1f°)\nΔN: %.3f m   ΔE: %.3f m\n终点(%s): N %.3f, E %.3f", dist, az, dN, dE, snap, cy, cx));
                    pickCard.setVisibility(View.VISIBLE);
                }
                return;
            }
            lastPickX = cx; lastPickY = cy;
            updatePickCardText(cx, cy, snap, layer);
            pickCard.setVisibility(View.VISIBLE);
        });

        setContentView(root);
        checkLocationPermission();

        SharedPreferences sp = getSharedPreferences("cad_cfg", MODE_PRIVATE);
        CadSound.sfxEnabled = sp.getBoolean("cad_sfx_on", true);
        CadSound.setBgmMode(sp.getInt("cad_bgm_mode", sp.getBoolean("cad_bgm_on", false) ? 1 : 0));
        String lastAsset = sp.getString("last_asset_dwg", null);
        String lastUri = sp.getString("last_dwg_uri", null);
        Uri extUri = getIntent() != null ? getIntent().getData() : null;
        if (extUri != null) {
            try { getContentResolver().takePersistableUriPermission(extUri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            sp.edit().putString("last_dwg_uri", extUri.toString()).remove("last_asset_dwg").apply();
            openDwgUri(extUri, false);
        } else if (lastAsset != null && !lastAsset.isEmpty()) {
            openAssetDwg(lastAsset, true);
        } else if (lastUri != null && !lastUri.isEmpty()) {
            openDwgUri(Uri.parse(lastUri), true);
        } else if (!sp.getBoolean("first_run_done", false)) {
            sp.edit().putBoolean("first_run_done", true).apply();
            try {
                String[] list = getAssets().list("");
                if (list != null) {
                    List<String> dwgs = new ArrayList<>();
                    for (String f : list) if (f.toLowerCase().endsWith(".dwg")) dwgs.add(f);
                    if (!dwgs.isEmpty()) {
                        Collections.sort(dwgs);
                        String firstDwg = dwgs.get(0);
                        sp.edit().putString("last_asset_dwg", firstDwg).apply();
                        openAssetDwg(firstDwg, true);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private void showScriptDialog() {
        CadScriptEngine.ensureSampleScripts();
        File[] files = new File("/storage/emulated/0/1hdm/scripts").listFiles();
        List<File> list = new ArrayList<>(); List<String> names = new ArrayList<>();
        if (files != null) for (File f : files) if (f.isFile()) { list.add(f); names.add("▶ " + f.getName()); }
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle("脚本扩展 (/sdcard/1hdm/scripts/)")
            .setItems(names.toArray(new String[0]), (d, w) -> runScriptFile(list.get(w)))
            .setNeutralButton("语法说明", (d, w) -> new AlertDialog.Builder(this).setTitle("二次开发脚本指令")
                .setMessage("脚本目录: /sdcard/1hdm/scripts/*.txt (或 .js)\n\n1. 变量与数组:\n• let x = 10 / var arr = [1, 2] / arr[0] = 5\n• arr.push(3) / arr.length (数组长度)\n\n2. 循环语句:\n• for (let i = 0; i < 5; i++) { ... }\n• 兼容 LOOP k FROM 0 TO 100 STEP 20 ... ENDLOOP\n\n3. 内置坐标与函数:\n• VIEW_X, VIEW_Y (屏幕中心E/N), GPS_X, GPS_Y, PI\n• sin, cos, tan (角度制), sqrt, abs, hypot\n\n4. 测量与绘图指令:\n• DIST x1, y1, x2, y2 (存入 OUT_DIST, OUT_AZ)\n• COLOR #RRGGBB / CLEAR_DRAW\n• DRAW_LINE / DRAW_CIRCLE / DRAW_POINT / DRAW_TEXT\n\n5. 弹窗与报表导出:\n• PRINT \"文本\" + 变量\n• EXPORT_HEADER / EXPORT_ROW / SAVE_CSV \"表.csv\"").setPositiveButton("知道了", null).show())
            .setPositiveButton("关闭", null);
        if (scriptOverlay != null && scriptOverlay.hasShapes()) b.setNegativeButton("清除脚本图形", (d, w) -> scriptOverlay.clearShapes());
        b.show();
    }
    private void runScriptFile(File f) {
        new Thread(() -> {
            try (FileInputStream fis = new FileInputStream(f); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096]; int n; while ((n = fis.read(buf)) != -1) bos.write(buf, 0, n);
                CadScriptEngine.RunResult res = CadScriptEngine.execute(bos.toString("UTF-8"), new CadScriptEngine.HostBridge() {
                    @Override public double[] getViewCenterCad() { return glView.screenToCad(glView.getWidth() / 2f, glView.getHeight() / 2f); }
                    @Override public double[] getGpsCad() { return locOverlay.hasLocation() ? new double[]{locOverlay.getCadX(), locOverlay.getCadY()} : null; }
                    @Override public double[] gpsToCad(double lat, double lon) { return gpsToCadXY(lat, lon); }
                    @Override public double[] cadToGps(double cx, double cy) { return null; }
                    @Override public void onDrawShapes(List<ScriptOverlayView.Shape> shapes) { runOnUiThread(() -> scriptOverlay.setShapes(shapes)); }
                    @Override public void onClearShapes() { runOnUiThread(() -> scriptOverlay.clearShapes()); }
                });
                CadSound.success();
                runOnUiThread(() -> new AlertDialog.Builder(this).setTitle("执行结果: " + f.getName()).setMessage(res.log + (res.shapeCount > 0 ? "\n已绘制 " + res.shapeCount + " 个图元" : "")).setPositiveButton("确定", null).show());
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, "失败: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
        }).start();
    }
    private void showTopMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(1, 1, 1, "🖼 视图居中");
        popup.getMenu().add(1, 2, 2, "🔠 字体微调");
        popup.getMenu().add(1, 3, 3, "📐 图元管理");
        popup.getMenu().add(1, 4, 4, "🌓 切换黑白背景");
        popup.getMenu().add(1, 5, 5, "📜 脚本扩展");
        popup.getMenu().add(1, 6, 6, CadSound.sfxEnabled ? "🔊 操作提示音：开" : "🔇 操作提示音：关");
        String[] bgmLabels = {"🎶 背景乐：关", "🎵 背景乐：五声音阶", "🍄 背景乐：超级玛丽"};
        popup.getMenu().add(1, 7, 7, bgmLabels[CadSound.bgmMode % 3]);
        popup.getMenu().add(1, 8, 8, "🌌 重力光点音律盒");
        
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) { glView.fitToScreen(); return true; }
            if (item.getItemId() == 2) { showFontTuningDialog(); return true; }
            if (item.getItemId() == 3) { showTypeDialog(); return true; }
            if (item.getItemId() == 5) { showScriptDialog(); return true; }
            if (item.getItemId() == 6) { CadSound.sfxEnabled = !CadSound.sfxEnabled; getSharedPreferences("cad_cfg", MODE_PRIVATE).edit().putBoolean("cad_sfx_on", CadSound.sfxEnabled).apply(); if (CadSound.sfxEnabled) CadSound.confirm(); Toast.makeText(this, "操作提示音已" + (CadSound.sfxEnabled ? "开启" : "关闭"), Toast.LENGTH_SHORT).show(); return true; }
            if (item.getItemId() == 8) { MusicBoxDialog.show(this); return true; }
            if (item.getItemId() == 7) { int nm = (CadSound.bgmMode + 1) % 3; CadSound.setBgmMode(nm); getSharedPreferences("cad_cfg", MODE_PRIVATE).edit().putInt("cad_bgm_mode", nm).apply(); String[] names = {"关闭", "五声音阶纯音", "🍄 超级玛丽 8-bit"}; Toast.makeText(this, "背景乐: " + names[nm], Toast.LENGTH_SHORT).show(); return true; }
            if (item.getItemId() == 4) { 
                glView.toggleTheme(); 
                // 【核心】切换背景时，同步修改系统状态栏颜色
                updateSystemBarColor(glView.isDarkMode); 
                return true; 
            }
            return false;
        });
        popup.show();
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

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (req == 2001 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED)
            startGpsTracking();
    }

    private void startGpsTracking() {
        if (locationManager == null)
            locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null || locationListener != null) return;
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location loc) {
                double[] xy = gpsToCadXY(loc.getLatitude(), loc.getLongitude());
                if (xy != null && xy.length >= 2) {
                    float bearing = loc.hasBearing() ? loc.getBearing() : 0f;
                    locOverlay.updateLocation(xy[0], xy[1], bearing, String.format("N: %.3f\nE: %.3f\n海拔: %.2f m", xy[1], xy[0], loc.getAltitude()));
                    if (!Double.isNaN(lastPickX)) updatePickCardText(lastPickX, lastPickY, "", "");
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
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, 16, 0, 16);
        TextView tvL = new TextView(this);
        tvL.setText(name);
        tvL.setWidth(220);
        row.addView(tvL);
        SeekBar sb = new SeekBar(this);
        sb.setMax(200);
        sb.setProgress((int) (((cur - min) / (max - min)) * 200));
        row.addView(sb, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView tvV = new TextView(this);
        tvV.setText(String.format("%.2f", cur));
        tvV.setWidth(110);
        tvV.setGravity(Gravity.RIGHT);
        row.addView(tvV);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int prog, boolean fromUser) {
                float v = min + (max - min) * (prog / 200f);
                tvV.setText(String.format("%.2f", v));
                if (fromUser) {
                    if (debounceRun != null) handler.removeCallbacks(debounceRun);
                    debounceRun = () -> cb.onVal(v);
                    handler.postDelayed(debounceRun, 80);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        p.addView(row);
    }

    private void showFontTuningDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 32, 48, 16);
        addSlider(box, "整体字高倍率:", 0.2f, 3.0f, glView.textScaleMult, v -> glView.updateTextParams(v, glView.enScale, glView.enSpacing, glView.punctSpacing));
        addSlider(box, "英文字高比例:", 0.5f, 3.0f, glView.enScale, v -> glView.updateTextParams(glView.textScaleMult, v, glView.enSpacing, glView.punctSpacing));
        addSlider(box, "字符间距倍率:", 0.5f, 2.5f, glView.enSpacing, v -> glView.updateTextParams(glView.textScaleMult, glView.enScale, v, glView.punctSpacing));
        addSlider(box, "标点压缩比例:", 0.1f, 1.5f, glView.punctSpacing, v -> glView.updateTextParams(glView.textScaleMult, glView.enScale, glView.enSpacing, v));
        new AlertDialog.Builder(this).setTitle("SHX 字体实时微调 (自动保存)").setView(box)
                .setNeutralButton("恢复默认", (d, w) -> glView.updateTextParams(1.0f, 1.85f, 1.13f, 0.63f)).setPositiveButton("完成", null).show();
    }

    private void showTypeDialog() {
        List<Integer> valid = new ArrayList<>();
        for (int i = 0; i < glView.typeNames.size(); i++)
            if (glView.typeCounts.get(i) > 0) valid.add(i);
        if (valid.isEmpty()) return;
        int n = valid.size();
        String[] labels = new String[n];
        boolean[] checked = new boolean[n];
        for (int i = 0; i < n; i++) {
            int idx = valid.get(i);
            labels[i] = glView.typeNames.get(idx) + " (" + glView.typeCounts.get(idx) + "线)";
            checked[i] = glView.typeVisible[idx];
        }
        new AlertDialog.Builder(this).setTitle("图元类型开关 (0ms秒切)").setMultiChoiceItems(labels, checked, (d, w, isC) -> {
            glView.typeVisible[valid.get(w)] = isC;
            glView.requestRender();
        }).setPositiveButton("完成", null).show();
    }

    private void showLayerDialog() {
        List<Integer> valid = new ArrayList<>();
        for (int i = 0; i < glView.layerNames.size(); i++)
            if (glView.layerCounts.get(i) > 0) valid.add(i);
        if (valid.isEmpty()) return;
        int n = valid.size();
        String[] labels = new String[n + 1];
        boolean[] checked = new boolean[n + 1];
        labels[0] = "👆 长按图元隐藏对应图层 (开关)";
        checked[0] = glView.longPressHideLayer;
        for (int i = 0; i < n; i++) {
            int idx = valid.get(i);
            labels[i + 1] = glView.layerNames.get(idx) + " (" + glView.layerCounts.get(idx) + "线)";
            checked[i + 1] = glView.layerVisible[idx];
        }
        new AlertDialog.Builder(this).setTitle("图层管理开关 (0ms秒切)").setMultiChoiceItems(labels, checked, (d, w, isC) -> {
            if (w == 0) {
                glView.longPressHideLayer = isC;
                getSharedPreferences("cad_cfg", MODE_PRIVATE).edit().putBoolean("long_press_hide_layer", isC).apply();
                Toast.makeText(this, "长按隐藏图层已" + (isC ? "开启" : "关闭"), Toast.LENGTH_SHORT).show();
            } else {
                glView.layerVisible[valid.get(w - 1)] = isC;
                glView.requestRender();
            }
        }).setNeutralButton("全显", (d, w) -> {
            Arrays.fill(glView.layerVisible, true);
            glView.requestRender();
        }).setNegativeButton("全隐", (d, w) -> {
            Arrays.fill(glView.layerVisible, false);
            glView.requestRender();
        }).setPositiveButton("完成", null).show();
    }

    @Override protected void onResume() { super.onResume(); glView.onResume(); CadSound.setBgmMode(getSharedPreferences("cad_cfg", MODE_PRIVATE).getInt("cad_bgm_mode", 0)); }
    @Override protected void onPause() { super.onPause(); glView.onPause(); CadSound.stopBgm(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        if (locationManager != null && locationListener != null)
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) {}
    }

    private void launchSystemPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        i.setType("*/*");
        startActivityForResult(i, 1001);
    }

    private void showOpenPicker() {
        List<String> assetDwgs = new ArrayList<>();
        try {
            String[] list = getAssets().list("");
            if (list != null) {
                for (String f : list) {
                    if (f.toLowerCase().endsWith(".dwg")) assetDwgs.add(f);
                }
            }
        } catch (Exception ignored) {}
        if (assetDwgs.isEmpty()) {
            launchSystemPicker();
            return;
        }
        Collections.sort(assetDwgs);
        String[] items = new String[assetDwgs.size() + 1];
        items[0] = "📂 浏览手机存储中的文件...";
        for (int i = 0; i < assetDwgs.size(); i++) items[i + 1] = "📦 内置资源: " + assetDwgs.get(i);
        new AlertDialog.Builder(this).setTitle("选择要打开的 DWG 图纸")
            .setItems(items, (d, which) -> {
                if (which == 0) launchSystemPicker();
                else {
                    String name = assetDwgs.get(which - 1);
                    getSharedPreferences("cad_cfg", MODE_PRIVATE).edit()
                        .putString("last_asset_dwg", name).remove("last_dwg_uri").apply();
                    openAssetDwg(name, false);
                }
            }).show();
    }

    private void openAssetDwg(final String assetName, final boolean isAuto) {
        new Thread(() -> {
            try (InputStream is = getAssets().open(assetName); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int len;
                while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
                final byte[] bytes = bos.toByteArray();
                String base = assetName.contains(".") ? assetName.substring(0, assetName.lastIndexOf('.')) : assetName;
                String projName = base + ".proj";
                ProjConfig parsed = null;
                try (InputStream pis = getAssets().open(projName)) {
                    parsed = ProjConfig.loadFromStream(pis, "assets/" + projName);
                } catch (Exception ignored) {
                    File extProj = new File("/storage/emulated/0/1hdm/" + projName);
                    if (extProj.exists()) {
                        try (InputStream pis = new FileInputStream(extProj)) {
                            parsed = ProjConfig.loadFromStream(pis, extProj.getName());
                        } catch (Exception ignored2) {}
                    }
                }
                final ProjConfig cfg = (parsed != null) ? parsed : new ProjConfig();
                runOnUiThread(() -> {
                    projConfig = cfg;
                    if (!isAuto) {
                        if (cfg.loaded) Toast.makeText(MainActivity.this, "已加载内置投影: " + cfg.sourceName, Toast.LENGTH_SHORT).show();
                        else Toast.makeText(MainActivity.this, "内置图纸未配同名.proj，使用默认UTM", Toast.LENGTH_SHORT).show();
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

    private void openDwgUri(final Uri uri, final boolean isAuto) {
        new Thread(() -> {
            try (InputStream is = getContentResolver().openInputStream(uri); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
                if (is == null) return;
                byte[] buf = new byte[8192]; int len;
                while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
                final byte[] bytes = bos.toByteArray();
                final ProjConfig cfg = ProjConfig.loadForDwg(MainActivity.this, uri);
                runOnUiThread(() -> {
                    projConfig = cfg;
                    if (!isAuto) {
                        if (cfg.loaded) Toast.makeText(MainActivity.this, "已加载投影: " + cfg.sourceName, Toast.LENGTH_SHORT).show();
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

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1001 && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            getSharedPreferences("cad_cfg", MODE_PRIVATE).edit().putString("last_dwg_uri", uri.toString()).remove("last_asset_dwg").apply();
            openDwgUri(uri, false);
        }
    }
}