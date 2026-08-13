package ai.aurum.personal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class VoiceTextPolicyTest {
    @Test
    public void firstNonBlankUsesMostLikelyUsableRecognitionCandidate() {
        assertEquals(
                "kumusta Aurum",
                VoiceTextPolicy.firstNonBlank(Arrays.asList("", "  kumusta Aurum  ", "hello"))
        );
    }

    @Test
    public void firstNonBlankHandlesMissingRecognitionResults() {
        assertEquals("", VoiceTextPolicy.firstNonBlank(null));
        assertEquals("", VoiceTextPolicy.firstNonBlank(Collections.emptyList()));
    }

    @Test
    public void boundedSpeechTextKeepsShortRepliesAndTruncatesLongOnes() {
        assertEquals("Salamat", VoiceTextPolicy.boundedSpeechText("  Salamat  ", 100));
        assertEquals("abcdef", VoiceTextPolicy.boundedSpeechText("abcdefghij", 6));
        assertEquals("", VoiceTextPolicy.boundedSpeechText("hello", 0));
    }

    @Test
    public void languageTagDiagnosticsRejectArbitraryText() {
        assertEquals("fil-PH", VoiceRuntimeState.sanitizeLanguageTag("fil-PH"));
        assertEquals("unrecognized tag", VoiceRuntimeState.sanitizeLanguageTag("raw transcript here"));
        assertEquals("not reported", VoiceRuntimeState.sanitizeLanguageTag(""));
    }
}
