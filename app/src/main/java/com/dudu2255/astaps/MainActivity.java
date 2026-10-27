package com.dudu2255.astaps;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.inputmethod.EditorInfo;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class MainActivity extends Activity {

    private static final int REQ_JAR = 1001;
    private static final int REQ_RES = 1002;
    private static final int REQ_PLUGIN = 1003;

    private TextView statusView;
    private TextView infoView;
    private TextView logView;
    private EditText commandInput;
    private int selectedLog;
    private String displayedLog;
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
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 2001);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 72, 48, 32);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(48, 72 + insets.getSystemWindowInsetTop(),
                    48, 32 + insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title = new TextView(this);
        title.setText("Grasscutter Android · 原神 7.1");
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
        row3.addView(button("导入插件", v -> pick(REQ_PLUGIN)));

        LinearLayout row4 = new LinearLayout(this);
        root.addView(row4);
        row4.addView(button("后台运行设置", v -> openBackgroundSettings()));

        Switch autostart = new Switch(this);
        autostart.setText("开机自动启动服务器");
        autostart.setChecked(Prefs.autostart(this));
        autostart.setOnCheckedChangeListener((button, checked) -> Prefs.setAutostart(this, checked));
        root.addView(autostart);

        LinearLayout logTabs = new LinearLayout(this);
        root.addView(logTabs);
        logTabs.addView(button("服务端日志", v -> {
            selectedLog = 0;
            refreshLog();
        }));
        logTabs.addView(button("MongoDB 日志", v -> {
            selectedLog = 1;
            refreshLog();
        }));

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

        LinearLayout commandRow = new LinearLayout(this);
        commandInput = new EditText(this);
        commandInput.setSingleLine(true);
        commandInput.setHint("输入服务端指令");
        commandInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        commandInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                submitCommand();
                return true;
            }
            return false;
        });
        commandInput.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        commandRow.addView(commandInput);
        Button sendCommand = new Button(this);
        sendCommand.setText("发送");
        sendCommand.setOnClickListener(v -> submitCommand());
        commandRow.addView(sendCommand, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(commandRow);

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
            toast("正在复制资源包（不解压）");
        } else if (requestCode == REQ_PLUGIN) {
            ServerService.importPlugin(this, uri);
            toast("正在导入插件…");
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void openBackgroundSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Exception e) {
            toast("无法打开电池优化设置：" + e.getMessage());
        }
    }

    private void submitCommand() {
        String command = commandInput.getText().toString().trim();
        if (command.isEmpty()) return;
        ServerService.sendCommand(this, command);
        commandInput.setText("");
    }

    private void refresh() {
        statusView.setText(ServerService.status);

        File files = getFilesDir();
        File jar = new File(files, "rootfs/opt/astaps/grasscutter.jar");
        File imported = new File(files, "import/grasscutter.jar");
        File config = ServerService.masterConfig(this);
        File resZip = new File(files, "Resources.zip");
        File serverDir = new File(files, "rootfs/opt/astaps");
        String resourcesPath;
        try {
            resourcesPath = ServerService.getConfiguredResourcesPath(this);
        } catch (Exception e) {
            resourcesPath = "./Resources.zip";
        }
        File configuredResources = new File(serverDir, resourcesPath);
        File resourceDir = configuredResources.isDirectory() ? configuredResources : null;
        File resourceZip = configuredResources.isFile() ? configuredResources : resZip;

        StringBuilder info = new StringBuilder();
        info.append("jar: ").append(jar.exists()
                ? (jar.length() / 1024 / 1024) + " MB" : "未就绪");
        if (imported.exists()) {
            info.append("（已导入 ").append(imported.length() / 1024 / 1024).append(" MB）");
        }
        info.append("\n配置: ").append(config.exists() ? "可编辑" : "默认");
        info.append("\n插件: ").append(Util.dirCount(new File(files, "plugins"))).append(" 个");
        info.append("\n资源包: ");
        if (resourceDir != null) {
            File[] entries = resourceDir.listFiles();
            info.append("已解压到 ").append(resourcesPath)
                    .append(entries == null ? "" : "（" + entries.length + " 项）");
        } else if (configuredResources.isFile() || resZip.isFile()) {
            info.append(resourceZip.length() / 1024 / 1024).append(" MB (")
                    .append(resourcesPath).append(")");
        } else {
            info.append("未导入");
        }
        infoView.setText(info.toString());

        refreshLog();
    }

    private void refreshLog() {
        File log = selectedLog == 0
                ? new File(new File(getFilesDir(), "logs"), "server.log")
                : ServerService.mongoLogFile(this);
        String tail = Util.tail(log, 200);
        String text = tail.isEmpty() ? "（暂无日志记录）" : tail;
        if (!text.equals(displayedLog)) {
            displayedLog = text;
            logView.setText(text);
        }
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
