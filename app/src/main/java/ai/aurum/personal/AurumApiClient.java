package ai.aurum.personal;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AurumApiClient implements AutoCloseable {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    private static final int POLL_INTERVAL_MS = 1_000;
    private static final int REPLY_TIMEOUT_MS = 120_000;
    private static final int MAX_SPEECH_AUDIO_BYTES = 12 * 1024 * 1024;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onSuccess(String value);
        void onError(String message);
    }

    public interface AudioCallback {
        void onSuccess(byte[] audio, String contentType);
        void onError(String message);
    }

    public void testConnection(String baseUrl, String accessToken, Callback callback) {
        executor.execute(() -> {
            try {
                request("GET", endpoint(baseUrl, "/api/remote/status"), null, accessToken);
                callback.onSuccess("connected");
            } catch (Exception exception) {
                callback.onError(safeError(exception));
            }
        });
    }

    public void sendMessage(String baseUrl, String accessToken, String text, Callback callback) {
        executor.execute(() -> {
            try {
                Set<String> existingBotIds = fetchRecentBotIds(baseUrl, accessToken);

                JSONObject body = new JSONObject();
                body.put("text", text);
                request("POST", endpoint(baseUrl, "/api/remote/messages"), body.toString(), accessToken);

                long deadline = System.currentTimeMillis() + REPLY_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    String reply = fetchNewAurumReply(baseUrl, accessToken, existingBotIds);
                    if (reply != null && !reply.trim().isEmpty()) {
                        callback.onSuccess(reply.trim());
                        return;
                    }
                    Thread.sleep(POLL_INTERVAL_MS);
                }
                callback.onError("Aurum Core accepted the message but no reply arrived within 120 seconds");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                callback.onError("Aurum request was interrupted");
            } catch (Exception exception) {
                callback.onError(safeError(exception));
            }
        });
    }

    public void synthesizeSpeech(String baseUrl, String accessToken, String text, AudioCallback callback) {
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("text", text == null ? "" : text.trim());
                BinaryResponse response = requestBytes(
                        "POST",
                        endpoint(baseUrl, "/api/remote/speech"),
                        body.toString(),
                        accessToken
                );
                callback.onSuccess(response.body, response.contentType);
            } catch (Exception exception) {
                callback.onError(safeError(exception));
            }
        });
    }

    private Set<String> fetchRecentBotIds(String baseUrl, String accessToken) throws IOException, JSONException {
        String response = request("GET", endpoint(baseUrl, "/api/remote/messages?limit=50"), null, accessToken);
        JSONObject root = new JSONObject(response);
        JSONArray messages = root.optJSONArray("messages");
        Set<String> ids = new HashSet<>();
        if (messages == null) return ids;
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.optJSONObject(index);
            if (message != null && isBotMessage(message)) {
                String id = message.optString("id", "");
                if (!id.isEmpty()) ids.add(id);
            }
        }
        return ids;
    }

    private String fetchNewAurumReply(String baseUrl, String accessToken, Set<String> existingBotIds)
            throws IOException, JSONException {
        String response = request("GET", endpoint(baseUrl, "/api/remote/messages?limit=50"), null, accessToken);
        JSONObject root = new JSONObject(response);
        JSONArray messages = root.optJSONArray("messages");
        if (messages == null) return null;

        String newest = null;
        for (int index = 0; index < messages.length(); index++) {
            JSONObject message = messages.optJSONObject(index);
            if (message == null || !isBotMessage(message)) continue;
            String id = message.optString("id", "");
            if (id.isEmpty() || existingBotIds.contains(id)) continue;
            String sender = message.optString("sender_name", "");
            if ("Sentry".equalsIgnoreCase(sender)) continue;
            String content = message.optString("content", "");
            if (!content.trim().isEmpty()) newest = content;
        }
        return newest;
    }

    static boolean isBotMessage(JSONObject message) {
        Object raw = message.opt("is_bot_message");
        if (raw instanceof Boolean) return (Boolean) raw;
        if (raw instanceof Number) return ((Number) raw).intValue() != 0;
        return "true".equalsIgnoreCase(String.valueOf(raw)) || "1".equals(String.valueOf(raw));
    }

    private static final class BinaryResponse {
        final byte[] body;
        final String contentType;

        BinaryResponse(byte[] body, String contentType) {
            this.body = body;
            this.contentType = contentType;
        }
    }

    private BinaryResponse requestBytes(String method, URL url, String body, String accessToken)
            throws IOException {
        if (accessToken == null || accessToken.trim().length() < 32) {
            throw new IOException("Aurum access key is not configured");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(45_000);
        connection.setRequestProperty("Accept", "audio/mpeg");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken.trim());
        connection.setRequestProperty("User-Agent", "Aurum-Android-A3");
        connection.setUseCaches(false);

        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }

        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            String response = readAll(connection.getErrorStream());
            connection.disconnect();
            throw new IOException(
                    response == null || response.trim().isEmpty()
                            ? "HTTP " + status
                            : compactServerError(response, status)
            );
        }

        String contentType = connection.getContentType();
        byte[] audio = readBytes(connection.getInputStream(), MAX_SPEECH_AUDIO_BYTES);
        connection.disconnect();
        if (audio.length == 0) throw new IOException("Aurum Core returned empty speech audio");
        return new BinaryResponse(audio, contentType == null ? "audio/mpeg" : contentType);
    }

    private String request(String method, URL url, String body, String accessToken) throws IOException {
        if (accessToken == null || accessToken.trim().length() < 32) {
            throw new IOException("Aurum access key is not configured");
        }
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken.trim());
        connection.setRequestProperty("User-Agent", "Aurum-Android-A2");
        connection.setUseCaches(false);

        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }

        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        String response = readAll(stream);
        connection.disconnect();

        if (status < 200 || status >= 300) {
            String detail = response == null || response.trim().isEmpty()
                    ? "HTTP " + status
                    : compactServerError(response, status);
            throw new IOException(detail);
        }
        return response == null ? "" : response;
    }

    private static String compactServerError(String response, int status) {
        try {
            JSONObject root = new JSONObject(response);
            String error = root.optString("error", "").trim();
            if (!error.isEmpty()) return "HTTP " + status + ": " + error;
        } catch (JSONException ignored) {
            // Fall through to a generic error; never surface arbitrary server HTML/log output.
        }
        return "HTTP " + status + " from Aurum Core";
    }

    private static byte[] readBytes(InputStream stream, int maxBytes) throws IOException {
        if (stream == null) return new byte[0];
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) throw new IOException("Aurum speech audio is too large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line);
        }
        return builder.toString();
    }

    private static URL endpoint(String baseUrl, String path) throws IOException {
        BackendConfig.Validation validation = BackendConfig.validate(baseUrl);
        if (!validation.valid) throw new IOException(validation.error);
        try {
            URI base = new URI(validation.normalized + "/");
            String relative = path.startsWith("/") ? path.substring(1) : path;
            return base.resolve(relative).toURL();
        } catch (Exception exception) {
            throw new IOException("Aurum Core URL could not be resolved", exception);
        }
    }

    private static String safeError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) return "Aurum Core connection failed";
        message = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (message.length() > 200) message = message.substring(0, 200);
        return message;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
