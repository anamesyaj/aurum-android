package ai.aurum.personal;

import java.util.List;

public final class VoiceTextPolicy {
    private VoiceTextPolicy() {}

    static String firstNonBlank(List<String> candidates) {
        if (candidates == null) return "";
        for (String candidate : candidates) {
            if (candidate == null) continue;
            String trimmed = candidate.trim();
            if (!trimmed.isEmpty()) return trimmed;
        }
        return "";
    }

    static String boundedSpeechText(String text, int maxChars) {
        if (text == null || maxChars <= 0) return "";
        String trimmed = text.trim();
        if (trimmed.length() <= maxChars) return trimmed;
        return trimmed.substring(0, maxChars).trim();
    }
}
