# Future state: infrastructure-network root-cause analysis

**Status: not built.** Documented here as a candidate future enhancement,
deliberately deferred — see [`PROJECT.md`](../PROJECT.md#open-items-to-revisit) for
how open items are tracked in this project. This file exists so the idea has a
home to come back to, not as a spec ready to implement.

## The problem this would solve

Today, two complaints only ever get linked if they share department + category
within a 7-day window (`DuplicateDetectionService`). That structurally can't catch
the case this enhancement targets: a water-main break causing a pothole complaint
(routed DOT), a discolored-water complaint (routed DEP), and a "low pressure"
complaint (routed DPW) on the same block — three citizens, three departments, three
categories, one physical root cause, and today's system sees zero relationship
between them.

The goal: given a cluster of superficially unrelated grievances, surface "these are
plausibly explained by the same upstream infrastructure failure" — the same instinct
behind the existing [Recurring Issues panel](../PROJECT.md#complaint-trends-dashboard)
on the Trends dashboard, but driven by physical network proximity/reachability
instead of a department+category+time match.

## Why this is genuinely graph-shaped (and the other cases in this project weren't)

This project has twice concluded a graph database wasn't justified — see
[`ARCHITECTURE.md`'s "Why no graph database"](ARCHITECTURE.md#why-no-graph-database-neo4jmemgraphkuzudbhugegraph)
for the duplicate-chain case, and the checkpointing analysis that reached the same
verdict for the same reason. Both of those are **strictly linear per-thread
histories** (a self-referencing FK walked with a recursive CTE) — not a network, so
SQL's own recursive-query primitive already handles them natively.

An infrastructure topology is different in kind, not just degree:
- It's a **real network** — street segments, water mains, sewer lines, power grid
  segments — with actual branching, merging, and multiple paths between two points,
  not a chain.
- The query that matters is **reachability/shortest-path**: "a water main breaks at
  node X — which downstream nodes (and therefore which addresses, and therefore
  which grievances) are affected?" That's a first-class graph-traversal query, not
  an occasional join.
- It requires data AIGRE has never had: today there is **no structured location
  field anywhere in the schema** — `raw_text` is free text only, confirmed when the
  graph-database question was first evaluated. This is the actual prerequisite
  blocking this enhancement, not database technology — you can't build a location
  network graph without locations.

## What would actually need to exist first

In rough order, none of this started:
1. **A structured location capture at intake** — address, coordinates, or a
   picked point on a map, added to the citizen submission flow (today: free text
   only).
2. **A real infrastructure topology data source.** AIGRE has no city GIS/asset data
   of any kind, synthetic or real — this would need to come from an external
   dataset (a city's GIS export of street/utility networks) or a
   deliberately-constructed synthetic one for demo purposes, mirroring how the
   existing 108-document policy corpus and 91-complaint eval set were
   purpose-built rather than sourced live.
3. **A geocoding/nearest-node step** linking each grievance to the infrastructure
   node closest to its (new) location field.
4. **The actual traversal feature** — a "possible shared root cause" query/panel
   surfacing grievances reachable from a common upstream node within some hop/
   distance threshold.

## Two implementation paths, not one foregone conclusion

Even once the prerequisites above exist, this doesn't automatically mean "add a
graph database" — it means "graph engine vs. `pgRouting`," which is a materially
different and closer call than "graph engine vs. a five-line recursive CTE":

| | `pgRouting` + PostGIS (Postgres extensions) | Dedicated graph DB (Neo4j Community) |
|---|---|---|
| New infrastructure | **None** — extensions on the existing `aigre-pg` instance | A separate server to run and operate |
| Fit for this use case | Purpose-built for exactly this: network routing, nearest-node, reachability over a real geometric network | General-purpose graph queries; would need the network modeled by hand as nodes/edges |
| Consistent with this project's own precedent | **Yes** — same "minimal new infrastructure" reasoning that picked pgvector over a dedicated vector DB | Only clears that bar if query patterns genuinely need Cypher-style pattern matching pgRouting can't express |
| LangChain4j tie-in | None needed — this isn't an LLM-retrieval feature | `Neo4jText2CypherRetriever` exists if this ever grows an LLM-driven "ask about the infrastructure network" chat feature |

**Recommendation if/when this is picked up**: prototype with `pgRouting`/PostGIS
first, for the same reason pgvector was chosen over a standalone vector database —
it's the extension on infrastructure this project already runs, not a new system to
operate. Fall back to a dedicated graph database only if the actual query patterns
that emerge (not hypothetical ones) exceed what `pgRouting`'s SQL-based routing
functions can express. If that fallback is ever needed, Neo4j Community remains the
most defensible choice among the four options already weighed in `ARCHITECTURE.md`,
specifically because of its maintained LangChain4j integration.

## Explicit non-goals for now

- No schema changes, no location-capture UI, no GIS data acquisition has started.
- Not a replacement for the existing department+category+time duplicate detection —
  this would be additive, a different signal for a different pattern (physical
  co-location vs. same-department repeat reports).
- Not scoped until there's a real location field and real (or realistically
  synthetic) infrastructure topology data to work with — the database-technology
  question is secondary to that data-availability gap.
