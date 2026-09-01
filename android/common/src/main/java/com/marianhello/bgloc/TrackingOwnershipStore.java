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
    private static final String PENDING_STOP_OWNER_KEY = "pending_stop_owner";

    private final SharedPreferences prefs;

    TrackingOwnershipStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    int getOwner() {
        int owner = prefs.getInt(OWNER_KEY, OWNER_NONE);
        return normalizeOwner(owner);
    }

    void setOwner(int owner) {
        if (normalizeOwner(owner) == OWNER_NONE) {
            clearOwner();
            return;
        }
        prefs.edit().putInt(OWNER_KEY, owner).apply();
    }

    void clearOwner() {
        prefs.edit().remove(OWNER_KEY).apply();
    }

    int getPendingStartOwner() {
        return normalizeOwner(prefs.getInt(PENDING_START_OWNER_KEY, OWNER_NONE));
    }

    void setPendingStartOwner(int owner) {
        int normalizedOwner = normalizeOwner(owner);
        if (normalizedOwner == OWNER_NONE) {
            clearPendingStartOwner();
            return;
        }
        prefs.edit().putInt(PENDING_START_OWNER_KEY, normalizedOwner).apply();
    }

    void clearPendingStartOwner() {
        prefs.edit().remove(PENDING_START_OWNER_KEY).apply();
    }

    int getPendingStopOwner() {
        return normalizeOwner(prefs.getInt(PENDING_STOP_OWNER_KEY, OWNER_NONE));
    }

    void setPendingStopOwner(int owner) {
        int normalizedOwner = normalizeOwner(owner);
        if (normalizedOwner == OWNER_NONE) {
            clearPendingStopOwner();
            return;
        }
        prefs.edit().putInt(PENDING_STOP_OWNER_KEY, normalizedOwner).apply();
    }

    void clearPendingStopOwner() {
        prefs.edit().remove(PENDING_STOP_OWNER_KEY).apply();
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

    int reconcileWithServiceState(boolean serviceStarted) {
        int owner = getOwner();
        int pendingStartOwner = getPendingStartOwner();
        int pendingStopOwner = getPendingStopOwner();

        if (serviceStarted) {
            if (owner == OWNER_NONE && pendingStartOwner != OWNER_NONE) {
                setOwner(pendingStartOwner);
                clearPendingStartOwner();
                owner = pendingStartOwner;
            }
            if (pendingStopOwner != OWNER_NONE) {
                clearPendingStopOwner();
            }
            return owner;
        }

        if (pendingStartOwner != OWNER_NONE) {
            clearPendingStartOwner();
        }

        if (pendingStopOwner != OWNER_NONE) {
            if (owner == pendingStopOwner || owner == OWNER_NONE) {
                clearOwner();
                owner = OWNER_NONE;
            }
            clearPendingStopOwner();
        } else if (owner == OWNER_GEOFENCE) {
            // Do not keep geofence ownership when service is already stopped.
            clearOwner();
            owner = OWNER_NONE;
        }
        return owner;
    }

    private int normalizeOwner(int owner) {
        if (owner == OWNER_MANUAL || owner == OWNER_GEOFENCE) {
            return owner;
        }
        return OWNER_NONE;
    }
}
