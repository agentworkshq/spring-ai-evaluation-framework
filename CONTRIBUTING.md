# Contributing

## Building

You need Java 21. The RAG adapter and the sample depend on rag-engine, which is not on Maven Central yet. Check out [spring-ai-rag-engine](https://github.com/agentworkshq/spring-ai-rag-engine) next to this repository and install it locally:

```bash
cd ../spring-ai-rag-engine
./mvnw install -DskipTests
```

Then, in this repository:

```bash
./mvnw verify
```

## Project rules

A contribution must follow these rules:

- No Lombok. Use records for data classes and hand written accessors elsewhere.
- Tests use no network and no real model. Use fake targets and a fake judge model.
- Never log or print questions, answers or chunk text, also not in `toString`.
- Judge prompts keep untrusted text inside the nonce delimiters, tell the judge to ignore instructions inside them and accept only a strict JSON reply. An unparsable reply is a metric error.
- A new metric needs tests for pass, fail, skip and privacy: its results must not contain case or answer text.

## Reporting vulnerabilities

Do not open a public issue for a security problem. Follow [SECURITY.md](SECURITY.md).
