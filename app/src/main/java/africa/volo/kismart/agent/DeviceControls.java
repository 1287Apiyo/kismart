package africa.volo.kismart.agent;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.os.UserManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Locale;

final class DeviceControls {
    private static final String TAG = "KismartControls";
    private static final String KEY_PAYMENT_ONLY_SUSPENDED_PACKAGES = "payment_only_suspended_packages";
    private static final String KEY_PAYMENT_ONLY_HIDDEN_PACKAGES = "payment_only_hidden_packages";
    /** Wall-clock millis until which a verified admin may use AdminSetup only. */
    private static final String KEY_ADMIN_UNLOCK_UNTIL = "admin_unlock_until";
    /** STK PIN window — limit UI suspended only while Safaricom prompt is expected. */
    private static final String KEY_STK_EXEMPT_UNTIL = "stk_prompt_exempt_until";
    /** Admin may stay in AdminSetup this long after correct passcode (not free phone use). */
    private static final long ADMIN_SESSION_MS = 30L * 60L * 1000L;
    /**
     * STK PIN window only (~10s). After it ends we re-check payment quickly;
     * if not confirmed, limit screen returns and user cannot leave Pay.
     */
    private static final long STK_EXEMPT_DEFAULT_MS = 10L * 1000L;
    private static final Handler STK_HANDLER = new Handler(Looper.getMainLooper());
    private static Runnable pendingStkResumeRunnable;
    static final String FULL_LOCK_MESSAGE = "";
    private static final String[] DEFAULT_PAYMENT_PACKAGES = {
            "com.safaricom.mpesa",
            "com.safaricom.mpesa.lifestyle",
            "ke.co.safaricom.mpesa"
    };
    private static final String[] STK_PROMPT_PACKAGES = {
            "com.android.systemui",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.android.stk",
            "com.android.stk2",
            "com.safaricom.mpesa",
            "com.safaricom.mpesa.lifestyle",
            "ke.co.safaricom.mpesa"
    };
    
    // Packages that must be hidden in payment-only mode
    private static final String[] ALWAYS_HIDDEN_PACKAGES = {
            "com.android.settings",
            "com.android.recovery",
            "com.google.android.setupwizard",
            "com.android.managedprovisioning"
    };

    private DeviceControls() {
    }

    static ComponentName admin(Context context) {
        return new ComponentName(context, KismartDeviceAdminReceiver.class);
    }

    static boolean isAdminActive(Context context) {
        DevicePolicyManager manager = manager(context);
        return manager != null && manager.isAdminActive(admin(context));
    }

    static boolean isDeviceOwner(Context context) {
        DevicePolicyManager manager = manager(context);
        return manager != null && manager.isDeviceOwnerApp(context.getPackageName());
    }

    static boolean isAccessibilityGuardEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabled == null) return false;
        String normalized = enabled.toLowerCase(Locale.US);
        String packageName = context.getPackageName().toLowerCase(Locale.US);
        String canonical = new ComponentName(context, KismartAccessibilityService.class).flattenToString().toLowerCase(Locale.US);
        String shortForm = packageName + "/." + KismartAccessibilityService.class.getSimpleName().toLowerCase(Locale.US);
        String legacyForm = packageName + "/" + packageName + ".kismartaccessibilityservice";
        return normalized.contains(canonical)
                || normalized.contains(shortForm)
                || normalized.contains(legacyForm);
    }

    static boolean isPaymentLimitActive(Policy policy) {
        return policy != null && policy.shouldShowLimitScreen();
    }

    static boolean isLimitGuardReady(Context context, Policy policy) {
        if (!isPaymentLimitActive(policy)) return true;
        return isProtectionGuardReady(context);
    }

    static boolean enforceMissingLimitGuard(Context context, Policy policy) {
        if (isLimitGuardReady(context, policy)) return false;
        openProtectionGuardRequired(context);
        return true;
    }

    static boolean isFinancedPolicy(Policy policy) {
        if (policy == null) return false;
        if (policy.contractId == null || policy.contractId.trim().isEmpty()) return false;
        return !"Completed".equalsIgnoreCase(policy.status);
    }

    static boolean isFullLockPolicy(Policy policy) {
        return policy != null
                && policy.balance > 0
                && policy.restrictionActive
                && "Full lock".equals(policy.restrictionLevel);
    }

    static boolean enforceMissingProtectionGuard(Context context, Policy policy) {
        if (!isFinancedPolicy(policy)) return false;
        if (isProtectionGuardReady(context)) return false;
        openProtectionGuardRequired(context);
        return true;
    }

    static boolean isProtectionGuardReady(Context context) {
        return isDeviceOwner(context) || isAccessibilityGuardEnabled(context);
    }

    static boolean enforceProtectionGuardRequired(Context context) {
        if (isProtectionGuardReady(context)) return false;
        openProtectionGuardRequired(context);
        return true;
    }

    static void openProtectionGuardRequired(Context context) {
        Intent intent = new Intent(context, ProtectionGuardActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    static void requestAdmin(Activity activity) {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(activity));
        activity.startActivity(intent);
    }

    static void enforceFinancedDeviceHardening(Context context) {
        hideLauncherEntry(context);
        DevicePolicyManager manager = manager(context);
        if (manager == null || !isDeviceOwner(context)) return;
        applyBaseOwnerRestrictions(manager, admin(context));
    }

    static void applyPolicy(Activity activity, Policy policy) {
        if (policy == null) return;
        hideLauncherEntry(activity);
        enforceFinancedDeviceHardening(activity);

        // STK PIN window: do not fight the Safaricom dialog.
        if (isStkPromptExempt(activity)) {
            suspendLimitUiForStk(activity);
            return;
        }

        // Admin Setup only — does NOT unlock the whole phone for free use.
        if (isAdminSessionActive(activity) && activity instanceof AdminSetupActivity) {
            try {
                exitLockTask(activity);
            } catch (Exception ignored) {
            }
            return;
        }

        // Full access only when balance is zero (payment confirmed).
        if (policy.balance <= 0) {
            restoreOwnerRestrictions(activity);
            DeviceControls.exitLockTask(activity);
            return;
        }
        if (isFullLockPolicy(policy)) {
            enforceFullLock(activity);
            return;
        }
        if ("Lock screen message".equals(policy.restrictionLevel) && !isPaymentLimitActive(policy)) {
            setOwnerLockMessage(activity, policy.customerMessage);
            return;
        }
        // Unpaid balance → ALWAYS payment-limit mode (even after a previous admin session).
        if (enforceMissingLimitGuard(activity, policy)) return;
        enterPaymentOnlyMode(activity, policy);
        if (!(activity instanceof MainActivity) && !(activity instanceof AdminSetupActivity)) {
            bringLimitSurfaceToFront(activity);
        }
    }

    static void applyPolicyFromBackground(Context context, Policy policy) {
        if (policy == null) return;
        hideLauncherEntry(context);
        enforceFinancedDeviceHardening(context);
        if (isStkPromptExempt(context)) {
            suspendLimitUiForStk(context);
            return;
        }
        if (policy.balance <= 0) {
            restoreOwnerRestrictions(context);
            return;
        }
        if (isFullLockPolicy(policy)) {
            enforceFullLock(context);
            return;
        }
        if ("Lock screen message".equals(policy.restrictionLevel) && !isPaymentLimitActive(policy)) {
            setOwnerLockMessage(context, policy.customerMessage);
            return;
        }
        // Unpaid: always re-apply payment-only lockdown + bring Pay UI forward.
        if (enforceMissingLimitGuard(context, policy)) return;
        applyPaymentOnlyRestrictions(context, policy);
        bringLimitSurfaceToFront(context);
    }

    private static long lastLimitBringFrontAt;
    private static long lastForcePaymentScreenAt;

    /**
     * Force the payment screen into the foreground whenever debt remains.
     */
    static void bringLimitSurfaceToFront(Context context) {
        if (isStkPromptExempt(context)) return;
        if (!mustStayOnPaymentScreen(context)) return;
        long now = System.currentTimeMillis();
        if (now - lastLimitBringFrontAt < 800L) return;
        lastLimitBringFrontAt = now;
        forcePaymentScreen(context);
    }

    /** Open the payment UI (MainActivity). Lightly throttled to avoid intent spam. */
    static void forcePaymentScreen(Context context) {
        if (isStkPromptExempt(context)) return;
        long now = System.currentTimeMillis();
        if (now - lastForcePaymentScreenAt < 500L) return;
        lastForcePaymentScreenAt = now;
        openPaymentScreenNow(context);
    }

    /** Unthrottled open of MainActivity (Pay Now button / hard trap). */
    static void openPaymentScreenNow(Context context) {
        if (isStkPromptExempt(context)) return;
        try {
            Intent intent = new Intent(context, MainActivity.class);
            // NEW_TASK is required from AccessibilityService; CLEAR_TASK ensures Pay UI is top.
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                    | Intent.FLAG_ACTIVITY_NO_USER_ACTION);
            intent.putExtra("kismart_payment_lock", true);
            intent.putExtra("kismart_from_pay_now", true);
            context.startActivity(intent);
            lastForcePaymentScreenAt = System.currentTimeMillis();
        } catch (Exception e) {
            Log.e(TAG, "openPaymentScreenNow failed: " + e.getMessage());
            try {
                // Fallback: plain launch
                Intent fallback = new Intent(context, MainActivity.class);
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * True while any financed balance remains unpaid.
     * Admin session does NOT disable this for general phone use — only AdminSetup is special-cased.
     */
    static boolean mustStayOnPaymentScreen(Context context) {
        Policy policy = KismartApi.lastPolicy(context);
        return policy != null && policy.balance > 0;
    }

    static boolean mustStayOnPaymentScreen(Policy policy) {
        return policy != null && policy.balance > 0;
    }

    /** Call after correct admin passcode — allows Admin Setup only. */
    static void grantAdminSession(Context context) {
        long until = System.currentTimeMillis() + ADMIN_SESSION_MS;
        KismartApi.prefs(context).edit().putLong(KEY_ADMIN_UNLOCK_UNTIL, until).apply();
    }

    /** End admin session and re-apply payment limit if still unpaid. */
    static void clearAdminSession(Context context) {
        KismartApi.prefs(context).edit().remove(KEY_ADMIN_UNLOCK_UNTIL).apply();
        Policy policy = KismartApi.lastPolicy(context);
        if (policy != null && policy.balance > 0) {
            if (context instanceof Activity) {
                applyPolicy((Activity) context, policy);
            } else {
                applyPolicyFromBackground(context, policy);
            }
        }
    }

    static boolean isAdminSessionActive(Context context) {
        long until = KismartApi.prefs(context).getLong(KEY_ADMIN_UNLOCK_UNTIL, 0L);
        if (until <= 0L) return false;
        if (System.currentTimeMillis() >= until) {
            KismartApi.prefs(context).edit().remove(KEY_ADMIN_UNLOCK_UNTIL).apply();
            return false;
        }
        return true;
    }

    static long adminSessionRemainingMs(Context context) {
        long until = KismartApi.prefs(context).getLong(KEY_ADMIN_UNLOCK_UNTIL, 0L);
        return Math.max(0L, until - System.currentTimeMillis());
    }

    // ---------- STK PIN exemption (temporary only) ----------

    static void markStkPromptActive(Context context) {
        markStkPromptActive(context, STK_EXEMPT_DEFAULT_MS);
    }

    static void markStkPromptActive(Context context, long durationMs) {
        final Context app = context.getApplicationContext();
        // PIN window ~10s (not 45–90s). Then 3s later we re-check payment / restore limit.
        long duration = Math.max(8_000L, Math.min(durationMs, 12_000L));
        long until = System.currentTimeMillis() + duration;
        KismartApi.prefs(app).edit().putLong(KEY_STK_EXEMPT_UNTIL, until).apply();
        suspendLimitUiForStk(context);
        if (pendingStkResumeRunnable != null) {
            STK_HANDLER.removeCallbacks(pendingStkResumeRunnable);
        }
        pendingStkResumeRunnable = () -> STK_HANDLER.postDelayed(() -> resumePaymentLimitAfterStk(app), 3_000L);
        STK_HANDLER.postDelayed(pendingStkResumeRunnable, duration);
    }

    static void clearStkPromptExempt(Context context) {
        if (pendingStkResumeRunnable != null) {
            STK_HANDLER.removeCallbacks(pendingStkResumeRunnable);
            pendingStkResumeRunnable = null;
        }
        KismartApi.prefs(context).edit().remove(KEY_STK_EXEMPT_UNTIL).apply();
        resumePaymentLimitAfterStk(context);
    }

    /**
     * After STK PIN window ends (+ short confirm delay):
     * unpaid → limit on + trap on Pay screen; paid → full access.
     */
    static void resumePaymentLimitAfterStk(Context context) {
        KismartApi.prefs(context).edit().remove(KEY_STK_EXEMPT_UNTIL).apply();
        Policy policy = KismartApi.lastPolicy(context);
        if (policy == null) {
            openPaymentScreenNow(context);
            return;
        }
        if (policy.balance <= 0) {
            // Payment confirmed — restore normal access.
            if (context instanceof Activity) {
                applyPolicy((Activity) context, policy);
            } else {
                applyPolicyFromBackground(context, policy);
            }
            return;
        }
        // Not paid: limit ALWAYS returns; user cannot leave payment UI.
        if (context instanceof Activity) {
            applyPolicy((Activity) context, policy);
        } else {
            applyPolicyFromBackground(context, policy);
        }
        openPaymentScreenNow(context);
        // Hard re-trap a moment later in case user tried to leave during STK.
        STK_HANDLER.postDelayed(() -> {
            Policy p = KismartApi.lastPolicy(context);
            if (p != null && p.balance > 0 && !isStkPromptExempt(context)) {
                openPaymentScreenNow(context);
                if (context instanceof Activity) {
                    applyPolicy((Activity) context, p);
                } else {
                    applyPolicyFromBackground(context, p);
                }
            }
        }, 500L);
    }

    static boolean isStkPromptExempt(Context context) {
        long until = KismartApi.prefs(context).getLong(KEY_STK_EXEMPT_UNTIL, 0L);
        if (until <= 0L) return false;
        if (System.currentTimeMillis() >= until) {
            KismartApi.prefs(context).edit().remove(KEY_STK_EXEMPT_UNTIL).apply();
            final Context app = context.getApplicationContext();
            STK_HANDLER.post(() -> resumePaymentLimitAfterStk(app));
            return false;
        }
        return true;
    }

    static void suspendLimitUiForStk(Context context) {
        DevicePolicyManager dpm = manager(context);
        if (dpm != null && isDeviceOwner(context)) {
            ComponentName adminComponent = admin(context);
            try {
                Set<String> packages = new LinkedHashSet<>();
                packages.add(context.getPackageName());
                for (String pkg : STK_PROMPT_PACKAGES) packages.add(pkg);
                setLockTaskPackagesSafely(context, dpm, adminComponent, packages.toArray(new String[0]));
                dpm.setStatusBarDisabled(adminComponent, false);
            } catch (Exception ignored) {
            }
        }
        if (context instanceof Activity) {
            try {
                exitLockTask((Activity) context);
            } catch (Exception ignored) {
            }
        }
    }

    static boolean isStkPromptPackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        String pkg = packageName.trim().toLowerCase(Locale.US);
        for (String allowed : STK_PROMPT_PACKAGES) {
            if (pkg.equals(allowed) || pkg.startsWith(allowed + ".")) return true;
        }
        return pkg.contains(".stk") || pkg.contains("mpesa") || pkg.contains("simtoolkit");
    }

    static void enforceFullLock(Context context) {
        Policy policy = KismartApi.lastPolicy(context);
        if (policy != null && policy.balance <= 0) {
            restoreOwnerRestrictions(context);
            return;
        }
        applyStrictOwnerRestrictions(context);
        setOwnerLockMessage(context, FULL_LOCK_MESSAGE);
        if (context instanceof Activity) {
            enterLockTaskIfOwner((Activity) context);
        }
        startLockActivity(context);
        if (!isDeviceOwner(context)) lockNow(context);
    }

    static void reinforceVisibleFullLock(Activity activity) {
        applyStrictOwnerRestrictions(activity);
        setOwnerLockMessage(activity, FULL_LOCK_MESSAGE);
        enterLockTaskIfOwner(activity);
    }

    static void lockNow(Context context) {
        DevicePolicyManager manager = manager(context);
        if (manager != null && manager.isAdminActive(admin(context))) {
            manager.lockNow();
        }
    }

    static boolean openPaymentApp(Context context, Policy policy) {
        PackageManager packages = context.getPackageManager();
        for (String packageName : paymentPackageCandidates(policy)) {
            Intent launch = packages.getLaunchIntentForPackage(packageName);
            if (launch == null) continue;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(launch);
                return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    static int installedPaymentAppCount(Context context, Policy policy) {
        int installed = 0;
        for (String packageName : paymentPackageCandidates(policy)) {
            if (isPackageInstalled(context, packageName)) installed += 1;
        }
        return installed;
    }

    static int disabledAppCount(Context context) {
        return splitPackages(KismartApi.prefs(context).getString(KEY_PAYMENT_ONLY_SUSPENDED_PACKAGES, "")).length
                + splitPackages(KismartApi.prefs(context).getString(KEY_PAYMENT_ONLY_HIDDEN_PACKAGES, "")).length;
    }

    static void callEmergency(Context context) {
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    static void openAccessibilitySettings(Activity activity) {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    static void enterLockTaskIfOwner(Activity activity) {
        Set<String> allowedPackages = new LinkedHashSet<>();
        allowedPackages.add(activity.getPackageName());
        enterLockTaskIfOwner(activity, allowedPackages.toArray(new String[0]));
    }

    static void exitLockTask(Activity activity) {
        try {
            activity.stopLockTask();
        } catch (IllegalArgumentException | IllegalStateException ignored) {
        }
    }

    private static void enterPaymentOnlyMode(Activity activity, Policy policy) {
        if (isStkPromptExempt(activity)) {
            suspendLimitUiForStk(activity);
            return;
        }
        applyPaymentOnlyRestrictions(activity, policy);
        // Pin ONLY the KISMART payment app — no other packages in lock task.
        enterLockTaskIfOwner(activity, paymentOnlyPackages(activity, policy));
    }

    private static void enterLockTaskIfOwner(Activity activity, String[] allowedPackages) {
        DevicePolicyManager manager = manager(activity);
        if (manager == null || !isDeviceOwner(activity)) return;
        setLockTaskPackagesSafely(activity, manager, admin(activity), allowedPackages);
        try {
            activity.startLockTask();
        } catch (IllegalArgumentException ignored) {
        }
    }

    private static void startLockActivity(Context context) {
        Intent intent = new Intent(context, LockActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private static void startMainActivity(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            context.startActivity(intent);
        } catch (Exception ignored) {
        }
    }

    private static void setOwnerLockMessage(Context context, String message) {
        DevicePolicyManager manager = manager(context);
        if (manager == null || !isDeviceOwner(context)) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            manager.setDeviceOwnerLockScreenInfo(admin(context), message);
        }
    }

    private static void applyPaymentOnlyRestrictions(Context context, Policy policy) {
        DevicePolicyManager manager = manager(context);
        if (manager == null) return;
        if (!isDeviceOwner(context)) {
            return;
        }
        ComponentName admin = admin(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            manager.setDeviceOwnerLockScreenInfo(admin, policy.customerMessage);
        }
        applyBaseOwnerRestrictions(manager, admin);
        try {
            manager.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);
            manager.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER);
            manager.addUserRestriction(admin, UserManager.DISALLOW_REMOVE_USER);
            manager.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS);
            manager.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_CREDENTIALS);
            manager.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS);
            manager.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
            manager.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES);
            manager.addUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS);
            manager.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI);
            manager.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_BLUETOOTH);
            manager.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER);
            manager.setStatusBarDisabled(admin, true);
            setCameraDisabledSafely(manager, admin, true);
            
            // Block factory reset at the device policy level
            manager.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET);
        } catch (SecurityException ignored) {
        }
        String[] allowedPackages = paymentOnlyPackages(context, policy);
        setKismartAsHome(context, manager, admin);
        
        // Hide settings and other dangerous apps
        hideSettingsAndDangerousApps(context, manager, admin);
        
        disableNonAllowedLaunchableApps(context, manager, admin, allowedPackages);
    }

    private static void applyStrictOwnerRestrictions(Context context) {
        DevicePolicyManager manager = manager(context);
        if (manager == null || !isDeviceOwner(context)) return;
        ComponentName admin = admin(context);
        setLauncherEntryVisible(context, true);
        setKismartLockHome(context, manager, admin);
        applyBaseOwnerRestrictions(manager, admin);
        try {
            manager.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);
            manager.addUserRestriction(admin, UserManager.DISALLOW_ADD_USER);
            manager.addUserRestriction(admin, UserManager.DISALLOW_REMOVE_USER);
            manager.addUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS);
            manager.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_CREDENTIALS);
            manager.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS);
            manager.addUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
            manager.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES);
            manager.addUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS);
            manager.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI);
            manager.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_BLUETOOTH);
            manager.addUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER);
            manager.setStatusBarDisabled(admin, true);
            manager.setKeyguardDisabled(admin, true);
            setCameraDisabledSafely(manager, admin, true);
            
            // Block factory reset in full lock mode too
            manager.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET);
        } catch (SecurityException ignored) {
        }
        hideSettingsAndDangerousApps(context, manager, admin);
        disableNonAllowedLaunchableApps(context, manager, admin, new String[]{context.getPackageName()});
    }

    private static void applyBaseOwnerRestrictions(DevicePolicyManager manager, ComponentName admin) {
        try {
            manager.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);
            manager.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET);
            manager.addUserRestriction(admin, UserManager.DISALLOW_APPS_CONTROL);
            manager.addUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS);
            manager.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES);
            applySelfProtection(manager, admin);
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    private static void restoreOwnerRestrictions(Context context) {
        DevicePolicyManager manager = manager(context);
        if (manager == null || !isDeviceOwner(context)) return;
        ComponentName admin = admin(context);
        clearKismartHome(context, manager, admin);
        restoreHiddenPackages(context, manager, admin);
        restoreSuspendedPackages(context, manager, admin);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            manager.setDeviceOwnerLockScreenInfo(admin, null);
        }
        manager.clearUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT);
        manager.clearUserRestriction(admin, UserManager.DISALLOW_ADD_USER);
        manager.clearUserRestriction(admin, UserManager.DISALLOW_REMOVE_USER);
        manager.clearUserRestriction(admin, UserManager.DISALLOW_MODIFY_ACCOUNTS);
        manager.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_CREDENTIALS);
        manager.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS);
        manager.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
        manager.clearUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES);
        manager.clearUserRestriction(admin, UserManager.DISALLOW_CREATE_WINDOWS);
        manager.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_WIFI);
        manager.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_BLUETOOTH);
        manager.clearUserRestriction(admin, UserManager.DISALLOW_USB_FILE_TRANSFER);
        manager.clearUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET);
        setCameraDisabledSafely(manager, admin, false);
        try {
            manager.setStatusBarDisabled(admin, false);
            manager.setKeyguardDisabled(admin, false);
            setLockTaskPackagesSafely(context, manager, admin, new String[]{context.getPackageName()});
        } catch (SecurityException ignored) {
        }
        applyBaseOwnerRestrictions(manager, admin);
    }

    private static void setCameraDisabledSafely(DevicePolicyManager manager, ComponentName admin, boolean disabled) {
        try {
            manager.setCameraDisabled(admin, disabled);
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    private static String[] paymentOnlyPackages(Context context, Policy policy) {
        Set<String> allowedPackages = new LinkedHashSet<>();
        allowedPackages.add(context.getPackageName());
        return allowedPackages.toArray(new String[0]);
    }

    private static List<String> paymentPackageCandidates(Policy policy) {
        Set<String> packages = new LinkedHashSet<>();
        if (policy != null && policy.allowedPaymentPackages != null) {
            for (String packageName : policy.allowedPaymentPackages) addCleanPackage(packages, packageName);
        }
        if (packages.isEmpty()) {
            for (String packageName : DEFAULT_PAYMENT_PACKAGES) addCleanPackage(packages, packageName);
        }
        return new ArrayList<>(packages);
    }

    private static void addEmergencyDialerPackage(Context context, Set<String> packages) {
        PackageManager packageManager = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"));
        String packageName = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageName = packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0)) == null
                        ? null
                        : packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0)).activityInfo.packageName;
            } else {
                packageName = packageManager.resolveActivity(intent, 0) == null
                        ? null
                        : packageManager.resolveActivity(intent, 0).activityInfo.packageName;
            }
        } catch (Exception ignored) {
        }
        addCleanPackage(packages, packageName);
    }

    private static void addCleanPackage(Set<String> packages, String packageName) {
        if (packageName == null) return;
        String cleaned = packageName.trim();
        if (!cleaned.isEmpty()) packages.add(cleaned);
    }

    private static boolean isPackageInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private static void setLockTaskPackagesSafely(Context context, DevicePolicyManager manager, ComponentName admin, String[] allowedPackages) {
        try {
            manager.setLockTaskPackages(admin, allowedPackages);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                manager.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    private static void hideSettingsAndDangerousApps(Context context, DevicePolicyManager manager, ComponentName admin) {
        for (String packageName : ALWAYS_HIDDEN_PACKAGES) {
            try {
                if (manager.setApplicationHidden(admin, packageName, true)) {
                    Log.d(TAG, "Hidden dangerous app: " + packageName);
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }
    }

    private static void disableNonAllowedLaunchableApps(Context context, DevicePolicyManager manager, ComponentName admin, String[] allowedPackages) {
        Set<String> allowed = new LinkedHashSet<>();
        for (String packageName : allowedPackages) addCleanPackage(allowed, packageName);
        allowed.add("com.android.systemui");

        Set<String> candidates = launchablePackages(context);
        candidates.removeAll(allowed);
        if (candidates.isEmpty()) return;
        String[] requested = candidates.toArray(new String[0]);
        Set<String> failedToSuspend = suspendPackages(context, manager, admin, requested);
        if (!failedToSuspend.isEmpty()) {
            hidePackages(context, manager, admin, failedToSuspend.toArray(new String[0]));
        }
    }

    private static Set<String> launchablePackages(Context context) {
        Set<String> result = new LinkedHashSet<>();
        PackageManager packageManager = context.getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        try {
            List<ResolveInfo> launchers;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                launchers = packageManager.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0));
            } else {
                launchers = packageManager.queryIntentActivities(launcherIntent, 0);
            }
            for (ResolveInfo info : launchers) {
                if (info != null && info.activityInfo != null) addCleanPackage(result, info.activityInfo.packageName);
            }
        } catch (Exception ignored) {
        }
        try {
            List<PackageInfo> installedPackages = packageManager.getInstalledPackages(0);
            for (PackageInfo info : installedPackages) {
                if (info == null || info.packageName == null) continue;
                if (packageManager.getLaunchIntentForPackage(info.packageName) == null) continue;
                addCleanPackage(result, info.packageName);
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private static void setKismartAsHome(Context context, DevicePolicyManager manager, ComponentName admin) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        try {
            manager.addPersistentPreferredActivity(admin, filter, new ComponentName(context, MainActivity.class));
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    private static void setKismartLockHome(Context context, DevicePolicyManager manager, ComponentName admin) {
        IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        try {
            manager.clearPackagePersistentPreferredActivities(admin, context.getPackageName());
            manager.addPersistentPreferredActivity(admin, filter, new ComponentName(context, LockActivity.class));
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    private static void clearKismartHome(Context context, DevicePolicyManager manager, ComponentName admin) {
        try {
            manager.clearPackagePersistentPreferredActivities(admin, context.getPackageName());
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
    }

    static void hideLauncherEntry(Context context) {
        setLauncherEntryVisible(context, false);
    }

    static void showLauncherEntry(Context context) {
        setLauncherEntryVisible(context, true);
    }

    private static void setLauncherEntryVisible(Context context, boolean visible) {
        try {
            ComponentName launcher = new ComponentName(context, LauncherActivity.class);
            int desiredState = visible
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            PackageManager packageManager = context.getPackageManager();
            if (packageManager.getComponentEnabledSetting(launcher) != desiredState) {
                packageManager.setComponentEnabledSetting(launcher, desiredState, PackageManager.DONT_KILL_APP);
            }
        } catch (Exception ignored) {
        }
    }

    private static void hidePackages(Context context, DevicePolicyManager manager, ComponentName admin, String[] requested) {
        List<String> hidden = new ArrayList<>();
        for (String packageName : requested) {
            try {
                if (manager.setApplicationHidden(admin, packageName, true)) {
                    hidden.add(packageName);
                }
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }
        if (!hidden.isEmpty()) {
            KismartApi.prefs(context).edit()
                    .putString(KEY_PAYMENT_ONLY_HIDDEN_PACKAGES, joinPackages(hidden))
                    .apply();
        }
    }

    private static Set<String> suspendPackages(Context context, DevicePolicyManager manager, ComponentName admin, String[] requested) {
        Set<String> failedSet = new LinkedHashSet<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            for (String packageName : requested) addCleanPackage(failedSet, packageName);
            return failedSet;
        }
        try {
            String[] failed = manager.setPackagesSuspended(admin, requested, true);
            if (failed != null) {
                for (String packageName : failed) addCleanPackage(failedSet, packageName);
            }
            List<String> suspended = new ArrayList<>();
            for (String packageName : requested) {
                if (!failedSet.contains(packageName)) suspended.add(packageName);
            }
            if (!suspended.isEmpty()) {
                KismartApi.prefs(context).edit()
                        .putString(KEY_PAYMENT_ONLY_SUSPENDED_PACKAGES, joinPackages(suspended))
                        .apply();
            }
        } catch (SecurityException | IllegalArgumentException ignored) {
            for (String packageName : requested) addCleanPackage(failedSet, packageName);
        }
        return failedSet;
    }

    private static void restoreHiddenPackages(Context context, DevicePolicyManager manager, ComponentName admin) {
        String raw = KismartApi.prefs(context).getString(KEY_PAYMENT_ONLY_HIDDEN_PACKAGES, "");
        if (raw == null || raw.trim().isEmpty()) return;
        String[] packages = splitPackages(raw);
        for (String packageName : packages) {
            try {
                manager.setApplicationHidden(admin, packageName, false);
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }
        KismartApi.prefs(context).edit().remove(KEY_PAYMENT_ONLY_HIDDEN_PACKAGES).apply();
    }

    private static void restoreSuspendedPackages(Context context, DevicePolicyManager manager, ComponentName admin) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        String raw = KismartApi.prefs(context).getString(KEY_PAYMENT_ONLY_SUSPENDED_PACKAGES, "");
        if (raw == null || raw.trim().isEmpty()) return;
        String[] packages = splitPackages(raw);
        if (packages.length == 0) return;
        try {
            manager.setPackagesSuspended(admin, packages, false);
        } catch (SecurityException | IllegalArgumentException ignored) {
        } finally {
            KismartApi.prefs(context).edit().remove(KEY_PAYMENT_ONLY_SUSPENDED_PACKAGES).apply();
        }
    }

    private static String joinPackages(List<String> packages) {
        StringBuilder builder = new StringBuilder();
        for (String packageName : packages) {
            if (builder.length() > 0) builder.append(",");
            builder.append(packageName);
        }
        return builder.toString();
    }

    private static String[] splitPackages(String raw) {
        String[] parts = raw.split(",");
        List<String> packages = new ArrayList<>();
        for (String part : parts) {
            String cleaned = part == null ? "" : part.trim();
            if (!cleaned.isEmpty()) packages.add(cleaned);
        }
        return packages.toArray(new String[0]);
    }

    static void protectAppFromUninstall(Context context) {
        DevicePolicyManager manager = manager(context);
        if (manager == null) return;

        ComponentName admin = admin(context);
        String packageName = context.getPackageName();

        if (manager.isDeviceOwnerApp(packageName)) {
            applyBaseOwnerRestrictions(manager, admin);
            applySelfProtection(manager, admin);
        }

        if (manager.isAdminActive(admin)) {
            try {
                UserManager userManager = (UserManager) context.getSystemService(Context.USER_SERVICE);
                if (userManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    userManager.getUserRestrictions();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void applySelfProtection(DevicePolicyManager manager, ComponentName admin) {
        try {
            manager.setUninstallBlocked(admin, admin.getPackageName(), true);
        } catch (SecurityException | IllegalArgumentException ignored) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                manager.setUserControlDisabledPackages(admin, Collections.singletonList(admin.getPackageName()));
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }
    }

    private static DevicePolicyManager manager(Context context) {
        return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
    }
}
