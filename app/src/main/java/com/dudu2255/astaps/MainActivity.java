package com.dudu2255.astaps;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class MainActivity extends Activity {

    private TextView statusView;
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
        statusView.setPadding(0, 28, 0, 28);
        statusView.setTextColor(Color.parseColor("#1565C0"));
        root.addView(statusView);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row1);
        row1.addView(button("启动", v -> startServer()));
        row1.addView(button("停止", v -> stopServer()));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(row2);
        row2.addView(button("下载资源", v -> downloadResources()));
        row2.addView(button("解压资源", v -> ServerService.unzip(this)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = 24;
        scroll.setLayoutParams(lp);
        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
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
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(lp);
        return b;
    }

    private void startServer() {
        ServerService.start(this);
        toast("正在启动…");
    }

    private void stopServer() {
        ServerService.stop(this);
        toast("已停止");
    }

    private void downloadResources() {
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(ServerService.RES_ZIP_URL));
            req.setTitle("AstaPS 资源包");
            req.setDescription("约 1.5 GB，下载完成后点“解压资源”");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalFilesDir(this, null, "resources.zip");
            dm.enqueue(req);
            toast("已加入下载，完成后点“解压资源”");
        } catch (Exception e) {
            toast("下载失败：" + e.getMessage());
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void refresh() {
        statusView.setText(ServerService.status);
        File log = new File(new File(getFilesDir(), "logs"), "server.log");
        String tail = Util.tail(log, 25);
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        File zip = new File(getExternalFilesDir(null), "resources.zip");
        String extra = "";
        if (zip.exists()) {
            extra = "\n[资源包] " + (zip.length() / (1024 * 1024)) + " MB 已下载";
        }
        logView.setText(tail.isEmpty() ? "（暂无日志）" + extra : tail + extra);
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
