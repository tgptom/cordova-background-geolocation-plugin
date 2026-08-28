package com.marianhello.bgloc;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.marianhello.bgloc.data.ConfigurationDAO;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.service.LocationServiceImpl;
import com.marianhello.bgloc.service.LocationServiceProxy;

import org.json.JSONException;

public final class GeofenceTransitionHandler {
    private static final String TAG = GeofenceTransitionHandler.class.getName();
    private static final int GEOFENCE_TRANSITION_ENTER = 1;
    private static final int GEOFENCE_TRANSITION_EXIT = 2;

    private GeofenceTransitionHandler() {
    }

    public static void onGeofenceTransition(Context context, int transitionType,
                                             boolean hasActiveInsideGeofence) {
        Context applicationContext = context.getApplicationContext();
        if (transitionType == GEOFENCE_TRANSITION_EXIT) {
            if (hasActiveInsideGeofence || !LocationServiceImpl.isRunning()) {
                return;
            }

            Log.i(TAG, "Stopping precise tracking after native geofence exit");
            try {
                new LocationServiceProxy(applicationContext).stop();
            } catch (RuntimeException error) {
                Log.e(TAG, "Unable to stop precise tracking after geofence exit", error);
            }
            return;
        }

        if (transitionType != GEOFENCE_TRANSITION_ENTER || LocationServiceImpl.isRunning()) {
            return;
        }

        ConfigurationDAO configurationDAO = DAOFactory.createConfigurationDAO(applicationContext);
        Config config;
        try {
            config = configurationDAO.retrieveConfiguration();
        } catch (JSONException error) {
            Log.e(TAG, "Unable to read configuration after geofence entry", error);
            return;
        }

        if (config == null || !config.hasValidUrl() || !Boolean.TRUE.equals(config.getStartForeground())) {
            Log.i(TAG, "Ignoring geofence entry without a valid foreground tracking configuration");
            return;
        }

        Log.i(TAG, "Starting precise tracking after native geofence entry");
        Intent serviceIntent = new Intent(applicationContext, LocationServiceImpl.class);
        serviceIntent.addFlags(Intent.FLAG_FROM_BACKGROUND);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent);
            } else {
                applicationContext.startService(serviceIntent);
            }
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to start precise tracking after geofence entry", error);
        }
    }
}