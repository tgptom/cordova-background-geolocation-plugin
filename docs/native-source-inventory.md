---
layout: default
title: Native Source Inventory
nav_order: 10
---

# Vendored native source inventory

This repository currently vendors native sources via `plugin.xml`.

## Android vendored sources (high-level)

- JS evaluator (`com.evgenii.jsevaluator`) — vendored fork.
- Apache Commons IO fragments (`org.apache.commons.io`) — vendored subset.
- SQL builder (`ru.andremoniy.sqlbuilder`) — vendored subset.
- Core plugin/service/provider classes (`com.marianhello.bgloc.*`) — project-owned.

## iOS vendored sources (high-level)

- FMDB (`FMDB.*`) — vendored copy.
- CocoaLumberjack (`CocoaLumberjack.*`) — vendored copy.
- INTULocationManager (`INTULocationManager/*`) — vendored copy.
- Reachability (`Reachability.*`) — vendored copy.
- SOMotionDetector (`SOMotionDetector/*`) — vendored copy.
- SQLQueryBuilder (`SQLQueryBuilder/*`) — vendored subset.

## Audit/update policy

For each vendored upstream dependency, maintain:

- Upstream repository URL
- Pinned upstream version/tag/commit
- Local patch status (none/minor/custom)
- License identifier
- Update cadence owner

Short-term, this file records the active vendored surface and blocks accidental silent expansion. Follow-up modernization should move safe dependencies to Maven/CocoaPods/SPM one component at a time with native regression coverage.
