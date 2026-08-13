package ai.aurum.personal;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public final class BackendConfig {
    private static final String PREFS = "aurum_backend";
    private static final String KEY_URL = "base_url";
    private static final String KEY_CONNECTION = "connection_state";

    private BackendConfig() {}

    public static String loadBaseUrl(Context context) {
        return preferences(context).getString(KEY_URL, "");
    }

    public static void saveBaseUrl(Context context, String baseUrl) {
        Validation validation = validate(baseUrl);
        if (!validation.valid) {
            throw new IllegalArgumentException(validation.error);
        }
        preferences(context).edit().putString(KEY_URL, validation.normalized).apply();
    }

    public static void recordConnectionState(Context context, String state) {
        preferences(context).edit().putString(KEY_CONNECTION, sanitizeState(state)).apply();
    }

    public static String loadConnectionState(Context context) {
        return preferences(context).getString(KEY_CONNECTION, "not tested");
    }

    public static String diagnosticBackend(Context context) {
        String configured = loadBaseUrl(context);
        if (configured == null || configured.trim().isEmpty()) {
            return "not configured";
        }
        try {
            URI uri = new URI(configured);
            String host = uri.getHost() == null ? "unknown" : uri.getHost();
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            String path = uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath())
                    ? ""
                    : uri.getPath();
            return "configured " + uri.getScheme() + "://" + host + port + path;
        } catch (URISyntaxException exception) {
            return "configured (invalid URL)";
        }
    }

    public static Validation validate(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return Validation.invalid("Aurum Core URL is required");
        }

        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException exception) {
            return Validation.invalid("Aurum Core URL is not valid");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (!("https".equals(scheme) || "http".equals(scheme))) {
            return Validation.invalid("Use an https:// Aurum Core URL, or private-LAN http:// for development");
        }
        if (host == null || host.trim().isEmpty()) {
            return Validation.invalid("Aurum Core URL must include a host");
        }
        if (uri.getUserInfo() != null) {
            return Validation.invalid("Do not put usernames, passwords, or tokens in the Core URL");
        }
        if (uri.getQuery() != null || uri.getFragment() != null) {
            return Validation.invalid("Aurum Core URL must not include a query string or fragment");
        }
        if ("http".equals(scheme) && !isPrivateDevelopmentHost(host)) {
            return Validation.invalid("Plain HTTP is allowed only for localhost/private-LAN development addresses");
        }

        String normalized = value;
        while (normalized.endsWith("/") && normalized.length() > (scheme.length() + 3)) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return Validation.valid(normalized, "http".equals(scheme));
    }

    static boolean isPrivateDevelopmentHost(String host) {
        if (host == null) return false;
        String value = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(value) || "::1".equals(value) || value.endsWith(".local")) {
            return true;
        }
        if (value.startsWith("127.")) return true;
        if (value.startsWith("10.")) return true;
        if (value.startsWith("192.168.")) return true;
        if (value.startsWith("169.254.")) return true;

        String[] parts = value.split("\\.");
        if (parts.length == 4) {
            try {
                int first = Integer.parseInt(parts[0]);
                int second = Integer.parseInt(parts[1]);
                if (first == 172 && second >= 16 && second <= 31) return true;
                // 100.64.0.0/10 includes common private-overlay addresses such as Tailscale.
                if (first == 100 && second >= 64 && second <= 127) return true;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String sanitizeState(String state) {
        String value = state == null ? "unknown" : state.trim();
        if (value.length() > 160) value = value.substring(0, 160);
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    public static final class Validation {
        public final boolean valid;
        public final String normalized;
        public final String error;
        public final boolean privateHttp;

        private Validation(boolean valid, String normalized, String error, boolean privateHttp) {
            this.valid = valid;
            this.normalized = normalized;
            this.error = error;
            this.privateHttp = privateHttp;
        }

        static Validation valid(String normalized, boolean privateHttp) {
            return new Validation(true, normalized, "", privateHttp);
        }

        static Validation invalid(String error) {
            return new Validation(false, "", error, false);
        }
    }
}
