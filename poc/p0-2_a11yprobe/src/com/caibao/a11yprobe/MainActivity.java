package com.caibao.a11yprobe;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Process;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private TextView status, out;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        float d = getResources().getDisplayMetrics().density;
        int p = (int) (16 * d);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("A11yProbe — HyperOS 3 存活探针");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(14);
        status.setPadding(0, p, 0, p);
        root.addView(status);

        out = new TextView(this);
        out.setTextSize(12);
        out.setMovementMethod(new ScrollingMovementMethod());
        out.setBackgroundColor(0xFF111111);
        out.setTextColor(0xFFDDDDDD);
        out.setPadding(p / 2, p / 2, p / 2, p / 2);
        root.addView(out, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row);

        row.addView(btn("刷新", v -> refresh()));
        row.addView(btn("读当前屏", v -> out.setText(ProbeService.dumpRoot(4000))));
        row.addView(btn("杀进程", v -> {
            ProbeService.fileLog("MANUAL_KILL 由按钮触发，观察系统是否自动重绑");
            Process.killProcess(Process.myPid());
        }));

        setContentView(root);
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        String enabled = Settings.Secure.getString(
                getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean listed = enabled != null
                && enabled.toLowerCase().contains(getPackageName());
        StringBuilder sb = new StringBuilder();
        sb.append("系统无障碍开关列入: ").append(listed ? "是" : "否").append("\n");
        sb.append("服务连接: ").append(ProbeService.connected ? "已连接" : "未连接")
          .append("\n");
        sb.append("累计事件: ").append(ProbeService.eventCount)
          .append("   最近前台: ").append(ProbeService.lastPkg);
        status.setText(sb.toString());
        out.setText(ProbeService.tailLog());
    }
}
