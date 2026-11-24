package dev.agentworkshq.eval.core.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.agentworkshq.eval.core.exception.BaseException;
import dev.agentworkshq.eval.core.exception.ResourceNotFoundException;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.EvalDataset;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DatasetLoaderTest {

    private static final String MARKER = "SECRET-QUESTION-TEXT";

    private static final String YAML = """
            name: sample
            cases:
              - id: b
                question: Question b
                expectedAnswer: Answer b
                expectedSources: [s1, s2]
                expectNoAnswer: false
                tags: [t1, t2]
                attributes: {k2: v2, k1: v1}
              - id: a
                question: Question a
                expectNoAnswer: true
            """;

    private static final String JSON = """
            {"name": "sample", "cases": [
              {"id": "b", "question": "Question b", "expectedAnswer": "Answer b",
               "expectedSources": ["s1", "s2"], "expectNoAnswer": false,
               "tags": ["t1", "t2"], "attributes": {"k2": "v2", "k1": "v1"}},
              {"id": "a", "question": "Question a", "expectNoAnswer": true}
            ]}
            """;

    private static final String JSONL = """
            {"id": "b", "question": "Question b", "expectedAnswer": "Answer b", "expectedSources": ["s1", "s2"], "expectNoAnswer": false, "tags": ["t1", "t2"], "attributes": {"k2": "v2", "k1": "v1"}}

            {"id": "a", "question": "Question a", "expectNoAnswer": true}
            """;

    @TempDir
    Path dir;

    private DatasetLoader loader() {
        return new DatasetLoader(dir, DatasetLimits.defaults());
    }

    private DatasetLoader loader(DatasetLimits limits) {
        return new DatasetLoader(dir, limits);
    }

    private Path write(String name, String content) throws IOException {
        Path file = dir.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static BaseException thrown(Runnable action) {
        try {
            action.run();
        } catch (BaseException e) {
            return e;
        }
        throw new AssertionError("expected an exception");
    }

    private static void assertNoMarker(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            assertThat(String.valueOf(t.getMessage())).doesNotContain(MARKER);
        }
    }

    @Test
    void loadsAllThreeFormatsWithSameFingerprint() throws IOException {
        write("sample.yaml", YAML);
        write("sample.json", JSON);
        write("sample.jsonl", JSONL);

        EvalDataset yaml = loader().load("sample.yaml");
        EvalDataset json = loader().load("sample.json");
        EvalDataset jsonl = loader().load("sample.jsonl");

        assertThat(yaml.cases()).hasSize(2);
        assertThat(yaml).isEqualTo(json).isEqualTo(jsonl);
        assertThat(yaml.fingerprint()).isEqualTo(json.fingerprint()).isEqualTo(jsonl.fingerprint());
    }

    @Test
    void ymlExtensionIsAccepted() throws IOException {
        write("sample.yml", YAML);
        assertThat(loader().load("sample.yml").name()).isEqualTo("sample");
    }

    @Test
    void readsEveryCaseFieldAndSortsById() throws IOException {
        write("sample.yaml", YAML);

        EvalDataset dataset = loader().load("sample.yaml");

        assertThat(dataset.name()).isEqualTo("sample");
        assertThat(dataset.cases()).extracting(EvalCase::id).containsExactly("a", "b");
        EvalCase b = dataset.cases().get(1);
        assertThat(b.question()).isEqualTo("Question b");
        assertThat(b.expectedAnswer()).isEqualTo("Answer b");
        assertThat(b.expectedSources()).containsExactly("s1", "s2");
        assertThat(b.expectNoAnswer()).isFalse();
        assertThat(b.tags()).containsExactly("t1", "t2");
        assertThat(b.attributes()).isEqualTo(Map.of("k1", "v1", "k2", "v2"));
        EvalCase a = dataset.cases().get(0);
        assertThat(a.expectedAnswer()).isNull();
        assertThat(a.expectedSources()).isEmpty();
        assertThat(a.expectNoAnswer()).isTrue();
        assertThat(a.tags()).isEmpty();
        assertThat(a.attributes()).isEmpty();
    }

    @Test
    void rejectsUnknownTopLevelField() throws IOException {
        write("d.yaml", "name: d\nextra: 1\ncases:\n  - id: a\n    question: q\n");

        BaseException e = thrown(() -> loader().load("d.yaml"));

        assertThat(e).isInstanceOf(ValidationException.class);
        assertThat(e.getErrorCode()).isEqualTo("EVAL_E008");
        assertThat(e.getMessage()).contains("unknown field 'extra'");
    }

    @Test
    void rejectsUnknownCaseField() throws IOException {
        write("d.json", "{\"name\":\"d\",\"cases\":[{\"id\":\"a\",\"question\":\"q\",\"bogus\":\"x\"}]}");

        BaseException e = thrown(() -> loader().load("d.json"));

        assertThat(e.getErrorCode()).isEqualTo("EVAL_E008");
        assertThat(e.getMessage()).contains("unknown field 'bogus'").contains("case 1");
    }

    @Test
    void rejectsDuplicateKeysInJsonAndYaml() throws IOException {
        write("d.json", "{\"name\":\"d\",\"name\":\"e\",\"cases\":[{\"id\":\"a\",\"question\":\"q\"}]}");
        write("d.yaml", "name: d\nname: e\ncases:\n  - id: a\n    question: q\n");
        write("e.yaml", "name: d\ncases:\n  - id: a\n    question: q\n    attributes: {k: 1x, k: 2x}\n");

        assertThat(thrown(() -> loader().load("d.json")).getErrorCode()).isEqualTo("EVAL_E007");
        assertThat(thrown(() -> loader().load("d.yaml")).getErrorCode()).isEqualTo("EVAL_E007");
        assertThat(thrown(() -> loader().load("e.yaml")).getErrorCode()).isEqualTo("EVAL_E007");
    }

    @Test
    void numericIdTellsUserToQuote() throws IOException {
        write("d.yaml", "name: d\ncases:\n  - id: 7\n    question: q\n");

        BaseException e = thrown(() -> loader().load("d.yaml"));

        assertThat(e.getErrorCode()).isEqualTo("EVAL_E008");
        assertThat(e.getMessage()).contains("field 'id'").contains("quote");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "\"id\": true, \"question\": \"q\"",
        "\"id\": \"a\", \"question\": 5",
        "\"id\": \"a\", \"question\": \"q\", \"expectedAnswer\": 5",
        "\"id\": \"a\", \"question\": \"q\", \"expectNoAnswer\": \"yes\"",
        "\"id\": \"a\", \"question\": \"q\", \"expectedSources\": \"s\"",
        "\"id\": \"a\", \"question\": \"q\", \"expectedSources\": [1]",
        "\"id\": \"a\", \"question\": \"q\", \"tags\": \"t\"",
        "\"id\": \"a\", \"question\": \"q\", \"tags\": [false]",
        "\"id\": \"a\", \"question\": \"q\", \"attributes\": [\"x\"]",
        "\"id\": \"a\", \"question\": \"q\", \"attributes\": {\"k\": 1}"
    })
    void rejectsWrongFieldTypes(String caseBody) throws IOException {
        write("d.json", "{\"name\":\"d\",\"cases\":[{" + caseBody + "}]}");

        BaseException e = thrown(() -> loader().load("d.json"));

        assertThat(e).isInstanceOf(ValidationException.class);
        assertThat(e.getErrorCode()).isEqualTo("EVAL_E008");
    }

    @Test
    void rejectsEmptyCasesMissingQuestionAndDuplicateIds() throws IOException {
        write("empty.yaml", "name: d\ncases: []\n");
        write("noq.yaml", "name: d\ncases:\n  - id: a\n");
        write("dup.yaml", "name: d\ncases:\n  - id: a\n    question: q\n  - id: a\n    question: r\n");

        assertThat(thrown(() -> loader().load("empty.yaml")).getMessage()).contains("must not be empty");
        assertThat(thrown(() -> loader().load("noq.yaml")).getMessage()).contains("field 'question' is required");
        assertThat(thrown(() -> loader().load("dup.yaml")).getMessage()).contains("duplicates").contains("'a'");
    }

    @Test
    void rejectsInvalidDatasetNames() throws IOException {
        write("bad.yaml", "name: \"has space\"\ncases:\n  - id: a\n    question: q\n");
        write("has space.jsonl", "{\"id\": \"a\", \"question\": \"q\"}\n");

        assertThat(thrown(() -> loader().load("bad.yaml")).getMessage()).contains("field 'name'");
        assertThat(thrown(() -> loader().load("has space.jsonl")).getMessage()).contains("field 'name'");
    }

    @Test
    void rejectsYamlAnchorAndAlias() throws IOException {
        write("d.yaml", "name: d\ncases:\n  - id: a\n    question: &q hello\n  - id: b\n    question: *q\n");

        BaseException e = thrown(() -> loader().load("d.yaml"));

        assertThat(e.getErrorCode()).isEqualTo("EVAL_E007");
        assertThat(e.getMessage()).contains("anchors and aliases");
    }

    @Test
    void rejectsBillionLaughsQuickly() throws IOException {
        StringBuilder sb = new StringBuilder("a0: &a0 [x, x, x, x, x, x, x, x, x, x]\n");
        for (int i = 1; i < 10; i++) {
            String refs = ("*a" + (i - 1) + ", ").repeat(9) + "*a" + (i - 1);
            sb.append("a").append(i).append(": &a").append(i).append(" [").append(refs).append("]\n");
        }
        write("bomb.yaml", sb.toString());

        BaseException e = assertTimeoutPreemptively(Duration.ofSeconds(5), () -> thrown(() -> loader().load("bomb.yaml")));

        assertThat(e.getErrorCode()).isEqualTo("EVAL_E007");
    }

    @Test
    void rejectsNestingBeyondLimit() throws IOException {
        String json = "{\"name\":\"d\",\"cases\":[{\"id\":\"a\",\"question\":\"q\",\"attributes\":"
                + "{\"a\":".repeat(25) + "\"x\"" + "}".repeat(25) + "}]}";
        String yaml = "name: d\ncases:\n  - id: a\n    question: q\n    tags: " + "[".repeat(25) + "]".repeat(25) + "\n";
        write("deep.json", json);
        write("deep.yaml", yaml);
        write("deep.jsonl", "{\"id\":\"a\",\"question\":\"q\",\"attributes\":" + "{\"a\":".repeat(25) + "\"x\"" + "}".repeat(25) + "}\n");

        assertThat(thrown(() -> loader().load("deep.json")).getErrorCode()).isEqualTo("EVAL_E007");
        assertThat(thrown(() -> loader().load("deep.yaml")).getErrorCode()).isEqualTo("EVAL_E007");
        assertThat(thrown(() -> loader().load("deep.jsonl")).getErrorCode()).isEqualTo("EVAL_E007");
    }

    @Test
    void rejectsFileOverMaxFileBytes() throws IOException {
        write("big.json", JSON);
        DatasetLimits limits = new DatasetLimits(100, 1000, 1000, 20);

        BaseException e = thrown(() -> loader(limits).load("big.json"));

        assertThat(e.getErrorCode()).isEqualTo("EVAL_E005");
        assertThat(e.getStatus()).isEqualTo(413);
    }

    @Test
    void sizeCapIsEnforcedWhileReading() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[50]);

        assertThatThrownBy(() -> DatasetLoader.readCapped(in, 10, "x.json"))
                .isInstanceOf(ValidationException.class)
                .extracting(t -> ((BaseException) t).getErrorCode())
                .isEqualTo("EVAL_E005");
        assertThat(in.available()).isGreaterThan(0);
    }

    @Test
    void rejectsMoreCasesThanMax() throws IOException {
        write("d.yaml", "name: d\ncases:\n  - {id: a, question: q}\n  - {id: b, question: q}\n  - {id: c, question: q}\n");
        write("d.jsonl", "{\"id\":\"a\",\"question\":\"q\"}\n{\"id\":\"b\",\"question\":\"q\"}\n{\"id\":\"c\",\"question\":\"q\"}\n");
        DatasetLimits limits = new DatasetLimits(10_000, 2, 1000, 20);

        assertThat(thrown(() -> loader(limits).load("d.yaml")).getErrorCode()).isEqualTo("EVAL_E006");
        assertThat(thrown(() -> loader(limits).load("d.jsonl")).getErrorCode()).isEqualTo("EVAL_E006");
    }

    @Test
    void rejectsJsonlLineOverLimitWithLineNumber() throws IOException {
        String longLine = "{\"id\":\"b\",\"question\":\"" + "x".repeat(100) + "\"}";
        write("d.jsonl", "{\"id\":\"a\",\"question\":\"q\"}\n\n" + longLine + "\n");
        DatasetLimits limits = new DatasetLimits(10_000, 10, 60, 20);

        BaseException e = thrown(() -> loader(limits).load("d.jsonl"));

        assertThat(e.getErrorCode()).isEqualTo("EVAL_E005");
        assertThat(e.getMessage()).contains("line 3");
    }

    @Test
    void jsonlSkipsBlankLinesAndKeepsLineNumbers() throws IOException {
        write("ok.jsonl", "\n{\"id\":\"a\",\"question\":\"q\"}\n   \n\n{\"id\":\"b\",\"question\":\"q\"}\n");
        write("bad.jsonl", "{\"id\":\"a\",\"question\":\"q\"}\n\n\n{\"id\":\"b\",\"question\":\"q\",\"bogus\":1}\n");
        write("broken.jsonl", "{\"id\":\"a\",\"question\":\"q\"}\n\n{\"id\": \n");

        assertThat(loader().load("ok.jsonl").cases()).hasSize(2);
        assertThat(thrown(() -> loader().load("bad.jsonl")).getMessage()).contains("line 4");
        assertThat(thrown(() -> loader().load("broken.jsonl")).getMessage()).contains("line 3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/abs/d.yaml", "\\abs\\d.yaml", "../d.yaml", "a/../d.yaml", "a\\..\\d.yaml", "C:\\d.yaml", "C:d.yaml", "", " "})
    void rejectsBadRelativePaths(String path) {
        BaseException e = thrown(() -> loader().load(path));

        assertThat(e).isInstanceOf(ValidationException.class);
        assertThat(e.getErrorCode()).isEqualTo("EVAL_E003");
    }

    @Test
    void rejectsNullPath() {
        assertThat(thrown(() -> loader().load(null)).getErrorCode()).isEqualTo("EVAL_E003");
    }

    @Test
    void rejectsDirectoryMissingFileAndUnsupportedExtension() throws IOException {
        Files.createDirectory(dir.resolve("folder.yaml"));
        write("notes.txt", "x");

        assertThat(thrown(() -> loader().load("folder.yaml")).getErrorCode()).isEqualTo("EVAL_E003");
        BaseException missing = thrown(() -> loader().load("missing.yaml"));
        assertThat(missing).isInstanceOf(ResourceNotFoundException.class);
        assertThat(missing.getErrorCode()).isEqualTo("EVAL_E002");
        assertThat(thrown(() -> loader().load("notes.txt")).getErrorCode()).isEqualTo("EVAL_E004");
        assertThat(thrown(() -> loader().load("noext")).getErrorCode()).isEqualTo("EVAL_E004");
    }

    @Test
    void failsFastWhenRootIsMissing() {
        Path missing = dir.resolve("nope");

        assertThatThrownBy(() -> new DatasetLoader(missing, DatasetLimits.defaults()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsSymlinkPointingOutsideRoot(@TempDir Path outside) throws IOException {
        Path root = Files.createDirectory(dir.resolve("root"));
        Files.writeString(outside.resolve("secret.yaml"), YAML);
        Path link = root.resolve("link.yaml");
        assumeSymlink(link, outside.resolve("secret.yaml"));

        BaseException e = thrown(() -> new DatasetLoader(root, DatasetLimits.defaults()).load("link.yaml"));

        assertThat(e.getErrorCode()).isEqualTo("EVAL_E003");
    }

    @Test
    void allowsSymlinkInsideRoot() throws IOException {
        Path root = Files.createDirectory(dir.resolve("root"));
        Files.writeString(root.resolve("real.yaml"), YAML);
        assumeSymlink(root.resolve("alias.yaml"), root.resolve("real.yaml"));

        EvalDataset dataset = new DatasetLoader(root, DatasetLimits.defaults()).load("alias.yaml");

        assertThat(dataset.cases()).hasSize(2);
    }

    private static void assumeSymlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException | SecurityException e) {
            assumeTrue(false, "symlinks are not supported here");
        }
    }

    @Test
    void rejectsMalformedUtf8() throws IOException {
        byte[] bad = "name: d\ncases:\n  - id: a\n    question: ".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[bad.length + 3];
        System.arraycopy(bad, 0, bytes, 0, bad.length);
        bytes[bad.length] = (byte) 0xC3;
        bytes[bad.length + 1] = (byte) 0x28;
        bytes[bad.length + 2] = '\n';
        Files.write(dir.resolve("bad.yaml"), bytes);
        Files.write(dir.resolve("bad.jsonl"), new byte[] {'{', '"', 'i', (byte) 0xFF, '"', '}', '\n'});

        assertThat(thrown(() -> loader().load("bad.yaml")).getErrorCode()).isEqualTo("EVAL_E007");
        BaseException jsonl = thrown(() -> loader().load("bad.jsonl"));
        assertThat(jsonl.getErrorCode()).isEqualTo("EVAL_E007");
        assertThat(jsonl.getMessage()).contains("line 1");
    }

    @Test
    void stripsLeadingBom() throws IOException {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        for (String[] f : new String[][] {{"b.yaml", YAML}, {"b.json", JSON}, {"b.jsonl", JSONL}}) {
            byte[] body = f[1].getBytes(StandardCharsets.UTF_8);
            byte[] all = new byte[bom.length + body.length];
            System.arraycopy(bom, 0, all, 0, bom.length);
            System.arraycopy(body, 0, all, bom.length, body.length);
            Files.write(dir.resolve(f[0]), all);
        }

        assertThat(loader().load("b.yaml").cases()).hasSize(2);
        assertThat(loader().load("b.json").cases()).hasSize(2);
        assertThat(loader().load("b.jsonl").cases()).hasSize(2);
    }

    @Test
    void errorMessagesNeverContainCaseText() throws IOException {
        write("type.yaml", "name: d\ncases:\n  - id: 7\n    question: " + MARKER + "\n");
        write("broken.yaml", "name: d\ncases:\n  - id: a\n    question: " + MARKER + "\n    tags: [unclosed\n");
        write("unknown.yaml", "name: d\ncases:\n  - id: a\n    question: " + MARKER + "\n    bogus: x\n");
        write("badtype.json", "{\"name\":\"d\",\"cases\":[{\"id\":\"a\",\"question\":\"" + MARKER + "\",\"tags\":5}]}");
        write("broken.json", "{\"name\":\"d\",\"cases\":[{\"id\":\"a\",\"question\":\"" + MARKER + "\" \"tags\":5}]}");
        write("broken.jsonl", "{\"id\":\"a\",\"question\":\"" + MARKER + "\" oops}\n");
        write("unknown.jsonl", "{\"id\":\"a\",\"question\":\"" + MARKER + "\",\"bogus\":1}\n");
        write("dup.yaml", "name: d\ncases:\n  - id: a\n    question: " + MARKER + "\n  - id: a\n    question: " + MARKER + "\n");

        for (String file : List.of("type.yaml", "broken.yaml", "unknown.yaml", "badtype.json", "broken.json",
                "broken.jsonl", "unknown.jsonl", "dup.yaml")) {
            BaseException e = thrown(() -> loader().load(file));
            assertNoMarker(e);
        }
    }

    @Test
    void parseLoadsContentAndEnforcesLimits() {
        EvalDataset yaml = loader().parse("sample", DatasetFormat.YAML, YAML);
        EvalDataset json = loader().parse("sample", DatasetFormat.JSON, JSON);
        EvalDataset jsonl = loader().parse("sample", DatasetFormat.JSONL, JSONL);
        assertThat(yaml.fingerprint()).isEqualTo(json.fingerprint()).isEqualTo(jsonl.fingerprint());
        assertThat(loader().parse("sample", DatasetFormat.YAML, "\uFEFF" + YAML).cases()).hasSize(2);

        DatasetLimits tiny = new DatasetLimits(100, 1, 1000, 20);
        assertThat(thrown(() -> loader(tiny).parse("sample", DatasetFormat.JSON, JSON)).getErrorCode()).isEqualTo("EVAL_E005");
        DatasetLimits oneCase = new DatasetLimits(10_000, 1, 1000, 20);
        assertThat(thrown(() -> loader(oneCase).parse("sample", DatasetFormat.YAML, YAML)).getErrorCode()).isEqualTo("EVAL_E006");
        assertThat(thrown(() -> loader().parse("sample", DatasetFormat.YAML, "name: d\nx: &a 1\ncases: []\n")).getErrorCode())
                .isEqualTo("EVAL_E007");
    }

    @Test
    void formatLookupAndLimitsValidation() {
        assertThat(DatasetFormat.fromFileName("a.YML")).isEqualTo(DatasetFormat.YAML);
        assertThat(DatasetFormat.fromFileName("a.json")).isEqualTo(DatasetFormat.JSON);
        assertThat(DatasetFormat.fromFileName("a.jsonl")).isEqualTo(DatasetFormat.JSONL);
        assertThatThrownBy(() -> DatasetFormat.fromFileName("a.csv")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new DatasetLimits(0, 1, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(DatasetLimits.defaults()).isEqualTo(new DatasetLimits(5 * 1024 * 1024, 1000, 64 * 1024, 20));
    }
}
