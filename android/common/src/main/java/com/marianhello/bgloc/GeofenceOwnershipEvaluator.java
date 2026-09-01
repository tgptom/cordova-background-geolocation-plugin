package com.marianhello.bgloc;

final class GeofenceOwnershipEvaluator {
    static final int TRANSITION_ENTER = 1;
    static final int TRANSITION_EXIT = 2;
    static final int TRANSITION_DWELL = 4;

    static final class Result {
        final boolean shouldStart;
        final boolean shouldStop;
        final boolean shouldClearOwner;

        Result(boolean shouldStart, boolean shouldStop, boolean shouldClearOwner) {
            this.shouldStart = shouldStart;
            this.shouldStop = shouldStop;
            this.shouldClearOwner = shouldClearOwner;
        }
    }

    private GeofenceOwnershipEvaluator() {}

    static Result evaluate(int transitionType, boolean hasActiveInsideGeofence,
                           boolean serviceStarted, int owner, int pendingStartOwner) {
        int normalizedOwner = normalizeOwner(owner);
        int normalizedPendingStartOwner = normalizeOwner(pendingStartOwner);
        boolean shouldClearOwner = !serviceStarted && normalizedOwner == TrackingOwnershipStore.OWNER_GEOFENCE;
        int reconciledOwner = shouldClearOwner ? TrackingOwnershipStore.OWNER_NONE : normalizedOwner;

        if (transitionType == TRANSITION_EXIT) {
            boolean shouldStop = !hasActiveInsideGeofence
                    && serviceStarted
                    && reconciledOwner == TrackingOwnershipStore.OWNER_GEOFENCE;
            return new Result(false, shouldStop, shouldClearOwner);
        }

        if (transitionType == TRANSITION_ENTER || transitionType == TRANSITION_DWELL) {
            boolean shouldStart = hasActiveInsideGeofence
                    && !serviceStarted
                    && reconciledOwner != TrackingOwnershipStore.OWNER_MANUAL
                    && normalizedPendingStartOwner == TrackingOwnershipStore.OWNER_NONE;
            return new Result(shouldStart, false, shouldClearOwner);
        }

        return new Result(false, false, shouldClearOwner);
    }

    private static int normalizeOwner(int owner) {
        if (owner == TrackingOwnershipStore.OWNER_MANUAL || owner == TrackingOwnershipStore.OWNER_GEOFENCE) {
            return owner;
        }
        return TrackingOwnershipStore.OWNER_NONE;
    }
}
