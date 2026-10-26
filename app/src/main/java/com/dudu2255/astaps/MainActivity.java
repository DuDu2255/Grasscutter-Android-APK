package com.dudu2255.astaps;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class MainActivity extends Activity {

    private static final int REQ_JAR = 1001;
    private static final int REQ_RES = 1002;

    private TextView statusView;
    private TextView infoView;
    private TextView logView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 72, 48, 32);

        TextView title = new TextView(this);
        title.setText("AstaPS 服务器 (原神 7.1)");
        title.setTextSize(22);
        root.addView(title);

        statusView = new TextView(this);
        statusView.setTextSize(16);
        statusView.setPadding(0, 24, 0, 8);
        statusView.setTextColor(Color.parseColor("#1565C0"));
        root.addView(statusView);

        infoView = new TextView(this);
        infoView.setTextSize(12);
        infoView.setPadding(0, 0, 0, 16);
        root.addView(infoView);

        LinearLayout row1 = new LinearLayout(this);
        root.addView(row1);
        row1.addView(button("启动", v -> {
            ServerService.start(this);
            toast("正在启动…");
        }));
        row1.addView(button("停止", v -> {
            ServerService.stop(this);
            toast("已停止");
        }));

        LinearLayout row2 = new LinearLayout(this);
        root.addView(row2);
        row2.addView(button("导入 JAR", v -> pick(REQ_JAR)));
        row2.addView(button("导入资源包", v -> pick(REQ_RES)));

        LinearLayout row3 = new LinearLayout(this);
        root.addView(row3);
        row3.addView(button("编辑 config.json", v ->
                startActivity(new Intent(this, ConfigActivity.class))));

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = 20;
        scroll.setLayoutParams(lp);
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setMovementMethod(new ScrollingMovementMethod());
        scroll.addView(logView);
        root.addView(scroll);

        setContentView(root);
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            toast("没有文件管理器：" + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        if (requestCode == REQ_JAR) {
            ServerService.importJar(this, uri);
            toast("正在导入 jar…");
        } else if (requestCode == REQ_RES) {
            ServerService.importResources(this, uri);
            toast("正在导入资源包（较大，请等待）");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void refresh() {
        statusView.setText(ServerService.status);

        File files = getFilesDir();
        File jar = new File(files, "rootfs/opt/astaps/grasscutter.jar");
        File imported = new File(files, "import/grasscutter.jar");
        File config = ServerService.masterConfig(this);
        File res = new File(getExternalFilesDir(null), "resources");

        StringBuilder info = new StringBuilder();
        info.append("jar: ").append(jar.exists()
                ? (jar.length() / 1024 / 1024) + " MB" : "未就绪");
        if (imported.exists()) {
            info.append("（已导入 ").append(imported.length() / 1024 / 1024).append(" MB）");
        }
        info.append("\n配置: ").append(config.exists() ? "可编辑" : "默认");
        info.append("\n资源: ").append(Util.dirCount(res)).append(" 项");
        infoView.setText(info.toString());

        String tail = Util.tail(new File(new File(files, "logs"), "server.log"), 25);
        logView.setText(tail.isEmpty() ? "（暂无日志）" : tail);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }
}
