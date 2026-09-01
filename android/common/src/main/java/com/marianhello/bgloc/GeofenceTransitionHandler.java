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
            "Companion contract: tgptom/cordova-plugin-geofence PR #11 or successor (hardened geofence transition contract)";

    private static final String TAG = GeofenceTransitionHandler.class.getName();
    private static final long ACK_TIMEOUT_MS = 15000L;

    private GeofenceTransitionHandler() {}

    public static void onGeofenceTransition(Context context, int transitionType,
                                            boolean hasActiveInsideGeofence) {
        Context applicationContext = context.getApplicationContext();
        TrackingLifecycleCoordinator coordinator = TrackingLifecycleCoordinator.getInstance(applicationContext);
        TrackingOwnershipStore.ReconciledState state = coordinator.reconcileState();

        GeofenceOwnershipEvaluator.Result evaluation = GeofenceOwnershipEvaluator.evaluate(
                transitionType,
                hasActiveInsideGeofence,
                state.serviceStarted,
                state.owner,
                state.pendingStartOwner
        );

        if (evaluation.shouldClearOwner) {
            coordinator.clearOwner();
            Log.i(TAG, "Cleared stale tracking owner because service is not running");
        }

        if (evaluation.shouldStop) {
            stopGeofenceOwnedTracking(applicationContext);
            return;
        }

        if (!evaluation.shouldStart) {
            if (transitionType == GeofenceOwnershipEvaluator.TRANSITION_EXIT && hasActiveInsideGeofence) {
                Log.i(TAG, "Ignoring geofence EXIT while another geofence is still active");
            } else if ((transitionType == GeofenceOwnershipEvaluator.TRANSITION_ENTER
                    || transitionType == GeofenceOwnershipEvaluator.TRANSITION_DWELL) && state.serviceStarted) {
                if (state.owner == TrackingOwnershipStore.OWNER_MANUAL) {
                    Log.i(TAG, "Ignoring geofence ENTER/DWELL because manual tracking owner is active");
                } else {
                    Log.i(TAG, "Ignoring geofence ENTER/DWELL because tracking is already active");
                }
            } else if ((transitionType == GeofenceOwnershipEvaluator.TRANSITION_ENTER
                    || transitionType == GeofenceOwnershipEvaluator.TRANSITION_DWELL) && !hasActiveInsideGeofence) {
                Log.i(TAG, "Ignoring geofence ENTER/DWELL because no active geofence remains");
            } else if (transitionType == GeofenceOwnershipEvaluator.TRANSITION_EXIT
                    && state.owner != TrackingOwnershipStore.OWNER_GEOFENCE) {
                Log.i(TAG, "Ignoring geofence EXIT because tracking owner is not geofence");
            } else if ((transitionType == GeofenceOwnershipEvaluator.TRANSITION_ENTER
                    || transitionType == GeofenceOwnershipEvaluator.TRANSITION_DWELL)
                    && state.pendingStartOwner == TrackingOwnershipStore.OWNER_MANUAL) {
                Log.i(TAG, "Ignoring geofence ENTER/DWELL because manual tracking startup is pending");
            }
            return;
        }

        startGeofenceOwnedTracking(applicationContext, coordinator);
    }

    private static void startGeofenceOwnedTracking(Context context,
                                                   TrackingLifecycleCoordinator coordinator) {
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

        long generation = 0L;
        try {
            Log.i(TAG, "Starting precise tracking after geofence ENTER/DWELL");
            generation = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, ACK_TIMEOUT_MS, null);
            LocationServiceProxy serviceProxy = new LocationServiceProxy(context);
            serviceProxy.startForegroundService(generation);
        } catch (RuntimeException error) {
            if (generation != 0L) {
                coordinator.clearPendingStart(generation);
            } else {
                coordinator.clearPendingStart();
            }
            Log.e(TAG, "Unable to start geofence tracking due to runtime failure", error);
        }
    }

    private static void stopGeofenceOwnedTracking(Context context) {
        TrackingLifecycleCoordinator coordinator = TrackingLifecycleCoordinator.getInstance(context);
        long generation = 0L;
        try {
            Log.i(TAG, "Stopping precise tracking after final geofence EXIT");
            generation = coordinator.requestStop(TrackingOwnershipStore.OWNER_GEOFENCE, ACK_TIMEOUT_MS, null);
            LocationServiceProxy serviceProxy = new LocationServiceProxy(context);
            serviceProxy.stop();
        } catch (RuntimeException error) {
            if (generation != 0L) {
                coordinator.clearPendingStop(generation);
            } else {
                coordinator.clearPendingStop();
            }
            Log.e(TAG, "Unable to stop geofence tracking via service STOP command", error);
        }
    }
}
