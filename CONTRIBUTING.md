# Contributing

1. Run `lein test` before opening a pull request.
2. Keep the observer bounded and provider-neutral; do not add transcript or
   prompt retention to the snapshot.
3. Add a regression test for every framing or terminal-state change.
4. Describe compatibility and security implications in the pull request.

Please report suspected vulnerabilities privately as described in
[SECURITY.md](SECURITY.md).
