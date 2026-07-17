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

/**
 * Shows the limit / protection overlay immediately on restricted surfaces only:
 * - KISMART / Device Service app info & uninstall paths
 * - Factory reset (including Settings search results)
 * - Accessibility control screens
 * - Device admin deactivation screens
 *
 * Normal Settings (Wi‑Fi, display, etc.) stay usable.
 */
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
        // Fast path: react on the event package/text before a full tree walk.
        if (isRestrictedEvent(event)) {
            showBlockerNow();
            return;
        }
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

        // Never block KISMART itself (payment / account UI).
        if (!packageName.isEmpty() && getPackageName().equals(packageName)) {
            hideBlockerNow();
            return;
        }

        // Named restricted screens only — not all of Settings.
        if (!packageName.isEmpty() && isDangerousScreenNow(packageName)) {
            showBlockerNow();
            return;
        }

        if (policy == null) {
            hideBlockerNow();
            return;
        }

        if (DeviceControls.isFullLockPolicy(policy)) {
            showFullLockBlockerNow();
            DeviceControls.enforceFullLock(this);
            return;
        }

        // Limit mode: block other apps immediately; keep launcher / system / normal settings usable
        // unless the surface is a named restricted screen (handled above).
        if (DeviceControls.isPaymentLimitActive(policy)) {
            if (packageName.isEmpty()) {
                // Keep existing overlay if any; do not flash-hide on empty package samples.
                return;
            }
            if (isLauncherPackage(packageName) || isAllowedSystemPackage(packageName)) {
                hideBlockerNow();
                return;
            }
            if (isSettingsLikePackage(packageName)) {
                // Normal Settings home / Wi‑Fi / sound / etc. stay open.
                hideBlockerNow();
                return;
            }
            if (System.currentTimeMillis() < allowKismartOpenUntil && isOverlayEventPackage(packageName)) {
                hideBlockerNow();
                return;
            }
            showBlockerNow();
            return;
        }

        if (!DeviceControls.isFinancedPolicy(policy)) {
            hideBlockerNow();
            return;
        }

        // Financed, not limited: only protect the named dangerous screens (already checked).
        if (packageName.isEmpty() || isLauncherPackage(packageName) || isSettingsLikePackage(packageName)) {
            hideBlockerNow();
            return;
        }
        hideBlockerNow();
    }

    /**
     * Instant event-level match so restricted screens are blocked without waiting for the watchdog.
     */
    private boolean isRestrictedEvent(AccessibilityEvent event) {
        CharSequence packageCs = event.getPackageName();
        String packageName = packageCs == null ? "" : packageCs.toString().trim();
        if (!packageName.isEmpty() && getPackageName().equals(packageName)) return false;

        // Uninstall / package installer UI for any package is always high risk while enrolled.
        if (isPackageInstallerPackage(packageName)) {
            String eventText = eventText(event);
            if (mentionsProtectedApp(eventText) || mentionsProtectedApp(collectQuickScreenText())) {
                return true;
            }
            // Installer confirmation dialogs still need a look at on-screen text.
            return isDangerousScreenNow(packageName);
        }

        String eventText = eventText(event);
        if (!eventText.isEmpty() && isRestrictedContent(eventText)) {
            return true;
        }

        if (!packageName.isEmpty() && (isSettingsLikePackage(packageName) || isPackageInstallerPackage(packageName))) {
            return isDangerousScreenNow(packageName);
        }
        return false;
    }

    private String eventText(AccessibilityEvent event) {
        StringBuilder builder = new StringBuilder();
        try {
            if (event.getText() != null) {
                for (CharSequence item : event.getText()) {
                    if (item != null) builder.append(' ').append(item);
                }
            }
            if (event.getContentDescription() != null) {
                builder.append(' ').append(event.getContentDescription());
            }
            if (event.getClassName() != null) {
                builder.append(' ').append(event.getClassName());
            }
        } catch (Exception ignored) {
        }
        return builder.toString().toLowerCase();
    }

    private String collectQuickScreenText() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) return "";
            return collectScreenText(root).toLowerCase();
        } catch (Exception ignored) {
            return "";
        } finally {
            if (root != null) root.recycle();
        }
    }

    /** Checks package type and on-screen content for the named restricted surfaces only. */
    private boolean isDangerousScreenNow(String packageName) {
        if (isPackageInstallerPackage(packageName)) {
            String text = collectQuickScreenText();
            // Block installer when it mentions our app, or any uninstall confirmation while financed/limited.
            if (mentionsProtectedApp(text) || isUninstallConfirmation(text)) return true;
        }
        if (isSettingsLikePackage(packageName) || isPackageInstallerPackage(packageName)) {
            return isDangerousSettingsScreenContent() || isDangerousRemovalSurfaceContent();
        }
        return isDangerousRemovalSurfaceContent();
    }

    private boolean isDangerousSettingsScreenContent() {
        AccessibilityNodeInfo root = null;
        try {
            root = activeInspectionRoot();
            if (root == null) return false;
            String text = collectScreenText(root).toLowerCase();
            return isRestrictedContent(text);
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
            return isProtectedAppManagementScreen(text) || isUninstallConfirmation(text);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (root != null) root.recycle();
        }
    }

    private boolean isRestrictedContent(String text) {
        if (text == null || text.isEmpty()) return false;
        return isFactoryResetScreen(text)
                || isAccessibilityControlScreen(text)
                || isDeviceAdminControlScreen(text)
                || isProtectedAppManagementScreen(text);
    }

    // ---------- Keyword matchers (named screens only) ----------
    private boolean isFactoryResetScreen(String text) {
        // Includes Settings search hits for factory reset wording.
        return containsAny(text,
                "factory reset", "factory data reset", "reset options",
                "erase all data", "erase all content", "erase all content and settings",
                "delete all data", "delete all contents", "reset phone", "reset tablet",
                "wipe data", "format data", "restore factory settings", "clear all data",
                "erase phone", "erase tablet", "erasing", "factory data");
    }

    private boolean isAccessibilityControlScreen(String text) {
        if (!text.contains("accessibility")) return false;
        // Always block accessibility settings while protection is active (can disable this service).
        if (text.contains("kismart") || text.contains("device service")) return true;
        return containsAny(text,
                "downloaded apps", "installed apps", "installed services",
                "accessibility shortcut", "volume key shortcut",
                "screen reader", "interaction controls", "use service", "stop service",
                "turn off", "turn on", "allow restricted setting", "device service",
                "accessibility services", "downloaded services");
    }

    private boolean isDeviceAdminControlScreen(String text) {
        return containsAny(text,
                "device admin apps", "device administrator", "device administrators",
                "deactivate this device admin app", "deactivate device admin", "device admin");
    }

    private boolean isProtectedAppManagementScreen(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        if (!mentionsProtectedApp(lower)) return false;
        return containsAny(lower,
                "uninstall", "uninstall app", "delete", "delete app",
                "remove app", "remove from device", "remove this app",
                "app info", "application info", "app details", "manage app", "manage apps",
                "disable", "deactivate", "device admin", "device administrator",
                "force stop", "clear data", "clear cache", "storage", "trash",
                "drag here to uninstall", "open by default", "set as default",
                "permissions", "force stop");
    }

    private boolean isUninstallConfirmation(String text) {
        return containsAny(text,
                "do you want to uninstall", "uninstall app", "drag here to uninstall",
                "this app will be removed", "uninstall this app");
    }

    private boolean mentionsProtectedApp(String text) {
        if (text == null || text.isEmpty()) return false;
        String lower = text.toLowerCase();
        String appLabel = getString(R.string.app_name).toLowerCase();
        String packageName = getPackageName().toLowerCase();
        return lower.contains("kismart")
                || lower.contains("device service")
                || lower.contains(appLabel)
                || lower.contains(packageName)
                || lower.contains("africa.volo.kismart");
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
        return "com.android.settings".equals(v)
                || v.endsWith(".settings")
                || v.contains(".settings.")
                || v.contains("securitycenter")
                || v.contains("permissioncontroller")
                || v.contains("systemmanager")
                || v.contains("safecenter")
                || v.contains("permcenter")
                || v.contains("smartmanager")
                || v.contains("settings.intelligence");
    }

    private boolean isPackageInstallerPackage(String pkg) {
        String v = pkg == null ? "" : pkg.toLowerCase();
        return v.contains("packageinstaller")
                || v.contains("installer")
                || v.equals("com.google.android.packageinstaller")
                || v.equals("com.android.packageinstaller")
                || v.equals("com.miui.packageinstaller")
                || v.equals("com.samsung.android.packageinstaller");
    }

    private boolean isLauncherPackage(String packageName) {
        String v = packageName == null ? "" : packageName.toLowerCase();
        return v.contains("launcher") || v.contains(".home")
                || v.equals("com.miui.home")
                || v.equals("com.android.launcher")
                || v.equals("com.google.android.apps.nexuslauncher");
    }

    private boolean isAllowedSystemPackage(String packageName) {
        if ("android".equals(packageName)) return true;
        if ("com.android.systemui".equals(packageName)) return true;
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
            // Capture touches immediately so the user cannot keep navigating under the overlay.
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
        } catch (Exception ignored) {
        } finally {
            blocker = null;
            blockerVisible = false;
            fullLockBlockerVisible = false;
        }
    }

    // ========== Helpers ==========
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
