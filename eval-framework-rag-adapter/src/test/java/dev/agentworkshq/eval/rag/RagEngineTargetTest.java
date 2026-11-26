package dev.agentworkshq.eval.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.agentworkshq.eval.core.exception.ValidationException;
import dev.agentworkshq.eval.core.model.CaseResult;
import dev.agentworkshq.eval.core.model.CaseStatus;
import dev.agentworkshq.eval.core.model.EvalCase;
import dev.agentworkshq.eval.core.model.EvalDataset;
import dev.agentworkshq.eval.core.model.EvalRun;
import dev.agentworkshq.eval.core.model.RetrievedChunk;
import dev.agentworkshq.eval.core.model.TargetOutput;
import dev.agentworkshq.eval.core.runner.EvalRunner;
import dev.agentworkshq.eval.core.runner.RunConfig;
import dev.agentworkshq.rag.core.answer.Citation;
import dev.agentworkshq.rag.core.answer.RagAnswer;
import dev.agentworkshq.rag.core.answer.RagContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RagEngineTargetTest {

    private static final EvalCase CASE = new EvalCase("case-1", "How do I reset it?", null, List.of(), false,
            List.of(), Map.of("tenantId", "other-tenant"), List.of(), List.of());

    @Test
    void mapsEveryField() {
        RagAnswer answer = new RagAnswer("Hold the button [1]",
                List.of(new Citation(1, "doc-1", "a.txt", 3, "first snippet", true)), true);

        TargetOutput output = target((q, c) -> answer).run(CASE);

        assertThat(output.answer()).isEqualTo("Hold the button [1]");
        assertThat(output.answered()).isTrue();
        assertThat(output.retrieved()).containsExactly(
                new RetrievedChunk("citation-1", "doc-1", "a.txt", 3, "first snippet", null));
        assertThat(output.citedSources()).containsExactly("a.txt");
        assertThat(output.attributes()).isEmpty();
    }

    @Test
    void passesTheQuestionToAsk() {
        AtomicReference<String> seen = new AtomicReference<>();

        target((q, c) -> {
            seen.set(q);
            return new RagAnswer("ok", List.of(), true);
        }).run(CASE);

        assertThat(seen.get()).isEqualTo("How do I reset it?");
    }

    @Test
    void measuresLatency() {
        TargetOutput output = target((q, c) -> {
            long until = System.nanoTime() + Duration.ofMillis(20).toNanos();
            while (System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            return new RagAnswer("ok", List.of(), true);
        }).run(CASE);

        assertThat(output.latency()).isGreaterThanOrEqualTo(Duration.ofMillis(20));
    }

    @Test
    void keepsTheOrderOfRetrievedChunks() {
        List<Citation> citations = List.of(
                citation(3, "c.txt", false), citation(1, "a.txt", false), citation(2, "b.txt", false));

        TargetOutput output = target((q, c) -> new RagAnswer("x", citations, true)).run(CASE);

        assertThat(output.retrieved()).extracting(RetrievedChunk::id)
                .containsExactly("citation-3", "citation-1", "citation-2");
        assertThat(output.retrieved()).extracting(RetrievedChunk::source).containsExactly("c.txt", "a.txt", "b.txt");
    }

    @Test
    void citesOnlyUsedFilesOnceInOrder() {
        List<Citation> citations = List.of(citation(1, "b.txt", true), citation(2, "skip.txt", false),
                citation(3, "a.txt", true), citation(4, "b.txt", true));

        TargetOutput output = target((q, c) -> new RagAnswer("x", citations, true)).run(CASE);

        assertThat(output.citedSources()).containsExactly("b.txt", "a.txt");
        assertThat(output.retrieved()).hasSize(4);
    }

    @Test
    void mapsAnUnansweredAnswer() {
        TargetOutput output = target((q, c) -> new RagAnswer("I do not know", List.of(), false)).run(CASE);

        assertThat(output.answered()).isFalse();
        assertThat(output.answer()).isEqualTo("I do not know");
        assertThat(output.retrieved()).isEmpty();
        assertThat(output.citedSources()).isEmpty();
    }

    @Test
    void handlesNullValues() {
        List<Citation> citations = List.of(new Citation(1, null, null, null, null, true));

        TargetOutput output = target((q, c) -> new RagAnswer(null, citations, true)).run(CASE);

        assertThat(output.answer()).isEmpty();
        assertThat(output.retrieved()).containsExactly(new RetrievedChunk("citation-1", null, null, null, "", null));
        assertThat(output.citedSources()).isEmpty();
        assertThat(target((q, c) -> new RagAnswer("x", null, true)).run(CASE).retrieved()).isEmpty();
    }

    @Test
    void sendsOptionsAsTheContext() {
        AtomicReference<RagContext> seen = new AtomicReference<>();
        RagTargetOptions options = RagTargetOptions.defaults()
                .withTenantId("tenant-a")
                .withSourceNames(List.of("docs", "faq"))
                .withTopK(4);

        RagEngineTarget.of((q, c) -> {
            seen.set(c);
            return new RagAnswer("ok", List.of(), true);
        }, options).run(CASE);

        assertThat(seen.get()).isEqualTo(new RagContext("tenant-a", List.of("docs", "faq"), 4));
    }

    @Test
    void defaultOptionsUseTheDefaultTenantAndNoOverrides() {
        AtomicReference<RagContext> seen = new AtomicReference<>();

        target((q, c) -> {
            seen.set(c);
            return new RagAnswer("ok", List.of(), true);
        }).run(CASE);

        assertThat(seen.get()).isEqualTo(new RagContext("default", List.of(), null));
        assertThat(RagTargetOptions.defaults().name()).isEqualTo("rag-engine");
    }

    @Test
    void describesTheDefaults() {
        assertThat(target((q, c) -> null).describe())
                .containsExactly(Map.entry("engine", "rag-engine"), Map.entry("sourceFilter", "0"),
                        Map.entry("topK", "default"));
    }

    @Test
    void describesOptionsAndExtraEntries() {
        RagTargetOptions options = RagTargetOptions.defaults()
                .withName("support-bot")
                .withSourceNames(List.of("docs"))
                .withTopK(5)
                .withDescription(Map.of("model", "some-model", "embedding-model", "some-embedding"));

        RagEngineTarget target = RagEngineTarget.of((q, c) -> null, options);

        assertThat(target.name()).isEqualTo("support-bot");
        assertThat(target.describe()).containsOnly(Map.entry("engine", "rag-engine"), Map.entry("topK", "5"),
                Map.entry("sourceFilter", "1"), Map.entry("model", "some-model"),
                Map.entry("embedding-model", "some-embedding"));
    }

    @Test
    void validatesTheName() {
        RagTargetOptions defaults = RagTargetOptions.defaults();

        for (String name : new String[] {null, " ", "a".repeat(101), "a\nb", "https://host/x", "api-key"}) {
            assertThatThrownBy(() -> defaults.withName(name)).isInstanceOf(ValidationException.class);
        }
        assertThat(defaults.withName("a".repeat(100)).name()).hasSize(100);
    }

    @Test
    void validatesTenantSourcesAndTopK() {
        RagTargetOptions defaults = RagTargetOptions.defaults();

        assertThatThrownBy(() -> defaults.withTenantId(" ")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> defaults.withTenantId(null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> defaults.withSourceNames(List.of("docs", " ")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> defaults.withSourceNames(java.util.Arrays.asList("docs", null)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> defaults.withSourceNames(numbered(21))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> defaults.withTopK(0)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> defaults.withTopK(51)).isInstanceOf(ValidationException.class);
        assertThat(defaults.withSourceNames(numbered(20)).sourceNames()).hasSize(20);
        assertThat(defaults.withSourceNames(null).sourceNames()).isEmpty();
        assertThat(defaults.withTopK(1).topK()).isEqualTo(1);
        assertThat(defaults.withTopK(50).topK()).isEqualTo(50);
        assertThat(defaults.withTopK(5).withTopK(null).topK()).isNull();
    }

    @Test
    void validatesTheDescription() {
        RagTargetOptions defaults = RagTargetOptions.defaults();
        Map<String, String> tooMany = IntStream.range(0, 21).boxed()
                .collect(java.util.stream.Collectors.toMap(i -> "setting" + i, i -> "v"));

        assertThatThrownBy(() -> defaults.withDescription(tooMany)).isInstanceOf(ValidationException.class);
        for (Map<String, String> bad : List.of(Map.of("api-key", "x"), Map.of("dbPassword", "x"), Map.of(" ", "x"),
                Map.of("engine", "x"), Map.of("model", "x".repeat(201)))) {
            assertThatThrownBy(() -> defaults.withDescription(bad)).isInstanceOf(ValidationException.class);
        }
        assertThat(defaults.withDescription(Map.of("model", "x".repeat(200))).description()).hasSize(1);
        assertThat(defaults.withDescription(null).description()).isEmpty();
    }

    @Test
    void optionsAreImmutable() {
        List<String> sources = new ArrayList<>(List.of("docs"));
        RagTargetOptions options = RagTargetOptions.defaults().withSourceNames(sources);
        sources.add("more");

        assertThat(options.sourceNames()).containsExactly("docs");
        assertThatThrownBy(() -> options.description().put("a", "b")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsNullArguments() {
        assertThatThrownBy(() -> RagEngineTarget.of((RagAsk) null, RagTargetOptions.defaults()))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> RagEngineTarget.of((q, c) -> null, null)).isInstanceOf(ValidationException.class);
    }

    @Test
    void isThreadSafe() throws Exception {
        RagEngineTarget target = target((q, c) -> new RagAnswer("answer to " + q,
                List.of(citation(1, q + ".txt", true)), true));
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                String question = "q" + i;
                futures.add(executor.submit(() -> {
                    start.await();
                    TargetOutput output = target.run(new EvalCase(question, question, null, List.of(), false,
                            List.of(), Map.of(), List.of(), List.of()));
                    return output.answer().equals("answer to " + question)
                            && output.citedSources().equals(List.of(question + ".txt"));
                }));
            }
            start.countDown();
            for (Future<Boolean> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void exceptionMessagesNeverReachTheResult() {
        RagEngineTarget target = target((q, c) -> {
            throw new IllegalStateException("SECRET-MARKER-TEXT");
        });

        EvalRun run = new EvalRunner().run(new EvalDataset("privacy", List.of(CASE)), target, List.of(),
                RunConfig.defaults());

        CaseResult result = run.caseResults().getFirst();
        assertThat(result.status()).isEqualTo(CaseStatus.TARGET_FAILED);
        assertThat(result.errorMessage()).contains("IllegalStateException").doesNotContain("SECRET-MARKER-TEXT");
        assertThat(result.toString()).doesNotContain("SECRET-MARKER-TEXT");
        assertThat(run.toString()).doesNotContain("SECRET-MARKER-TEXT");
        assertThatThrownBy(() -> target.run(CASE)).hasMessage("SECRET-MARKER-TEXT");
    }

    private static RagEngineTarget target(RagAsk ask) {
        return RagEngineTarget.of(ask, RagTargetOptions.defaults());
    }

    private static Citation citation(int index, String filename, boolean used) {
        return new Citation(index, "doc-" + index, filename, null, "snippet " + index, used);
    }

    private static List<String> numbered(int count) {
        return IntStream.range(0, count).mapToObj(i -> "source" + i).toList();
    }
}
