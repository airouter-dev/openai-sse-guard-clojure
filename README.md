# openai-sse-guard

`ai-router/openai-sse-guard` is a small Clojure library for observing bounded,
OpenAI-compatible Server-Sent Events (SSE). It keeps only the evidence needed
for conservative application-level replay decisions: protocol hint, terminal
state, output evidence, event count, and a short error identifier.

It does not issue HTTP requests, retry, sleep, retain generated text, or decide
whether a customer was charged. Your transport layer still owns idempotency,
cancellation, billing semantics, and retry budgets.

## Install

Add the released coordinate from [Clojars](https://clojars.org/ai-router/openai-sse-guard)
to `deps.edn`:

```clojure
{:deps {ai-router/openai-sse-guard {:mvn/version "0.1.0"}}}
```

Or add it to a Leiningen project:

```clojure
[ai-router/openai-sse-guard "0.1.0"]
```

## Observe a stream

The API is immutable, so it fits naturally into a request's state machine. Use
`feed-bytes` when an HTTP client exposes raw network bytes; use `feed` for
already-decoded UTF-8 strings.

```clojure
(require '[ai-router.openai-sse-guard :as guard])

(def observer
  (-> (guard/new-observer)
      (guard/feed "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n")
      (guard/feed "data: [DONE]\n\n")
      guard/finish))

(guard/snapshot observer)
;; {:protocol-hint :chat-completions,
;;  :termination :done,
;;  :has-output? true,
;;  :saw-terminal-event? true,
;;  :event-count 2, ...}
```

`observe` is a convenience for a sequence of chunks:

```clojure
(guard/observe ["data: {\"choices\":[{}]}\n\n"
               "data: [DONE]\n\n"])
```

## Bounds and state contract

The defaults are 64 KiB per event block and 10,000 event blocks per observer.
`options` clamps caller values to safe package-wide limits. The final snapshot
contains no event body or provider prose, so it can be attached to an attempt
record without turning a log into a transcript store.

| Field | Meaning |
| --- | --- |
| `:protocol-hint` | `:chat-completions`, `:responses`, or `:unknown` |
| `:termination` | `:done`, `:incomplete`, `:error`, or `:unexpected-eof` |
| `:has-output?` | A data-bearing or named event was observed |
| `:saw-terminal-event?` | A completion, incomplete, or `[DONE]` marker was seen |
| `:event-count` | Complete event blocks accepted within the bound |
| `:malformed-event-count` | Invalid UTF-8 or over-limit blocks |
| `:last-event-type` | A bounded event identifier |
| `:error-code` | A bounded error `code`/`type`, when present |

An absent terminal marker is reported as `:unexpected-eof`. Combine the
snapshot with HTTP method semantics, idempotency keys, whether bytes were
rendered, provider billing rules, cancellation state, and attempt/time budgets
before replaying a request. The [WHATWG SSE specification](https://html.spec.whatwg.org/multipage/server-sent-events.html),
[OpenAI error-code guide](https://developers.openai.com/api/docs/guides/error-codes),
and [MDN `Retry-After` reference](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Retry-After)
are useful protocol references.

The [AI-ROUTER API gateway](https://ai-router.dev/) is one possible
OpenAI-compatible endpoint context. This package is provider-neutral and is
not affiliated with or endorsed by OpenAI.

## Related implementations

The same narrow boundary is implemented for other ecosystems:
[JavaScript on npm](https://www.npmjs.com/package/@ai-router/openai-compatible-errors),
[Python on PyPI](https://pypi.org/project/openai-compatible-errors/),
[Ruby on RubyGems](https://rubygems.org/gems/openai-compatible-errors),
[PHP on Packagist](https://packagist.org/packages/airouter/openai-compatible-errors),
[Rust on crates.io](https://crates.io/crates/llm-stream-guard),
[Deno on JSR](https://jsr.io/@ai-router/openai-sse-guard),
[Dart on pub.dev](https://pub.dev/packages/openai_sse_guard),
[Swift Package Index](https://swiftpackageindex.com/airouter-dev/openai-sse-guard-swift),
and [Elixir on Hex](https://hex.pm/packages/openai_sse_guard). These are
contextual references, not claims of shared runtime code or third-party
endorsement.

## Development

```console
lein test
lein pom
```

Read [the replay-safety guide](doc/replay-safety.md),
[CONTRIBUTING.md](CONTRIBUTING.md), and [SECURITY.md](SECURITY.md) before
changing framing or terminal-state semantics.

MIT licensed. Maintained by [AI-ROUTER contributors](https://ai-router.dev/).
