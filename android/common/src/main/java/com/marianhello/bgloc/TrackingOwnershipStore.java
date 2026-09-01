package com.marianhello.bgloc;

import android.content.Context;
import android.content.SharedPreferences;

final class TrackingOwnershipStore {
    static final int OWNER_NONE = 0;
    static final int OWNER_MANUAL = 1;
    static final int OWNER_GEOFENCE = 2;

    private static final String PREFS_NAME = "com.marianhello.bgloc.geofence";
    private static final String OWNER_KEY = "tracking_owner";

    private final SharedPreferences prefs;

    TrackingOwnershipStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    int getOwner() {
        int owner = prefs.getInt(OWNER_KEY, OWNER_NONE);
        if (owner != OWNER_MANUAL && owner != OWNER_GEOFENCE) {
            return OWNER_NONE;
        }
        return owner;
    }

    void setOwner(int owner) {
        if (owner != OWNER_MANUAL && owner != OWNER_GEOFENCE) {
            clearOwner();
            return;
        }
        prefs.edit().putInt(OWNER_KEY, owner).apply();
    }

    void clearOwner() {
        prefs.edit().remove(OWNER_KEY).apply();
    }

    int reconcileWithServiceState(boolean serviceStarted) {
        int owner = getOwner();
        if (!serviceStarted && owner != OWNER_NONE) {
            clearOwner();
            return OWNER_NONE;
        }
        return owner;
    }
}
