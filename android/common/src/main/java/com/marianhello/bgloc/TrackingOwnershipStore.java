package com.marianhello.bgloc;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.LinkedHashMap;
import java.util.Map;

final class TrackingOwnershipStore {
    static final int OWNER_NONE = 0;
    static final int OWNER_MANUAL = 1;
    static final int OWNER_GEOFENCE = 2;

    private static final String PREFS_NAME = "com.marianhello.bgloc.geofence";
    private static final String OWNER_KEY = "tracking_owner";
    private static final String PENDING_START_OWNER_KEY = "pending_start_owner";
    private static final String PENDING_START_DEADLINE_KEY = "pending_start_deadline";
    private static final String PENDING_START_GENERATION_KEY = "pending_start_generation";
    private static final String PENDING_START_PHASE_KEY = "pending_start_phase";
    private static final String PENDING_STOP_OWNER_KEY = "pending_stop_owner";
    private static final String PENDING_STOP_DEADLINE_KEY = "pending_stop_deadline";
    private static final String PENDING_STOP_GENERATION_KEY = "pending_stop_generation";
    private static final String FAILED_START_GENERATIONS_KEY = "failed_start_generations";
    private static final String REQUEST_GENERATION_KEY = "request_generation";
    private static final String SERVICE_STARTED_KEY = "service_started";
    private static final int MAX_TERMINAL_START_GENERATIONS = 16;
    private static final int START_TERMINAL_STATUS_FAILED = 1;
    private static final int START_TERMINAL_STATUS_CANCELLED = 2;
    private static final int PENDING_START_PHASE_NONE = 0;
    private static final int PENDING_START_PHASE_PERMISSION = 1;
    private static final int PENDING_START_PHASE_SERVICE_ACK = 2;

    private final SharedPreferences prefs;

    static final class ReconciledState {
        final int owner;
        final int pendingStartOwner;
        final int pendingStopOwner;
        final boolean serviceStarted;

        ReconciledState(int owner, int pendingStartOwner, int pendingStopOwner, boolean serviceStarted) {
            this.owner = owner;
            this.pendingStartOwner = pendingStartOwner;
            this.pendingStopOwner = pendingStopOwner;
            this.serviceStarted = serviceStarted;
        }

        static final class StartGenerationStatus {
            final int owner;
            final boolean cancelled;

            StartGenerationStatus(int owner, boolean cancelled) {
                this.owner = owner;
                this.cancelled = cancelled;
            }
        }
    }

    TrackingOwnershipStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    int getOwner() {
        int owner = prefs.getInt(OWNER_KEY, OWNER_NONE);
        return normalizeOwner(owner);
    }

    void setOwner(int owner) {
        int normalizedOwner = normalizeOwner(owner);
        if (normalizedOwner == OWNER_NONE) {
            clearOwner();
            return;
        }
        prefs.edit().putInt(OWNER_KEY, normalizedOwner).apply();
    }

    void clearOwner() {
        prefs.edit().remove(OWNER_KEY).apply();
    }

    int getPendingStartOwner() {
        return normalizeOwner(prefs.getInt(PENDING_START_OWNER_KEY, OWNER_NONE));
    }

    long setPendingStartOwner(int owner, long deadlineEpochMs) {
        return setPendingStartOwner(owner, deadlineEpochMs, nextGeneration(), PENDING_START_PHASE_SERVICE_ACK);
    }

    long setPendingStartPermissionOwner(int owner, long deadlineEpochMs) {
        return setPendingStartOwner(owner, deadlineEpochMs, nextGeneration(), PENDING_START_PHASE_PERMISSION);
    }

    boolean promotePendingStartToServiceAck(long generation, long deadlineEpochMs) {
        if (!isPendingStartGeneration(generation)) {
            return false;
        }
        prefs.edit()
                .putInt(PENDING_START_PHASE_KEY, PENDING_START_PHASE_SERVICE_ACK)
                .putLong(PENDING_START_DEADLINE_KEY, deadlineEpochMs)
                .apply();
        return true;
    }

    int getPendingStartPhase() {
        return prefs.getInt(PENDING_START_PHASE_KEY, PENDING_START_PHASE_NONE);
    }

    void clearPendingStartOwner() {
        prefs.edit()
                .remove(PENDING_START_OWNER_KEY)
                .remove(PENDING_START_DEADLINE_KEY)
                .remove(PENDING_START_GENERATION_KEY)
                .remove(PENDING_START_PHASE_KEY)
                .apply();
    }

    long getPendingStartGeneration() {
        return prefs.getLong(PENDING_START_GENERATION_KEY, 0L);
    }

    boolean isPendingStartGeneration(long generation) {
        return generation != 0L
                && getPendingStartOwner() != OWNER_NONE
                && getPendingStartGeneration() == generation;
    }

    void clearPendingStartOwnerIfGeneration(long generation) {
        if (isPendingStartGeneration(generation)) {
            clearPendingStartOwner();
        }
    }

    void markFailedStart(long generation, int owner) {
        markTerminalStart(generation, owner, START_TERMINAL_STATUS_FAILED);
    }

    void markCancelledStart(long generation, int owner) {
        markTerminalStart(generation, owner, START_TERMINAL_STATUS_CANCELLED);
    }

    StartGenerationStatus getStartGenerationStatus(long generation) {
        TerminalStartRecord record = getTerminalStartRecord(generation);
        if (record == null) {
            return null;
        }
        return new StartGenerationStatus(
                normalizeOwner(record.owner),
                record.status == START_TERMINAL_STATUS_CANCELLED
        );
    }

    boolean isTerminalStartGenerationForOwner(long generation, int owner) {
        TerminalStartRecord record = getTerminalStartRecord(generation);
        return record != null && normalizeOwner(record.owner) == normalizeOwner(owner);
    }

    boolean isCancelledStartGenerationForOwner(long generation, int owner) {
        TerminalStartRecord record = getTerminalStartRecord(generation);
        return record != null
                && normalizeOwner(record.owner) == normalizeOwner(owner)
                && record.status == START_TERMINAL_STATUS_CANCELLED;
    }

    private void markTerminalStart(long generation, int owner, int status) {
        int normalizedOwner = normalizeOwner(owner);
        if (generation == 0L || normalizedOwner == OWNER_NONE) {
            return;
        }
        LinkedHashMap<Long, TerminalStartRecord> records = getTerminalStartRecords();
        records.remove(generation);
        records.put(generation, new TerminalStartRecord(normalizedOwner, status));
        trimTerminalStartRecords(records);
        persistTerminalStartRecords(records);
    }

    boolean isFailedStartGenerationForOwner(long generation, int owner) {
        return isTerminalStartGenerationForOwner(generation, owner);
    }

    void clearFailedStartOwnerIfGeneration(long generation) {
        if (generation == 0L) {
            return;
        }
        LinkedHashMap<Long, TerminalStartRecord> records = getTerminalStartRecords();
        if (records.remove(generation) != null) {
            persistTerminalStartRecords(records);
        }
    }

    void clearFailedStartOwner() {
        prefs.edit().remove(FAILED_START_GENERATIONS_KEY).apply();
    }

    int getPendingStopOwner() {
        return normalizeOwner(prefs.getInt(PENDING_STOP_OWNER_KEY, OWNER_NONE));
    }

    long setPendingStopOwner(int owner, long deadlineEpochMs) {
        int normalizedOwner = normalizeOwner(owner);
        if (normalizedOwner == OWNER_NONE) {
            clearPendingStopOwner();
            return 0L;
        }

        long generation = nextGeneration();
        prefs.edit()
                .putInt(PENDING_STOP_OWNER_KEY, normalizedOwner)
                .putLong(PENDING_STOP_DEADLINE_KEY, deadlineEpochMs)
                .putLong(PENDING_STOP_GENERATION_KEY, generation)
                .apply();
        return generation;
    }

    void clearPendingStopOwner() {
        prefs.edit()
                .remove(PENDING_STOP_OWNER_KEY)
                .remove(PENDING_STOP_DEADLINE_KEY)
                .remove(PENDING_STOP_GENERATION_KEY)
                .apply();
    }

    long getPendingStopGeneration() {
        return prefs.getLong(PENDING_STOP_GENERATION_KEY, 0L);
    }

    boolean isPendingStopGeneration(long generation) {
        return generation != 0L
                && getPendingStopOwner() != OWNER_NONE
                && getPendingStopGeneration() == generation;
    }

    void clearPendingStopOwnerIfGeneration(long generation) {
        if (isPendingStopGeneration(generation)) {
            clearPendingStopOwner();
        }
    }

    void onServiceStartedAcknowledged() {
        markServiceStartedAcknowledged();
        commitOwnerOnServiceStarted();
    }

    void markServiceStartedAcknowledged() {
        markServiceStarted(true);
    }

    void onServiceStoppedAcknowledged() {
        markServiceStarted(false);
        clearOwnerOnServiceStopped();
    }

    void commitOwnerOnServiceStarted() {
        int pendingOwner = getPendingStartOwner();
        if (pendingOwner != OWNER_NONE) {
            setOwner(pendingOwner);
            clearPendingStartOwner();
        }
    }

    void clearOwnerOnServiceStopped() {
        clearOwner();
        clearPendingStopOwner();
        clearPendingStartOwner();
    }

    boolean isServiceStartedPersisted() {
        return prefs.getBoolean(SERVICE_STARTED_KEY, false);
    }

    ReconciledState reconcileWithServiceState(boolean serviceStartedHint, long nowEpochMs) {
        boolean persistedServiceStarted = isServiceStartedPersisted();
        int owner = getOwner();
        int pendingStartOwner = getPendingStartOwner();
        int pendingStopOwner = getPendingStopOwner();
        if (persistedServiceStarted
                && !serviceStartedHint
                && owner == OWNER_GEOFENCE
                && pendingStartOwner == OWNER_NONE
                && pendingStopOwner == OWNER_NONE) {
            markServiceStarted(false);
            persistedServiceStarted = false;
        }
        boolean serviceStarted = persistedServiceStarted || serviceStartedHint;

        if (serviceStarted) {
            return new ReconciledState(owner, pendingStartOwner, pendingStopOwner, true);
        }

        if (!persistedServiceStarted) {
            markServiceStarted(false);
        }

        long pendingStartDeadline = prefs.getLong(PENDING_START_DEADLINE_KEY, 0L);
        if (pendingStartOwner != OWNER_NONE && pendingStartDeadline > 0L && pendingStartDeadline <= nowEpochMs) {
            clearPendingStartOwner();
            pendingStartOwner = OWNER_NONE;
        }

        long pendingStopDeadline = prefs.getLong(PENDING_STOP_DEADLINE_KEY, 0L);
        if (pendingStopOwner != OWNER_NONE && pendingStopDeadline > 0L && pendingStopDeadline <= nowEpochMs) {
            if (owner == pendingStopOwner || owner == OWNER_GEOFENCE || owner == OWNER_NONE) {
                clearOwner();
                owner = OWNER_NONE;
            }
            clearPendingStopOwner();
            pendingStopOwner = OWNER_NONE;
        }

        if (owner == OWNER_GEOFENCE && pendingStopOwner == OWNER_NONE && pendingStartOwner == OWNER_NONE) {
            clearOwner();
            owner = OWNER_NONE;
        }

        return new ReconciledState(owner, pendingStartOwner, pendingStopOwner, false);
    }

    private void markServiceStarted(boolean started) {
        prefs.edit().putBoolean(SERVICE_STARTED_KEY, started).apply();
    }

    private long nextGeneration() {
        long generation = prefs.getLong(REQUEST_GENERATION_KEY, 0L) + 1L;
        prefs.edit().putLong(REQUEST_GENERATION_KEY, generation).apply();
        return generation;
    }

    private long setPendingStartOwner(int owner, long deadlineEpochMs, long generation, int phase) {
        int normalizedOwner = normalizeOwner(owner);
        if (normalizedOwner == OWNER_NONE) {
            clearPendingStartOwner();
            return 0L;
        }
        prefs.edit()
                .putInt(PENDING_START_OWNER_KEY, normalizedOwner)
                .putLong(PENDING_START_DEADLINE_KEY, deadlineEpochMs)
                .putLong(PENDING_START_GENERATION_KEY, generation)
                .putInt(PENDING_START_PHASE_KEY, phase)
                .apply();
        return generation;
    }

    private int normalizeOwner(int owner) {
        if (owner == OWNER_MANUAL || owner == OWNER_GEOFENCE) {
            return owner;
        }
        return OWNER_NONE;
    }

    private TerminalStartRecord getTerminalStartRecord(long generation) {
        if (generation == 0L) {
            return null;
        }
        return getTerminalStartRecords().get(generation);
    }

    private LinkedHashMap<Long, TerminalStartRecord> getTerminalStartRecords() {
        LinkedHashMap<Long, TerminalStartRecord> records = new LinkedHashMap<>();
        String serialized = prefs.getString(FAILED_START_GENERATIONS_KEY, null);
        if (TextUtils.isEmpty(serialized)) {
            return records;
        }
        String[] entries = serialized.split(",");
        for (String entry : entries) {
            if (TextUtils.isEmpty(entry)) {
                continue;
            }
            String[] parts = entry.split(":");
            if (parts.length != 3) {
                continue;
            }
            try {
                long generation = Long.parseLong(parts[0]);
                int owner = normalizeOwner(Integer.parseInt(parts[1]));
                int status = Integer.parseInt(parts[2]);
                if (generation == 0L || owner == OWNER_NONE) {
                    continue;
                }
                if (status != START_TERMINAL_STATUS_FAILED && status != START_TERMINAL_STATUS_CANCELLED) {
                    continue;
                }
                records.put(generation, new TerminalStartRecord(owner, status));
            } catch (NumberFormatException ignored) {
                // ignore malformed entries
            }
        }
        trimTerminalStartRecords(records);
        return records;
    }

    private void persistTerminalStartRecords(LinkedHashMap<Long, TerminalStartRecord> records) {
        if (records.isEmpty()) {
            prefs.edit().remove(FAILED_START_GENERATIONS_KEY).apply();
            return;
        }
        StringBuilder serialized = new StringBuilder();
        for (Map.Entry<Long, TerminalStartRecord> entry : records.entrySet()) {
            if (serialized.length() > 0) {
                serialized.append(",");
            }
            serialized.append(entry.getKey())
                    .append(":")
                    .append(entry.getValue().owner)
                    .append(":")
                    .append(entry.getValue().status);
        }
        prefs.edit().putString(FAILED_START_GENERATIONS_KEY, serialized.toString()).apply();
    }

    private void trimTerminalStartRecords(LinkedHashMap<Long, TerminalStartRecord> records) {
        while (records.size() > MAX_TERMINAL_START_GENERATIONS) {
            Long oldest = records.keySet().iterator().next();
            records.remove(oldest);
        }
    }

    private static final class TerminalStartRecord {
        final int owner;
        final int status;

        TerminalStartRecord(int owner, int status) {
            this.owner = owner;
            this.status = status;
        }
    }
}
