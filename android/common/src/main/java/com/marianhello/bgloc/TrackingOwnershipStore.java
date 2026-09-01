package com.marianhello.bgloc;

import android.content.Context;
import android.content.SharedPreferences;

final class TrackingOwnershipStore {
    static final int OWNER_NONE = 0;
    static final int OWNER_MANUAL = 1;
    static final int OWNER_GEOFENCE = 2;

    private static final String PREFS_NAME = "com.marianhello.bgloc.geofence";
    private static final String OWNER_KEY = "tracking_owner";
    private static final String PENDING_START_OWNER_KEY = "pending_start_owner";
    private static final String PENDING_START_DEADLINE_KEY = "pending_start_deadline";
    private static final String PENDING_START_GENERATION_KEY = "pending_start_generation";
    private static final String PENDING_STOP_OWNER_KEY = "pending_stop_owner";
    private static final String PENDING_STOP_DEADLINE_KEY = "pending_stop_deadline";
    private static final String PENDING_STOP_GENERATION_KEY = "pending_stop_generation";
    private static final String REQUEST_GENERATION_KEY = "request_generation";
    private static final String SERVICE_STARTED_KEY = "service_started";

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
        int normalizedOwner = normalizeOwner(owner);
        if (normalizedOwner == OWNER_NONE) {
            clearPendingStartOwner();
            return 0L;
        }

        long generation = nextGeneration();
        prefs.edit()
                .putInt(PENDING_START_OWNER_KEY, normalizedOwner)
                .putLong(PENDING_START_DEADLINE_KEY, deadlineEpochMs)
                .putLong(PENDING_START_GENERATION_KEY, generation)
                .apply();
        return generation;
    }

    long setPendingStartOwnerWithoutDeadline(int owner) {
        int normalizedOwner = normalizeOwner(owner);
        if (normalizedOwner == OWNER_NONE) {
            clearPendingStartOwner();
            return 0L;
        }

        long generation = nextGeneration();
        prefs.edit()
                .putInt(PENDING_START_OWNER_KEY, normalizedOwner)
                .remove(PENDING_START_DEADLINE_KEY)
                .putLong(PENDING_START_GENERATION_KEY, generation)
                .apply();
        return generation;
    }

    void clearPendingStartOwner() {
        prefs.edit()
                .remove(PENDING_START_OWNER_KEY)
                .remove(PENDING_START_DEADLINE_KEY)
                .remove(PENDING_START_GENERATION_KEY)
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
        markServiceStarted(true);
        commitOwnerOnServiceStarted();
        clearPendingStopOwner();
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
            if (persistedServiceStarted && pendingStartOwner != OWNER_NONE) {
                setOwner(pendingStartOwner);
                clearPendingStartOwner();
                owner = pendingStartOwner;
                pendingStartOwner = OWNER_NONE;
            }

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

    private int normalizeOwner(int owner) {
        if (owner == OWNER_MANUAL || owner == OWNER_GEOFENCE) {
            return owner;
        }
        return OWNER_NONE;
    }
}
