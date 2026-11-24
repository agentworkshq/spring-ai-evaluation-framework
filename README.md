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

A case has `id` and `question` (required strings) and the optional fields `expectedAnswer` (string), `expectedSources` (list of strings), `expectNoAnswer` (boolean), `tags` (list of strings) and `attributes` (map of string to string). Dataset names and case ids must be unique within their scope. Dataset names use letters, digits, `.`, `_` and `-`, at most 64 characters.

Parsing is strict:

- Types are not coerced. A number where a string is expected is an error, so quote values such as `"001"`.
- Unknown fields, duplicate keys, YAML anchors and aliases are rejected.
- Input must be valid UTF-8. A leading BOM is ignored.
- Paths must be relative to the dataset directory. Absolute paths, drive letters, `..` segments and symlinks that leave the directory are rejected.

Limits, all configurable through `DatasetLimits`: 5 MB per file, 1000 cases, 64 KB per JSONL line, nesting depth 20. A file that goes over a limit is rejected while it is read.

Datasets can contain real data. Do not commit datasets that contain personal data.
