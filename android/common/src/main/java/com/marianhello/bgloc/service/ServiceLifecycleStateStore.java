package com.marianhello.bgloc.service;

import android.content.Context;
import android.content.SharedPreferences;

final class ServiceLifecycleStateStore {
    private static final String PREFS_NAME = "com.marianhello.bgloc.geofence";
    private static final String SERVICE_STARTED_KEY = "service_started";
    private static final String SERVICE_GENERATION_KEY = "service_generation";

    private final SharedPreferences prefs;

    ServiceLifecycleStateStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    long markStarted() {
        return markState(true);
    }

    long markStopped() {
        return markState(false);
    }

    private long markState(boolean started) {
        boolean previous = prefs.getBoolean(SERVICE_STARTED_KEY, false);
        long generation = prefs.getLong(SERVICE_GENERATION_KEY, 0L);
        if (previous != started) {
            generation += 1L;
        }
        prefs.edit()
                .putBoolean(SERVICE_STARTED_KEY, started)
                .putLong(SERVICE_GENERATION_KEY, generation)
                .apply();
        return generation;
    }
}
