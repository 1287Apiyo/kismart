package africa.volo.kismart.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AgentSyncService extends Service {
    private static final String CHANNEL_ID = "kismart_policy_monitor";
    private static final String PROTECTION_CHANNEL_ID = "kismart_protection_required";
    private static final int NOTIFICATION_ID = 2601;
    private static final int RESTART_REQUEST_ID = 2602;
    private static final int PROTECTION_NOTIFICATION_ID = 2603;
    private static final long SYNC_INTERVAL_MS = 5000L;
    private static final long SYNC_INTERVAL_LIMIT_MS = 2000L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean syncing;
    private long lastGuardTamperReportAt;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private WindowManager windowManager;
    private View protectionOverlay;

    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            // Re-apply last known limit immediately (offline-safe) before waiting on network sync.
            enforceLastKnownPolicy();
            syncOnce();
            long delay = DeviceControls.isPaymentLimitActive(KismartApi.lastPolicy(AgentSyncService.this))
                    || DeviceControls.isFullLockPolicy(KismartApi.lastPolicy(AgentSyncService.this))
                    ? SYNC_INTERVAL_LIMIT_MS
                    : SYNC_INTERVAL_MS;
            handler.postDelayed(this, delay);
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
        enforceProtectionGuard();
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
        enforceProtectionGuard();
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
        hideProtectionOverlay();
        scheduleProtectionRestart();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        scheduleProtectionRestart();
        try {
            AgentSyncService.start(getApplicationContext());
        } catch (Exception ignored) {
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void scheduleProtectionRestart() {
        try {
            Intent restart = new Intent(this, UninstallProtectionReceiver.class);
            restart.setAction(UninstallProtectionReceiver.ACTION_RESTART_PROTECTION);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pendingIntent = PendingIntent.getBroadcast(this, RESTART_REQUEST_ID, restart, flags);
            AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (alarmManager != null) {
                alarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + 1500L,
                        pendingIntent
                );
            }
        } catch (Exception ignored) {
        }
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
                    showProtectionRequiredNotification();
                    reportGuardTamper("Accessibility Guard disabled while financed protection is active");
                } else if (limitGuardMissing) {
                    showProtectionRequiredNotification();
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
            showProtectionRequiredNotification();
            reportGuardTamper("Accessibility Guard disabled while financed protection is active");
        } else if (limitGuardMissing) {
            showProtectionRequiredNotification();
            reportGuardTamper("Accessibility Guard disabled while Limit is active");
        }
        DeviceControls.applyPolicyFromBackground(this, policy);
        // Unpaid balance: keep yanking the phone back to Pay (unless admin session is active).
        if (DeviceControls.mustStayOnPaymentScreen(this)) {
            DeviceControls.forcePaymentScreen(this);
        }
    }

    private void enforceProtectionGuard() {
        if (DeviceControls.isProtectionGuardReady(this)) {
            hideProtectionOverlay();
            return;
        }
        DeviceControls.openProtectionGuardRequired(this);
        showProtectionOverlayIfAllowed();
        showProtectionRequiredNotification();
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
        Intent contentIntent = new Intent(this, MainActivity.class);
        contentIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, contentIntent, flags);

        Intent setupIntent = new Intent(this, MainActivity.class);
        setupIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        setupIntent.putExtra(AdminSetupReceiver.ACTION_EXTRA_OPEN_ADMIN_SETUP, true);
        PendingIntent setupPendingIntent = PendingIntent.getActivity(this, 1, setupIntent, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Device Service")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_manage, "Open setup", setupPendingIntent)
                .build();
    }

    private void showProtectionRequiredNotification() {
        createProtectionChannel();
        Intent intent = new Intent(this, ProtectionGuardActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 2, intent, flags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, PROTECTION_CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Device Service required")
                .setContentText("Enable Device Service in Accessibility to continue.")
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true)
                .setOngoing(true)
                .setAutoCancel(false)
                .setCategory(Notification.CATEGORY_ALARM)
                .setPriority(Notification.PRIORITY_MAX)
                .build();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(PROTECTION_NOTIFICATION_ID, notification);
    }

    private void showProtectionOverlayIfAllowed() {
        if (protectionOverlay != null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return;
        try {
            protectionOverlay = buildProtectionOverlay();
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_SECURE
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;
            windowManager.addView(protectionOverlay, params);
        } catch (Exception ignored) {
            protectionOverlay = null;
        }
    }

    private void hideProtectionOverlay() {
        if (protectionOverlay == null || windowManager == null) return;
        try {
            windowManager.removeView(protectionOverlay);
        } catch (Exception ignored) {
        } finally {
            protectionOverlay = null;
        }
    }

    private View buildProtectionOverlay() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(5, 8, 7));
        root.setClickable(true);
        root.setFocusable(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        int pad = dp(28);
        content.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("DEVICE SERVICE REQUIRED");
        title.setTextColor(Color.rgb(22, 163, 74));
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        TextView message = new TextView(this);
        message.setText("Enable Device Service in Accessibility to continue.");
        message.setTextColor(Color.rgb(170, 184, 176));
        message.setTextSize(15);
        message.setGravity(Gravity.CENTER);

        Button open = new Button(this);
        open.setText("Open Accessibility");
        open.setAllCaps(false);
        open.setTextColor(Color.WHITE);
        open.setTextSize(15);
        open.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        open.setBackgroundColor(Color.rgb(22, 163, 74));
        open.setOnClickListener(view -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(intent);
            } catch (Exception ignored) {
            }
        });

        content.addView(title, wrapParams(0, 14));
        content.addView(message, wrapParams(0, 22));
        content.addView(open, heightParams(0, 0, dp(52)));
        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        return root;
    }

    private LinearLayout.LayoutParams wrapParams(int topDp, int bottomDp) {
        return heightParams(topDp, bottomDp, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams heightParams(int topDp, int bottomDp, int height) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                height
        );
        params.setMargins(0, dp(topDp), 0, dp(bottomDp));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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

    private void createProtectionChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                PROTECTION_CHANNEL_ID,
                "Device service required",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }
}
