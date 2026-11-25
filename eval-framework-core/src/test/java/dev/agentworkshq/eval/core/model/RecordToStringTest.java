package dev.agentworkshq.eval.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordToStringTest {

    private static final String MARKER = "SECRET-MARKER-TEXT";

    private static EvalCase evalCase() {
        return new EvalCase("case-1", MARKER + " question", MARKER + " expected", List.of(MARKER + ".md"), false,
                List.of(MARKER + "-tag"), Map.of(MARKER + "-key", MARKER + "-value"), List.of(MARKER + " must"),
                List.of(MARKER + " mustNot"));
    }

    private static RetrievedChunk chunk() {
        return new RetrievedChunk(MARKER + "-id", MARKER + "-doc", MARKER + ".md", 1, MARKER + " chunk", 0.5);
    }

    private static TargetOutput output() {
        return new TargetOutput(MARKER + " answer", true, List.of(chunk()), List.of(MARKER + ".md"),
                Duration.ofMillis(5), Map.of(MARKER + "-key", MARKER + "-value"));
    }

    private static CaseResult caseResult() {
        return new CaseResult("case-1", CaseStatus.TARGET_FAILED, output(), MARKER + " error", List.of(), Duration.ZERO);
    }

    @Test
    void evalCaseHidesText() {
        assertThat(evalCase().toString()).doesNotContain(MARKER).contains("case-1");
    }

    @Test
    void evalDatasetHidesText() {
        EvalDataset dataset = new EvalDataset("sample", List.of(evalCase()));

        assertThat(dataset.toString()).doesNotContain(MARKER).isEqualTo("EvalDataset[name=sample, cases=1]");
    }

    @Test
    void retrievedChunkHidesText() {
        assertThat(chunk().toString()).doesNotContain(MARKER).contains("textLength=" + (MARKER + " chunk").length());
    }

    @Test
    void targetOutputHidesTextAndShowsLengths() {
        assertThat(output().toString()).doesNotContain(MARKER).contains("answerLength=" + (MARKER + " answer").length())
                .contains("retrieved=1");
    }

    @Test
    void caseResultHidesText() {
        assertThat(caseResult().toString()).doesNotContain(MARKER).contains("case-1", "TARGET_FAILED");
    }

    @Test
    void evalRunHidesText() {
        Instant now = Instant.parse("2026-01-02T03:04:05Z");
        EvalRun run = new EvalRun("run-1", "sample", MARKER + "-fingerprint", MARKER + "-target", now, now,
                Map.of("label.note", MARKER), List.of(caseResult()));

        assertThat(run.toString()).doesNotContain(MARKER).contains("run-1", "sample", "cases=1");
    }
}
