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
    private static final long WATCHDOG_INTERVAL_MS = 600L;
    private static final long EMERGENCY_ALLOW_MS = 30000L;
    private static final long KISMART_OPEN_ALLOW_MS = 5000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            checkBlockerState();
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    private WindowManager windowManager;
    private View blocker;
    private boolean blockerVisible;
    private boolean fullLockBlockerVisible;
    private long emergencyAllowedUntil;
    private long allowKismartOpenUntil;
    /** Debounce limit overlay so stale/offline policy flips do not flash the screen. */
    private int limitShowStableCount;
    private int limitHideStableCount;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        // React to all events to catch any window change or click
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
        String packageName = activePackageName();
        if (packageName.isEmpty()) {
            hideBlockerNow();
            return;
        }

        // Never block KISMART itself
        if (getPackageName().equals(packageName)) {
            hideBlockerNow();
            return;
        }

        // Always protect KISMART's app-info/removal screens, even before policy sync.
        if (isDangerousScreenNow(packageName)) {
            showBlockerNow();
            return;
        }

        Policy policy = KismartApi.lastPolicy(this);
        if (policy == null) {
            clearLimitDebounce();
            hideBlockerNow();
            return;
        }

        // Full lock only while balance remains.
        if (DeviceControls.isFullLockPolicy(policy)) {
            clearLimitDebounce();
            showFullLockBlockerNow();
            DeviceControls.enforceFullLock(this);
            return;
        }

        // Limit screen only when backend policy says limit is active AND balance > 0.
        if (DeviceControls.isPaymentLimitActive(policy)) {
            if (isCameraScreen(packageName)) {
                stableShowLimitBlocker();
                return;
            }
            if (isLauncherPackage(packageName) || isAllowedSystemPackage(packageName)) {
                stableHideLimitBlocker();
            } else if (System.currentTimeMillis() < allowKismartOpenUntil
                    && isOverlayEventPackage(packageName)) {
                stableHideLimitBlocker();
            } else {
                stableShowLimitBlocker();
            }
            return;
        }

        clearLimitDebounce();

        // No payable limit → hide limit overlay; keep protection only on dangerous screens.
        if (!DeviceControls.isFinancedPolicy(policy)) {
            hideBlockerNow();
            return;
        }

        if (isLauncherPackage(packageName)) {
            hideBlockerNow();
            return;
        }

        boolean dangerous = isDangerousScreenNow(packageName);
        if (dangerous) {
            showBlockerNow();
        } else {
            hideBlockerNow();
        }
    }

    private void stableShowLimitBlocker() {
        limitHideStableCount = 0;
        limitShowStableCount += 1;
        // Require two consecutive samples (~1.2s) before showing, unless already visible.
        if (blockerVisible || limitShowStableCount >= 2) {
            showBlockerNow();
        }
    }

    private void stableHideLimitBlocker() {
        limitShowStableCount = 0;
        limitHideStableCount += 1;
        if (!blockerVisible || limitHideStableCount >= 2) {
            hideBlockerNow();
        }
    }

    private void clearLimitDebounce() {
        limitShowStableCount = 0;
        limitHideStableCount = 0;
    }

    /** Checks both package type and actual on‑screen content */
    private boolean isDangerousScreenNow(String packageName) {
        if (isSettingsLikePackage(packageName)) {
            return isDangerousSettingsScreenContent() || isDangerousRemovalSurfaceContent();
        }
        return isDangerousRemovalSurfaceContent();
    }

    // ========== Screen content detection (unchanged) ==========
    private boolean isDangerousSettingsScreenContent() {
        AccessibilityNodeInfo root = null;
        try {
            root = activeInspectionRoot();
            if (root == null) return false;
            String text = collectScreenText(root).toLowerCase();
            return isFactoryResetScreen(text)
                    || isAccessibilityControlScreen(text)
                    || isDeviceAdminControlScreen(text)
                    || isKismartRemovalScreen(text);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (root != null) root.recycle();
        }
    }

    private boolean isDangerousRemovalSurfaceContent() {
        AccessibilityNodeInfo root = null;
        try {
            root = activeInspectionRoot();
            if (root == null) return false;
            String text = collectScreenText(root).toLowerCase();
            return isKismartRemovalScreen(text);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (root != null) root.recycle();
        }
    }

    // ---------- Keyword matchers (unchanged) ----------
    private boolean isFactoryResetScreen(String text) {
        return containsAny(text,
                "factory reset", "factory data reset", "reset options",
                "erase all data", "erase all content", "erase all content and settings",
                "delete all data", "delete all contents", "reset phone", "reset tablet",
                "wipe data", "format data", "restore factory settings", "clear all data");
    }

    private boolean isAccessibilityControlScreen(String text) {
        if (!text.contains("accessibility")) return false;
        if (text.contains("kismart")) return true;
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

    private boolean isKismartRemovalScreen(String text) {
        return isProtectedAppManagementScreen(text);
    }

    private boolean isProtectedAppManagementScreen(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        String appLabel = getString(R.string.app_name).toLowerCase();
        String packageName = getPackageName().toLowerCase();
        boolean mentionsProtectedApp = lower.contains("kismart")
                || lower.contains(appLabel)
                || lower.contains(packageName);
        if (!mentionsProtectedApp) return false;
        return containsAny(lower,
                "uninstall", "uninstall app", "delete", "delete app",
                "remove app", "remove from device", "remove this app",
                "app info", "app details", "manage app", "manage apps",
                "disable", "deactivate", "device admin", "device administrator",
                "force stop", "clear data", "clear cache", "storage", "trash",
                "drag here to uninstall");
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
    private boolean isSettingsLikePackage(String pkg) {
        String v = pkg == null ? "" : pkg.toLowerCase();
        return "com.android.settings".equals(v) || v.contains(".settings")
                || v.contains("securitycenter") || v.contains("permissioncontroller")
                || v.contains("packageinstaller") || v.contains("installer");
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

    // ========== Blocker show / hide (simple, no debounce) ==========
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
            int flags = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_SECURE;
            if (fullLockBlocker) {
                flags |= WindowManager.LayoutParams.FLAG_FULLSCREEN;
            } else {
                flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            }
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    flags,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            windowManager.addView(blocker, params);
            blockerVisible = true;
            fullLockBlockerVisible = fullLockBlocker;
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
