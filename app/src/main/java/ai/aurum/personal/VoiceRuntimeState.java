package ai.aurum.personal;

public final class VoiceRuntimeState {
    private static volatile String speechState = "idle";
    private static volatile String ttsState = "initializing";
    private static volatile String detectedLanguage = "not reported";

    private VoiceRuntimeState() {}

    static void setSpeechState(String value) {
        speechState = safeState(value, "idle");
    }

    static void setTtsState(String value) {
        ttsState = safeState(value, "unavailable");
    }

    static void setDetectedLanguage(String value) {
        detectedLanguage = sanitizeLanguageTag(value);
    }

    public static String speechState() {
        return speechState;
    }

    public static String ttsState() {
        return ttsState;
    }

    public static String detectedLanguage() {
        return detectedLanguage;
    }

    static String sanitizeLanguageTag(String value) {
        if (value == null) return "not reported";
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return "not reported";
        if (!trimmed.matches("[A-Za-z0-9]{1,8}(-[A-Za-z0-9]{1,8}){0,4}")) {
            return "unrecognized tag";
        }
        return trimmed;
    }

    private static String safeState(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (trimmed.isEmpty()) return fallback;
        return trimmed.length() <= 96 ? trimmed : trimmed.substring(0, 96);
    }
}
