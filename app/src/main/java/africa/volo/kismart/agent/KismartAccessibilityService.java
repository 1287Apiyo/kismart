package africa.volo.kismart.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Payment-debt lockdown + uninstall protection.
 * - While balance &gt; 0: ONLY the KISMART payment screen is allowed; every other app/surface is blocked.
 * - Always protects Device Service uninstall / factory reset / accessibility tampering.
 */
public class KismartAccessibilityService extends AccessibilityService {
    private static final long WATCHDOG_INTERVAL_MS = 200L;
    private static final long WATCHDOG_LIMIT_INTERVAL_MS = 50L;
    private static final long WATCHDOG_APP_INFO_INTERVAL_MS = 50L;
    private static final long EMERGENCY_ALLOW_MS = 30000L;
    /** After "Pay Now", keep overlay off until MainActivity is clearly in front. */
    private static final long KISMART_OPEN_ALLOW_MS = 25000L;
    /** Keep blocking App Info / uninstall for this long after Device Service is detected. */
    private static final long PROTECTED_SURFACE_STICKY_MS = 12000L;
    /** Event types that can expose a Factory Reset result before its destination activity changes. */
    private static final int FACTORY_RESET_INTERACTION_EVENTS =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_FOCUSED
                    | AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
                    | AccessibilityEvent.TYPE_VIEW_SELECTED
                    | AccessibilityEvent.TYPE_VIEW_CLICKED
                    | AccessibilityEvent.TYPE_VIEW_LONG_CLICKED;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (DeviceControls.isStkPromptExempt(KismartAccessibilityService.this)) {
                hideBlockerNow();
                handler.postDelayed(this, 400L);
                return;
            }
            checkBlockerState();
            long delay = WATCHDOG_INTERVAL_MS;
            if (System.currentTimeMillis() < protectedSurfaceUntil || watchingAppDetails) {
                delay = WATCHDOG_APP_INFO_INTERVAL_MS;
            } else if (DeviceControls.mustStayOnPaymentScreen(KismartAccessibilityService.this)
                    || DeviceControls.isPaymentLimitActive(KismartApi.lastPolicy(KismartAccessibilityService.this))
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
    private long lastPaymentHandoffOpenAt;
    /** Sticky latch: once Device Service app info is seen, keep overlay until user leaves. */
    private long protectedSurfaceUntil;
    /** True while Settings App Details / Uninstaller activity class is in the foreground. */
    private boolean watchingAppDetails;
    private boolean optimisticAppDetailsBlock;

    private enum BlockReason { PAYMENT, FACTORY_RESET, ACCESSIBILITY, APPS }
    private BlockReason currentBlockReason = BlockReason.PAYMENT;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        DeviceControls.protectAppFromUninstall(this);

        // STK PIN open: never draw limit overlay.
        if (DeviceControls.isStkPromptExempt(this)) {
            hideBlockerNow();
            return;
        }

        String packageName = packageOf(event);
        String className = classOf(event);
        String eventText = eventText(event);

        // Factory Reset must be blocked before Settings can finish a search-result click.
        // This runs ahead of all slower tree-walk and activity-transition logic, so the
        // payment-limit surface appears on the first visible result, focus, or tap.
        if (isProtectionArmed()
                && isFactoryResetInteraction(event, packageName, className, eventText)) {
            armProtectedSurface();
            currentBlockReason = BlockReason.FACTORY_RESET;
            showBlockerNow();
            return;
        }

        // Once a reset result has been identified, keep the input-capturing limit overlay
        // in place for the entire Settings handoff instead of allowing a tap to race it.
        if (isProtectionArmed()
                && System.currentTimeMillis() < protectedSurfaceUntil
                && (isSettingsLikePackage(packageName)
                || isPackageInstallerPackage(packageName))) {
            showBlockerNow();
            return;
        }

        // 1) App Details / Uninstaller activity opened → block IMMEDIATELY (no tree wait).
        //    Other apps' details may flash briefly then release if not Device Service.
        if (isAppDetailsOrUninstallClass(className) || isPackageInstallerPackage(packageName)) {
            watchingAppDetails = true;
            if (isProtectionArmed()) {
                if (mentionsProtectedApp(eventText) || packageNameHintsProtectedApp(eventText, className)) {
                    armProtectedSurface();
                    currentBlockReason = BlockReason.APPS;
                    showBlockerNow();
                    return;
                }
                // Optimistic: cover App Info before labels finish loading.
                optimisticAppDetailsBlock = true;
                currentBlockReason = BlockReason.APPS;
                showBlockerNow();
                // Confirm within a few frames whether this is Device Service.
                handler.post(this::confirmOptimisticAppDetailsBlock);
                handler.postDelayed(this::confirmOptimisticAppDetailsBlock, 40L);
                handler.postDelayed(this::confirmOptimisticAppDetailsBlock, 100L);
                handler.postDelayed(this::confirmOptimisticAppDetailsBlock, 200L);
                return;
            }
        }

        // 1b) Factory reset / device wipe surface opened → block IMMEDIATELY (class match, no tree walk).
        //     Covers Settings search results navigating to Reset options / Factory data reset,
        //     regardless of how deep the text sits in the accessibility tree.
        if (isProtectionArmed() && isFactoryResetClass(className)) {
            armProtectedSurface();
            currentBlockReason = BlockReason.FACTORY_RESET;
            showBlockerNow();
            return;
        }

        // 2) Any event text mentioning Device Service / package → sticky block now.
        if (isProtectionArmed() && (mentionsProtectedApp(eventText) || isRestrictedContent(eventText))) {
            if (isSettingsLikePackage(packageName)
                    || isPackageInstallerPackage(packageName)
                    || isAppDetailsOrUninstallClass(className)
                    || isRestrictedContent(eventText)) {
                armProtectedSurface();
                currentBlockReason = isAccessibilityControlScreen(eventText)
                        ? BlockReason.ACCESSIBILITY : BlockReason.APPS;
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
        if (DeviceControls.isAdminSessionActive(this)) {
            hideBlockerNow();
            return;
        }
        if (DeviceControls.isStkPromptExempt(this)) {
            hideBlockerNow();
            return;
        }

        // User tapped Pay Now — keep overlay OFF while MainActivity comes forward.
        if (isPaymentUiHandoffActive()) {
            hideBlockerNow();
            if (isKismartInForeground()) {
                allowKismartOpenUntil = 0L;
                DeviceControls.clearPaymentUiOpening(this);
            } else {
                openPaymentScreenDuringHandoff();
            }
            return;
        }

        Policy policy = KismartApi.lastPolicy(this);

        // If KISMART payment UI is already visible, never cover it with the limit overlay.
        if (isKismartInForeground()) {
            hideBlockerNow();
            protectedSurfaceUntil = 0L;
            watchingAppDetails = false;
            optimisticAppDetailsBlock = false;
            return;
        }

        String packageName = activePackageName();
        String className = activeClassName();

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

        // FULL LOCK first: it wins over every surface, including factory-reset screens —
        // the device shows the full-lock screen instead of any Settings content.
        if (policy != null && DeviceControls.isFullLockPolicy(policy)) {
            showFullLockBlockerNow();
            DeviceControls.enforceFullLock(this);
            return;
        }

        // Named restricted screens (factory reset / accessibility / device admin / our app info).
        // Class match first (covers search-driven navigation where text may be deep in the tree),
        // then keyword content match.
        if (isProtectionArmed() && isFactoryResetClass(className)) {
            armProtectedSurface();
            showBlockerNow();
            return;
        }
        if (!packageName.isEmpty() && isDangerousScreenNow(packageName)) {
            showBlockerNow();
            return;
        }

        // General Settings must remain available; only named dangerous Settings surfaces above are blocked.
        if (isSettingsLikePackage(packageName)) {
            hideBlockerNow();
            return;
        }

        watchingAppDetails = false;
        optimisticAppDetailsBlock = false;

        if (policy == null) {
            hideBlockerNow();
            return;
        }

        // UNPAID: automatic limit — non-KISMART surfaces get PAYMENT REQUIRED overlay.
        if (DeviceControls.isPaymentLimitActive(policy)) {
            if (System.currentTimeMillis() < emergencyAllowedUntil) {
                hideBlockerNow();
                return;
            }
            // Outside KISMART: show limit overlay. User taps Pay Now to enter payment UI.
            // Do NOT open MainActivity under the overlay (that looked "stuck").
            showBlockerNow();
            return;
        }

        if (!DeviceControls.isFinancedPolicy(policy)) {
            hideBlockerNow();
            return;
        }

        hideBlockerNow();
    }

    /** True if any app window belongs to KISMART (MainActivity / AdminSetup). */
    private boolean isKismartInForeground() {
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                if (window == null) continue;
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                try {
                    CharSequence pkg = root.getPackageName();
                    if (pkg != null && getPackageName().equals(pkg.toString())) {
                        return true;
                    }
                } finally {
                    root.recycle();
                }
            }
        } catch (Exception ignored) {
        }
        try {
            AccessibilityNodeInfo active = getRootInActiveWindow();
            if (active != null && !blockerVisible) {
                try {
                    CharSequence pkg = active.getPackageName();
                    return pkg != null && getPackageName().equals(pkg.toString());
                } finally {
                    active.recycle();
                }
            }
        } catch (Exception ignored) {
        }
        return false;
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

    /**
     * Factory reset / device wipe activities — matched by CLASS NAME so it works even when
     * the on-screen text is too deep for a quick tree walk (Settings search results,
     * Samsung/Xiaomi/Oppo deep preferences, confirm dialogs). Covers the "search" route:
     * search → "reset" → Reset options / Factory data reset activities.
     */
    private boolean isFactoryResetClass(String className) {
        if (className == null || className.isEmpty()) return false;
        String c = className.toLowerCase();
        return c.contains("masterclear")
                || c.contains("factoryreset")
                || c.contains("factoryresetconfirm")
                || c.contains("resetdashboard")
                || c.contains("resetoptions")
                || c.contains("resetnetwork")
                || c.contains("resetapppreferences")
                || c.contains("backupreset")
                || c.contains("eraseallcontent")
                || c.contains("erasedata")
                || c.contains("eraseeverything")
                || c.contains("resetphone")
                || c.contains("wipephone")
                || c.contains("recoverymode")
                || c.contains("hardreset");
    }

    /**
     * Detects Factory Reset at the earliest possible accessibility event. Settings search
     * frequently emits a focused/selected/clicked result while the root class is still a
     * generic SearchFragment, so the event text and source node must be checked together.
     */
    private boolean isFactoryResetInteraction(
            AccessibilityEvent event,
            String packageName,
            String className,
            String eventText
    ) {
        if (!isSettingsLikePackage(packageName) && !isPackageInstallerPackage(packageName)) {
            return false;
        }
        if (isFactoryResetClass(className) || isFactoryResetScreen(eventText)) return true;
        if (event == null || (event.getEventType() & FACTORY_RESET_INTERACTION_EVENTS) == 0) {
            return false;
        }
        // Read the clicked/focused result node first. This avoids waiting for the full
        // Settings accessibility tree and catches the result before navigation completes.
        if (eventSourceMentionsFactoryReset(event)) return true;
        if (sourceMentionsFactoryResetFast()) return true;
        return isFactoryResetScreen(collectQuickScreenText());
    }

    /** Direct event-source check for the search result currently being touched/focused. */
    private boolean eventSourceMentionsFactoryReset(AccessibilityEvent event) {
        AccessibilityNodeInfo source = null;
        try {
            source = event.getSource();
            if (source == null) return false;
            StringBuilder value = new StringBuilder();
            appendText(value, source.getText());
            appendText(value, source.getContentDescription());
            appendText(value, source.getViewIdResourceName());
            return isFactoryResetScreen(value.toString().toLowerCase());
        } catch (Exception ignored) {
            return false;
        } finally {
            if (source != null) source.recycle();
        }
    }

    /** Fast source-node text lookup used on focus/click events before a screen transition. */
    private boolean sourceMentionsFactoryResetFast() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) return false;
            for (String needle : factoryResetNeedles()) {
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
        } catch (Exception ignored) {
        } finally {
            if (root != null) root.recycle();
        }
        return false;
    }

    private String[] factoryResetNeedles() {
        return new String[]{
                "Factory reset",
                "Factory data reset",
                "Reset options",
                "Erase all data",
                "Erase all content",
                "Reset phone",
                "Wipe data",
                "Master reset",
                "Hard reset",
                "Reset device"
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
            // Bounded walk — deep enough to see Settings search results and reset screens,
            // still fast enough to beat uninstall/factory-reset actions.
            StringBuilder builder = new StringBuilder();
            appendNodeText(root, builder, 0, 6, 8000);
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
        // Includes Settings search hits for factory reset wording (search results screen
        // shows "Factory data reset", "Reset options", etc.).
        return containsAny(text,
                "factory reset", "factory data reset", "reset options",
                "erase all data", "erase all content", "erase all content and settings",
                "delete all data", "delete all contents", "reset phone", "reset tablet",
                "wipe data", "format data", "restore factory settings", "clear all data",
                "erase phone", "erase tablet", "erasing", "factory data",
                "master reset", "hard reset", "reset device", "reset this device",
                "backup & reset", "backup and reset", "reset all settings",
                "erase everything", "erase all", "wipe everything",
                "reset phone settings", "restore to factory", "format phone");
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
        if (DeviceControls.isStkPromptExempt(this)) {
            hideBlockerNow();
            return;
        }
        if (isPaymentUiHandoffActive()) {
            hideBlockerNow();
            return;
        }
        if (blockerVisible && !fullLockBlockerVisible) return;
        showBlocker(buildBlocker(), false);
    }

    private void showFullLockBlockerNow() {
        if (DeviceControls.isStkPromptExempt(this)) {
            hideBlockerNow();
            return;
        }
        if (blockerVisible && fullLockBlockerVisible) return;
        showBlocker(buildFullLockBlocker(), true);
    }

    private void showBlocker(View nextBlocker, boolean fullLockBlocker) {
        if (DeviceControls.isStkPromptExempt(this) || System.currentTimeMillis() < allowKismartOpenUntil) {
            hideBlockerNow();
            return;
        }
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
            currentBlockReason = BlockReason.PAYMENT;
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

    private String activeClassName() {
        AccessibilityNodeInfo root = null;
        try {
            root = activeInspectionRoot();
            if (root == null || root.getClassName() == null) return "";
            return root.getClassName().toString();
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
        root.setBackgroundColor(UiTheme.SURFACE);
        root.setClickable(true);
        root.setFocusable(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);
        int side = dp(28);
        content.setPadding(side, side, side, side);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setBackground(UiTheme.card(this));
        card.setPadding(dp(22), dp(26), dp(22), dp(22));
        card.setElevation(dp(2));

        ImageView logo = UiTheme.logo(this, 64);
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(64), dp(64));
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        logoParams.bottomMargin = dp(16);
        card.addView(logo, logoParams);

        TextView kicker = UiTheme.sectionLabel(this, "Payment required");
        kicker.setGravity(Gravity.CENTER);
        card.addView(kicker);

        TextView title = UiTheme.text(this, limitTitle(), 20, UiTheme.INK, true);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(10), 0, dp(8));
        card.addView(title);

        TextView message = UiTheme.text(
                this,
                limitMessage(),
                14,
                UiTheme.MUTED,
                false
        );
        message.setGravity(Gravity.CENTER);
        message.setLineSpacing(dp(2), 1.15f);
        message.setPadding(0, 0, 0, dp(18));
        card.addView(message);

        Button open = UiTheme.primaryButton(this, "Continue to pay", view -> openPaymentPrompt());
        card.addView(open, buttonParams());

        Button emergency = UiTheme.secondaryButton(this, "Emergency 112", view -> {
            emergencyAllowedUntil = System.currentTimeMillis() + EMERGENCY_ALLOW_MS;
            hideBlockerNow();
            DeviceControls.callEmergency(this);
        });
        card.addView(emergency, buttonParams());

        content.addView(card, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    private String limitTitle() {
        switch (currentBlockReason) {
            case FACTORY_RESET: return "Factory reset blocked";
            case ACCESSIBILITY: return "Accessibility blocked";
            case APPS: return "Apps blocked due to payment restrictions";
            default: return "Account payment needed";
        }
    }

    private String limitMessage() {
        switch (currentBlockReason) {
            case FACTORY_RESET: return "Factory reset blocked.";
            case ACCESSIBILITY: return "Accessibility blocked.";
            case APPS: return "Apps blocked due to payment restrictions.";
            default: return "This phone is restricted until the installment payment is completed.";
        }
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        params.setMargins(0, dp(8), 0, 0);
        return params;
    }

    private void openPaymentPrompt() {
        // Critical path: hide overlay FIRST, then open MainActivity, keep overlay off until Pay is up.
        allowKismartOpenUntil = System.currentTimeMillis() + KISMART_OPEN_ALLOW_MS;
        lastPaymentHandoffOpenAt = 0L;
        DeviceControls.markPaymentUiOpening(this, KISMART_OPEN_ALLOW_MS);
        hideBlockerNow();
        openPaymentScreenDuringHandoff(true);
        // Retries: startActivity from accessibility can race overlay teardown.
        handler.post(() -> {
            hideBlockerNow();
            openPaymentScreenDuringHandoff(false);
        });
        handler.postDelayed(() -> {
            hideBlockerNow();
            openPaymentScreenDuringHandoff(false);
        }, 300L);
        handler.postDelayed(() -> {
            hideBlockerNow();
            openPaymentScreenDuringHandoff(false);
        }, 800L);
        handler.postDelayed(() -> {
            hideBlockerNow();
            if (!isKismartInForeground()) {
                lastPaymentHandoffOpenAt = 0L;
                openPaymentScreenDuringHandoff(false);
            } else {
                allowKismartOpenUntil = 0L;
                DeviceControls.clearPaymentUiOpening(this);
            }
        }, 1500L);
    }

    private void openPaymentScreenDuringHandoff() {
        openPaymentScreenDuringHandoff(false);
    }

    private void openPaymentScreenDuringHandoff(boolean forceNewTask) {
        long now = System.currentTimeMillis();
        if (now - lastPaymentHandoffOpenAt < 650L) return;
        lastPaymentHandoffOpenAt = now;
        DeviceControls.openPaymentScreenNow(this, forceNewTask);
    }

    private boolean isPaymentUiHandoffActive() {
        if (System.currentTimeMillis() < allowKismartOpenUntil) return true;
        return DeviceControls.isPaymentUiOpening(this);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
