package dev.agentworkshq.eval.core.store;

import static dev.agentworkshq.eval.core.store.StoreFixtures.T0;
import static dev.agentworkshq.eval.core.store.StoreFixtures.completed;
import static dev.agentworkshq.eval.core.store.StoreFixtures.golden;
import static dev.agentworkshq.eval.core.store.StoreFixtures.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.agentworkshq.eval.core.exception.BaseException;
import dev.agentworkshq.eval.core.exception.ErrorMessages;
import dev.agentworkshq.eval.core.exception.ResourceNotFoundException;
import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.MetricResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FileRunStoreTest {

    @TempDir
    Path dir;

    private FileRunStore store() {
        return new FileRunStore(dir.resolve("runs"), StoreLimits.defaults());
    }

    private static ErrorMessages codeOf(Throwable t) {
        return ((BaseException) t).getErrorMessage();
    }

    private static List<String> names(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void readOnlyStoreNeverCreatesTheDirectoryAndRejectsSave() {
        Path missing = dir.resolve("missing");

        assertThatThrownBy(() -> FileRunStore.readOnly(missing, StoreLimits.defaults()))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> codeOf(e)).isEqualTo(ErrorMessages.EVAL_E020);

        assertThat(missing).doesNotExist();
    }

    @Test
    void readOnlyStoreLoadsAndListsButCannotSave() throws IOException {
        store().save(run("r1", T0, completed("a")));
        FileRunStore readOnly = FileRunStore.readOnly(dir.resolve("runs"), StoreLimits.defaults());

        assertThat(readOnly.load("r1").runId()).isEqualTo("r1");
        assertThat(readOnly.list()).hasSize(1);
        assertThatThrownBy(() -> readOnly.save(run("r2", T0, completed("a")))).isInstanceOf(ValidationException.class)
                .hasMessageContaining("read-only");
        assertThat(names(dir.resolve("runs"))).containsExactly("run-r1.json");
    }

    @Test
    void readOnlyStoreRejectsARegularFileRoot() throws IOException {
        Path file = Files.writeString(dir.resolve("file.txt"), "x");

        assertThatThrownBy(() -> FileRunStore.readOnly(file, StoreLimits.defaults())).isInstanceOf(ValidationException.class);
    }

    @Test
    void ioFailuresUseTheStoreIoCode() throws IOException {
        FileRunStore store = store();
        Files.delete(dir.resolve("runs"));

        assertThatThrownBy(store::list).isInstanceOf(BaseException.class)
                .hasMessage("Run store IO failure during list")
                .extracting(e -> codeOf(e)).isEqualTo(ErrorMessages.EVAL_E023);
        assertThatThrownBy(() -> store.save(golden())).isInstanceOf(BaseException.class)
                .extracting(e -> codeOf(e)).isEqualTo(ErrorMessages.EVAL_E023);
    }

    @Test
    void savesAndLoadsARun() {
        FileRunStore store = store();

        store.save(golden());

        assertThat(store.load("run-1")).isEqualTo(golden());
    }

    @Test
    void createsTheRootWhenMissing() {
        Path nested = dir.resolve("a").resolve("b");

        new FileRunStore(nested, StoreLimits.defaults());

        assertThat(nested).isDirectory();
    }

    @Test
    void rejectsARootThatIsARegularFile() throws IOException {
        Path file = Files.writeString(dir.resolve("file.txt"), "x");

        assertThatThrownBy(() -> new FileRunStore(file, StoreLimits.defaults()))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> codeOf(e)).isEqualTo(ErrorMessages.EVAL_E020);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "../x", "a/b", "a\\b", "..", ".hidden", "a b", "x:y"})
    void rejectsInvalidRunIds(String id) {
        FileRunStore store = store();

        assertThatThrownBy(() -> store.load(id)).isInstanceOf(ValidationException.class);
    }

    @Test
    void rejectsANullRunId() {
        FileRunStore store = store();

        assertThatThrownBy(() -> store.load(null)).isInstanceOf(ValidationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CON", "NUL", "aux", "COM1", "LPT1", "con.txt"})
    void windowsDeviceNamesAreOrdinaryRunIds(String id) {
        FileRunStore store = store();
        StoredRun saved = run(id, T0, completed("a"));

        store.save(saved);

        assertThat(store.load(id)).isEqualTo(saved);
        assertThat(store.list()).extracting(RunHeader::runId).containsExactly(id);
    }

    @Test
    void loadingAMissingRunFails() {
        FileRunStore store = store();

        assertThatThrownBy(() -> store.load("nope")).isInstanceOf(ResourceNotFoundException.class)
                .extracting(e -> codeOf(e)).isEqualTo(ErrorMessages.EVAL_E017);
    }

    @Test
    void savingTheSameIdTwiceFailsAndKeepsTheFirstFile() throws IOException {
        FileRunStore store = store();
        store.save(run("r1", T0, completed("a")));
        Path file = dir.resolve("runs").resolve("run-r1.json");
        byte[] before = Files.readAllBytes(file);

        assertThatThrownBy(() -> store.save(run("r1", T0.plusSeconds(5), completed("b"))))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> codeOf(e)).isEqualTo(ErrorMessages.EVAL_E018);

        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(names(dir.resolve("runs"))).containsExactly("run-r1.json");
    }

    @Test
    void idsThatDifferOnlyByCaseFailOnACaseInsensitiveFileSystem() throws IOException {
        Path probe = Files.createTempFile(dir, "CaseProbe", ".tmp");
        boolean insensitive = Files.exists(dir.resolve(probe.getFileName().toString().toUpperCase(Locale.ROOT)))
                && Files.exists(dir.resolve(probe.getFileName().toString().toLowerCase(Locale.ROOT)));
        assumeTrue(insensitive, "the file system is case sensitive");
        FileRunStore store = store();
        store.save(run("Abc", T0, completed("a")));

        assertThatThrownBy(() -> store.save(run("abc", T0, completed("a"))))
                .isInstanceOf(ValidationException.class)
                .extracting(e -> codeOf(e)).isEqualTo(ErrorMessages.EVAL_E018);
        assertThatThrownBy(() -> store.load("abc")).isInstanceOf(ResourceNotFoundException.class);
        assertThat(store.load("Abc").runId()).isEqualTo("Abc");
    }

    @Test
    void aSymlinkLeavingTheRootIsRejected() throws IOException {
        FileRunStore outside = new FileRunStore(dir.resolve("outside"), StoreLimits.defaults());
        outside.save(run("x", T0, completed("a")));
        FileRunStore store = store();
        try {
            Files.createSymbolicLink(dir.resolve("runs").resolve("run-x.json"), dir.resolve("outside").resolve("run-x.json"));
        } catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "symbolic links cannot be created here");
        }

        assertThatThrownBy(() -> store.load("x")).isInstanceOf(ValidationException.class)
                .extracting(e -> codeOf(e)).isEqualTo(ErrorMessages.EVAL_E020);
        assertThatThrownBy(store::list).isInstanceOf(ValidationException.class)
                .extracting(e -> codeOf(e)).isEqualTo(ErrorMessages.EVAL_E020);
        assertThatThrownBy(() -> store.save(run("x", T0, completed("a")))).isInstanceOf(ValidationException.class)
                .extracting(e -> codeOf(e)).isEqualTo(ErrorMessages.EVAL_E018);
    }

    @Test
    void foreignFilesAreIgnored() throws IOException {
        FileRunStore store = store();
        Path root = dir.resolve("runs");
        store.save(run("good", T0, completed("a")));
        Files.writeString(root.resolve("notes.txt"), "not json");
        Files.writeString(root.resolve("run-.json"), "not json");
        Files.writeString(root.resolve("run-a.json.bak"), "not json");
        Files.writeString(root.resolve("other-b.json"), "not json");
        Files.writeString(root.resolve("run-bad name.json"), "not json");
        Files.writeString(root.resolve("run-x.tmp"), "not json");
        Files.createDirectory(root.resolve("run-dir.json"));

        assertThat(store.list()).extracting(RunHeader::runId).containsExactly("good");
        assertThat(store.latest("ds", "target")).map(StoredRun::runId).contains("good");
    }

    @Test
    void aFailedSaveLeavesNoTempFile() throws IOException {
        FileRunStore store = store();
        store.save(run("r1", T0, completed("a")));

        assertThatThrownBy(() -> store.save(run("r1", T0, completed("a")))).isInstanceOf(ValidationException.class);
        FileRunStore tiny = new FileRunStore(dir.resolve("runs"), new StoreLimits(50, 10, 20));
        assertThatThrownBy(() -> tiny.save(run("r2", T0, completed("a")))).isInstanceOf(ValidationException.class);

        assertThat(names(dir.resolve("runs"))).containsExactly("run-r1.json");
    }

    @Test
    void listIsSortedByStartThenId() {
        FileRunStore store = store();
        store.save(run("r3", T0.plusSeconds(10), completed("a")));
        store.save(run("r2", T0, completed("a"), completed("b")));
        store.save(run("r1", T0, completed("a")));

        List<RunHeader> headers = store.list();

        assertThat(headers).extracting(RunHeader::runId).containsExactly("r1", "r2", "r3");
        assertThat(headers.get(1)).isEqualTo(new RunHeader("r2", "ds", "target", T0, 2));
    }

    @Test
    void latestPicksTheNewestRunAndBreaksTiesById() {
        FileRunStore store = store();
        store.save(run("old", T0, completed("a")));
        store.save(run("new", T0.plusSeconds(60), completed("a")));
        store.save(new StoredRun("other-ds", "other", "fp", "target", T0.plusSeconds(120), T0.plusSeconds(121), Map.of(), List.of()));
        store.save(new StoredRun("other-target", "ds", "fp", "elsewhere", T0.plusSeconds(120), T0.plusSeconds(121), Map.of(), List.of()));
        store.save(run("tie-a", T0.plusSeconds(90), completed("a")));
        store.save(run("tie-b", T0.plusSeconds(90), completed("a")));

        assertThat(store.latest("ds", "target")).map(StoredRun::runId).contains("tie-b");
        assertThat(store.latest("other", "target")).map(StoredRun::runId).contains("other-ds");
        assertThat(store.latest("ds", "none")).isEmpty();
        assertThat(store.latest("nothing", "target")).isEmpty();
    }

    @Test
    void latestOnAnEmptyStoreIsEmpty() {
        assertThat(store().latest("ds", "target")).isEmpty();
        assertThat(store().list()).isEmpty();
    }

    @Test
    void aCorruptFileFailsListAndNamesTheFile() throws IOException {
        FileRunStore store = store();
        store.save(run("good", T0, completed("a")));
        Files.writeString(dir.resolve("runs").resolve("run-broken.json"), "{ not json");

        assertThatThrownBy(store::list).isInstanceOf(ValidationException.class)
                .hasMessageContaining("run-broken.json")
                .extracting(e -> codeOf(e)).isEqualTo(ErrorMessages.EVAL_E019);
        assertThatThrownBy(() -> store.latest("ds", "target")).hasMessageContaining("run-broken.json");
        assertThatThrownBy(() -> store.load("broken")).hasMessageContaining("run-broken.json");
    }

    @Test
    void maxRunsIsEnforcedForList() {
        FileRunStore store = new FileRunStore(dir.resolve("runs"), new StoreLimits(1024 * 1024, 2, 20));
        store.save(run("r1", T0, completed("a")));
        store.save(run("r2", T0, completed("a")));
        assertThat(store.list()).hasSize(2);
        store.save(run("r3", T0, completed("a")));

        assertThatThrownBy(store::list).isInstanceOf(ValidationException.class)
                .extracting(e -> codeOf(e)).isEqualTo(ErrorMessages.EVAL_E021);
        assertThatThrownBy(() -> store.latest("ds", "target")).isInstanceOf(ValidationException.class);
        assertThat(store.load("r3").runId()).isEqualTo("r3");
    }

    @Test
    void loadRejectsAFileOverTheByteLimit() throws IOException {
        FileRunStore store = new FileRunStore(dir.resolve("runs"), new StoreLimits(200, 10, 20));
        Files.writeString(dir.resolve("runs").resolve("run-big.json"), "x".repeat(100_000));

        assertThatThrownBy(() -> store.load("big")).isInstanceOf(ValidationException.class)
                .hasMessageContaining("larger than 200 bytes")
                .extracting(e -> codeOf(e)).isEqualTo(ErrorMessages.EVAL_E019);
    }

    @Test
    void aFileWhoseContentHasAnotherRunIdIsRejected() throws IOException {
        FileRunStore store = store();
        store.save(run("a", T0, completed("a")));
        Files.copy(dir.resolve("runs").resolve("run-a.json"), dir.resolve("runs").resolve("run-b.json"));

        assertThatThrownBy(() -> store.load("b")).isInstanceOf(ValidationException.class)
                .hasMessageContaining("does not match the file name");
    }

    @Test
    void parallelSavesOfDifferentRunsAllSucceed() throws Exception {
        FileRunStore store = store();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String id = "p" + i;
                futures.add(pool.submit(() -> store.save(run(id, T0.plusSeconds(id.length()), completed("a")))));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(store.list()).hasSize(40);
        assertThat(names(dir.resolve("runs"))).hasSize(40).allMatch(n -> n.endsWith(".json"));
    }

    @Test
    void parallelSavesOfTheSameIdLetExactlyOneWin() throws Exception {
        FileRunStore store = store();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        int wins = 0;
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                futures.add(pool.submit(() -> store.save(run("same", T0, completed("a")))));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                    wins++;
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(codeOf(e.getCause())).isEqualTo(ErrorMessages.EVAL_E018);
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(wins).isEqualTo(1);
        assertThat(names(dir.resolve("runs"))).containsExactly("run-same.json");
    }

    @Test
    void theSavedFileNeverContainsQuestionAnswerOrChunkText() throws IOException {
        String marker = "UNIQUE-MARKER-TEXT";
        FileRunStore store = store();

        store.save(StoredRun.from(StoreFixtures.evalRunWithMarker(marker)));

        try (Stream<Path> files = Files.list(dir.resolve("runs"))) {
            List<Path> saved = files.toList();
            assertThat(saved).hasSize(1);
            assertThat(Files.readString(saved.get(0), StandardCharsets.UTF_8)).doesNotContain(marker);
        }
    }

    @Test
    void aSecretLookingConfigKeyCannotReachTheStore() {
        assertThatThrownBy(() -> new StoredRun("r", "ds", "fp", "t", T0, T0, Map.of("api-key", "v"), List.of()))
                .isInstanceOf(BaseException.class)
                .extracting(e -> codeOf(e)).isEqualTo(ErrorMessages.EVAL_E011);
    }

    @Test
    void metricResultsSurviveTheFileUnchanged() {
        FileRunStore store = store();
        StoredRun saved = run("m", T0, completed("a", MetricResult.passed("x", 0.1 + 0.2, "r"), MetricResult.error("y", "boom")));

        store.save(saved);

        assertThat(store.load("m")).isEqualTo(saved);
    }
}
