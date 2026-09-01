package com.marianhello.bgloc;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.marianhello.bgloc.data.ConfigurationDAO;
import com.marianhello.bgloc.data.DAOFactory;
import com.marianhello.bgloc.service.LocationServiceImpl;
import com.marianhello.bgloc.service.LocationServiceProxy;

import org.json.JSONException;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Keep
public final class GeofenceTransitionHandler {
    public static final String COMPATIBILITY_NOTE =
            "Companion contract: tgptom/cordova-plugin-geofence PR #9 (hardened geofence transition contract)";

    private static final String TAG = GeofenceTransitionHandler.class.getName();
    private static final long SERVICE_ACK_TIMEOUT_MS = 15000L;

    private GeofenceTransitionHandler() {}

    public static void onGeofenceTransition(Context context, int transitionType,
                                            boolean hasActiveInsideGeofence) {
        Context applicationContext = context.getApplicationContext();
        LocationServiceProxy serviceProxy = new LocationServiceProxy(applicationContext);
        TrackingOwnershipStore ownerStore = new TrackingOwnershipStore(applicationContext);
        boolean serviceStarted = serviceProxy.isStarted();
        int owner = ownerStore.reconcileWithServiceState(serviceStarted);

        int pendingStartOwner = ownerStore.getPendingStartOwner();
        if (!serviceStarted && pendingStartOwner == TrackingOwnershipStore.OWNER_MANUAL) {
            ownerStore.clearPendingStartOwner();
        }

        if (serviceStarted && pendingStartOwner == TrackingOwnershipStore.OWNER_MANUAL) {
            owner = TrackingOwnershipStore.OWNER_MANUAL;
        }

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
            stopGeofenceOwnedTracking(applicationContext, serviceProxy, ownerStore);
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
            ownerStore.clearPendingStartOwner();
            return;
        }
        if (!Boolean.TRUE.equals(config.getStartForeground())) {
            Log.w(TAG, "Ignoring geofence ENTER/DWELL because startForeground is disabled.");
            ownerStore.clearPendingStartOwner();
            return;
        }

        ServiceAckReceiver ackReceiver = new ServiceAckReceiver(context, LocationServiceImpl.MSG_ON_SERVICE_STARTED);

        try {
            Log.i(TAG, "Starting precise tracking after geofence ENTER/DWELL");
            ownerStore.setPendingStartOwner(TrackingOwnershipStore.OWNER_GEOFENCE);
            ackReceiver.register();
            serviceProxy.startForegroundService();
            if (!ackReceiver.await()) {
                ownerStore.clearPendingStartOwner();
                Log.w(TAG, "Timed out waiting for geofence start acknowledgement");
                return;
            }
            ownerStore.commitOwnerOnServiceStarted();
        } catch (SecurityException error) {
            ownerStore.clearPendingStartOwner();
            Log.e(TAG, "Unable to start geofence tracking: missing foreground-service or location permissions", error);
        } catch (IllegalStateException error) {
            ownerStore.clearPendingStartOwner();
            Log.e(TAG, "Unable to start geofence tracking from background state. Check app foreground-service configuration.", error);
        } catch (RuntimeException error) {
            ownerStore.clearPendingStartOwner();
            Log.e(TAG, "Unable to start geofence tracking due to runtime failure", error);
        } finally {
            ackReceiver.unregister();
        }
    }

    private static void stopGeofenceOwnedTracking(Context context, LocationServiceProxy serviceProxy,
                                                  TrackingOwnershipStore ownerStore) {
        ServiceAckReceiver ackReceiver = new ServiceAckReceiver(context, LocationServiceImpl.MSG_ON_SERVICE_STOPPED);
        try {
            Log.i(TAG, "Stopping precise tracking after final geofence EXIT");
            ownerStore.setPendingStopOwner(TrackingOwnershipStore.OWNER_GEOFENCE);
            ackReceiver.register();
            serviceProxy.stop();
            if (!ackReceiver.await()) {
                if (!serviceProxy.isStarted()) {
                    ownerStore.clearOwnerOnServiceStopped();
                } else {
                    ownerStore.clearPendingStopOwner();
                }
                Log.e(TAG, "Timed out waiting for geofence stop acknowledgement");
                return;
            }
            ownerStore.clearOwnerOnServiceStopped();
        } catch (RuntimeException error) {
            ownerStore.clearPendingStopOwner();
            Log.e(TAG, "Unable to stop geofence tracking via service STOP command", error);
        } finally {
            ackReceiver.unregister();
        }
    }

    private static final class ServiceAckReceiver {
        private final Context context;
        private final int expectedAction;
        private final CountDownLatch latch = new CountDownLatch(1);
        private final LocalBroadcastManager broadcastManager;
        private boolean registered;
        private final android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Bundle extras = intent.getExtras();
                if (extras == null) {
                    return;
                }
                int action = extras.getInt("action");
                if (action == expectedAction) {
                    latch.countDown();
                }
            }
        };

        ServiceAckReceiver(Context context, int expectedAction) {
            this.context = context.getApplicationContext();
            this.expectedAction = expectedAction;
            this.broadcastManager = LocalBroadcastManager.getInstance(this.context);
        }

        void register() {
            if (registered) {
                return;
            }
            broadcastManager.registerReceiver(receiver, new IntentFilter(LocationServiceImpl.ACTION_BROADCAST));
            registered = true;
        }

        boolean await() {
            try {
                return latch.await(SERVICE_ACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void unregister() {
            if (!registered) {
                return;
            }
            broadcastManager.unregisterReceiver(receiver);
            registered = false;
        }
    }
}
