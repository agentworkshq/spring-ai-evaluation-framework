# Security Policy

Report vulnerabilities privately by email to oss@agentworkshq.dev. Please do not open a public issue for a security problem.

Only the latest 0.x release is supported.

This library reads dataset and run files and calls a judge model. Reports about the following are especially welcome:

- path handling: traversal, symlinks and access outside the configured dataset or output directory
- file parsing: malformed, oversized or hostile YAML, JSON and JSONL input
- prompt injection against the judge model through answers, retrieved text or dataset text
- credentials or personal data ending up in reports, run files or logs
