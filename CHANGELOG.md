# Changelog

All notable changes to this project are documented in this file. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

## [0.1.0]

Not published to Maven Central yet.

### Added

- Datasets in YAML, JSON and JSONL with strict parsing, size, case count and nesting limits, and paths confined to a dataset directory.
- Metrics: hit at k, recall at k, precision at k, reciprocal rank, answer contains, answer does not contain, regular expression, no answer, citation, citation grounding and latency.
- Judge metrics: faithfulness, answer relevancy and correctness, with nonce delimited prompts, strict JSON replies and a judge call budget. An adapter for Spring AI evaluators, which uses the Spring AI prompt.
- Runner with tag selection, concurrency, retries, per case, per metric and total time limits, a case limit and a run listener.
- Run store that keeps one JSON file per run without question, answer or chunk text, never overwrites a run and reads files strictly.
- Run comparison against a baseline with per metric tolerances, minimum pass rates, case changes and a gate with violations.
- Console, Markdown and JSON comparison reports, and a report writer confined to an output directory.
- `EvalSuite`, which loads, runs, saves, compares, checks thresholds and writes reports in one call.
- Spring Boot auto-configuration under the `eval-framework` property prefix, and a starter.
- JUnit assertions for the gate, metric pass rates and mean scores, case regressions, metric errors and incomplete cases.
- Adapter that turns the RAG engine into an evaluation target.
- Offline sample with fake models, a golden dataset and a committed baseline.
