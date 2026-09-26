# Project Review: uuidv7.cljc

**Reviewer:** Devin (SWE-2)
**Date:** September 26, 2026
**Scope:** Full review of library, CLI, tests, build/CI, and docs at v0.7.2

---

## Overall

This is a well-executed small library. The 0.7.2 refactor (pure `next-state` +
impure `advance!` shell) is exactly right for `swap!` correctness, the CSPRNG
story is now solid on all five targets, and the verification apparatus
(5-platform test matrix, known-answer tests, published-artifact smoke tests,
release-time version cross-checks) is unusually thorough for a single-file
library. The prior reviews in `docs/` predate the 0.7.1/0.7.2 rewrites — their
suggestions (single `random-uuid` for entropy, magic-number constants,
concurrency test) are now either moot or done.

## Findings

### Real: extra positional args are silently ignored

`extract-positional` takes the first non-flag arg and hands the rest to
`cli/parse-opts`, which puts leftovers in `:args` — never checked. Verified:

```
$ uuidv7 valid <valid-uuid> garbage-not-a-uuid   → exit 0
$ uuidv7 parse <valid-uuid> garbage              → exit 0 (garbage dropped)
$ uuidv7 gen extra-positional                    → exit 0
```

For `valid` this is a soundness issue: a predicate that promises "exit 0 iff
all inputs are valid" skips inputs entirely. Either error (exit 2) or treat
all positionals as inputs.

### Minor: missing flag value produces a confusing error

`extract-positional` conj's `(second remaining)` even when it's nil. Verified:
`uuidv7 parse --input` → `uuidv7: input file not found:` (empty name),
exit 1 — should be a usage error (exit 2).

### Stale docs after 0.7.2

The known-answer tests added in 0.7.2 changed the counts but the docs weren't
updated:

- `CLAUDE.md` "Expected results" and `docs/uuidv7-tests.md` both claim
  15 tests/100 assertions on JVM/bb, 16/90 on CLJS. Actual (verified with
  `bb test:bb`): **19 tests / 252 assertions** on bb; CLJS runs 20.
- The three reviews in `docs/` analyze pre-0.7.0 code (`random-bits`,
  `extract-counter-hex` — both gone). Fine as history, but unlabeled stale
  analysis could mislead a reader.

### Naming-convention consistency nits

Per the project's own convention (CLAUDE.md):

- `check-uuidv7` returns `nil` on success, not its argument — the convention
  describes `check-…` as "return their argument or throw". Returning the arg
  would also make `(-> u check-uuidv7 extract-ts)` chains possible. Minor.
- In `bin/uuidv7`, `die`/`die-usage`/`run-gen`/`run-parse`/`run-valid`
  terminate the process and `make-output-stream` truncates a file — all
  writes that outlive the call, but only `parse-uuid!`/`validate-format!`/
  `write-line!` carry `!`. Probably covered by the "existing names not yet
  renamed" inventory, but the CLI predates that note.

### Design observations (not bugs)

- **Concurrency semantics**: strict increase holds in `swap!` commit order,
  but two concurrent calls can *return* out of order relative to their values
  (commit, deschedule, other thread commits and returns first). Docstrings say
  "successive calls … strictly increasing" — true per commit order; a one-line
  precision would help callers who fan out across threads.
- **`advance!` always draws 14 bytes**, using only 10 (new ms) or 4
  (increment). Deliberate tradeoff, documented — just noting it's a real
  entropy/allocation cost per call.
- **`extract-key` triple-validates** (`check-uuidv7` runs in `extract-key`,
  `extract-ts`, `extract-counter`). Negligible.
- **`read-uuid-strings` slurps stdin whole** — fine for a filter, worth
  knowing for very large inputs.
- **`uuidv7 parse`/`valid` with no args block on stdin** when attached to a
  TTY — standard filter behavior.
- **`deps.edn` has a redundant `:test` alias** duplicating `:test-clj`.

### Release workflow

Solid: three-way version check before deploy, Clojars-indexing wait loop,
clean-dir CLI smoke test, `release-check` refuses non-semver. One gap by
design: `release.yml` runs JVM/bb/CLI tests only — CLJS/nbb/Scittle coverage
relies on `ci.yml` being green on the tagged commit. That's consistent with
the documented "push main, wait green, tag" flow; just don't tag from a
commit that skipped CI.

### API surface

Clean and minimal. Possible additions (all optional, none required):

- A deterministic generator for tests/backfill — e.g. `make-generator`
  accepting a fixed clock/randomness source, or a public `uuidv7-at` — would
  aid consumer-side testing.
- `extract-inst` returns `java.util.Date` (consistent with `#inst`); a
  `java.time.Instant` variant would be more modern on JVM, though `js/Date`
  is the only CLJS option anyway.
- `uuid->bytes` for binary interop (also suggested by a prior review).

## Bottom line

Correctness of the core algorithm looks right — checked the bit layout
against RFC 9562, the seed/increment uniformity (all `mod`s are by
power-of-two divisors of power-of-two ranges, so fields stay uniform), carry
propagation, overflow handling, and the pure-`swap!` retry story. The library
itself is essentially done. The actionable items are the CLI's silent
extra-positionals (the `valid` case in particular), the missing-value error
path, and refreshing the stale test counts in the docs.

---

## Resolution (0.7.3, 2026-09-26)

Verified by reproduction and fixed; the CLI fixes have tests in
`cli_test.clj` shown to fail first (12 assertions):

- **Extra positionals**: fixed. `parse`/`valid` take every positional as an
  input (`valid <good> garbage` now exits 1); `gen` refuses them (exit 2).
- **Missing flag value**: fixed; "missing value for --input", exit 2.
- **Stale docs**: counts updated (19/252 JVM+bb, 20/242 CLJS+nbb+Scittle,
  31 CLI tests); the three pre-0.7.0 reviews are labelled historical.
- **`check-uuidv7`**: returns its argument.
- **CLI `!` names**: left as they are. Exiting or printing is not a write
  of lasting state under the convention, and the functions are internal to
  the script.
- **Concurrency semantics**: one sentence in the `uuidv7` docstring and the
  README.
- **Redundant `:test` alias**: removed (unused).
- **Design observations and API additions**: no action; noted for later.
