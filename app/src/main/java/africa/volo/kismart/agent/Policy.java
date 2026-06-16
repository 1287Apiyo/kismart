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
    final String imei;
    final String status;
    final String restrictionLevel;
    final boolean restrictionActive;
    final String customerMessage;
    final int balance;
    final int arrears;
    final String nextDue;
    final int pendingCommandCount;
    final boolean identityBound;
    final int identityMismatchCount;
    final boolean paymentOnlyActive;
    final String[] allowedPaymentPackages;

    private Policy(
            String contractId,
            String customer,
            String imei,
            String status,
            String restrictionLevel,
            boolean restrictionActive,
            String customerMessage,
            int balance,
            int arrears,
            String nextDue,
            int pendingCommandCount,
            boolean identityBound,
            int identityMismatchCount,
            boolean paymentOnlyActive,
            String[] allowedPaymentPackages
    ) {
        this.contractId = contractId;
        this.customer = customer;
        this.imei = imei;
        this.status = status;
        this.restrictionLevel = restrictionLevel;
        this.restrictionActive = restrictionActive;
        this.customerMessage = customerMessage;
        this.balance = balance;
        this.arrears = arrears;
        this.nextDue = nextDue;
        this.pendingCommandCount = pendingCommandCount;
        this.identityBound = identityBound;
        this.identityMismatchCount = identityMismatchCount;
        this.paymentOnlyActive = paymentOnlyActive;
        this.allowedPaymentPackages = allowedPaymentPackages;
    }

    static Policy fromJson(JSONObject object) {
        JSONObject restriction = object.optJSONObject("restriction");
        JSONObject identity = object.optJSONObject("identity");
        JSONObject paymentOnly = object.optJSONObject("paymentOnly");
        JSONArray commands = object.optJSONArray("pendingCommands");
        boolean restrictionActive = restriction != null && restriction.optBoolean("active", false);
        String restrictionLevel = restriction == null ? "None" : restriction.optString("level", "None");
        boolean paymentOnlyActive = paymentOnly != null
                ? paymentOnly.optBoolean("active", false)
                : restrictionActive && "Limited access".equals(restrictionLevel);
        return new Policy(
                object.optString("contractId"),
                object.optString("customer"),
                object.optString("imei"),
                object.optString("status"),
                restrictionLevel,
                restrictionActive,
                object.optString("customerMessage", "Account policy synced."),
                object.optInt("balance", 0),
                object.optInt("arrears", 0),
                object.optString("nextDue", ""),
                commands == null ? 0 : commands.length(),
                identity != null && identity.optBoolean("bound", false),
                identity == null ? 0 : identity.optInt("mismatchCount", 0),
                paymentOnlyActive,
                parseAllowedPackages(object.optJSONArray("allowedPaymentPackages"), paymentOnly == null ? null : paymentOnly.optJSONArray("allowedPackages"))
        );
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
