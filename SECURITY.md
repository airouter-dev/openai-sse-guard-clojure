# Security policy

Please do not disclose a suspected vulnerability in a public issue. Email the
maintainers through the contact channel on [ai-router.dev](https://ai-router.dev/)
with a minimal reproduction and the affected version.

The observer is designed for untrusted upstream bytes: it applies frame and
event limits, rejects malformed UTF-8, and exposes only bounded identifiers.
Applications must still set network timeouts, cap total response sizes, avoid
logging request/response bodies, and make their own replay and billing
decisions.
