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
    private static final long WATCHDOG_INTERVAL_MS = 450L;
    private static final long EMERGENCY_ALLOW_MS = 30000L;
    private static final long KISMART_OPEN_ALLOW_MS = 5000L;
    // Increased debounce time to prevent flickering
    private static final long MIN_BLOCKER_TOGGLE_MS = 800L;
    // Cooldown period after hiding before showing again
    private static final long SHOW_COOLDOWN_MS = 400L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            enforceCurrentWindow(null);
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    private WindowManager windowManager;
    private View blocker;
    private boolean blockerVisible;
    private boolean holdingDangerousSettings;
    private long emergencyAllowedUntil;
    private long allowKismartOpenUntil;
    private long lastBlockerToggle = 0L;
    private long lastHideTime = 0L;
    private Runnable pendingShow;
    private Runnable pendingHide;
    // Flag to prevent concurrent operations
    private boolean isUpdatingBlocker = false;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_VIEW_CLICKED) {
            return;
        }
        CharSequence sourcePackage = event.getPackageName();
        String packageName = sourcePackage == null ? "" : sourcePackage.toString();
        enforceCurrentWindow(packageName);
    }

    @Override
    public void onInterrupt() {
    }

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
        // Cancel all pending operations
        cancelPendingOperations();
        hideBlockerImmediate();
        super.onDestroy();
    }

    private void cancelPendingOperations() {
        if (pendingShow != null) {
            handler.removeCallbacks(pendingShow);
            pendingShow = null;
        }
        if (pendingHide != null) {
            handler.removeCallbacks(pendingHide);
            pendingHide = null;
        }
        isUpdatingBlocker = false;
    }

    private void configureService() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOWS_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_CLICKED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 50L; // Increased to reduce event frequency
        info.flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
    }

    private void enforceCurrentWindow(String eventPackage) {
        Policy policy = KismartApi.lastPolicy(this);
        if (policy == null) {
            hideBlocker();
            return;
        }

        String packageName = cleanPackageName(eventPackage);
        if (packageName.isEmpty()) packageName = activePackageName();
        if (packageName.isEmpty()) return;

        // Don't process if we're already updating the blocker
        if (isUpdatingBlocker) return;

        if (DeviceControls.isFullLockPolicy(policy)) {
            hideBlocker();
            if (!isKismartLockScreen()) {
                DeviceControls.enforceFullLock(this);
            }
            return;
        }

        boolean shouldShow = false;
        boolean shouldHide = false;
        boolean isDangerous = false;

        if (blockerVisible && holdingDangerousSettings) {
            if (shouldKeepDangerousBlocker(policy, packageName)) {
                return; // Keep showing
            }
            shouldHide = true;
        } else if (blockerVisible && isProtectionOverlayScreen()) {
            if (policy.paymentOnlyActive && isOverlayEventPackage(packageName)) {
                return; // Keep showing
            }
        }

        if (isFinancedPolicy(policy) && isFinancedDeviceControlSurface(packageName)) {
            shouldShow = true;
            isDangerous = true;
        } else if (policy.paymentOnlyActive) {
            if (getPackageName().equals(packageName)) {
                if (blockerVisible && isProtectionOverlayScreen()) {
                    return; // Keep showing
                }
                shouldHide = true;
            } else if (System.currentTimeMillis() < allowKismartOpenUntil && isOverlayEventPackage(packageName)) {
                shouldHide = true;
            } else if (isAllowedSystemPackage(packageName)) {
                // Don't change anything
            } else {
                shouldShow = true;
            }
        } else {
            shouldHide = true;
        }

        // Apply the determined state
        if (shouldShow) {
            showBlocker(isDangerous);
        } else if (shouldHide) {
            hideBlocker();
        }
    }

    private boolean isFinancedDeviceControlSurface(String packageName) {
        if (isDangerousSettingsScreen(packageName)) return true;
        return isDangerousRemovalSurface();
    }

    private boolean shouldKeepDangerousBlocker(Policy policy, String packageName) {
        if (!isFinancedPolicy(policy) && !policy.paymentOnlyActive) return false;
        if (isLauncherPackage(packageName)) return false;
        if (getPackageName().equals(packageName)) return false;
        if (isSettingsLikePackage(packageName)) return true;
        return isOverlayEventPackage(packageName);
    }

    private boolean isLauncherPackage(String packageName) {
        String value = packageName == null ? "" : packageName.toLowerCase();
        return value.contains("launcher")
                || value.contains(".home")
                || value.equals("com.miui.home")
                || value.equals("com.android.launcher")
                || value.equals("com.google.android.apps.nexuslauncher");
    }

    private boolean isAllowedSystemPackage(String packageName) {
        if ("android".equals(packageName)) return true;
        if ("com.android.systemui".equals(packageName)) return true;
        if (System.currentTimeMillis() < emergencyAllowedUntil && isEmergencyPackage(packageName)) return true;
        return false;
    }

    private boolean isEmergencyPackage(String packageName) {
        String value = packageName == null ? "" : packageName.toLowerCase();
        return value.contains("dialer")
                || value.contains("incallui")
                || value.contains("telecom")
                || value.contains("contacts");
    }

    private boolean isOverlayEventPackage(String packageName) {
        String value = cleanPackageName(packageName);
        return getPackageName().equals(value)
                || "android".equals(value)
                || "com.android.systemui".equals(value);
    }

    private boolean isFinancedPolicy(Policy policy) {
        if (policy == null) return false;
        if (policy.contractId == null || policy.contractId.trim().isEmpty()) return false;
        return !"Completed".equalsIgnoreCase(policy.status);
    }

    private boolean isSettingsLikePackage(String packageName) {
        String value = packageName == null ? "" : packageName.toLowerCase();
        return "com.android.settings".equals(value)
                || value.contains(".settings")
                || value.contains("securitycenter")
                || value.contains("permissioncontroller")
                || value.contains("packageinstaller")
                || value.contains("installer");
    }

    private boolean isDangerousSettingsScreen(String packageName) {
        if (!isSettingsLikePackage(packageName)) return false;
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) return false;
            String screenText = collectScreenText(root).toLowerCase();
            return isFactoryResetScreen(screenText)
                    || isAccessibilityControlScreen(screenText)
                    || isDeviceAdminControlScreen(screenText)
                    || isKismartRemovalScreen(screenText);
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (root != null) root.recycle();
        }
    }

    private boolean isDangerousRemovalSurface() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) return false;
            String screenText = collectScreenText(root).toLowerCase();
            return isKismartRemovalScreen(screenText);
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (root != null) root.recycle();
        }
    }

    private boolean isFactoryResetScreen(String screenText) {
        return containsAny(screenText,
                "factory reset",
                "factory data reset",
                "reset options",
                "erase all data",
                "erase all content",
                "erase all content and settings",
                "delete all data",
                "delete all contents",
                "reset phone",
                "reset tablet",
                "wipe data",
                "format data",
                "restore factory settings",
                "clear all data");
    }

    private boolean isAccessibilityControlScreen(String screenText) {
        if (!screenText.contains("accessibility")) return false;
        if (screenText.contains("kismart")) return true;
        return containsAny(screenText,
                "downloaded apps",
                "installed apps",
                "accessibility shortcut",
                "volume key shortcut",
                "screen reader",
                "interaction controls",
                "use service",
                "stop service",
                "turn off",
                "allow restricted setting");
    }

    private boolean isDeviceAdminControlScreen(String screenText) {
        return containsAny(screenText,
                "device admin apps",
                "device administrator",
                "device administrators",
                "deactivate this device admin app",
                "deactivate device admin",
                "device admin");
    }

    private boolean isKismartRemovalScreen(String screenText) {
        if (!screenText.contains("kismart")) return false;
        return containsAny(screenText,
                "uninstall",
                "uninstall app",
                "uninstall kismart",
                "delete",
                "delete app",
                "delete kismart",
                "remove app",
                "remove from device",
                "remove this app",
                "remove kismart",
                "app info",
                "app details",
                "manage app",
                "manage apps",
                "disable",
                "deactivate",
                "device admin",
                "device administrator",
                "force stop",
                "clear data",
                "storage",
                "trash",
                "drag here to uninstall");
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null) return false;
        for (String needle : needles) {
            if (needle != null && text.contains(needle)) return true;
        }
        return false;
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
        for (int index = 0; index < node.getChildCount(); index += 1) {
            AccessibilityNodeInfo child = null;
            try {
                child = node.getChild(index);
                appendNodeText(child, builder, depth + 1);
            } catch (RuntimeException ignored) {
            } finally {
                if (child != null) child.recycle();
            }
        }
    }

    private void appendText(StringBuilder builder, CharSequence value) {
        if (value == null) return;
        String text = value.toString().trim();
        if (text.isEmpty()) return;
        builder.append(' ').append(text);
    }

    private boolean isProtectionOverlayScreen() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) return false;
            String text = collectScreenText(root).toLowerCase();
            return text.contains("kismart protection active") || text.contains("open kismart");
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (root != null) root.recycle();
        }
    }

    private boolean isKismartLockScreen() {
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root == null) return false;
            if (root.getPackageName() == null || !getPackageName().equals(root.getPackageName().toString())) return false;
            String text = collectScreenText(root).toLowerCase();
            return text.contains("locked by kismart") || text.contains("admin unlock required");
        } catch (RuntimeException ignored) {
            return false;
        } finally {
            if (root != null) root.recycle();
        }
    }

    private String activePackageName() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null) return "";
            return cleanPackageName(root.getPackageName().toString());
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private String cleanPackageName(String value) {
        return value == null ? "" : value.trim();
    }

    private void showBlocker() {
        showBlocker(false);
    }

    private void showBlocker(boolean dangerousSettings) {
        long now = System.currentTimeMillis();

        // Prevent showing if recently hidden (cooldown)
        if (now - lastHideTime < SHOW_COOLDOWN_MS) {
            return;
        }

        // If already visible with same settings, don't update
        if (blockerVisible && holdingDangerousSettings == dangerousSettings) {
            return;
        }

        // If already visible but with different settings, hide first then show
        if (blockerVisible) {
            hideBlockerImmediate();
        }

        long since = now - lastBlockerToggle;
        if (since < MIN_BLOCKER_TOGGLE_MS) {
            // Cancel any existing pending show
            if (pendingShow != null) {
                handler.removeCallbacks(pendingShow);
            }

            pendingShow = new Runnable() {
                @Override
                public void run() {
                    isUpdatingBlocker = false;
                    lastBlockerToggle = System.currentTimeMillis();
                    actuallyShowBlocker(dangerousSettings);
                    pendingShow = null;
                }
            };
            isUpdatingBlocker = true;
            handler.postDelayed(pendingShow, MIN_BLOCKER_TOGGLE_MS - since);
            return;
        }

        lastBlockerToggle = now;
        actuallyShowBlocker(dangerousSettings);
    }

    private void actuallyShowBlocker(boolean dangerousSettings) {
        if (windowManager == null) windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            isUpdatingBlocker = false;
            return;
        }

        try {
            // Remove existing blocker if any
            if (blocker != null && blocker.isAttachedToWindow()) {
                try {
                    windowManager.removeView(blocker);
                } catch (Exception e) {
                    // Ignore if not attached
                }
            }

            // Create fresh blocker view
            blocker = buildBlocker();
            holdingDangerousSettings = dangerousSettings;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
            );
            params.gravity = Gravity.TOP | Gravity.START;

            windowManager.addView(blocker, params);
            blockerVisible = true;
            isUpdatingBlocker = false;
        } catch (Exception e) {
            blockerVisible = false;
            holdingDangerousSettings = false;
            isUpdatingBlocker = false;
        }
    }

    private void hideBlocker() {
        if (!blockerVisible) return;

        long now = System.currentTimeMillis();
        long since = now - lastBlockerToggle;

        if (since < MIN_BLOCKER_TOGGLE_MS) {
            // Cancel any existing pending hide
            if (pendingHide != null) {
                handler.removeCallbacks(pendingHide);
            }

            pendingHide = new Runnable() {
                @Override
                public void run() {
                    isUpdatingBlocker = false;
                    lastBlockerToggle = System.currentTimeMillis();
                    actuallyHideBlocker();
                    pendingHide = null;
                }
            };
            isUpdatingBlocker = true;
            handler.postDelayed(pendingHide, MIN_BLOCKER_TOGGLE_MS - since);
            return;
        }

        lastBlockerToggle = now;
        actuallyHideBlocker();
    }

    private void hideBlockerImmediate() {
        cancelPendingOperations();
        if (blocker != null && blocker.isAttachedToWindow() && windowManager != null) {
            try {
                windowManager.removeView(blocker);
            } catch (Exception e) {
                // Ignore errors
            }
        }
        blockerVisible = false;
        holdingDangerousSettings = false;
        lastHideTime = System.currentTimeMillis();
    }

    private void actuallyHideBlocker() {
        try {
            if (blocker != null && blocker.isAttachedToWindow() && windowManager != null) {
                windowManager.removeView(blocker);
            }
        } catch (Exception e) {
            // Ignore errors
        } finally {
            blockerVisible = false;
            holdingDangerousSettings = false;
            lastHideTime = System.currentTimeMillis();
            isUpdatingBlocker = false;
        }
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
            hideBlocker();
            DeviceControls.callEmergency(this);
        });

        content.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));
        content.addView(message, spacedParams());
        content.addView(open, buttonParams());
        content.addView(emergency, buttonParams());

        root.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        return root;
    }

    private LinearLayout.LayoutParams spacedParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(16), 0, dp(20));
        return params;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(48)
        );
        params.setMargins(0, dp(8), 0, 0);
        return params;
    }

    private void openKismart() {
        allowKismartOpenUntil = System.currentTimeMillis() + KISMART_OPEN_ALLOW_MS;
        hideBlocker();
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