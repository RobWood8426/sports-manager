# CLAUDE.md — Sports Manager

## Role

Executor agent for a Clojure backend project. Implement, refactor, and fix code. Receive well-scoped tasks and execute them precisely. Do not architect or make design decisions unilaterally — flag them.

## Core Principles

- Write small, pure functions. Prefer data in, data out.
- Never use floating point for monetary amounts. Use BigDecimal.
- Prefer threading macros (`->`, `->>`) for readability.
- Destructure at the function signature level where it aids clarity.
- Keep namespaces focused. If a function doesn't belong, say so rather than shoehorning it in.
- Prefer `ex-info` with data maps for errors over string-only exceptions.
- Always prefer Clojure MCP tools (`clojure_eval`, `clojure_edit`, `read_file`) over Bash/Read/Edit when working in this codebase. Do NOT use standard file tools or bash for Clojure tasks unless explicitly asked. If the tools are unavailable, ask the user to reconnect.
- For any work that might require a lot of context for a limited result, use a sub-agent to preserve your own context.

## Mandatory Workflow: Lint After Every Change

After making changes to any `.clj`, `.cljs`, or `.cljc` file, run clj-kondo and fix all reported issues before considering the task complete.

```bash
# Single file
clojure -M:lint path/to/changed/file.clj

# Multiple files
clj-kondo --lint src/sports_manager/foo.clj src/sports_manager/bar.clj

# Entire src tree
clj-kondo --lint src/
```

Fix priority:
- **:error** — Must fix. Do not proceed until resolved.
- **:warning** — Must fix. These are real issues (unused imports, wrong arity, etc.).
- **:info** — Fix if straightforward. Note if intentional.

Re-lint after fixes. Only report done when output is clean.

### Common Kondo Fixes

| Kondo Warning | Fix |
|---|---|
| `Unused import` | Remove from `:require` / `:import` |
| `Redundant do` | Unwrap the `do` block |
| `Unused binding` | Prefix with `_` or remove |
| `Wrong number of args` | Check function signature, fix call site |
| `Unresolved var` | Add missing require or fix typo |
| `Shadowed var` | Rename the local binding |
| `Redundant let` | Collapse into outer let or remove |

### Namespace Hygiene

- Keep requires sorted alphabetically.
- Remove any requires no longer used after changes.
- Use `:as` aliases consistently with project conventions.
- Run kondo after any `ns` form changes — it catches unused requires immediately.

## REPL Verification

After making changes, verify in the REPL:

1. Require the namespace with `:reload`: `(require 'sports-manager.foo :reload)`
2. Exercise the function with representative inputs.
3. Check edge cases: nil, empty collections, zero amounts, negative values.

If the REPL is unavailable, ask the user to fix it — it is critical for verification. If eval fails, fix the syntax error before running kondo.

## Task Completion Checklist

Before reporting a task as done:

- [ ] All requested changes implemented
- [ ] `clj-kondo --lint` returns clean on all changed files
- [ ] Changed functions evaluated in REPL without error
- [ ] No unrelated files modified
- [ ] No debugging print statements left in code (`println`, `prn`, `tap>` unless intentional)
- [ ] Namespace requires are clean and sorted

## Debugging

Always verify fixes at runtime (REPL eval or test run) before considering a task complete. Do not spend extended time tracing code paths without checking actual behavior.

## XTDB Pitfalls

### :xt/id IS the Business Key

Every document's `:xt/id` is its business key UUID (or keyword for sport-templates). Never use lookup refs like `[:event/id uuid]` — pass the UUID directly to `db/pull`, `db/entity`, `db/exists?`, `db/merge!`, `db/delete!`.

```clojure
;; WRONG
(db/pull pattern [:event/id event-id])

;; CORRECT
(db/pull pattern event-id)
```

### put Replaces the Whole Document

`db/put!` and `[::xt/put doc]` replace the entire document. For partial updates, use `db/merge!` (read-modify-write) or `db/retract-attrs!`. Never build a partial map and put it — you will lose existing fields.

### Queries Return Sets of Tuples

`db/q` returns `#{[v1 v2 ...] ...}`. Extract values with `(map first ...)` for single-column results, or destructure for multi-column. Don't treat the result as a sequence of scalars.

```clojure
;; single column
(map first (db/q '{:find [?id] :in [?tid] :where [[?e :event/tenant ?tid] [?e :event/id ?id]]} tenant-id))

;; scalar shortcut (ffirst)
(ffirst (db/q '{:find [?id] :in [?code] :where [[?e :event/code ?code] [?e :event/id ?id]]} code))
```

### No Referential Integrity

XTDB does not enforce refs. Dangling references silently return nil. Guard with `db/exists?` before storing a ref if the target might not exist.

### Composite :xt/id for Memberships

Membership `:xt/id` is `[firebase-uid tenant-id]` — a vector. Use `(db/entity [uid tid])` for direct lookup. Don't query for membership eids.

### await-tx is Always Called

`db/submit!` always calls `xt/await-tx` before returning, so reads immediately after writes see the new data. Don't add extra waits.

### Multi-Tenancy

Every tenant-scoped entity carries `:entity/tenant` (e.g. `:event/tenant`, `:fixture/tenant`). Use `db/tenant-scoped` — never query tenant data without filtering on it. `tenant-scoped` takes `tenant-attr` as first arg (e.g. `:event/tenant`) since XTDB has no shared tenant entity to join through.

## Architecture Notes

### Onion Architecture

This codebase follows an onion architecture. Follow these rules when adding or refactoring code:

**Layer order (inner → outer):**

| Layer | Examples | Rules |
|---|---|---|
| Pure inner ring | schema, rbac (predicates), domain logic | No I/O, no DB, no logging. Data in, data out. |
| Side-effect ring | db query helpers | Reads only. No entity creation. |
| Orchestration | db writes, auth, session | Entity creation and DB writes. Requires inner ring only. |
| Outer shell | routes, core | Public API, pipeline orchestration. Raises side effects to the top. |

**Key rule — lift side effects up:** If you find a DB write or I/O call buried inside a pure helper, extract the result as data and let the caller apply it.

**Key rule — don't pull outer concerns inward:** A pure function should never import a DB or HTTP namespace. If you need to, that's a signal the function belongs in a higher layer.

### File Size Limit

Keep every namespace file under **400 lines**. Before adding code that would push a file past 400 lines, stop and consult the architect (`mcp__clojure-mcp__architect`) to split the namespace into a folder structure (e.g. `views/` with `views/home.clj`, `views/events.clj`, etc.). Do not proceed past 400 lines without that consultation.

### Consulting the MCP Architect

Before making structural decisions — new namespace splits, changing which layer a function belongs to, altering the public API surface, or anything touching more than one namespace — consult:

```
mcp__clojure-mcp__architect
```

Routine changes within a single namespace (adding a helper, fixing a bug, extracting a private fn) do not require architect consultation.

## What Not To Do

- Do not refactor beyond the scope of the task. Flag suggestions but don't act on them.
- Do not add dependencies without explicit approval.
- Do not change public function signatures without flagging it — callers may break.
- Do not suppress kondo warnings with `#_:clj-kondo/ignore` unless there is a genuine false positive. Explain why if you do.
- Do not leave `TODO` or `FIXME` comments without noting them in your response.
