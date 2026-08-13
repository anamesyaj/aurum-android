package ai.aurum.personal;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DiagnosticsReportTest {
    @Test
    public void shortShaTruncatesLongCommit() {
        assertEquals("12345678", DiagnosticsReport.shortSha("1234567890abcdef"));
    }

    @Test
    public void shortShaKeepsShortValues() {
        assertEquals("local", DiagnosticsReport.shortSha("local"));
    }

    @Test
    public void shortShaHandlesMissingValue() {
        assertEquals("unknown", DiagnosticsReport.shortSha(""));
    }
}
