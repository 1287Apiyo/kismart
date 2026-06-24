package africa.volo.kismart.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AgentSyncService extends Service {
    private static final String CHANNEL_ID = "kismart_policy_monitor";
    private static final int NOTIFICATION_ID = 2601;
    private static final long SYNC_INTERVAL_MS = 5000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean syncing;
    private long lastGuardTamperReportAt;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;

    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            syncOnce();
            handler.postDelayed(this, SYNC_INTERVAL_MS);
        }
    };

    static void start(Context context) {
        Intent intent = new Intent(context, AgentSyncService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, AgentSyncService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        DeviceControls.enforceFinancedDeviceHardening(this);
        DeviceControls.protectAppFromUninstall(this);
        DeviceControls.hideLauncherEntry(this);
        enforceLastKnownPolicy();
        createChannel();
        startForeground(NOTIFICATION_ID, notification("Monitoring device service"));
        registerNetworkMonitor();
        handler.post(syncRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        DeviceControls.enforceFinancedDeviceHardening(this);
        DeviceControls.protectAppFromUninstall(this);
        DeviceControls.hideLauncherEntry(this);
        enforceLastKnownPolicy();
        startForeground(NOTIFICATION_ID, notification("Monitoring device service"));
        handler.removeCallbacks(syncRunnable);
        handler.post(syncRunnable);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(syncRunnable);
        unregisterNetworkMonitor();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void syncOnce() {
        if (syncing) return;
        String server = KismartApi.prefs(this).getString(KismartApi.KEY_SERVER_URL, "");
        String imei = KismartApi.prefs(this).getString(KismartApi.KEY_IMEI, "");
        if (server == null || server.trim().isEmpty() || imei == null || imei.trim().isEmpty()) return;
        syncing = true;
        executor.execute(() -> {
            try {
                Policy policy = KismartApi.sync(this);
                boolean protectionGuardMissing = DeviceControls.enforceMissingProtectionGuard(this, policy);
                boolean limitGuardMissing = DeviceControls.enforceMissingLimitGuard(this, policy);
                if (protectionGuardMissing) {
                    reportGuardTamper("Accessibility Guard disabled while financed protection is active");
                } else if (limitGuardMissing) {
                    reportGuardTamper("Accessibility Guard disabled while Limit is active");
                }
                DeviceControls.applyPolicyFromBackground(this, policy);
            } catch (Exception ignored) {
                enforceLastKnownPolicy();
            } finally {
                syncing = false;
            }
        });
    }

    private void enforceLastKnownPolicy() {
        Policy policy = KismartApi.lastPolicy(this);
        if (policy == null) return;
        boolean protectionGuardMissing = DeviceControls.enforceMissingProtectionGuard(this, policy);
        boolean limitGuardMissing = DeviceControls.enforceMissingLimitGuard(this, policy);
        if (protectionGuardMissing) {
            reportGuardTamper("Accessibility Guard disabled while financed protection is active");
        } else if (limitGuardMissing) {
            reportGuardTamper("Accessibility Guard disabled while Limit is active");
        }
        DeviceControls.applyPolicyFromBackground(this, policy);
    }

    private void reportGuardTamper(String message) {
        long now = System.currentTimeMillis();
        if (now - lastGuardTamperReportAt < 60000L) return;
        lastGuardTamperReportAt = now;
        try {
            KismartApi.reportTamper(this, message);
        } catch (Exception ignored) {
        }
    }

    private void registerNetworkMonitor() {
        if (networkCallback != null) return;
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (connectivityManager == null) return;
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                handler.post(() -> {
                    enforceLastKnownPolicy();
                    syncOnce();
                });
            }
        };
        try {
            connectivityManager.registerNetworkCallback(new NetworkRequest.Builder().build(), networkCallback);
        } catch (Exception ignored) {
            networkCallback = null;
        }
    }

    private void unregisterNetworkMonitor() {
        if (connectivityManager == null || networkCallback == null) return;
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback);
        } catch (Exception ignored) {
        }
        networkCallback = null;
    }

    private Notification notification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, flags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Device Service")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Device service monitor",
                NotificationManager.IMPORTANCE_LOW
        );
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
