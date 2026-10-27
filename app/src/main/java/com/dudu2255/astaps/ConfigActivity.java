package com.dudu2255.astaps;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ConfigActivity extends Activity {

    private EditText editor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 56, 32, 24);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(32, 56 + insets.getSystemWindowInsetTop(),
                    32, 24 + insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView tip = new TextView(this);
        tip.setText("config.json（dispatch 端口 10086 / 网关 22101，改完点保存，重启服务生效）");
        tip.setTextSize(12);
        root.addView(tip);

        editor = new EditText(this);
        editor.setTextSize(11);
        editor.setTypeface(android.graphics.Typeface.MONOSPACE);
        editor.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        editor.setHorizontallyScrolling(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        editor.setLayoutParams(lp);
        root.addView(editor);

        LinearLayout row = new LinearLayout(this);
        root.addView(row);
        row.addView(btn("保存", v -> save()));
        row.addView(btn("恢复默认", v -> reset()));

        setContentView(root);
        editor.setText(load());
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private String load() {
        File f = ServerService.masterConfig(this);
        if (!f.exists()) return readAsset();
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "// 读取失败: " + e.getMessage();
        }
    }

    private String readAsset() {
        try (InputStream in = getAssets().open("config.default.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void save() {
        try (FileOutputStream os = new FileOutputStream(ServerService.masterConfig(this))) {
            os.write(editor.getText().toString().getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "已保存（重启服务生效）", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void reset() {
        editor.setText(readAsset());
        Toast.makeText(this, "已恢复默认，记得保存", Toast.LENGTH_SHORT).show();
    }
}
