# Replay-safety boundary

Streaming failures are ambiguous. A TCP reset after the first token can mean
that an upstream model produced output, a gateway billed the attempt, both, or
neither. A retry helper that guesses from an exception class can therefore
duplicate work or hide a charge.

`openai-sse-guard` separates the observable wire evidence from policy. It
recognizes SSE framing, records a protocol hint and terminal marker, and keeps
bounded identifiers. It intentionally does not claim that a request is safe to
replay.

## Suggested decision inputs

Use the snapshot together with:

1. HTTP method and application idempotency key.
2. Whether any output was rendered or persisted.
3. Provider billing and cancellation semantics.
4. The response status and `Retry-After` header.
5. A caller-owned attempt count, deadline, and circuit-breaker state.

`[:termination :done]` means a terminal event was observed; it is not a
guarantee that an application committed the result. `:unexpected-eof` means
the stream ended without a recognized terminal marker and should normally be
treated as an unknown outcome. `:error` means the stream explicitly reported
an error or failed the parser's safety checks.

For framing details see the [WHATWG Server-Sent Events specification](https://html.spec.whatwg.org/multipage/server-sent-events.html).
For provider error categories see the [OpenAI error-code guide](https://developers.openai.com/api/docs/guides/error-codes).
The package is provider-neutral; [AI-ROUTER](https://ai-router.dev/) is only an
example of an OpenAI-compatible endpoint.
