package com.caibao.a11yprobe;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * P0-2 存活探针：记录 AccessibilityService 在 HyperOS 3 下的完整生命周期，
 * 心跳输出前台包名 + 可读节点统计，用于验证：
 *   1. adb 追加写入后能否成功绑定（HyperOS 是否放行）
 *   2. 事件流是否持续、节点树是否可读（对照微信 uiautomator nodes=1）
 *   3. 进程被杀后系统是否自动重绑、耗时多久
 *   4. 锁屏/重启后是否存活
 */
public class ProbeService extends AccessibilityService {

    static final String TAG = "A11yProbe";
    static volatile boolean connected = false;
    static volatile int eventCount = 0;
    static volatile String lastPkg = "-";
    static volatile ProbeService instance;

    private long lastHeartbeatAt = 0;
    private android.os.Handler handler;
    private boolean timerOn = false;

    private void startTimer() {
        if (timerOn) return;
        timerOn = true;
        handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                logHeartbeat("timer");
                if (timerOn) handler.postDelayed(this, 10000);
            }
        }, 10000);
    }

    private void stopTimer() {
        timerOn = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
    }

    // ---------- 日志（logcat + 私有文件双写） ----------

    private static File logFile() {
        ProbeService s = instance;
        return s != null
                ? new File(s.getFilesDir(), "probe.log")
                : new File("/data/data/com.caibao.a11yprobe/files/probe.log");
    }

    static void fileLog(String msg) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                .format(new Date()) + " " + msg;
        Log.i(TAG, line);
        try {
            Writer w = new OutputStreamWriter(
                    new FileOutputStream(logFile(), true), StandardCharsets.UTF_8);
            w.write(line + "\n");
            w.close();
        } catch (Throwable t) {
            Log.e(TAG, "fileLog fail", t);
        }
    }

    static String tailLog() {
        Deque<String> lines = new ArrayDeque<>();
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(logFile()), StandardCharsets.UTF_8));
            String l;
            while ((l = r.readLine()) != null) {
                if (lines.size() >= 60) lines.pollFirst();
                lines.addLast(l);
            }
            r.close();
        } catch (Throwable t) {
            return "(无日志: " + t + ")";
        }
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append("\n");
        return sb.toString();
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onServiceConnected() {
        instance = this;
        connected = true;
        startTimer();
        fileLog("LIFECYCLE onServiceConnected flags=" + getServiceInfo().flags);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        stopTimer();
        fileLog("LIFECYCLE onUnbind（系统解绑，事件流中断）");
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        connected = false;
        stopTimer();
        fileLog("LIFECYCLE onDestroy（服务对象销毁）");
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        fileLog("LIFECYCLE onTaskRemoved（任务被划掉）");
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onInterrupt() {
        fileLog("LIFECYCLE onInterrupt（被系统中断）");
    }

    // ---------- 事件流 + 心跳（5s 节流） ----------

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        eventCount++;
        if (e != null && e.getPackageName() != null) {
            lastPkg = e.getPackageName().toString();
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastHeartbeatAt < 5000) return;
        logHeartbeat("event");
    }

    private void logHeartbeat(String src) {
        lastHeartbeatAt = SystemClock.uptimeMillis();
        StringBuilder sb = new StringBuilder("HEARTBEAT src=").append(src)
                .append(" pkg=").append(lastPkg)
                .append(" totalEvents=").append(eventCount);
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r == null) {
                sb.append(" root=null");
            } else {
                int[] n = {0}, c = {0};
                countNodes(r, n, c, 0);
                sb.append(" nodes=").append(n[0])
                  .append(" clickable=").append(c[0])
                  .append(" windowPkg=").append(r.getPackageName());
            }
        } catch (Throwable t) {
            sb.append(" walkErr=").append(t.getClass().getSimpleName());
        }
        fileLog(sb.toString());
    }

    private static void countNodes(AccessibilityNodeInfo n, int[] nodeCnt,
                                   int[] clickCnt, int depth) {
        if (depth > 50 || nodeCnt[0] > 5000) return;
        nodeCnt[0]++;
        if (n.isClickable()) clickCnt[0]++;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) {
                countNodes(c, nodeCnt, clickCnt, depth + 1);
                c.recycle();
            }
        }
    }

    // ---------- 手动深读（Activity 按钮调用） ----------

    static String dumpRoot(int maxNodes) {
        ProbeService s = instance;
        if (s == null || !connected) return "服务未连接，无法读取";
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            if (root == null) return "getRootInActiveWindow() == null（活动窗口无内容或被屏蔽）";
            StringBuilder sb = new StringBuilder();
            int[] cnt = {0};
            walk(root, 0, cnt, maxNodes, sb);
            sb.insert(0, "pkg=" + root.getPackageName() + " 共读节点=" + cnt[0] + "\n\n");
            return sb.toString();
        } catch (Throwable t) {
            return "读取失败: " + t;
        }
    }

    private static void walk(AccessibilityNodeInfo n, int depth, int[] cnt,
                             int max, StringBuilder sb) {
        if (cnt[0] >= max || sb.length() > 20000) return;
        cnt[0]++;
        if (depth <= 3) {
            sb.append("  ".repeat(Math.max(0, depth)))
              .append(n.getClassName())
              .append(" text=").append(n.getText())
              .append(" desc=").append(n.getContentDescription())
              .append(n.isClickable() ? " [可点]" : "")
              .append("\n");
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = n.getChild(i);
            if (c != null) {
                walk(c, depth + 1, cnt, max, sb);
                c.recycle();
            }
        }
    }
}
