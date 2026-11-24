# spring-ai-evaluation-framework

Evaluation framework for Spring AI applications.

Planned Maven coordinates:

```xml
<dependency>
    <groupId>dev.agentworkshq</groupId>
    <artifactId>eval-framework-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Artifacts are not on Maven Central yet.

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
- Unknown fields, duplicate keys, YAML anchors and aliases are rejected.
- Input must be valid UTF-8. A leading BOM is ignored.
- Paths must be relative to the dataset directory. Absolute paths, drive letters, `..` segments and symlinks that leave the directory are rejected.

Limits, all configurable through `DatasetLimits`: 5 MB per file, 1000 cases, 64 KB per JSONL line, nesting depth 20. A file that goes over a limit is rejected while it is read.

Exception causes can contain fragments of dataset content, for example a key name. A host application must not send stack traces to untrusted places.

Datasets can contain real data. Do not commit datasets that contain personal data.

## Run config

An `EvalRun` carries a config map that ends up in run files and reports. Keys are split into words on non-alphanumeric characters and camelCase boundaries, and a key is rejected when any word is one of `key`, `secret`, `token`, `password`, `passwd`, `authorization`, `credential`, `credentials`, `apikey` or `bearer`. So `api-key` and `db_password` are rejected, while `max-tokens`, `monkey` and `top-k` are allowed. This check is only a safety net. The runner and adapters must add only known, non-secret settings to the run config.

## Metrics

Every metric returns `PASSED`, `FAILED`, `ERROR` or `SKIPPED` with a score between 0 and 1 (no score for `ERROR` and `SKIPPED`) and a short reason. Reasons contain only ranks, counts and 1-based indexes. They never contain text from the dataset, the question, the answer or retrieved chunks.

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

`RegexMetric` checks its deadline only when the matcher reads characters. It is a safety net, not a hard real-time limit. Patterns must always come from code, never from datasets or users.

The retrieval metrics are `FAILED` with score 0.0 when nothing was retrieved. `k` is 1 to 100 and thresholds are 0 to 1. The regex pattern must come from code, never from a dataset.

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
| `labels` | none | at most 20 entries, keys and values at most 200 characters |

Durations must be positive (`retryDelay` may be zero) and at most 365 days.

Behaviour:

- Cases are sorted by id and results are collected by id, so the order of `caseResults` never depends on which case finished first. The same input with the same clock gives an equal `EvalRun`.
- The target must be thread safe when `concurrency` is above 1.
- A target that throws is retried up to `maxAttempts` times with `retryDelay` between attempts, then the case is `TARGET_FAILED`. A timeout or an interrupt is never retried. A target that returns null counts as a failure.
- A target that exceeds `perCaseTimeout` gives `TIMED_OUT` and its thread is interrupted. The runner cannot kill a thread that ignores interrupts. If the call is still running 1 second after the interrupt it is counted as abandoned and it is no longer counted against `concurrency`. When abandoned calls exceed `maxAbandonedCalls`, no new case is started, every case that has not started is `SKIPPED_BUDGET` and the run finishes normally. Metric calls that ignore their time box are counted the same way.
- A metric that throws, returns null, returns a result with another name or exceeds `perMetricTimeout` becomes an `ERROR` result. The other metrics still run and the case stays `COMPLETED` with its output.
- After `totalTimeout` the remaining cases are `SKIPPED_BUDGET` with no output, no metric results and a zero duration. Running cases finish within their own time boxes.
- If the calling thread is interrupted, the run is cancelled, the interrupt flag is restored and a `ServiceException` (`EVAL_E016`) is thrown. The runner creates daemon threads named `eval-runner-*` and always shuts them down.
- `errorMessage` and metric error reasons contain the exception class name and the attempt count only, never an exception message, so they cannot leak the question or the answer.

The run config contains exactly these entries: `runner.concurrency`, `runner.perCaseTimeoutMs`, `runner.perMetricTimeoutMs`, `runner.totalTimeoutMs`, `runner.maxAttempts`, `runner.maxCases`, `runner.includeTags` (sorted, comma separated), `runner.selectedCases`, `runner.abandonedCalls`, `dataset.caseCount`, `metrics` (sorted names, comma separated), `library.version` (`unknown` when the package has no implementation version) and one `label.<key>` per label.

Labels are free text copied into run files and reports. Do not put secrets in them. A label key that looks like a credential is rejected.
