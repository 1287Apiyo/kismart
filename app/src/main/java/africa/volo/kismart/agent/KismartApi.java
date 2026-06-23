package africa.volo.kismart.agent;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class KismartApi {
    static final String PREFS = "kismart_agent";
    static final String KEY_SERVER_URL = "server_url";
    static final String KEY_IMEI = "imei";
    static final String KEY_SECRET = "device_secret";
    static final String KEY_LAST_POLICY = "last_policy";
    static final String KEY_LAST_POLICY_AT = "last_policy_at";
    static final String KEY_INSTALL_ID = "install_id";
    static final String KEY_BINDING_TOKEN = "binding_token";
    private static final String KEY_APPLIED_COMMAND_IDS = "applied_command_ids";
    static final String APP_VERSION = "android-1.0.34";
    static final String DEFAULT_SERVER_URL = "http://192.168.98.7:8787";
    static final String DEFAULT_IMEI = "357527486213862";
    static final String DEFAULT_DEVICE_SECRET = "change-this-device-secret";
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 30000;

    private KismartApi() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static Policy fetchPolicy(Context context) throws Exception {
        SharedPreferences prefs = prefs(context);
        String baseUrl = cleanBaseUrl(prefs.getString(KEY_SERVER_URL, ""));
        String imei = prefs.getString(KEY_IMEI, "");
        if (baseUrl.isEmpty() || imei.isEmpty()) {
            throw new IllegalStateException("Enter backend URL and device IMEI first.");
        }
        JSONObject response = request(context, "GET", baseUrl + "/api/devices/" + encode(imei) + "/policy", null);
        persistPolicy(context, response);
        return Policy.fromJson(response);
    }

    static Policy sync(Context context) throws Exception {
        SharedPreferences prefs = prefs(context);
        String baseUrl = cleanBaseUrl(prefs.getString(KEY_SERVER_URL, ""));
        String imei = prefs.getString(KEY_IMEI, "");
        if (baseUrl.isEmpty() || imei.isEmpty()) {
            throw new IllegalStateException("Enter backend URL and device IMEI first.");
        }
        JSONObject body = new JSONObject();
        body.put("appVersion", APP_VERSION);
        body.put("network", "manual-sync");
        body.put("battery", 0);
        body.put("message", "Device agent manual sync");
        body.put("identity", deviceIdentity(context));
        body.put("appliedCommandIds", pendingAppliedCommandIds(context));
        JSONObject response = request(context, "POST", baseUrl + "/api/devices/" + encode(imei) + "/sync", body);
        JSONObject policy = response.optJSONObject("policy");
        if (policy == null) throw new IllegalStateException("Backend did not return a policy.");
        persistPolicy(context, policy);
        rememberCommandsToAcknowledge(context, response, policy);
        return Policy.fromJson(policy);
    }

    static JSONObject reportTamper(Context context, String reason) throws Exception {
        SharedPreferences prefs = prefs(context);
        String baseUrl = cleanBaseUrl(prefs.getString(KEY_SERVER_URL, ""));
        String imei = prefs.getString(KEY_IMEI, "");
        JSONObject body = new JSONObject();
        body.put("reason", reason);
        body.put("attemptedRemoval", true);
        body.put("appVersion", APP_VERSION);
        body.put("network", "manual-report");
        body.put("identity", deviceIdentity(context));
        return request(context, "POST", baseUrl + "/api/devices/" + encode(imei) + "/tamper", body);
    }

    static Policy simulateStkPayment(Context context, int amount) throws Exception {
        SharedPreferences prefs = prefs(context);
        String baseUrl = cleanBaseUrl(prefs.getString(KEY_SERVER_URL, ""));
        String imei = prefs.getString(KEY_IMEI, "");
        if (baseUrl.isEmpty() || imei.isEmpty()) {
            throw new IllegalStateException("Enter backend URL and device IMEI first.");
        }
        JSONObject body = new JSONObject();
        body.put("amount", amount);
        body.put("reference", "STK-" + System.currentTimeMillis());
        body.put("appVersion", APP_VERSION);
        body.put("network", "stk-prompt");
        body.put("identity", deviceIdentity(context));
        JSONObject response = request(context, "POST", baseUrl + "/api/devices/" + encode(imei) + "/stk-test", body);
        JSONObject policy = response.optJSONObject("policy");
        if (policy == null) throw new IllegalStateException("Backend did not return a policy.");
        persistPolicy(context, policy);
        return Policy.fromJson(policy);
    }

    static JSONObject submitPaybillStk(Context context, int amount, String phoneNumber) throws Exception {
        SharedPreferences prefs = prefs(context);
        String baseUrl = cleanBaseUrl(prefs.getString(KEY_SERVER_URL, ""));
        String imei = prefs.getString(KEY_IMEI, "");
        if (baseUrl.isEmpty() || imei.isEmpty()) {
            throw new IllegalStateException("Enter backend URL and device IMEI first.");
        }
        JSONObject body = new JSONObject();
        body.put("amount", amount);
        body.put("phoneNumber", phoneNumber);
        body.put("reference", "PAYBILL-" + System.currentTimeMillis());
        body.put("appVersion", APP_VERSION);
        body.put("network", "paybill-stk");
        body.put("identity", deviceIdentity(context));
        return request(context, "POST", baseUrl + "/api/devices/" + encode(imei) + "/paybill-stk", body);
    }

    static Policy lastPolicy(Context context) {
        try {
            String raw = prefs(context).getString(KEY_LAST_POLICY, "");
            if (raw == null || raw.isEmpty()) return null;
            return Policy.fromJson(new JSONObject(raw));
        } catch (Exception ignored) {
            return null;
        }
    }

    static boolean isLastPolicyFresh(Context context) {
        long syncedAt = prefs(context).getLong(KEY_LAST_POLICY_AT, 0L);
        return syncedAt > 0L && System.currentTimeMillis() - syncedAt < 30000L;
    }

    private static JSONObject request(Context context, String method, String urlValue, JSONObject body) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(urlValue).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Content-Type", "application/json");
            String secret = prefs(context).getString(KEY_SECRET, "");
            if (secret != null && !secret.trim().isEmpty()) {
                connection.setRequestProperty("X-KISMART-Device-Secret", secret.trim());
            }
            addDeviceIdentityHeaders(context, connection);
            if (body != null) {
                connection.setDoOutput(true);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = readAll(stream);
            if (status >= 400) {
                if (response.contains("Invalid device sync signature")) {
                    throw new IllegalStateException("Device sync secret is wrong. Enter: " + DEFAULT_DEVICE_SECRET);
                }
                if (response.contains("Device identity mismatch")) {
                    throw new IllegalStateException("This IMEI is already bound to another phone identity. Ask the admin to verify this handset and reset Device ID.");
                }
                if (response.contains("Device identity is missing")) {
                    throw new IllegalStateException("Device identity is missing. Reinstall the latest KISMART agent and sync again.");
                }
                throw new IllegalStateException(response.isEmpty() ? "Backend request failed: " + status : response);
            }
            return new JSONObject(response);
        } catch (SocketTimeoutException error) {
            throw new IllegalStateException("Sync timed out. Confirm the backend is running at " + DEFAULT_SERVER_URL + " and the phone is on the same Wi-Fi.", error);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void addDeviceIdentityHeaders(Context context, HttpURLConnection connection) throws Exception {
        JSONObject identity = deviceIdentity(context);
        connection.setRequestProperty("X-KISMART-Install-Id", identity.optString("installId"));
        connection.setRequestProperty("X-KISMART-Android-Id", identity.optString("androidId"));
        connection.setRequestProperty("X-KISMART-Device-Fingerprint", identity.optString("fingerprint"));
        String token = identity.optString("bindingToken");
        if (token != null && !token.trim().isEmpty()) {
            connection.setRequestProperty("X-KISMART-Binding-Token", token.trim());
        }
        connection.setRequestProperty("X-KISMART-Device-Manufacturer", identity.optString("manufacturer"));
        connection.setRequestProperty("X-KISMART-Device-Brand", identity.optString("brand"));
        connection.setRequestProperty("X-KISMART-Device-Model", identity.optString("model"));
        connection.setRequestProperty("X-KISMART-Android-SDK", identity.optString("sdk"));
    }

    private static JSONObject deviceIdentity(Context context) throws Exception {
        JSONObject identity = new JSONObject();
        identity.put("installId", installId(context));
        identity.put("androidId", valueOrFallback(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID), "android-" + installId(context)));
        identity.put("fingerprint", valueOrFallback(Build.FINGERPRINT, "fingerprint-" + Build.MODEL));
        identity.put("bindingToken", bindingToken(context));
        identity.put("manufacturer", valueOrFallback(Build.MANUFACTURER, "unknown"));
        identity.put("brand", valueOrFallback(Build.BRAND, "unknown"));
        identity.put("model", valueOrFallback(Build.MODEL, "unknown"));
        identity.put("sdk", String.valueOf(Build.VERSION.SDK_INT));
        return identity;
    }

    private static String installId(Context context) {
        SharedPreferences prefs = prefs(context);
        String existing = prefs.getString(KEY_INSTALL_ID, "");
        if (existing != null && !existing.trim().isEmpty()) return existing.trim();
        String generated = UUID.randomUUID().toString();
        prefs.edit().putString(KEY_INSTALL_ID, generated).apply();
        return generated;
    }

    private static String bindingToken(Context context) {
        String value = prefs(context).getString(KEY_BINDING_TOKEN, "");
        return value == null ? "" : value.trim();
    }

    private static void persistPolicy(Context context, JSONObject policy) {
        SharedPreferences.Editor editor = prefs(context).edit();
        JSONObject identity = policy.optJSONObject("identity");
        if (identity != null) {
            String token = identity.optString("bindingToken", "");
            if (token != null && !token.trim().isEmpty()) {
                editor.putString(KEY_BINDING_TOKEN, token.trim());
                identity.remove("bindingToken");
            }
        }
        editor.putString(KEY_LAST_POLICY, policy.toString());
        editor.putLong(KEY_LAST_POLICY_AT, System.currentTimeMillis());
        editor.apply();
    }

    private static JSONArray pendingAppliedCommandIds(Context context) {
        String raw = prefs(context).getString(KEY_APPLIED_COMMAND_IDS, "");
        if (raw == null || raw.trim().isEmpty()) return new JSONArray();
        try {
            return new JSONArray(raw);
        } catch (Exception ignored) {
            prefs(context).edit().remove(KEY_APPLIED_COMMAND_IDS).apply();
            return new JSONArray();
        }
    }

    private static void rememberCommandsToAcknowledge(Context context, JSONObject response, JSONObject policy) {
        JSONArray commands = response.optJSONArray("commands");
        if (commands == null || commands.length() == 0) {
            commands = policy.optJSONArray("pendingCommands");
        }
        JSONArray ids = commandIds(commands);
        SharedPreferences.Editor editor = prefs(context).edit();
        if (ids.length() > 0) {
            editor.putString(KEY_APPLIED_COMMAND_IDS, ids.toString());
        } else {
            editor.remove(KEY_APPLIED_COMMAND_IDS);
        }
        editor.apply();
    }

    private static JSONArray commandIds(JSONArray commands) {
        JSONArray ids = new JSONArray();
        if (commands == null) return ids;
        for (int index = 0; index < commands.length(); index++) {
            JSONObject command = commands.optJSONObject(index);
            String id = command == null ? "" : command.optString("id", "").trim();
            if (!id.isEmpty()) ids.put(id);
        }
        return ids;
    }

    private static String valueOrFallback(String value, String fallback) {
        String cleaned = value == null ? "" : value.trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private static String cleanBaseUrl(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (!cleaned.isEmpty() && !cleaned.startsWith("http://") && !cleaned.startsWith("https://")) {
            cleaned = "http://" + cleaned;
        }
        while (cleaned.endsWith("/")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        return cleaned;
    }

    private static String encode(String value) {
        return value == null ? "" : value.trim().replace(" ", "%20");
    }
}
