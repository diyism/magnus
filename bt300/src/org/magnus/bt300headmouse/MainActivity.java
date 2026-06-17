package org.magnus.bt300headmouse;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private static final String PREFS = "bt300-headmouse";
    private static final String DEFAULT_HOST = "192.168.15.102";
    private static final int DEFAULT_PORT = 39500;

    private boolean running = false;

    private EditText hostEdit;
    private EditText portEdit;
    private TextView status;
    private TextView poseView;
    private Button startButton;
    private BroadcastReceiver statusReceiver;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        registerStatusReceiver();
    }

    private void buildUi() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 12);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        status = new TextView(this);
        status.setTextSize(18);
        status.setText("Ready");
        root.addView(status, fillWrap());

        Button topOpenButton = new Button(this);
        topOpenButton.setText("Open in Chrome");
        topOpenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openKasmVncInChrome();
            }
        });
        root.addView(topOpenButton, fillWrap());

        hostEdit = new EditText(this);
        hostEdit.setSingleLine(true);
        hostEdit.setHint("Debian host");
        hostEdit.setText(prefs.getString("host", DEFAULT_HOST));
        root.addView(hostEdit, fillWrap());

        portEdit = new EditText(this);
        portEdit.setSingleLine(true);
        portEdit.setHint("UDP port");
        portEdit.setText(String.valueOf(prefs.getInt("port", DEFAULT_PORT)));
        root.addView(portEdit, fillWrap());

        startButton = new Button(this);
        startButton.setText("Start");
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running) {
                    stopSending();
                } else {
                    startSending();
                }
            }
        });
        root.addView(startButton, fillWrap());

        poseView = new TextView(this);
        poseView.setTextSize(16);
        poseView.setText("gyro gx 0.000  gy 0.000  gz 0.000 rad/s");
        root.addView(poseView, fillWrap());

        setContentView(root);
    }

    private LinearLayout.LayoutParams fillWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private String kasmUrl(String host) {
        return "http://" + host + ":8445";
    }

    private void openKasmVncInChrome() {
        String host = hostEdit.getText().toString().trim();
        if (host.length() == 0) {
            return;
        }
        startSendingIfNeeded();
        String url = kasmUrl(host);
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.setPackage("com.android.chrome");
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(fallback);
        }
    }

    private void startSending() {
        startSendingIfNeeded();
    }

    private void startSendingIfNeeded() {
        if (running) {
            return;
        }
        try {
            String host = hostEdit.getText().toString().trim();
            int port = Integer.parseInt(portEdit.getText().toString().trim());
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("host", host)
                    .putInt("port", port)
                    .apply();
            Intent intent = new Intent(this, HeadMouseService.class);
            intent.setAction(HeadMouseService.ACTION_START);
            intent.putExtra(HeadMouseService.EXTRA_HOST, host);
            intent.putExtra(HeadMouseService.EXTRA_PORT, port);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            setRunningUi(true, "Starting service...");
        } catch (Exception e) {
            status.setText("Start failed: " + e.getMessage());
            setRunningUi(false, "Stopped");
        }
    }

    private void stopSending() {
        Intent intent = new Intent(this, HeadMouseService.class);
        intent.setAction(HeadMouseService.ACTION_STOP);
        startService(intent);
        setRunningUi(false, "Stopped");
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (statusReceiver != null) {
            unregisterReceiver(statusReceiver);
            statusReceiver = null;
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
    }

    private void registerStatusReceiver() {
        statusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean isRunning = intent.getBooleanExtra(
                        HeadMouseService.EXTRA_RUNNING, false);
                String text = intent.getStringExtra(HeadMouseService.EXTRA_STATUS);
                String pose = intent.getStringExtra(HeadMouseService.EXTRA_POSE);
                setRunningUi(isRunning, text == null ? "" : text);
                if (pose != null && pose.length() > 0) {
                    poseView.setText(pose);
                }
            }
        };
        IntentFilter filter = new IntentFilter(HeadMouseService.ACTION_STATUS);
        registerReceiver(statusReceiver, filter);
    }

    private void setRunningUi(boolean isRunning, String text) {
        running = isRunning;
        startButton.setText(running ? "Stop" : "Start");
        status.setText(text);
    }
}
