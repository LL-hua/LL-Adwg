package com.cadapp;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class CadScriptEngine {
    public interface HostBridge {
        double[] getViewCenterCad();
        double[] getGpsCad();
        double[] gpsToCad(double lat, double lon);
        double[] cadToGps(double cadX, double cadY);
        void onDrawShapes(List<ScriptOverlayView.Shape> shapes);
        void onClearShapes();
    }
    public static class RunResult { public String log, savedCsvPath; public int shapeCount; }

    public static void ensureSampleScripts() {
        File dir = new File("/storage/emulated/0/1hdm/scripts");
        if (!dir.exists()) dir.mkdirs();
        String s3 = "// JS风格示例：使用 let/var、数组 [] 与 for 循环批量绘制多边形控制网\n"
                + "CLEAR_DRAW\n"
                + "let cx = VIEW_X\n"
                + "let cy = VIEW_Y\n"
                + "let dx = [-40, 40, 40, -40, -40]\n"
                + "let dy = [-30, -30, 30, 30, -30]\n"
                + "var pts = []\n"
                + "for (let i = 0; i < 4; i++) {\n"
                + "  let x1 = cx + dx[i]\n"
                + "  let y1 = cy + dy[i]\n"
                + "  let x2 = cx + dx[i + 1]\n"
                + "  let y2 = cy + dy[i + 1]\n"
                + "  DRAW_LINE x1, y1, x2, y2\n"
                + "  DRAW_POINT x1, y1, \"K\" + (i + 1)\n"
                + "  pts.push(x1)\n"
                + "  pts.push(y1)\n"
                + "}\n"
                + "PRINT \"共生成控制点坐标个数: \" + pts.length\n"
                + "PRINT \"K1坐标: E=\" + pts[0] + \", N=\" + pts[1]\n";
        try {
            File f0 = new File(dir, "00_脚本开发手册与全示例.txt");
            if (!f0.exists()) writeUtf8(f0, "// =========================================================================\n// LL-dwg 二次开发脚本随身手册与全功能示例 (UTF-8 编码)\n// 存放目录: /sdcard/1hdm/scripts/*.txt (或 .js)\n// =========================================================================\n// 【一、语法规则】\n// 1. 变量定义: let x = 10  /  var y = 20  /  const r = 5  /  或直接 x = x + 1\n// 2. 数组操作: let arr = [10, 20, 30]  |  arr[0]  |  arr.push(40)  |  arr.length\n// 3. 循环语句: for (let i = 0; i < 5; i++) { ... }  或  LOOP k FROM 0 TO 100 STEP 20 ... ENDLOOP\n// 4. 数学函数: sin(度), cos(度), tan(度), sqrt(x), abs(x), hypot(dx, dy)\n// 5. 内置变量: VIEW_X, VIEW_Y (当前屏幕中心E/N), GPS_X, GPS_Y (当前定位E/N), PI\n//\n// 【二、可调用指令清单】\n// • COLOR RRGGBB               -> 设置绘图颜色 (如 FF3B30红, 34C759绿, 00E5FF青, FFCC00黄)\n// • DRAW_LINE x1, y1, x2, y2   -> 绘制直线段\n// • DRAW_CIRCLE cx, cy, r      -> 绘制圆 (中心cx,cy, 半径r米)\n// • DRAW_POINT x, y, \"标签\"    -> 绘制双环控制点并标注文字\n// • DRAW_TEXT x, y, \"文字\"     -> 绘制纯文字标注\n// • CLEAR_DRAW                 -> 清空脚本绘制的所有图形\n// • DIST x1, y1, x2, y2        -> 算平距与方位角，结果存入 OUT_DIST 和 OUT_AZ\n// • PRINT \"文本\" + 变量        -> 输出结果到弹窗\n// • EXPORT_HEADER \"列1,列2\"    -> 设置CSV表头\n// • EXPORT_ROW v1, v2          -> 追加CSV数据行 (保留4位小数)\n// • SAVE_CSV \"文件名.csv\"      -> 保存到 /sdcard/1hdm/output/文件名.csv\n// =========================================================================\n\nCLEAR_DRAW\nlet x0 = VIEW_X\nlet y0 = VIEW_Y\n\n// --- 示例A: 极坐标辐射法批量放样 ---\nlet azList = [15, 45, 75, 105, 135]\nlet distList = [20, 25, 30, 25, 20]\nCOLOR #00E5FF\nDRAW_POINT x0, y0, \"测站O\"\nEXPORT_HEADER \"点名,东坐标E(m),北坐标N(m),平距(m),方位角(度)\"\n\nfor (let i = 0; i < azList.length; i++) {\n  let az = azList[i]\n  let d = distList[i]\n  let px = x0 + d * sin(az)\n  let py = y0 + d * cos(az)\n  DRAW_LINE x0, y0, px, py\n  DRAW_POINT px, py, \"P\" + (i + 1)\n  EXPORT_ROW i + 1, px, py, d, az\n}\n\n// --- 示例B: 绘制安全警戒圆与文字提示 ---\nCOLOR #FF3B30\nDRAW_CIRCLE x0, y0, 35\nDRAW_TEXT x0 - 15, y0 + 38, \"35m 施工警戒圈\"\n\n// --- 示例C: 反算两点平距与方位角 ---\nlet xA = x0 + 20 * sin(45)\nlet yA = y0 + 20 * cos(45)\nDIST x0, y0, xA, yA\nPRINT \"测站至P2反算平距 = \" + OUT_DIST + \" m, 方位角 = \" + OUT_AZ + \"°\"\nSAVE_CSV \"全功能演示放样表.csv\"");
        } catch (Exception ignored) {}
    }

    private static void writeUtf8(File f, String txt) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) { fos.write(txt.getBytes(StandardCharsets.UTF_8)); }
    }
    public static RunResult execute(String scriptText, HostBridge bridge) {
        RunResult res = new RunResult();
        StringBuilder outLog = new StringBuilder(), csvBuf = new StringBuilder();
        List<ScriptOverlayView.Shape> shapes = new ArrayList<>();
        Map<String, Double> vars = new LinkedHashMap<>();
        Map<String, List<Double>> arrs = new LinkedHashMap<>();

        double[] vc = bridge.getViewCenterCad(), gc = bridge.getGpsCad();
        vars.put("VIEW_X", vc != null ? vc[0] : 0.0); vars.put("VIEW_Y", vc != null ? vc[1] : 0.0);
        vars.put("GPS_X", gc != null ? gc[0] : 0.0); vars.put("GPS_Y", gc != null ? gc[1] : 0.0);
        vars.put("PI", Math.PI);

        List<String> lines = new ArrayList<>();
        for (String raw : scriptText.replace("\r", "").split("\n")) {
            String t = raw.trim();
            if (t.endsWith(";")) t = t.substring(0, t.length() - 1).trim();
            if (!t.isEmpty() && !t.startsWith("#") && !t.startsWith("//")) lines.add(t);
        }

        int curColor = 0xFF00E5FF, pc = 0, steps = 0;
        Deque<Integer> loopPc = new ArrayDeque<>();
        Deque<String> loopVar = new ArrayDeque<>(), loopCond = new ArrayDeque<>(), loopStepExpr = new ArrayDeque<>();

        try {
            while (pc < lines.size()) {
                if (++steps > 50000) throw new RuntimeException("循环超过50000步上限");
                String line = lines.get(pc), upper = line.toUpperCase(Locale.US);

                if (line.startsWith("for ") || line.startsWith("for(")) {
                    int p1 = line.indexOf('('), p2 = line.lastIndexOf(')');
                    String[] parts = line.substring(p1 + 1, p2).split(";");
                    String init = parts[0].trim().replaceFirst("^(let|var|const)\\s+", "");
                    int eq = init.indexOf('=');
                    String vn = init.substring(0, eq).trim();
                    vars.put(vn, evalExpr(init.substring(eq + 1), vars, arrs));
                    String cond = parts[1].trim();
                    String step = parts[2].trim().replace("++", " = " + vn + " + 1").replace("--", " = " + vn + " - 1");
                    if (step.contains("+=")) step = vn + " = " + vn + " + " + step.split("\\+=")[1];
                    if (!evalCond(cond, vars, arrs)) {
                        int depth = 1;
                        while (++pc < lines.size() && depth > 0) { if (lines.get(pc).endsWith("{")) depth++; if (lines.get(pc).equals("}")) depth--; }
                        continue;
                    }
                    loopPc.push(pc); loopVar.push(vn); loopCond.push(cond); loopStepExpr.push(step);
                } else if (upper.startsWith("LOOP ")) {
                    String[] tk = line.split("\\s+");
                    double st = evalExpr(tk[3], vars, arrs), ed = evalExpr(tk[5], vars, arrs), sp = tk.length >= 8 ? evalExpr(tk[7], vars, arrs) : 1.0;
                    vars.put(tk[1], st); loopPc.push(pc); loopVar.push(tk[1]);
                    loopCond.push(sp > 0 ? tk[1] + " <= " + ed : tk[1] + " >= " + ed);
                    loopStepExpr.push(tk[1] + " = " + tk[1] + " + " + sp);
                } else if (line.equals("}") || upper.equals("ENDLOOP")) {
                    if (!loopPc.isEmpty()) {
                        String step = loopStepExpr.peek(); int eq = step.indexOf('=');
                        vars.put(step.substring(0, eq).trim(), evalExpr(step.substring(eq + 1), vars, arrs));
                        if (evalCond(loopCond.peek(), vars, arrs)) { pc = loopPc.peek() + 1; continue; }
                        loopPc.pop(); loopVar.pop(); loopCond.pop(); loopStepExpr.pop();
                    }
                } else if (line.contains(".push(") && line.endsWith(")")) {
                    int dot = line.indexOf(".push("); String an = line.substring(0, dot).trim();
                    arrs.computeIfAbsent(an, k -> new ArrayList<>()).add(evalExpr(line.substring(dot + 6, line.length() - 1), vars, arrs));
                } else if (upper.startsWith("COLOR ")) {
                    String hx = line.substring(6).trim().replace("#", "");
                    if (hx.length() == 6) curColor = 0xFF000000 | Integer.parseInt(hx, 16);
                } else if (upper.equals("CLEAR_DRAW")) { shapes.clear(); bridge.onClearShapes();
                } else if (upper.startsWith("DRAW_LINE ")) {
                    List<String> a = splitArgs(line.substring(10));
                    if (a.size() >= 4) shapes.add(new ScriptOverlayView.Shape(1, evalExpr(a.get(0), vars, arrs), evalExpr(a.get(1), vars, arrs), evalExpr(a.get(2), vars, arrs), evalExpr(a.get(3), vars, arrs), 0, null, curColor));
                } else if (upper.startsWith("DRAW_CIRCLE ")) {
                    List<String> a = splitArgs(line.substring(12));
                    if (a.size() >= 3) shapes.add(new ScriptOverlayView.Shape(2, evalExpr(a.get(0), vars, arrs), evalExpr(a.get(1), vars, arrs), 0, 0, evalExpr(a.get(2), vars, arrs), null, curColor));
                } else if (upper.startsWith("DRAW_POINT ")) {
                    List<String> a = splitArgs(line.substring(11));
                    if (a.size() >= 2) shapes.add(new ScriptOverlayView.Shape(3, evalExpr(a.get(0), vars, arrs), evalExpr(a.get(1), vars, arrs), 0, 0, 0, a.size() >= 3 ? evalString(a.get(2), vars, arrs) : "", curColor));
                } else if (upper.startsWith("DRAW_TEXT ")) {
                    List<String> a = splitArgs(line.substring(10));
                    if (a.size() >= 3) shapes.add(new ScriptOverlayView.Shape(4, evalExpr(a.get(0), vars, arrs), evalExpr(a.get(1), vars, arrs), 0, 0, 0, evalString(a.get(2), vars, arrs), curColor));
                }
                else if (upper.startsWith("DIST ")) {
                    List<String> a = splitArgs(line.substring(5));
                    if (a.size() >= 4) {
                        double dx = evalExpr(a.get(2), vars, arrs) - evalExpr(a.get(0), vars, arrs), dy = evalExpr(a.get(3), vars, arrs) - evalExpr(a.get(1), vars, arrs);
                        double az = Math.toDegrees(Math.atan2(dx, dy)); if (az < 0) az += 360.0;
                        vars.put("OUT_DIST", Math.hypot(dx, dy)); vars.put("OUT_AZ", az);
                    }
                } else if (upper.startsWith("PRINT ")) { outLog.append(evalString(line.substring(6), vars, arrs)).append("\n");
                } else if (upper.startsWith("EXPORT_HEADER ")) { csvBuf.append(evalString(line.substring(14), vars, arrs)).append("\n");
                } else if (upper.startsWith("EXPORT_ROW ")) {
                    List<String> a = splitArgs(line.substring(11));
                    for (int i = 0; i < a.size(); i++) { if (i > 0) csvBuf.append(","); csvBuf.append(String.format(Locale.US, "%.4f", evalExpr(a.get(i), vars, arrs))); }
                    csvBuf.append("\n");
                } else if (upper.startsWith("SAVE_CSV ")) {
                    String fn = evalString(line.substring(9), vars, arrs).trim(); if (fn.isEmpty()) fn = "output.csv";
                    File outDir = new File("/storage/emulated/0/1hdm/output"); if (!outDir.exists()) outDir.mkdirs();
                    File outFile = new File(outDir, fn); writeUtf8(outFile, "\uFEFF" + csvBuf.toString());
                    res.savedCsvPath = outFile.getAbsolutePath(); outLog.append("已导出: ").append(res.savedCsvPath).append("\n");
                } else if (line.contains("=")) {
                    String clean = line.replaceFirst("^(let|var|const|SET|LET)\\s+", "").trim();
                    int eq = clean.indexOf('='); String lhs = clean.substring(0, eq).trim(), rhs = clean.substring(eq + 1).trim();
                    if (rhs.startsWith("[") && rhs.endsWith("]")) {
                        List<Double> list = new ArrayList<>(); String inner = rhs.substring(1, rhs.length() - 1).trim();
                        if (!inner.isEmpty()) for (String it : splitArgs(inner)) list.add(evalExpr(it, vars, arrs));
                        arrs.put(lhs, list);
                    } else if (lhs.contains("[") && lhs.endsWith("]")) {
                        int lb = lhs.indexOf('['); String an = lhs.substring(0, lb).trim();
                        int idx = (int) Math.round(evalExpr(lhs.substring(lb + 1, lhs.length() - 1), vars, arrs));
                        List<Double> list = arrs.computeIfAbsent(an, k -> new ArrayList<>());
                        while (list.size() <= idx) list.add(0.0); list.set(idx, evalExpr(rhs, vars, arrs));
                    } else vars.put(lhs, evalExpr(rhs, vars, arrs));
                }
                pc++;
            }
        } catch (Exception e) { outLog.append("第").append(pc + 1).append("行错误: ").append(e.getMessage()).append("\n"); }
        if (!shapes.isEmpty()) { bridge.onDrawShapes(shapes); res.shapeCount = shapes.size(); }
        res.log = outLog.length() > 0 ? outLog.toString() : "执行完成";
        return res;
    }

    private static boolean evalCond(String cond, Map<String, Double> vars, Map<String, List<Double>> arrs) {
        for (String op : new String[]{"<=", ">=", "==", "!=", "<", ">"}) {
            int idx = cond.indexOf(op);
            if (idx > 0) {
                double a = evalExpr(cond.substring(0, idx), vars, arrs), b = evalExpr(cond.substring(idx + op.length()), vars, arrs);
                if (op.equals("<=")) return a <= b + 1e-9; if (op.equals(">=")) return a >= b - 1e-9;
                if (op.equals("==")) return Math.abs(a - b) < 1e-9; if (op.equals("!=")) return Math.abs(a - b) >= 1e-9;
                if (op.equals("<")) return a < b - 1e-9; if (op.equals(">")) return a > b + 1e-9;
            }
        }
        return evalExpr(cond, vars, arrs) != 0;
    }

    private static List<String> splitArgs(String s) {
        List<String> list = new ArrayList<>(); StringBuilder cur = new StringBuilder(); boolean q = false; int d = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i); if (ch == '"') q = !q;
            else if (!q) { if (ch == '(' || ch == '[') d++; else if (ch == ')' || ch == ']') d--; else if (ch == ',' && d == 0) { list.add(cur.toString().trim()); cur.setLength(0); continue; } }
            cur.append(ch);
        }
        if (cur.length() > 0) list.add(cur.toString().trim());
        return list;
    }

    private static String evalString(String expr, Map<String, Double> vars, Map<String, List<Double>> arrs) {
        StringBuilder sb = new StringBuilder();
        for (String p : expr.split("\\+")) {
            String t = p.trim();
            if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) sb.append(t.substring(1, t.length() - 1));
            else if (!t.isEmpty()) { double v = evalExpr(t, vars, arrs); sb.append(Math.abs(v - Math.round(v)) < 1e-9 ? String.valueOf(Math.round(v)) : String.format(Locale.US, "%.3f", v)); }
        }
        return sb.toString();
    }

    private static double evalExpr(final String str, final Map<String, Double> vars, final Map<String, List<Double>> arrs) {
        return new Object() {
            int pos = -1, ch;
            void next() { ch = (++pos < str.length()) ? str.charAt(pos) : -1; }
            boolean eat(int c) { while (ch == ' ') next(); if (ch == c) { next(); return true; } return false; }
            double parse() { next(); return expr(); }
            double expr() { double x = term(); for (;;) { if (eat('+')) x += term(); else if (eat('-')) x -= term(); else return x; } }
            double term() { double x = factor(); for (;;) { if (eat('*')) x *= factor(); else if (eat('/')) x /= factor(); else return x; } }
            double factor() {
                if (eat('+')) return factor(); if (eat('-')) return -factor();
                double x; int sp = this.pos;
                if (eat('(')) { x = expr(); eat(')'); }
                else if ((ch >= '0' && ch <= '9') || ch == '.') { while ((ch >= '0' && ch <= '9') || ch == '.') next(); x = Double.parseDouble(str.substring(sp, this.pos)); }
                else if (Character.isLetter(ch) || ch == '_') {
                    while (Character.isLetterOrDigit(ch) || ch == '_' || ch == '.') next(); String nm = str.substring(sp, this.pos);
                    if (nm.endsWith(".length")) { List<Double> l = arrs.get(nm.substring(0, nm.length() - 7)); x = l != null ? l.size() : 0.0; }
                    else if (eat('[')) { int idx = (int) Math.round(expr()); eat(']'); List<Double> l = arrs.get(nm); if (l == null || idx < 0 || idx >= l.size()) throw new RuntimeException("数组越界:" + nm); x = l.get(idx); }
                    else if (eat('(')) {
                        double a = expr(), b = eat(',') ? expr() : 0; eat(')'); String fn = nm.toLowerCase(Locale.US);
                        if (fn.equals("sin")) x = Math.sin(Math.toRadians(a)); else if (fn.equals("cos")) x = Math.cos(Math.toRadians(a));
                        else if (fn.equals("tan")) x = Math.tan(Math.toRadians(a)); else if (fn.equals("sqrt")) x = Math.sqrt(a);
                        else if (fn.equals("abs")) x = Math.abs(a); else if (fn.equals("hypot")) x = Math.hypot(a, b);
                        else throw new RuntimeException("未知函数:" + nm);
                    } else { Double v = vars.get(nm); if (v == null) throw new RuntimeException("未定义:" + nm); x = v; }
                } else throw new RuntimeException("非法字符");
                return x;
            }
        }.parse();
    }
}
