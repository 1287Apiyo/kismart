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
    private long emergencyAllowedUntil;
    private long allowKismartOpenUntil;

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
        super.onDestroy();
    }

    private void configureService() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0L; // immediate response
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
    }

    // ========== Main decision logic ==========
    private void checkBlockerState() {
        Policy policy = KismartApi.lastPolicy(this);
        if (policy == null) {
            hideBlockerNow();
            return;
        }

        // Full lock is handled by other components – hide our overlay
        if (DeviceControls.isFullLockPolicy(policy)) {
            hideBlockerNow();
            if (!isKismartLockScreen()) {
                DeviceControls.enforceFullLock(this);
            }
            return;
        }

        // No restriction active → hide
        if (!DeviceControls.isFinancedPolicy(policy) && !policy.paymentOnlyActive) {
            hideBlockerNow();
            return;
        }

        // Get current package
        String packageName = activePackageName();
        // If we can't determine the package, hide (safety)
        if (packageName.isEmpty()) {
            hideBlockerNow();
            return;
        }

        // Never block KISMART itself
        if (getPackageName().equals(packageName)) {
            hideBlockerNow();
            return;
        }

        // Always hide on launcher / home screen
        if (isLauncherPackage(packageName)) {
            hideBlockerNow();
            return;
        }

        // Show blocker ONLY on dangerous screens, hide everywhere else
        boolean dangerous = isDangerousScreenNow(packageName);
        if (dangerous) {
            showBlockerNow();
        } else {
            hideBlockerNow();
        }
    }

    /** Checks both package type and actual on‑screen content */
    private boolean isDangerousScreenNow(String packageName) {
        // In settings-like apps: check content for dangerous screens
        if (isSettingsLikePackage(packageName)) {
            return isDangerousSettingsScreenContent() || isDangerousRemovalSurfaceContent();
        }
        // For any other app: only block if a KISMART removal dialog is visible
        return isDangerousRemovalSurfaceContent();
    }

    // ========== Screen content detection ==========
    private boolean isDangerousSettingsScreenContent() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
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
            root = getRootInActiveWindow();
            if (root == null) return false;
            String text = collectScreenText(root).toLowerCase();
            return isKismartRemovalScreen(text);
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
        if (!text.contains("kismart")) return false;
        return containsAny(text,
                "uninstall", "uninstall app", "uninstall kismart",
                "delete", "delete app", "delete kismart",
                "remove app", "remove from device", "remove this app", "remove kismart",
                "app info", "app details", "manage app", "manage apps",
                "disable", "deactivate", "device admin", "device administrator",
                "force stop", "clear data", "storage", "trash", "drag here to uninstall");
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null) return false;
        for (String needle : needles) {
            if (needle != null && text.contains(needle)) return true;
        }
        return false;
    }

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

    // ========== Blocker show / hide (simple, no debounce) ==========
    private void showBlockerNow() {
        if (blockerVisible) return;

        if (windowManager == null) windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) return;

        try {
            blocker = buildBlocker();
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            windowManager.addView(blocker, params);
            blockerVisible = true;
        } catch (Exception e) {
            blockerVisible = false;
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
        }
    }

    // ========== Helpers ==========
    private String activePackageName() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) return "";
            return root.getPackageName().toString();
        } catch (Exception ignored) { return ""; }
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

    // ========== UI ==========
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
        title.setText("KISMART Protection Active");
        title.setTextColor(Color.rgb(10, 15, 13));
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        TextView message = new TextView(this);
        message.setText("This action is not available on this financed phone. Open KISMART to continue.");
        message.setTextColor(Color.rgb(62, 74, 68));
        message.setTextSize(15);
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(dp(2), 1.0f);

        Button open = new Button(this);
        open.setText("Open KISMART");
        open.setTextColor(Color.WHITE);
        open.setTextSize(14);
        open.setAllCaps(false);
        open.setBackgroundColor(Color.rgb(10, 15, 13));
        open.setOnClickListener(view -> openKismart());

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

    private void openKismart() {
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