package com.marianhello.bgloc;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Keep;

import com.marianhello.bgloc.data.ConfigurationDAO;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.service.LocationServiceProxy;

import org.json.JSONException;

@Keep
public final class GeofenceTransitionHandler {
    public static final String COMPATIBILITY_NOTE =
            "Companion contract: tgptom/cordova-plugin-geofence PR #8";

    private static final String TAG = GeofenceTransitionHandler.class.getName();

    private GeofenceTransitionHandler() {}

    public static void onGeofenceTransition(Context context, int transitionType,
                                            boolean hasActiveInsideGeofence) {
        Context applicationContext = context.getApplicationContext();
        LocationServiceProxy serviceProxy = new LocationServiceProxy(applicationContext);
        TrackingOwnershipStore ownerStore = new TrackingOwnershipStore(applicationContext);
        boolean serviceStarted = serviceProxy.isStarted();
        int owner = ownerStore.getOwner();
        GeofenceOwnershipEvaluator.Result evaluation = GeofenceOwnershipEvaluator.evaluate(
                transitionType,
                hasActiveInsideGeofence,
                serviceStarted,
                owner
        );

        if (evaluation.shouldClearOwner) {
            ownerStore.clearOwner();
            Log.i(TAG, "Cleared stale tracking owner because service is not running");
        }

        if (evaluation.shouldStop) {
            stopGeofenceOwnedTracking(serviceProxy, ownerStore);
            return;
        }

        if (!evaluation.shouldStart) {
            if (transitionType == GeofenceOwnershipEvaluator.TRANSITION_EXIT && hasActiveInsideGeofence) {
                Log.i(TAG, "Ignoring geofence EXIT while another geofence is still active");
            } else if ((transitionType == GeofenceOwnershipEvaluator.TRANSITION_ENTER
                    || transitionType == GeofenceOwnershipEvaluator.TRANSITION_DWELL) && serviceStarted) {
                if (owner == TrackingOwnershipStore.OWNER_MANUAL) {
                    Log.i(TAG, "Ignoring geofence ENTER/DWELL because manual tracking owner is active");
                } else {
                    Log.i(TAG, "Ignoring geofence ENTER/DWELL because tracking is already active");
                }
            } else if ((transitionType == GeofenceOwnershipEvaluator.TRANSITION_ENTER
                    || transitionType == GeofenceOwnershipEvaluator.TRANSITION_DWELL) && !hasActiveInsideGeofence) {
                Log.i(TAG, "Ignoring geofence ENTER/DWELL because no active geofence remains");
            } else if (transitionType == GeofenceOwnershipEvaluator.TRANSITION_EXIT
                    && owner != TrackingOwnershipStore.OWNER_GEOFENCE) {
                Log.i(TAG, "Ignoring geofence EXIT because tracking owner is not geofence");
            }
            return;
        }

        startGeofenceOwnedTracking(applicationContext, serviceProxy, ownerStore);
    }

    private static void startGeofenceOwnedTracking(Context context, LocationServiceProxy serviceProxy,
                                                   TrackingOwnershipStore ownerStore) {
        ConfigurationDAO configurationDAO = DAOFactory.createConfigurationDAO(context);
        Config config;
        try {
            config = configurationDAO.retrieveConfiguration();
        } catch (JSONException error) {
            Log.e(TAG, "Unable to read persisted configuration for geofence start", error);
            return;
        }

        if (config == null || !config.hasValidUrl()) {
            Log.i(TAG, "Ignoring geofence ENTER/DWELL because tracking is not configured with a valid URL");
            return;
        }
        if (!Boolean.TRUE.equals(config.getStartForeground())) {
            Log.w(TAG, "Ignoring geofence ENTER/DWELL because startForeground is disabled.");
            return;
        }

        try {
            Log.i(TAG, "Starting precise tracking after geofence ENTER/DWELL");
            ownerStore.setOwner(TrackingOwnershipStore.OWNER_GEOFENCE);
            serviceProxy.startForegroundService();
            if (!serviceProxy.isStarted()) {
                ownerStore.clearOwner();
                Log.w(TAG, "Geofence start command completed but location service is still not running");
            }
        } catch (SecurityException error) {
            ownerStore.clearOwner();
            Log.e(TAG, "Unable to start geofence tracking: missing foreground-service or location permissions", error);
        } catch (IllegalStateException error) {
            ownerStore.clearOwner();
            Log.e(TAG, "Unable to start geofence tracking from background state. Check app foreground-service configuration.", error);
        } catch (RuntimeException error) {
            ownerStore.clearOwner();
            Log.e(TAG, "Unable to start geofence tracking due to runtime failure", error);
        }
    }

    private static void stopGeofenceOwnedTracking(LocationServiceProxy serviceProxy,
                                                  TrackingOwnershipStore ownerStore) {
        try {
            Log.i(TAG, "Stopping precise tracking after final geofence EXIT");
            serviceProxy.stop();
            if (serviceProxy.isStarted()) {
                Log.e(TAG, "Geofence stop command completed but location service is still running");
                return;
            }
            ownerStore.clearOwner();
        } catch (RuntimeException error) {
            Log.e(TAG, "Unable to stop geofence tracking via service STOP command", error);
        }
    }
}
