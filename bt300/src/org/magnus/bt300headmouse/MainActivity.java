package org.magnus.bt300headmouse;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String PREFS = "bt300-headmouse";
    private static final String DEFAULT_HOST = "192.168.15.102";
    private static final int DEFAULT_PORT = 39500;
    private static final long SEND_INTERVAL_NS = 20000000L;

    private SensorManager sensorManager;
    private Sensor sensor;
    private HandlerThread senderThread;
    private Handler sender;
    private DatagramSocket socket;
    private InetAddress targetAddress;
    private int targetPort = DEFAULT_PORT;
    private boolean running = false;
    private long lastSendNs = 0;
    private long sequence = 0;
    private long sentCount = 0;
    private float lastGx = 0;
    private float lastGy = 0;
    private float lastGz = 0;

    private EditText hostEdit;
    private EditText portEdit;
    private TextView status;
    private TextView poseView;
    private Button startButton;
    private WebView webView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        buildUi();
    }

    private void buildUi() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 12);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        status = new TextView(this);
        status.setTextSize(18);
        status.setText(sensor == null ? "No gyroscope sensor" : "Ready: " + sensor.getName());
        root.addView(status, fillWrap());

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

        Button openButton = new Button(this);
        openButton.setText("Open KasmVNC");
        openButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadKasmVnc(hostEdit.getText().toString().trim());
            }
        });
        root.addView(openButton, fillWrap());

        poseView = new TextView(this);
        poseView.setTextSize(16);
        poseView.setText("gyro gx 0.000  gy 0.000  gz 0.000 rad/s");
        root.addView(poseView, fillWrap());

        webView = new WebView(this);
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                status.setText("Loading " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                status.setText("Loaded " + url);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                status.setText("WebView error " + errorCode + ": " + description);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                status.setText("WebView error: " + error.getDescription());
            }
        });
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        root.addView(webView, fillWeight());

        setContentView(root);
        loadKasmVnc(hostEdit.getText().toString().trim());
    }

    private LinearLayout.LayoutParams fillWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams fillWeight() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
    }

    private void loadKasmVnc(String host) {
        if (host == null || host.length() == 0 || webView == null) {
            return;
        }
        String url = "http://" + host + ":8445";
        status.setText("Opening " + url);
        webView.setVisibility(View.VISIBLE);
        webView.loadUrl(url);
    }

    private void startSending() {
        if (sensor == null) {
            status.setText("No usable gyroscope sensor");
            return;
        }
        try {
            String host = hostEdit.getText().toString().trim();
            int port = Integer.parseInt(portEdit.getText().toString().trim());
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString("host", host)
                    .putInt("port", port)
                    .apply();
            loadKasmVnc(host);
            targetAddress = InetAddress.getByName(host);
            targetPort = port;
            socket = new DatagramSocket();
            senderThread = new HandlerThread("udp-sender");
            senderThread.start();
            sender = new Handler(senderThread.getLooper());
            running = true;
            sequence = 0;
            sentCount = 0;
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
            startButton.setText("Stop");
            status.setText("Sending to " + host + ":" + port);
        } catch (Exception e) {
            status.setText("Start failed: " + e.getMessage());
            stopSending();
        }
    }

    private void stopSending() {
        running = false;
        sensorManager.unregisterListener(this);
        if (senderThread != null) {
            senderThread.quitSafely();
            senderThread = null;
        }
        if (socket != null) {
            socket.close();
            socket = null;
        }
        startButton.setText("Start");
        status.setText(sensor == null ? "No gyroscope sensor" : "Stopped");
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopSending();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!running || event.sensor.getType() != sensor.getType()) {
            return;
        }
        if (event.timestamp - lastSendNs < SEND_INTERVAL_NS) {
            return;
        }
        lastSendNs = event.timestamp;

        lastGx = event.values[0];
        lastGy = event.values[1];
        lastGz = event.values[2];

        final float gx = lastGx;
        final float gy = lastGy;
        final float gz = lastGz;
        final long seq = sequence++;

        poseView.setText(String.format(Locale.US,
                "gyro gx %.3f  gy %.3f  gz %.3f rad/s  sent %d",
                gx, gy, gz, sentCount));
        sendGyro(seq, gx, gy, gz);
    }

    private void sendGyro(final long seq, final float gx, final float gy, final float gz) {
        if (sender == null || socket == null || targetAddress == null) {
            return;
        }
        sender.post(new Runnable() {
            @Override
            public void run() {
                try {
                    String payload = String.format(Locale.US,
                            "{\"seq\":%d,\"gx\":%.6f,\"gy\":%.6f,\"gz\":%.6f}",
                            seq, gx, gy, gz);
                    byte[] bytes = payload.getBytes(UTF8);
                    DatagramPacket packet = new DatagramPacket(
                            bytes, bytes.length, targetAddress, targetPort);
                    socket.send(packet);
                    sentCount++;
                    if (seq % 20 == 0) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                status.setText("Sending to "
                                        + targetAddress.getHostAddress() + ":"
                                        + targetPort + " sent " + sentCount);
                            }
                        });
                    }
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            status.setText("Send failed: " + e.getMessage());
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
