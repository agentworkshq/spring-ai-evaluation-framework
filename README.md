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

The retrieval metrics are `FAILED` with score 0.0 when nothing was retrieved. `k` is 1 to 100 and thresholds are 0 to 1. The regex pattern must come from code, never from a dataset.
