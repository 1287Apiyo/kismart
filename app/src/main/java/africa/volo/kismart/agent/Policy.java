package africa.volo.kismart.agent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class Policy {
    final String contractId;
    final String customer;
    final String customerPhone;
    final String imei;
    final String status;
    final String restrictionLevel;
    final boolean restrictionActive;
    final String customerMessage;
    final int balance;
    final int arrears;
    final String nextDue;
    final int nextAmount;
    final int pendingCommandCount;
    final boolean identityBound;
    final int identityMismatchCount;
    final boolean paymentOnlyActive;
    final boolean mpesaReady;
    final String[] allowedPaymentPackages;
    final String pendingStkStatus;
    final String pendingStkMessage;
    final String pendingStkReference;

    private Policy(
            String contractId,
            String customer,
            String customerPhone,
            String imei,
            String status,
            String restrictionLevel,
            boolean restrictionActive,
            String customerMessage,
            int balance,
            int arrears,
            String nextDue,
            int nextAmount,
            int pendingCommandCount,
            boolean identityBound,
            int identityMismatchCount,
            boolean paymentOnlyActive,
            boolean mpesaReady,
            String[] allowedPaymentPackages,
            String pendingStkStatus,
            String pendingStkMessage,
            String pendingStkReference
    ) {
        this.contractId = contractId;
        this.customer = customer;
        this.customerPhone = customerPhone;
        this.imei = imei;
        this.status = status;
        this.restrictionLevel = restrictionLevel;
        this.restrictionActive = restrictionActive;
        this.customerMessage = customerMessage;
        this.balance = balance;
        this.arrears = arrears;
        this.nextDue = nextDue;
        this.nextAmount = nextAmount;
        this.pendingCommandCount = pendingCommandCount;
        this.identityBound = identityBound;
        this.identityMismatchCount = identityMismatchCount;
        this.paymentOnlyActive = paymentOnlyActive;
        this.mpesaReady = mpesaReady;
        this.allowedPaymentPackages = allowedPaymentPackages;
        this.pendingStkStatus = pendingStkStatus;
        this.pendingStkMessage = pendingStkMessage;
        this.pendingStkReference = pendingStkReference;
    }

    static Policy fromJson(JSONObject object) {
        JSONObject restriction = object.optJSONObject("restriction");
        JSONObject identity = object.optJSONObject("identity");
        JSONObject paymentOnly = object.optJSONObject("paymentOnly");
        JSONObject pendingStk = object.optJSONObject("pendingStk");
        JSONArray commands = object.optJSONArray("pendingCommands");
        int balance = object.optInt("balance", 0);
        if (paymentOnly != null && paymentOnly.has("balance")) {
            balance = paymentOnly.optInt("balance", balance);
        }
        int arrears = object.optInt("arrears", 0);
        if (paymentOnly != null && paymentOnly.has("arrears")) {
            arrears = paymentOnly.optInt("arrears", arrears);
        }
        boolean restrictionActive = restriction != null && restriction.optBoolean("active", false);
        String restrictionLevel = restriction == null ? "None" : restriction.optString("level", "None");

        // Limit stays on while any financed balance remains (server paymentOnly.active).
        boolean paymentOnlyActive = paymentOnly != null
                ? paymentOnly.optBoolean("active", false)
                : restrictionActive && "Limited access".equals(restrictionLevel);
        // Client-side safety: unpaid balance always keeps limit UX until server confirms paid.
        if (balance > 0 && !"Full lock".equals(restrictionLevel)) {
            paymentOnlyActive = true;
            restrictionActive = true;
            restrictionLevel = "Limited access";
        }
        if (balance <= 0) {
            paymentOnlyActive = false;
            if ("Limited access".equals(restrictionLevel) || "Full lock".equals(restrictionLevel)) {
                restrictionActive = false;
                restrictionLevel = "None";
            }
        }

        int nextAmount = object.optInt("nextAmount", 0);
        return new Policy(
                object.optString("contractId"),
                object.optString("customer"),
                object.optString("customerPhone", ""),
                object.optString("imei"),
                object.optString("status"),
                restrictionLevel,
                restrictionActive,
                object.optString("customerMessage", "Account policy synced."),
                balance,
                arrears,
                object.optString("nextDue", ""),
                nextAmount,
                commands == null ? 0 : commands.length(),
                identity != null && identity.optBoolean("bound", false),
                identity == null ? 0 : identity.optInt("mismatchCount", 0),
                paymentOnlyActive,
                object.optBoolean("mpesaReady", true),
                parseAllowedPackages(object.optJSONArray("allowedPaymentPackages"), paymentOnly == null ? null : paymentOnly.optJSONArray("allowedPackages")),
                pendingStk == null ? "" : pendingStk.optString("status", ""),
                pendingStk == null ? "" : pendingStk.optString("message", ""),
                pendingStk == null ? "" : pendingStk.optString("reference", "")
        );
    }

    /**
     * True while unpaid balance remains — the only allowed UI is the payment screen
     * until a real payment is confirmed and balance reaches zero.
     */
    boolean shouldShowLimitScreen() {
        if (balance <= 0) return false;
        // Full lock is a different surface; still unpaid debt blocks free use.
        if ("Full lock".equals(restrictionLevel) && restrictionActive) return false;
        return true;
    }

    boolean isPendingStkFailed() {
        return "Failed".equalsIgnoreCase(pendingStkStatus == null ? "" : pendingStkStatus.trim());
    }

    boolean isPendingStkPending() {
        return "Pending".equalsIgnoreCase(pendingStkStatus == null ? "" : pendingStkStatus.trim());
    }

    private static String[] parseAllowedPackages(JSONArray... arrays) {
        Set<String> packages = new LinkedHashSet<>();
        if (arrays != null) {
            for (JSONArray array : arrays) {
                if (array == null) continue;
                for (int index = 0; index < array.length(); index += 1) {
                    String packageName = array.optString(index, "").trim();
                    if (!packageName.isEmpty()) packages.add(packageName);
                }
            }
        }
        List<String> result = new ArrayList<>(packages);
        return result.toArray(new String[0]);
    }
}
