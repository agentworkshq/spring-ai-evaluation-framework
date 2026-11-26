# spring-ai-evaluation-framework

## What it is

Evaluation framework for Spring AI applications.
It runs a dataset of cases against your application, scores every answer with metrics and compares the run with a committed baseline.
It is a library that you call from a test or from your own code, and a failed gate can fail the build.

What it does not do:

- it has no web interface, REST endpoints or database
- it does not generate datasets or decide what a correct answer is, you write the cases
- it does not monitor production traffic, it evaluates the cases you give it
- it does not test statistical significance
- it calls no model on its own, only your target and the judge you configure do

## Quick start

Planned Maven coordinates are `dev.agentworkshq:eval-framework-spring-boot-starter:0.1.0-SNAPSHOT`. Artifacts are not on Maven Central yet, see [Building from source](#building-from-source).

Add the starter and, for tests, the JUnit module:

```xml
<dependency>
    <groupId>dev.agentworkshq</groupId>
    <artifactId>eval-framework-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>dev.agentworkshq</groupId>
    <artifactId>eval-framework-junit</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

A complete test with a fake target and deterministic metrics. The dataset file is `src/test/resources/eval-datasets/support.yaml`:

```java
@SpringBootTest
class SupportBotEvalTest {

    @Autowired
    EvalSuite suite;

    @Autowired
    EvalFrameworkProperties properties;

    @Test
    void supportBotMeetsTheGate() {
        EvalTarget target = new EvalTarget() {
            public String name() {
                return "support-bot";
            }

            public TargetOutput run(EvalCase evalCase) {
                return new TargetOutput("Reset it under Settings.", true, List.of(), List.of(), Duration.ZERO, Map.of());
            }
        };
        EvalRequest request = EvalRequest.defaults()
                .withRunConfig(properties.runner().toRunConfig().withRunId("support-" + System.getenv("BUILD_NUMBER")))
                .withThresholds(Thresholds.defaults().withMinPassRate("answer_contains", 1.0));

        EvalOutcome outcome = suite.evaluate("support.yaml", target,
                List.of(new AnswerContainsMetric(), new LatencyMetric(Duration.ofSeconds(2))), request);

        EvalAssertions.assertGate(outcome);
    }
}
```

See [JUnit and Spring Boot](#junit-and-spring-boot) for what the suite does and [Configuration reference](#configuration-reference) for the properties.

## Concepts

| Concept | Meaning |
| --- | --- |
| Dataset | a named list of cases: a question and what you expect of the answer |
| Target | the system under test, an `EvalTarget` that turns one case into a `TargetOutput` |
| Metric | scores one output against its case as `PASSED`, `FAILED`, `ERROR` or `SKIPPED` |
| Run | the results of one dataset against one target with a list of metrics |
| Baseline | a saved run that you accepted and committed, later runs are compared with it |
| Gate | the pass or fail decision from thresholds and the comparison with the baseline |

## Datasets

A dataset is a file inside a configured dataset directory. Three formats are supported.

YAML (`.yaml`, `.yml`):

```yaml
name: support-faq
cases:
  - id: refund-1
    question: How long do refunds take?
    expectedAnswer: Five business days.
```

JSON (`.json`):

```json
{"name": "support-faq", "cases": [{"id": "refund-1", "question": "How long do refunds take?"}]}
```

JSONL (`.jsonl`), one case per line, blank lines skipped. The dataset name is the file name without its extension:

```
{"id": "refund-1", "question": "How long do refunds take?", "expectNoAnswer": false}
```

A case has `id` and `question` (required strings) and the optional fields `expectedAnswer` (string), `expectedSources` (list of strings), `expectNoAnswer` (boolean), `tags` (list of strings), `attributes` (map of string to string), `mustContain` and `mustNotContain` (lists of strings). Case ids must be unique within a dataset. Dataset names, case ids and run ids start with a letter or digit, then use letters, digits, `.`, `_` and `-`, at most 100 characters.

`mustContain` and `mustNotContain` are phrases checked against the answer by the `answer_contains` and `answer_not_contains` metrics. Each list has at most 20 entries, each entry is not blank, at most 500 characters, and unique. Both lists are stored sorted. `expectNoAnswer` cannot be combined with a non-empty `mustContain`, `expectedAnswer` or `expectedSources`.

Parsing is strict:

- Types are not coerced. A number where a string is expected is an error, so quote values such as `"001"`.
- Unknown fields, duplicate keys, YAML anchors, aliases and explicit tags such as `!!int` are rejected.
- Input must be valid UTF-8. A leading BOM is ignored.
- Paths must be relative to the dataset directory. Absolute paths, drive letters, `..` segments and symlinks that leave the directory are rejected.

Limits, all configurable through `DatasetLimits`: 5 MB per file, 1000 cases, 64 KB per JSONL line, nesting depth 20. A file that goes over a limit is rejected while it is read.

Datasets can contain real data, see [Security and privacy](#security-and-privacy).

## Metrics

Every metric returns `PASSED`, `FAILED`, `ERROR` or `SKIPPED` with a score between 0 and 1 (no score for `ERROR` and `SKIPPED`) and a short reason. The reasons of the built-in metrics contain only ranks, counts, 1-based indexes, scores and exception class names. They never contain text from the dataset, the question, the answer or retrieved chunks.

Source matching: entries are trimmed, backslashes become slashes and one leading `./` is dropped. An expected source matches a chunk when it equals the chunk's `source` or `documentId` exactly, case-sensitive. A chunk with neither is never relevant. Chunks keep the target's order as their rank, scores are never used to re-sort.

Text matching for the contains metrics: Unicode NFKC, lowercase, runs of whitespace collapsed to one space, trimmed.

| Metric | Name | Needs from the case | Skipped when | Rule |
| --- | --- | --- | --- | --- |
| `HitAtKMetric(k)` | `hit_at_<k>` | `expectedSources` | `expectNoAnswer` or no expected sources | 1.0 if any of the first k chunks is relevant |
| `RecallAtKMetric(k, threshold = 1.0)` | `recall_at_<k>` | `expectedSources` | same | distinct expected sources covered by the first k chunks, divided by the number of distinct expected sources |
| `PrecisionAtKMetric(k, threshold)` | `precision_at_<k>` | `expectedSources` | same | relevant chunks among the first min(k, retrieved) divided by that count, duplicates of a document each count |
| `ReciprocalRankMetric(k, threshold = 0.5)` | `mrr_at_<k>` | `expectedSources` | same | 1 / rank of the first relevant chunk within k, else 0. The mean over cases is the MRR |
| `AnswerContainsMetric` | `answer_contains` | `mustContain` | `mustContain` is empty | fraction of phrases present, passes only when all are |
| `AnswerNotContainsMetric` | `answer_not_contains` | `mustNotContain` | `mustNotContain` is empty | fraction of phrases absent, passes only when all are |
| `RegexMetric(name, pattern, mode)` | your name | nothing | never | `MUST_MATCH` or `MUST_NOT_MATCH`, score 1.0 or 0.0. A timeout (default 200 ms) or an answer over 50000 characters gives `ERROR` |
| `NoAnswerMetric` | `no_answer` | `expectNoAnswer` | never | passes when `answered` equals `!expectNoAnswer` |
| `CitationMetric(threshold = 1.0)` | `citation` | `expectedSources` | `expectNoAnswer` or no expected sources | fraction of distinct expected sources found in `citedSources` |
| `CitationGroundingMetric` | `citation_grounded` | nothing | nothing was cited | fraction of cited entries that match a retrieved chunk, passes only at 1.0 |
| `LatencyMetric(max)` | `latency` | nothing | never | passes when latency is at most `max` |

The retrieval metrics are `FAILED` with score 0.0 when nothing was retrieved. `k` is 1 to 100 and thresholds are 0 to 1.

The `RegexMetric` deadline is 1 millisecond to 10 seconds. `RegexMetric` checks it only when the matcher reads characters. It is a safety net, not a hard real-time limit. Patterns must always come from code, never from datasets or users.

## LLM judge metrics

**Warning: the question, the answer and the retrieved chunks are sent to the judge model. With a cloud model your data leaves your machine.**

| Metric | Name | Rubric | Skipped when | Short circuit |
| --- | --- | --- | --- | --- |
| `FaithfulnessMetric` | `faithfulness` | is every claim of the answer supported by the retrieved context | `expectNoAnswer`, output not answered, blank answer | `FAILED` with 0.0 and "no retrieved context" when nothing (or only blank text) was retrieved, no judge call |
| `AnswerRelevancyMetric` | `answer_relevancy` | does the answer address the question | same | none |
| `CorrectnessMetric` | `correctness` | does the answer agree with the expected answer | no `expectedAnswer` or `expectNoAnswer` | none |

Constructor arguments: the `JudgeModel`, `repeats` (1 to 5, default 1), `passScore` (1 to 5, default 4) and optional `JudgeLimits` with the maximum characters per section (question 2000, answer 6000, retrieved context 12000 in total, expected answer 4000). Rubric text lives only in code and is never read from datasets or users.

Scoring: the judge gives an integer s from 1 to 5. With several repeats the mean is used. The metric score is (mean - 1) / 4, so 1 gives 0.0, 3 gives 0.5 and 5 gives 1.0. The metric passes when the mean is at least `passScore`. The reason has only numbers, for example `judge scores 4,4,5 mean 4.33 pass score 4`, plus `input truncated` when a section was cut.

Budget: every run has `maxJudgeCalls`. A metric first decides whether to skip or short circuit, which costs nothing, then takes `repeats` calls from the budget all at once. If they are not available the result is `ERROR` with "judge call budget exhausted", and no call is made. It is an error and not a failure or a skip because the case was not judged: a silent zero or pass would hide that the run hit its cost limit. With one worker, cases run in id order, so the earlier cases get the budget.

Hardening:

- Each data section is wrapped in begin and end markers that contain a fresh random nonce for every prompt. Marker-like text inside the data is neutralized, and the system prompt says everything between the markers is untrusted data that must never be followed as instructions.
- Sections are cut at their maximum length, never inside a surrogate pair.
- The reply must be exactly one JSON object (one markdown code fence is allowed) with an integer `score` from 1 to 5. Anything else, including trailing text, duplicate keys, a decimal or a string score, or a reply over 2000 characters, makes the metric `ERROR` with "invalid judge reply". Only the integer score is used. Other keys and the judge's text are never stored, logged or put in a reason.
- A judge that throws gives `ERROR` with the exception class name only. A result is never built from partial repeats.

Advice: use a judge model that differs from the target model, keep the temperature at 0 (`ChatClientJudgeModel` does), and raise `repeats` when scores look noisy. `perMetricTimeout` must be at least `repeats` times the judge latency, otherwise the metric ends as `ERROR` "metric timed out".

```java
JudgeModel judge = new ChatClientJudgeModel(chatClientBuilder, "gpt-4o-mini");
List<Metric> metrics = List.of(
        new FaithfulnessMetric(judge, 3, 4),
        new AnswerRelevancyMetric(judge),
        new SpringAiEvaluatorMetric("spring_ai_relevancy", new RelevancyEvaluator(chatClientBuilder)));
EvalRun run = new EvalRunner().run(dataset, target, metrics,
        RunConfig.defaults().withMaxJudgeCalls(200).withPerMetricTimeout(Duration.ofSeconds(120)));
```

`ChatClientJudgeModel` sends one system and one user message as message objects, so braces in the text are not treated as template variables. The identifier ends up in run files: at most 100 characters, no control characters, no URL and nothing that looks like a key or token.

`SpringAiEvaluatorMetric(name, evaluator)` wraps any Spring AI `Evaluator`. It spends one judge call per case, gives 1.0 for a pass and 0.0 for a fail, is skipped when the output was not answered, and turns an exception into `ERROR` with the class name only. **It uses Spring AI's own prompt template, so it does not get the nonce delimiters or the strict reply parsing described above.** Prefer the three metrics above for untrusted data.

## Runner

`EvalRunner.run(dataset, target, metrics, config)` runs every selected case against the target, evaluates the metrics and returns an `EvalRun`. A `RunListener` can be passed as a fifth argument. It is called from worker threads once per case with the result, the number of finished cases and the total, including cases skipped by a budget. An exception thrown by a listener is swallowed.

`RunConfig` fields, each with `with...` methods that return a new instance:

| Field | Default | Rule |
| --- | --- | --- |
| `runId` | generated | `<datasetName>-<UTC yyyyMMdd'T'HHmmssSSS>` from the clock, the dataset name is cut so the id has at most 100 characters |
| `maxCases` | 1000 | 1 to 100000. A selection above it is rejected, a dataset is never truncated |
| `includeTags` | empty (all cases) | a case is selected when it has at least one of the tags |
| `concurrency` | 1 | 1 to 16, the most target calls running at once |
| `perCaseTimeout` | 60 s | time box for one target call |
| `perMetricTimeout` | 60 s | time box for one metric call |
| `totalTimeout` | 30 min | after it no new case is started |
| `maxAttempts` | 1 | 1 to 5, target calls only |
| `retryDelay` | 0 | wait between attempts |
| `maxAbandonedCalls` | `concurrency` | at least 1 |
| `maxJudgeCalls` | 500 | 0 to 100000, the most LLM judge calls in one run |
| `labels` | none | at most 20 entries, keys and values at most 200 characters |

Timeouts must be at least 1 millisecond, `retryDelay` may be zero, and no duration may be longer than 365 days.

Behaviour:

- Cases are sorted by id and results are collected by id, so the order of `caseResults` never depends on which case finished first. The same input with the same clock gives an equal `EvalRun`.
- The target must be thread safe when `concurrency` is above 1.
- A target that throws is retried up to `maxAttempts` times with `retryDelay` between attempts, then the case is `TARGET_FAILED`. A timeout or an interrupt is never retried. A target that returns null counts as a failure. A retry delay never lasts past `totalTimeout`, and when the deadline has passed before the next attempt the case is `SKIPPED_BUDGET`.
- A target that exceeds `perCaseTimeout` gives `TIMED_OUT` and its thread is interrupted. The runner cannot kill a thread that ignores interrupts. If the call is still running 1 second after the interrupt it is counted as abandoned and it is no longer counted against `concurrency`. When abandoned calls exceed `maxAbandonedCalls`, no new case is started, every case that has not started is `SKIPPED_BUDGET` and the run finishes normally. Metric calls that ignore their time box are counted the same way.
- A metric that throws, returns null, returns a result with another name or exceeds `perMetricTimeout` becomes an `ERROR` result. The other metrics still run and the case stays `COMPLETED` with its output.
- After `totalTimeout` the remaining cases are `SKIPPED_BUDGET` with no output, no metric results and a zero duration. Running attempts finish within their own time boxes.
- If the calling thread is interrupted, the run is cancelled, the interrupt flag is restored and a `ServiceException` (`EVAL_E016`) is thrown. The runner creates daemon threads named `eval-runner-*` and always shuts them down.
- `errorMessage` and the error reasons written by the runner contain the exception class name and the attempt count or timeout only, never an exception message, so they cannot leak the question or the answer.

### Run config

An `EvalRun` carries a config map that ends up in run files and reports. It contains exactly these entries: `runner.concurrency`, `runner.perCaseTimeoutMs`, `runner.perMetricTimeoutMs`, `runner.totalTimeoutMs`, `runner.maxAttempts`, `runner.maxCases`, `runner.includeTags` (sorted, comma separated), `runner.selectedCases`, `runner.abandonedCalls`, `runner.maxJudgeCalls`, `runner.judgeCallsUsed`, `dataset.caseCount`, `metrics` (sorted names, comma separated), `library.version`, one `label.<key>` per label, one `metric.<metricName>.<key>` per entry of `Metric.runConfig()` and one `target.<key>` per entry of `EvalTarget.describe()`.

`library.version` is the implementation version from the library jar. It is `unknown` when the classes do not come from a jar with that manifest entry, for example from a class directory.

Label, metric and target keys are split into words on non-alphanumeric characters and camelCase boundaries, and a key is rejected when any word is one of `key`, `secret`, `token`, `password`, `passwd`, `authorization`, `credential`, `credentials`, `apikey` or `bearer`. So `api-key` and `db_password` are rejected, while `max-tokens`, `monkey` and `top-k` are allowed. Values are at most 200 characters. A run with a rejected entry does not start, and the message names the key. This check is only a safety net. A metric or target must only report known, non-secret settings.

If `describe()` has a `model` entry equal to the `judge` entry of a metric, the runner logs one warning, because a model should not grade its own answers.

## Run store

A run is saved as a `StoredRun`, a copy of an `EvalRun` that keeps only safe fields: ids, statuses, durations, answer length, latency, number of retrieved chunks and the metric results. `StoredRun.from(evalRun)` does the copy.

`FileRunStore(root, StoreLimits)` keeps one file per run, named `run-<runId>.json`, in the root directory. The root is created when missing and must not be a regular file. Other files in the directory are ignored. Every path is resolved to its real path and must stay inside the root, so a symbolic link that leaves the root is rejected.

File format (`formatVersion` 1): a JSON object with `formatVersion`, `runId`, `datasetName`, `datasetFingerprint`, `targetName`, `startedAt`, `finishedAt`, `config` and `cases`. Each case has `caseId`, `status`, `errorMessage`, `durationMs`, `answered`, `answerChars`, `latencyMs`, `retrievedCount` and `metricResults` (`metricName`, `status`, `score`, `reason`). Fields are written in a fixed order with a 2 space indent and LF line endings on every OS. Instants are ISO-8601 UTC strings, durations are integer milliseconds and scores use the shortest form that reads back to the same double.

Reading is strict, like the dataset loader: an unknown `formatVersion`, an unknown or duplicate field, a wrong type (a string where a number is expected), a number that is out of range, trailing content, invalid UTF-8, a document that is too deep or too large, all fail with an error that names the file and, for a syntax error, the line and column. A byte order mark is accepted. Everything read goes through the record constructors again, so a hand edited file cannot create an invalid run.

| Limit | Default |
| --- | --- |
| `maxFileBytes` | 20 MB |
| `maxRuns` (most run files `list` and `latest` will scan) | 10000 |
| `maxNestingDepth` | 20 |

Files are never overwritten. `save` fails when the run id already exists, also on a case insensitive file system where two ids differ only by case. It writes a temporary file in the same directory, flushes it to disk and moves it into place without replacing anything. `list` is sorted by start time then run id and fails, naming the file, when one file is corrupt. `latest(datasetName, targetName)` returns the run that started last, ties go to the greater run id.

Concurrency: one process is protected by a lock, so two threads saving the same run id never both succeed. Two separate processes saving the same run id at the same moment can overwrite each other on Linux, because the JDK checks for the file and then renames it. On Windows the move itself refuses to replace an existing file. In CI give every run a unique run id, for example with the build number.

### Baseline workflow

1. Run the evaluation once with a fixed run id (`RunConfig.withRunId`) and save it to an output directory outside `src`. Run ids must be valid names.
2. Copy the run file you accept as the baseline into `src/test/resources/eval-baselines/` and commit it. With `EvalSuite` the file is `target/eval-runs/run-<id>.json`.
3. In a test, run again and compare. With `EvalSuite`, set the id in the request: `request.withBaselineRunId("support-baseline")`.

Without the suite, load and compare the runs yourself:

```java
EvalRun run = new EvalRunner().run(dataset, target, metrics, RunConfig.defaults());
RunStore store = new FileRunStore(Path.of("target/eval-runs"), StoreLimits.defaults());
store.save(StoredRun.from(run));

// later, in a test, once the file is committed
RunStore baselines = new FileRunStore(Path.of("src/test/resources/eval-baselines"), StoreLimits.defaults());
Comparison comparison = RunComparator.compare(
        baselines.load("support-bot-baseline"), StoredRun.from(run), ComparisonConfig.defaults());
assertThat(comparison.passesGate()).as(comparison.violations().toString()).isTrue();
```

## Regression gate

`RunSummary.of(storedRun)` gives the case counts per status and one `MetricAggregate` per metric. For each metric:

- Mean score: the mean of the scores of `PASSED` and `FAILED` results. It is empty when there are none.
- Pass rate: `PASSED` divided by `PASSED` plus `FAILED` plus `ERROR`. It is empty when that sum is 0. An `ERROR` counts against the pass rate because the case was not judged. `SKIPPED` never counts.

`RunComparator.compare(baseline, candidate, ComparisonConfig)` pairs the cases by id and returns a `Comparison` with metric verdicts, case changes and a list of violations. `passesGate()` is true when there are no violations. Runs of different datasets, or of different dataset content while `requireSameDataset` is true, are an error. With `requireSameDataset` false only the common cases are compared and the extra case ids are listed.

A metric is `REGRESSED` when its mean score or pass rate drops by more than the tolerance, or when it has more errors than before. A case change is a `REGRESSION` (passed to failed or error, or a case that no longer completes), a `FIX`, a `SCORE_DROP` or `SCORE_GAIN` beyond the tolerance, or `COVERAGE_LOST` and `COVERAGE_GAINED` (scored to skipped and back). One metric result gives at most one change, a status change wins over a score change. `maxCaseRegressions` (default 0) allows that many `REGRESSION` changes, every `SCORE_DROP` is a violation. `minPassRate` sets an absolute floor on the candidate for a metric.

The violation for too many case regressions lists them as `caseId/metricName`, sorted by case id and metric name, for example `case regressions: 2 (allowed 0): refund-1/answer_contains, refund-2`. An entry without a metric name is a case that no longer completes. At most 10 entries are listed, followed by `and N more`. The violation for a regressed metric gives the baseline and candidate mean score and pass rate, the error counts and the tolerance.

Tolerance is the allowed drop before something counts as a regression, with a small epsilon. Use 0.0 for the deterministic metrics. Use 0.25 for the judge metrics that score 1 to 5, because one rubric step is 0.25 and a single step of judge noise should not fail the build. Set it per metric with `withMetricTolerance`.

A metric is `NOT_COMPARABLE` when its config values `metric.<name>.promptVersion`, `metric.<name>.judge` or `metric.<name>.repeats` differ between the two runs, and a key missing on one side counts as different. A different judge model, prompt or number of repeats changes what a score means, so comparing the two would measure the judge and not your system. A not comparable metric gets no regression verdict and no case changes, and fails the gate unless `failOnIncomparable` is false. Record a new baseline after changing the judge.

## Reports

Reports are pure functions that return strings. They take only a `StoredRun` based `RunSummary` and a `Comparison`, never an `EvalRun`.

| Report | Content |
| --- | --- |
| `ConsoleReport.render(summary, comparison, options)` | Plain ASCII tables, no colors, LF line endings: run header, metric table (counts, mean score, pass rate and, with a comparison, baseline mean, delta and verdict), `GATE: PASSED` or `GATE: FAILED` with every violation, config changes and the case changes (regressions first, then score drops, fixes, gains, coverage) |
| `MarkdownReport.render(summary, comparison, options)` | The same sections as GitHub tables, starting with the hidden marker `<!-- eval-framework-report -->` and a level 3 heading with the gate result |
| `ComparisonJson.render(comparison, options)` | A JSON document for CI artifacts with `formatVersion` 1, verdicts, aggregates, case changes, violations and the gate result |

The comparison can be `null`, then the report has only the run header and the metric table. `ReportOptions` has `maxCaseRows` (50, 1 to 500, the JSON lists at most that many case changes and also gives the total), `maxChars` (60000, 1000 to 65000, Markdown only), `includeRunConfig` (false) and `maxFieldChars` (80, 10 to 200). The Markdown text is cut at a line boundary, never inside a table row, and ends with a line saying how many characters were left out.

```java
StoredRun baseline = new FileRunStore(Path.of("src/test/resources/eval-baselines"), StoreLimits.defaults())
        .load("support-bot-baseline");
StoredRun candidate = StoredRun.from(run);
Comparison comparison = RunComparator.compare(baseline, candidate, ComparisonConfig.defaults());
String markdown = MarkdownReport.render(RunSummary.of(candidate), comparison, ReportOptions.defaults());
new ReportWriter(Path.of("target/eval-reports")).write("eval-report.md", markdown);
```

`ReportWriter` creates the directory, accepts file names that match `[A-Za-z0-9][A-Za-z0-9._-]{0,99}` and end in `.md`, `.txt` or `.json`, rejects symbolic links that leave the directory, and replaces an existing report because reports are regenerated.

A CI step can post the Markdown file as a pull request comment with the GitHub CLI. The hidden marker lets it update its earlier comment instead of adding a new one. This is a documentation snippet, the library ships no workflow file:

```yaml
- name: Comment on the pull request
  env:
    GH_TOKEN: ${{ github.token }}
  run: |
    id=$(gh api repos/${{ github.repository }}/issues/${{ github.event.pull_request.number }}/comments \
      --jq ".[] | select(.body | startswith(\"<!-- eval-framework-report -->\")) | .id" | head -n 1)
    if [ -n "$id" ]; then
      gh api --method PATCH repos/${{ github.repository }}/issues/comments/$id -F body=@target/eval-reports/eval-report.md
    else
      gh pr comment ${{ github.event.pull_request.number }} --body-file target/eval-reports/eval-report.md
    fi
```

Escaping and limits:

- In Markdown every dynamic value (run ids, dataset and target names, case ids, metric names, config keys and values, violations) is shown inside a code span. The fence is longer than any backtick run in the text and a pipe in a table cell is escaped.
- Control characters, line and paragraph separators, bidirectional controls and zero width characters in these values are replaced by a space. Every value is cut at `maxFieldChars`, a violation at 1900 characters.
- The Markdown has no images, links or mentions. The only raw HTML is the marker comment and the static `<details>` and `<summary>` tags.
- The Markdown size is capped at `maxChars`.

## JUnit and Spring Boot

The starter auto-configures an `EvalSuite`. `EvalSuite.evaluate` loads the dataset, runs it, saves the run file, compares it with the baseline when the request has a `baselineRunId`, checks the thresholds and renders the reports. The baseline is loaded and its dataset name, and its dataset content while `requireSameDataset` is true, are checked before the target or any metric is called, so a mismatch starts no run and writes no file. The gate fails when there is any violation. An exhausted judge budget is a metric error and fails the gate on purpose (`maxMetricErrors` is 0 by default). A threshold on a metric that has no pass rate or mean (all skipped, or absent) is a violation, never a silent pass.

`Thresholds`: `minPassRate` and `minMeanScore` per metric name (0 to 1), `maxIncompleteCases` (cases that are `TARGET_FAILED`, `TIMED_OUT` or `SKIPPED_BUDGET`, default 0) and `maxMetricErrors` (default 0).

`EvalAssertions.assertGate(outcome)` throws an `AssertionFailedError` whose message is the console report, cut at 20000 characters. `EvalAssertions.assertThat(outcome)` gives `passesGate()`, `metricPassRateAtLeast`, `metricMeanScoreAtLeast`, `noCaseRegressions()`, `noMetricErrors()` and `allCasesCompleted()`. Their messages are built from run ids, case ids, metric names, numbers and violations.

Files written by the suite, with `<id>` the run id: the run file `run-<id>.json` in the store directory, and `eval-report-<id>.md` and `eval-report-<id>.json` in the report directory. A run id longer than 83 characters is cut in the report names, and `-` plus the first 8 hex characters of its SHA-256 are appended, so that the file name stays within 100 characters and run ids that differ only after the cut get different names. The JSON report is only written when there is a baseline comparison.

The baseline directory is opened read-only: a missing directory or file is an error and a run never creates the folder. In CI use a unique run id for every run, because a run id that already exists is never overwritten.

Setting `eval-framework.judge.identifier` creates a `JudgeModel` bean that uses the application's default `ChatClient.Builder`. The context starts without a builder, and the first judge call then fails with a clear error. To use a different judge model than the one your application uses, define your own `JudgeModel` bean, it replaces the auto-configured one.

## Example with the RAG engine

`eval-framework-rag-adapter` turns a `RagService` from [spring-ai-rag-engine](https://github.com/agentworkshq/spring-ai-rag-engine) into an `EvalTarget`. It is not part of the starter, add it yourself:

```java
RagEngineTarget target = RagEngineTarget.of(ragService, RagTargetOptions.defaults().withTopK(3));
```

The tenant, the source filter and `topK` come from `RagTargetOptions`, never from a dataset case.

### Adapter requirements

- `rag-engine.answer.cite-sources` must be `true`, which is the default. `RagEngineTarget` builds the retrieved chunks from the citations of `RagAnswer`. With `false` nothing is retrieved, so the retrieval metrics and the `citation` metric fail, `faithfulness` fails with "no retrieved context" and `citation_grounded` is skipped.
- Source filter entries are rag-engine source names, not file names.
- The documents must be ingested for the tenant in `RagTargetOptions` before the run.

A citation carries a snippet of at most 200 characters, not the whole chunk. Judge metrics such as faithfulness therefore judge against truncated context and can report supported claims as unsupported.

### The offline sample

`eval-framework-sample` is a complete offline example with fake embedding and chat models, so it needs no model server, API key or Docker. Install rag-engine first, see [Building from source](#building-from-source), then run:

```bash
./mvnw -pl eval-framework-sample -am verify
```

`GoldenRagEvalTest` runs 15 golden cases against the real ingestion and retrieval code and compares each run with a committed baseline. It shows that:

- the healthy target passes the gate and reproduces the baseline scores
- a target limited to the troubleshooting document fails the gate, and the report names the metrics and cases that got worse
- the run file and the reports contain no question, answer or document text
- two runs of the same target give the same results

The scores say nothing about the quality of a real system: the fake embedding model only matches shared words, and the fake chat model repeats the first sentence of the best chunk.

To run the same dataset against real models, outside the offline test configuration:

- add a Spring AI model starter and a vector store, and leave out the fake `EmbeddingModel`, `VectorStore` and `ChatClient.Builder` beans
- set the provider properties, for Ollama `spring.ai.ollama.base-url`, `spring.ai.ollama.chat.model` and `spring.ai.ollama.embedding.model`
- retune `rag-engine.chunking.chunk-size`, `rag-engine.chunking.overlap`, `rag-engine.retrieval.top-k` and `rag-engine.retrieval.similarity-threshold`, the sample values fit only the fake embedding model
- record the model names with `RagTargetOptions.withDescription` so that they show up in run files
- replace the `mustContain` expectations if your model paraphrases, record a new baseline and loosen the thresholds to what your models reliably reach
- to add judge metrics, set `eval-framework.judge.identifier` to a model other than the one under test

## Configuration reference

Relative paths are resolved against the working directory. None of the directories is created when the application starts. The `runner` properties apply to a run only when you build its config from them with `properties.runner().toRunConfig()`, `EvalRequest.defaults()` does not read them.

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `eval-framework.enabled` | boolean | `true` | `false` creates no beans |
| `eval-framework.dataset.root` | String | `src/test/resources/eval-datasets` | directory that dataset paths are relative to |
| `eval-framework.dataset.max-file-bytes` | int | `5242880` | largest dataset file |
| `eval-framework.dataset.max-cases` | int | `1000` | most cases in one dataset file |
| `eval-framework.baseline.dir` | String | `src/test/resources/eval-baselines` | directory of the committed baseline run files, only read |
| `eval-framework.store.enabled` | boolean | `true` | `false` creates no `RunStore`, so the suite saves no run file |
| `eval-framework.store.dir` | String | `target/eval-runs` | directory for run files |
| `eval-framework.report.enabled` | boolean | `true` | `false` creates no `ReportWriter`, so the suite writes no report files |
| `eval-framework.report.dir` | String | `target/eval-reports` | directory for report files |
| `eval-framework.runner.concurrency` | int | `1` | most target calls at once, 1 to 16 |
| `eval-framework.runner.per-case-timeout` | Duration | `60s` | time box for one target call |
| `eval-framework.runner.per-metric-timeout` | Duration | `60s` | time box for one metric call |
| `eval-framework.runner.total-timeout` | Duration | `30m` | after it no new case is started |
| `eval-framework.runner.max-attempts` | int | `1` | target attempts per case, 1 to 5 |
| `eval-framework.runner.max-cases` | int | `1000` | most selected cases in one run, 1 to 100000 |
| `eval-framework.runner.max-judge-calls` | int | `500` | most judge calls in one run, 0 to 100000 |
| `eval-framework.judge.identifier` | String | none | judge model name, creates a `JudgeModel` bean |

The same properties as YAML with their defaults:

```yaml
eval-framework:
  enabled: true
  dataset:
    root: src/test/resources/eval-datasets
    max-file-bytes: 5242880
    max-cases: 1000
  baseline:
    dir: src/test/resources/eval-baselines
  store:
    enabled: true
    dir: target/eval-runs
  report:
    enabled: true
    dir: target/eval-reports
  runner:
    concurrency: 1
    per-case-timeout: 60s
    per-metric-timeout: 60s
    total-timeout: 30m
    max-attempts: 1
    max-cases: 1000
    max-judge-calls: 500
  judge:
    identifier:   # optional, creates a JudgeModel bean
```

## Security and privacy

Report vulnerabilities as described in [SECURITY.md](SECURITY.md).

### What the library does

- Run files and reports never hold question, answer or chunk text. `StoredRun` copies only safe fields, and reports are built from it.
- The library sends case text only to your target and, for judge metrics, to the judge model. A custom `Metric`, `EvalTarget` or `RunListener` sees the case text: a target gets the case, a metric gets the case and the output, and a listener gets the result with the answer and the retrieved chunks. Any code that holds the `EvalRun` sees the outputs too.
- Questions, answers and chunk text are never logged by the library. Exception messages from targets, metrics and judges are not copied into results, only class names.
- Judge prompts put every piece of case text between markers with a random nonce, tell the judge to ignore instructions inside them and accept only a strict JSON reply, see [LLM judge metrics](#llm-judge-metrics). `SpringAiEvaluatorMetric` is the exception.
- Paths are confined. Datasets are read only from inside the dataset directory, the baseline directory is only read, and run files and reports are written only inside their configured directories. Symbolic links that leave a directory are rejected.
- Limits apply to dataset files, case fields, run files, judge prompts and replies, regular expressions, reports, run time, cases per run and judge calls per run.
- The `toString` methods of the library's records print validated ids, statuses, numbers and counts, never free text such as case text, reasons, labels or config values. Exceptions print their message.
- Names, keys, ids and paths that an exception message or the model warning repeats have control and bidirectional characters removed and are cut to 64 characters.
- Config keys that look like a credential and judge identifiers that look like a credential or a URL are rejected.
- The library never calls `System.exit`.

### What the library cannot protect against

- What a target, judge or model provider does with the data it receives.
- Prompt injection as a whole. Delimiters and strict parsing make it harder, a judge model can still be influenced by the text it grades.
- Your own code. A custom target, metric or listener can log or write anything. The reason, metric name and run config entries of a custom metric, and the name and `describe()` entries of a target, are copied into run files and reports as they are.
- Secrets that you put into labels, config values, descriptions, names, case ids or tags. They are free text, appear in run files, and appear in reports in the config changes table and, when `includeRunConfig` is true, in the run config. Never put secrets in them.
- Dataset files. They hold your questions and expected answers in plain text. Do not commit datasets that contain personal data.
- Exception causes. They can contain fragments of dataset content, for example a key name, and absolute paths. Do not send stack traces to untrusted places.
- Logging by other libraries, such as Spring AI or an HTTP client, which can include prompts when configured to.
- Anyone who can change the committed baseline, the dataset or the test code. Run files are not signed, so they decide what the gate accepts.
- A process with write access to the configured directories that changes files while they are being checked and opened.
- Code that ignores interrupts. The target and metrics run inside your JVM without a sandbox.

Run files and reports go under `target/` by default so they are not committed.

## Known limitations

- No statistical significance test. The gate compares paired cases and relies on tolerances and on `repeats`. With a small dataset a small tolerance will flag noise, and a large tolerance can hide a real drop.
- LLM judges are noisy. Use `repeats` and a tolerance.
- The auto-configured judge uses the default `ChatClient.Builder` unless you supply your own `JudgeModel`.
- The regular expression deadline is a safety net, not a hard limit.
- Abandoned calls cannot be killed.
- `totalTimeout` stops new cases and new attempts from starting. A running attempt and the metrics of its case can end after it.
- Custom metrics and targets are trusted. The reason and metric name of a metric and the `describe()` values of a target are stored as given, so they must never contain case text.
- Path checks do not protect against file races or hard links in the configured directories.
- Config differences in a comparison, for example a new `library.version`, are informational and do not fail the gate. The exception is the judge, prompt version and repeats of a judge metric, see [Regression gate](#regression-gate).
- The file run store protects only one process on Linux.
- Run files are not tenant scoped.
- Runs are synchronous, the caller waits for the whole run.
- The RAG engine adapter sees only snippets of at most 200 characters.
- Results are only as good as the dataset.

## Roadmap

Not done yet:

- a JPA run store
- an HTML report
- more adapters, such as one for a plain `ChatClient`
- changes in rag-engine that would remove the snippet limit of the adapter
- publishing to Maven Central

## Building from source

You need Java 21. The rag-engine artifacts are not on Maven Central yet, so install them into your local Maven repository first. This is needed for any build from the root of this repository:

```bash
git clone https://github.com/agentworkshq/spring-ai-rag-engine.git
cd spring-ai-rag-engine
./mvnw install -DskipTests
```

Then, in this repository:

```bash
./mvnw verify
```

`./mvnw install` also puts the evaluation framework into your local Maven repository, so that other projects can use it.

## License

Apache License, Version 2.0, see [LICENSE](LICENSE).
