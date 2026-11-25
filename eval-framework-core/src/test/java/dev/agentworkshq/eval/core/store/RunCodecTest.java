package dev.agentworkshq.eval.core.store;

import static dev.agentworkshq.eval.core.store.StoreFixtures.golden;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.exception.BaseException;
import dev.agentworkshq.eval.core.exception.ErrorMessages;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RunCodecTest {

    private static final String FILE = "x.json";
    private static final String GOLDEN = StoreFixtures.resource("golden-run.json");

    private final RunCodec codec = new RunCodec(StoreLimits.defaults());

    private StoredRun read(String json) {
        return codec.read(json.getBytes(StandardCharsets.UTF_8), FILE);
    }

    private BaseException rejected(String json) {
        return rejected(codec, json.getBytes(StandardCharsets.UTF_8));
    }

    private static BaseException rejected(RunCodec codec, byte[] bytes) {
        try {
            codec.read(bytes, FILE);
        } catch (BaseException e) {
            assertThat(e.getErrorMessage()).isEqualTo(ErrorMessages.EVAL_E019);
            assertThat(e.getMessage())
                    .startsWith("Invalid run file: " + FILE)
                    .doesNotContain("Unexpected", "Duplicate", "Unrecognized", "Cannot", "token", "jackson", "Jackson");
            return e;
        }
        throw new AssertionError("expected the file to be rejected");
    }

    @Test
    void writesTheGoldenFileCharacterForCharacter() {
        String written = new String(codec.write(golden()), StandardCharsets.UTF_8);

        assertThat(written).isEqualTo(GOLDEN);
        assertThat(written).doesNotContain("\r").endsWith("}\n").doesNotEndWith("\n\n");
    }

    @Test
    void readsTheGoldenFileBack() {
        assertThat(read(GOLDEN)).isEqualTo(golden());
    }

    @Test
    void roundTripIsEqual() {
        StoredRun run = golden();

        assertThat(codec.read(codec.write(run), FILE)).isEqualTo(run);
    }

    @Test
    void emptyRunRoundTrips() {
        StoredRun run = new StoredRun("r", "ds", "fp", "t", StoreFixtures.T0, StoreFixtures.T0, null, null);

        String json = new String(codec.write(run), StandardCharsets.UTF_8);

        assertThat(json).contains("\"config\": {},").contains("\"cases\": []");
        assertThat(read(json)).isEqualTo(run);
    }

    @Test
    void stripsABom() {
        byte[] body = GOLDEN.getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        assertThat(codec.read(withBom, FILE)).isEqualTo(golden());
    }

    @Test
    void rejectsMalformedUtf8() {
        byte[] body = GOLDEN.getBytes(StandardCharsets.UTF_8);
        int at = GOLDEN.indexOf("target-a");
        byte[] bad = Arrays.copyOf(body, body.length + 2);
        System.arraycopy(body, at, bad, at + 2, body.length - at);
        bad[at] = (byte) 0xC3;
        bad[at + 1] = (byte) 0x28;

        assertThat(rejected(codec, bad).getMessage()).contains("invalid UTF-8");
    }

    @Test
    void rejectsUnknownFormatVersion() {
        rejected(GOLDEN.replace("\"formatVersion\": 1", "\"formatVersion\": 2"));
        rejected(GOLDEN.replace("\"formatVersion\": 1", "\"formatVersion\": \"1\""));
        rejected(GOLDEN.replace("\"formatVersion\": 1", "\"formatVersion\": 1.0"));
        rejected(GOLDEN.replace("  \"formatVersion\": 1,\n", ""));
    }

    @Test
    void rejectsUnknownFields() {
        rejected(GOLDEN.replace("\"runId\": \"run-1\",", "\"runId\": \"run-1\", \"extra\": 1,"));
        rejected(GOLDEN.replace("\"caseId\": \"a\",", "\"caseId\": \"a\", \"question\": \"q\","));
        rejected(GOLDEN.replace("\"metricName\": \"hit_at_3\",", "\"metricName\": \"hit_at_3\", \"other\": null,"));
    }

    @Test
    void rejectsMissingFields() {
        rejected(GOLDEN.replace("  \"runId\": \"run-1\",\n", ""));
        rejected(GOLDEN.replace("      \"answerChars\": 5,\n", ""));
        rejected(GOLDEN.replace("          \"score\": 1.0,\n", ""));
    }

    @Test
    void rejectsDuplicateKeys() {
        rejected(GOLDEN.replace("\"runId\": \"run-1\",", "\"runId\": \"run-1\", \"runId\": \"run-2\","));
        rejected(GOLDEN.replace("\"caseId\": \"a\",", "\"caseId\": \"a\", \"caseId\": \"z\","));
        rejected(GOLDEN.replace("\"runner.concurrency\": \"1\"", "\"runner.concurrency\": \"1\", \"runner.concurrency\": \"2\""));
    }

    @Test
    void rejectsTrailingContent() {
        rejected(GOLDEN + "{}");
        rejected(GOLDEN + "x");
    }

    @Test
    void parseErrorsHaveOnlyFileLineAndColumn() {
        assertThat(rejected("{\n  \"formatVersion\": 1,,\n}").getMessage())
                .matches("Invalid run file: x\\.json \\(malformed JSON, line \\d+, column \\d+\\)");
        assertThat(rejected(GOLDEN + "{}").getMessage())
                .matches("Invalid run file: x\\.json \\(malformed JSON, line \\d+, column \\d+\\)");
        assertThat(rejected("").getMessage()).startsWith("Invalid run file: x.json");
        assertThat(rejected("[]").getMessage()).startsWith("Invalid run file: x.json");
    }

    @Test
    void rejectsTooDeepDocuments() {
        RunCodec shallow = new RunCodec(new StoreLimits(1024 * 1024, 10, 3));
        rejected(shallow, GOLDEN.getBytes(StandardCharsets.UTF_8));

        rejected("[".repeat(100) + "]".repeat(100));
    }

    @Test
    void rejectsFilesOverTheByteLimit() {
        RunCodec small = new RunCodec(new StoreLimits(100, 10, 20));

        rejected(small, GOLDEN.getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> small.write(golden())).isInstanceOf(BaseException.class).hasMessageContaining("larger than 100");
    }

    static Stream<Arguments> wrongTypes() {
        return Stream.of(
                Arguments.of("\"runId\": \"run-1\"", "\"runId\": 5"),
                Arguments.of("\"datasetName\": \"ds\"", "\"datasetName\": true"),
                Arguments.of("\"datasetFingerprint\": \"fp-abc\"", "\"datasetFingerprint\": 1"),
                Arguments.of("\"targetName\": \"target-a\"", "\"targetName\": null"),
                Arguments.of("\"startedAt\": \"2026-01-02T03:04:05.678Z\"", "\"startedAt\": 1767323045678"),
                Arguments.of("\"startedAt\": \"2026-01-02T03:04:05.678Z\"", "\"startedAt\": \"yesterday\""),
                Arguments.of("\"finishedAt\": \"2026-01-02T03:04:06Z\"", "\"finishedAt\": []"),
                Arguments.of("\"runner.concurrency\": \"1\"", "\"runner.concurrency\": 1"),
                Arguments.of("\"caseId\": \"a\"", "\"caseId\": 7"),
                Arguments.of("\"status\": \"COMPLETED\",\n      \"errorMessage\"", "\"status\": 3,\n      \"errorMessage\""),
                Arguments.of("\"status\": \"COMPLETED\",\n      \"errorMessage\"", "\"status\": \"DONE\",\n      \"errorMessage\""),
                Arguments.of("\"errorMessage\": null,\n      \"durationMs\": 12", "\"errorMessage\": 5,\n      \"durationMs\": 12"),
                Arguments.of("\"durationMs\": 12", "\"durationMs\": \"12\""),
                Arguments.of("\"durationMs\": 12", "\"durationMs\": 12.5"),
                Arguments.of("\"durationMs\": 12", "\"durationMs\": 12.0"),
                Arguments.of("\"durationMs\": 12", "\"durationMs\": null"),
                Arguments.of("\"answered\": true", "\"answered\": \"true\""),
                Arguments.of("\"answered\": true", "\"answered\": 1"),
                Arguments.of("\"answerChars\": 5", "\"answerChars\": \"5\""),
                Arguments.of("\"answerChars\": 5", "\"answerChars\": 5000000000"),
                Arguments.of("\"latencyMs\": 3", "\"latencyMs\": \"3\""),
                Arguments.of("\"retrievedCount\": 2", "\"retrievedCount\": 2.5"),
                Arguments.of("\"metricResults\": []", "\"metricResults\": {}"),
                Arguments.of("\"metricName\": \"hit_at_3\"", "\"metricName\": 3"),
                Arguments.of("\"status\": \"PASSED\",\n          \"score\": 1.0", "\"status\": false,\n          \"score\": 1.0"),
                Arguments.of("\"score\": 1.0", "\"score\": \"1.0\""),
                Arguments.of("\"score\": 1.0", "\"score\": true"),
                Arguments.of("\"reason\": \"all phrases present\"", "\"reason\": 5"));
    }

    @ParameterizedTest
    @MethodSource("wrongTypes")
    void rejectsWrongTypes(String find, String replacement) {
        assertThat(GOLDEN).contains(find);

        rejected(GOLDEN.replace(find, replacement));
    }

    @Test
    void rejectsWrongContainerTypes() {
        StoredRun empty = new StoredRun("r", "ds", "fp", "t", StoreFixtures.T0, StoreFixtures.T0, null, null);
        String json = new String(codec.write(empty), StandardCharsets.UTF_8);

        rejected(json.replace("\"config\": {}", "\"config\": []"));
        rejected(json.replace("\"config\": {}", "\"config\": null"));
        rejected(json.replace("\"cases\": []", "\"cases\": {}"));
        rejected(json.replace("\"cases\": []", "\"cases\": [1]"));
        rejected(json.replace("\"cases\": []", "\"cases\": [[]]"));
    }

    static Stream<Arguments> brokenRecordRules() {
        return Stream.of(
                Arguments.of("an error result with a score", "\"status\": \"ERROR\",\n          \"score\": null",
                        "\"status\": \"ERROR\",\n          \"score\": 0.5"),
                Arguments.of("a skipped result with a score", "\"status\": \"SKIPPED\",\n          \"score\": null",
                        "\"status\": \"SKIPPED\",\n          \"score\": 0.5"),
                Arguments.of("a passed result without a score", "\"status\": \"PASSED\",\n          \"score\": 1.0",
                        "\"status\": \"PASSED\",\n          \"score\": null"),
                Arguments.of("a score above 1", "\"score\": 1.0", "\"score\": 1.5"),
                Arguments.of("a duplicate metric name", "\"metricName\": \"hit_at_3\"", "\"metricName\": \"answer_contains\""),
                Arguments.of("a secret looking config key", "\"runner.concurrency\"", "\"api-key\""),
                Arguments.of("a negative duration", "\"durationMs\": 12", "\"durationMs\": -1"),
                Arguments.of("a negative answer length", "\"answerChars\": 5", "\"answerChars\": -5"),
                Arguments.of("an invalid run id", "\"runId\": \"run-1\"", "\"runId\": \"../x\""),
                Arguments.of("an invalid case id", "\"caseId\": \"a\"", "\"caseId\": \"a b\""),
                Arguments.of("a duplicate case id", "\"caseId\": \"b\"", "\"caseId\": \"a\""),
                Arguments.of("a finish time before the start", "\"finishedAt\": \"2026-01-02T03:04:06Z\"",
                        "\"finishedAt\": \"2026-01-01T03:04:06Z\""),
                Arguments.of("a failed case without a message",
                        "\"errorMessage\": \"IllegalStateException after 1 attempts\"", "\"errorMessage\": null"),
                Arguments.of("a too long config value", "\"judge-1\"", "\"" + "x".repeat(201) + "\""));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("brokenRecordRules")
    void revalidatesThroughTheRecords(String name, String find, String replacement) {
        assertThat(GOLDEN).contains(find);

        rejected(GOLDEN.replace(find, replacement));
    }

    @Test
    void anUnsortedFileReadsAsTheSortedRun() {
        String swapped = GOLDEN.replace("\"caseId\": \"a\"", "\"caseId\": \"zz\"");

        assertThat(read(swapped).cases()).extracting(StoredCase::caseId).containsExactly("b", "c", "zz");
    }
}
