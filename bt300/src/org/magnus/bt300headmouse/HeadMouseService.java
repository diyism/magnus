package org.magnus.bt300headmouse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.Charset;
import java.util.Locale;

public class HeadMouseService extends Service implements SensorEventListener {
    public static final String ACTION_START = "org.magnus.bt300headmouse.START";
    public static final String ACTION_STOP = "org.magnus.bt300headmouse.STOP";
    public static final String ACTION_STATUS = "org.magnus.bt300headmouse.STATUS";
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_POSE = "pose";

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long SEND_INTERVAL_NS = 20000000L;
    private static final int NOTIFICATION_ID = 300;
    private static final String CHANNEL_ID = "head_mouse";
    private static final String PREFS = "bt300-headmouse";
    private static final String DEFAULT_HOST = "192.168.15.102";
    private static final int DEFAULT_PORT = 39500;

    private SensorManager sensorManager;
    private Sensor sensor;
    private HandlerThread senderThread;
    private Handler sender;
    private DatagramSocket socket;
    private InetAddress targetAddress;
    private int targetPort;
    private boolean running = false;
    private long lastSendNs = 0;
    private long sequence = 0;
    private long sentCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        sensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSending();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null && ACTION_START.equals(intent.getAction())) {
            String host = intent.getStringExtra(EXTRA_HOST);
            int port = intent.getIntExtra(EXTRA_PORT, DEFAULT_PORT);
            startSending(host, port);
        } else if (running) {
            startForeground(NOTIFICATION_ID, buildNotification("Sending to "
                    + targetAddress.getHostAddress() + ":" + targetPort));
        } else {
            String host = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(EXTRA_HOST, DEFAULT_HOST);
            int port = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getInt(EXTRA_PORT, DEFAULT_PORT);
            startSending(host, port);
        }
        return START_STICKY;
    }

    private void startSending(String host, int port) {
        if (host == null || host.length() == 0) {
            publishStatus(false, "No target host", "");
            return;
        }
        if (sensor == null) {
            publishStatus(false, "No usable gyroscope sensor", "");
            stopSelf();
            return;
        }
        stopSending();
        try {
            targetAddress = InetAddress.getByName(host);
            targetPort = port;
            socket = new DatagramSocket();
            senderThread = new HandlerThread("udp-sender");
            senderThread.start();
            sender = new Handler(senderThread.getLooper());
            sequence = 0;
            sentCount = 0;
            lastSendNs = 0;
            running = true;
            startForeground(NOTIFICATION_ID, buildNotification("Sending to "
                    + targetAddress.getHostAddress() + ":" + targetPort));
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME);
            publishStatus(true, "Sending to " + targetAddress.getHostAddress()
                    + ":" + targetPort, "gyro gx 0.000  gy 0.000  gz 0.000 rad/s");
        } catch (Exception e) {
            publishStatus(false, "Start failed: " + e.getMessage(), "");
            stopSending();
            stopSelf();
        }
    }

    private Notification buildNotification(String text) {
        createNotificationChannel();
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, openIntent, pendingIntentFlags());
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_stat_head_mouse)
                .setContentTitle("BT300 Head Mouse")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private int pendingIntentFlags() {
        if (Build.VERSION.SDK_INT >= 23) {
            return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Head Mouse", NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
    }

    private void stopSending() {
        running = false;
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (senderThread != null) {
            senderThread.quitSafely();
            senderThread = null;
        }
        sender = null;
        if (socket != null) {
            socket.close();
            socket = null;
        }
        targetAddress = null;
        publishStatus(false, "Stopped", "");
    }

    @Override
    public void onDestroy() {
        stopSending();
        super.onDestroy();
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

        final float gx = event.values[0];
        final float gy = event.values[1];
        final float gz = event.values[2];
        final long seq = sequence++;
        sendGyro(seq, gx, gy, gz);

        if (seq % 10 == 0) {
            publishStatus(true, "Sending to " + targetAddress.getHostAddress()
                    + ":" + targetPort + " sent " + sentCount,
                    String.format(Locale.US,
                            "gyro gx %.3f  gy %.3f  gz %.3f rad/s  sent %d",
                            gx, gy, gz, sentCount));
        }
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
                } catch (Exception e) {
                    publishStatus(false, "Send failed: " + e.getMessage(), "");
                }
            }
        });
    }

    private void publishStatus(boolean isRunning, String text, String pose) {
        Intent intent = new Intent(ACTION_STATUS);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_RUNNING, isRunning);
        intent.putExtra(EXTRA_STATUS, text);
        intent.putExtra(EXTRA_POSE, pose);
        sendBroadcast(intent);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
