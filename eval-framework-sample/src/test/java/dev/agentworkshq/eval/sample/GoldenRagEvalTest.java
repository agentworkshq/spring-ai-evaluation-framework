package dev.agentworkshq.eval.sample;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.autoconfigure.EvalFrameworkProperties;
import dev.agentworkshq.eval.core.compare.CaseChange;
import dev.agentworkshq.eval.core.compare.ChangeKind;
import dev.agentworkshq.eval.core.compare.RunSummary;
import dev.agentworkshq.eval.core.dataset.DatasetLoader;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.report.MarkdownReport;
import dev.agentworkshq.eval.core.store.FileRunStore;
import dev.agentworkshq.eval.core.store.StoreLimits;
import dev.agentworkshq.eval.core.store.StoredCase;
import dev.agentworkshq.eval.core.store.StoredRun;
import dev.agentworkshq.eval.core.suite.EvalOutcome;
import dev.agentworkshq.eval.core.suite.EvalRequest;
import dev.agentworkshq.eval.core.suite.EvalSuite;
import dev.agentworkshq.eval.core.suite.Thresholds;
import dev.agentworkshq.eval.junit.EvalAssertions;
import dev.agentworkshq.eval.rag.RagAsk;
import dev.agentworkshq.eval.rag.RagEngineTarget;
import dev.agentworkshq.eval.rag.RagTargetOptions;
import dev.agentworkshq.rag.core.answer.Citation;
import dev.agentworkshq.rag.core.answer.RagAnswer;
import dev.agentworkshq.rag.core.answer.RagService;
import dev.agentworkshq.rag.core.ingest.IngestionResult;
import dev.agentworkshq.rag.core.ingest.IngestionService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.opentest4j.AssertionFailedError;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(OfflineModelsConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GoldenRagEvalTest {

    private static final String DATASET = "aurora-golden.yaml";
    private static final String BASELINE = "aurora-baseline-v1";
    private static final String DOCS = "docs";
    private static final String DEGRADED = "troubleshooting.txt";
    private static final String MARKER = "SECRET-MARKER-TEXT";

    @Autowired
    EvalSuite suite;

    @Autowired
    RagService ragService;

    @Autowired
    IngestionService ingestionService;

    @Autowired
    DatasetLoader datasetLoader;

    @Autowired
    EvalFrameworkProperties properties;

    @Autowired
    ExtractiveChatModel chatModel;

    @BeforeAll
    void ingest() {
        ingest(DOCS, 3);
        ingest(DEGRADED, 1);
    }

    @Test
    void healthyTargetPassesTheGate() {
        int callsBefore = chatModel.calls();

        EvalOutcome outcome = evaluate(target(DOCS));

        EvalAssertions.assertGate(outcome);
        StoredRun baseline = FileRunStore
                .readOnly(Path.of(properties.baseline().dir()), StoreLimits.defaults())
                .load(BASELINE);
        assertThat(outcome.summary().metrics()).isEqualTo(RunSummary.of(baseline).metrics());
        assertThat(outcome.stored().cases())
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields("durationMs", "latencyMs")
                .containsExactlyElementsOf(baseline.cases());
        // the four out of scope cases retrieve nothing, so they never reach the model
        assertThat(chatModel.calls() - callsBefore).isEqualTo(11);
    }

    @Test
    void degradedTargetFailsTheGate() {
        EvalOutcome outcome = evaluate(target(DEGRADED));

        assertThat(outcome.passesGate()).isFalse();
        for (String metric : List.of("answer_contains", "citation", "hit_at_3", "mrr_at_3", "no_answer",
                "recall_at_3")) {
            assertThat(outcome.violations()).anyMatch(v -> v.startsWith("metric " + metric + " regressed"));
            assertThat(outcome.violations()).anyMatch(v -> v.startsWith("pass rate of metric " + metric + " is"));
        }
        assertThat(outcome.violations()).contains("coverage lost: case warranty-01 metric citation_grounded");
        assertThat(outcome.comparison().caseChanges())
                .filteredOn(c -> c.kind() == ChangeKind.REGRESSION)
                .extracting(CaseChange::caseId)
                .contains("setup-01", "firmware-01", "warranty-01")
                .doesNotContain("troubleshooting-01", "troubleshooting-02", "troubleshooting-03");
        assertThat(outcome.markdownReport()).startsWith(MarkdownReport.MARKER).contains("setup-01", "warranty-01");
        assertThatThrownBy(() -> EvalAssertions.assertGate(outcome)).isInstanceOf(AssertionFailedError.class);
    }

    @Test
    void runFileAndReportsContainNoDatasetOrDocumentText() throws IOException {
        RagAsk marked = (question, context) -> mark(ragService.ask(question, context));

        EvalOutcome outcome = evaluate(RagEngineTarget.of(marked, options(DOCS)));

        assertThat(outcome.run().caseResults()).allMatch(r -> r.output().answer().contains(MARKER));
        assertThat(outcome.reportFiles()).hasSize(2);
        List<String> written = new ArrayList<>();
        written.add(Files.readString(Path.of(properties.store().dir(), "run-" + outcome.run().runId() + ".json")));
        for (String name : outcome.reportFiles()) {
            written.add(Files.readString(Path.of(properties.report().dir(), name)));
        }
        written.add(outcome.consoleReport());
        List<String> forbidden = forbiddenText();
        assertThat(written).allSatisfy(content -> assertThat(content)
                .doesNotContain(MARKER)
                .doesNotContain(forbidden));
    }

    @Test
    void healthyRunsAreDeterministic() {
        EvalOutcome first = evaluate(target(DOCS));
        EvalOutcome second = evaluate(target(DOCS));

        assertThat(second.run().runId()).isNotEqualTo(first.run().runId());
        assertThat(second.summary()).usingRecursiveComparison().ignoringFields("runId").isEqualTo(first.summary());
        assertThat(second.stored().cases()).extracting(StoredCase::metricResults)
                .containsExactlyElementsOf(first.stored().cases().stream().map(StoredCase::metricResults).toList());
    }

    private void ingest(String source, int documents) {
        IngestionResult result = ingestionService.ingest(source, "default");
        assertThat(result.failed()).isZero();
        assertThat(result.added()).isEqualTo(documents);
    }

    private EvalOutcome evaluate(RagEngineTarget target) {
        EvalRequest request = EvalRequest.defaults()
                .withRunConfig(properties.runner().toRunConfig())
                .withBaselineRunId(BASELINE)
                .withThresholds(thresholds());
        return suite.evaluate(DATASET, target, SampleMetrics.all(), request);
    }

    private RagEngineTarget target(String source) {
        return RagEngineTarget.of(ragService, options(source));
    }

    private static RagTargetOptions options(String source) {
        return RagTargetOptions.defaults().withSourceNames(List.of(source));
    }

    // the baseline scores 1.0 everywhere, so 0.9 leaves room for one miss among the 11 answerable cases
    private static Thresholds thresholds() {
        Thresholds thresholds = Thresholds.defaults().withMinMeanScore("mrr_at_3", 0.9);
        for (String metric : List.of("answer_contains", "citation", "citation_grounded", "hit_at_3", "latency",
                "mrr_at_3", "no_answer", "recall_at_3")) {
            thresholds = thresholds.withMinPassRate(metric, 0.9);
        }
        return thresholds;
    }

    private static RagAnswer mark(RagAnswer answer) {
        List<Citation> citations = answer.citations().stream()
                .map(c -> new Citation(c.index(), c.documentId(), c.filename(), c.page(),
                        MARKER + " " + c.snippet(), c.used()))
                .toList();
        return new RagAnswer(answer.text() + " " + MARKER, citations, answer.answered());
    }

    private List<String> forbiddenText() throws IOException {
        List<String> text = new ArrayList<>();
        for (EvalCase evalCase : datasetLoader.load(DATASET).cases()) {
            text.add(evalCase.question());
            text.addAll(evalCase.mustContain());
        }
        Pattern sentences = Pattern.compile("(?<=[.!?])\\s+|\\R+");
        for (String file : List.of("getting-started.txt", "troubleshooting.txt", "warranty-and-support.txt")) {
            sentences.splitAsStream(Files.readString(Path.of("docs", file)))
                    .map(String::strip)
                    .filter(s -> !s.isEmpty())
                    .forEach(text::add);
        }
        return text;
    }
}
