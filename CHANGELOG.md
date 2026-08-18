# Changelog

## 0.1.0 - 2026-08-18

- Add immutable bounded SSE observer for OpenAI-compatible chat and responses
  streams.
- Handle LF, CRLF, and CR event framing, split UTF-8 chunks, terminal markers,
  and bounded error identifiers.
- Add tests, replay-safety documentation, and Leiningen metadata for Clojars.
