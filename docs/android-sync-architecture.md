---
layout: default
title: Android Sync Architecture
nav_order: 9
---

# Android sync/account architecture status

This plugin still uses `AccountManager` + `SyncAdapter` for batched uploads.

## Current hardening in this release

- `SyncService` now requires `android.permission.BIND_SYNC_ADAPTER`.
- `AuthenticatorService` now has explicit `exported`/`permission` metadata and rejects unexpected bind intents.
- `SyncService` and `AuthenticatorService` validate incoming bind intents.
- `org.apache.http.legacy` is no longer required (`required="false"`).

## Why this was not fully replaced in one PR

A full migration from `SyncAdapter` to `WorkManager` changes background execution timing, retry semantics, and OS scheduling behavior. Doing that safely requires dedicated Android regression coverage (batch upload timing, reboot behavior, doze, forced sync parity).

## Planned deprecation path

1. Introduce a WorkManager-backed uploader behind a runtime feature flag.
2. Dual-run validation in CI and device matrix (manual start, geofence start, reboot, doze).
3. Remove account/sync components (`SyncService`, `AuthenticatorService`, sync adapter XML, related account/sync permissions) after parity is confirmed.
