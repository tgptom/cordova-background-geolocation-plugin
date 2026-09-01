package com.marianhello.bgloc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.marianhello.bgloc.service.LocationServiceImpl;
import com.marianhello.bgloc.service.LocationServiceProxy;

final class TrackingLifecycleCoordinator {
    interface TimeoutCallback {
        void onTimeout(long generation);
    }

    private static final Object LOCK = new Object();
    private static final long DEFAULT_ACK_TIMEOUT_MS = 15000L;

    private static TrackingLifecycleCoordinator sInstance;

    static TrackingLifecycleCoordinator getInstance(Context context) {
        synchronized (LOCK) {
            if (sInstance == null) {
                sInstance = new TrackingLifecycleCoordinator(context.getApplicationContext());
            }
            return sInstance;
        }
    }

    private final Context context;
    private final TrackingOwnershipStore store;
    private final LocationServiceProxy serviceProxy;
    private final LocalBroadcastManager localBroadcastManager;
    private final Handler handler;

    private boolean receiverRegistered = false;
    private Runnable pendingStartTimeout;
    private Runnable pendingStopTimeout;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle extras = intent.getExtras();
            if (extras == null) {
                return;
            }
            int action = extras.getInt("action");
            long requestGeneration = extras.getLong("requestGeneration", 0L);
            handleServiceLifecycleAction(action, requestGeneration);
        }
    };

    private TrackingLifecycleCoordinator(Context context) {
        this.context = context;
        this.store = new TrackingOwnershipStore(context);
        this.serviceProxy = new LocationServiceProxy(context);
        this.localBroadcastManager = LocalBroadcastManager.getInstance(context);
        this.handler = new Handler(Looper.getMainLooper());
    }

    synchronized TrackingOwnershipStore.ReconciledState reconcileState() {
        return store.reconcileWithServiceState(serviceProxy.isStarted(), System.currentTimeMillis());
    }

    synchronized long requestStart(int owner, long timeoutMs, TimeoutCallback onTimeout) {
        ensureReceiverRegistered();
        long generation = store.setPendingStartOwner(owner, System.currentTimeMillis() + timeoutMs);
        scheduleStartTimeout(generation, timeoutMs, onTimeout);
        return generation;
    }

    synchronized long requestPermissionPendingStart(int owner, long timeoutMs) {
        ensureReceiverRegistered();
        clearStartTimeoutLocked();
        return store.setPendingStartPermissionOwner(owner, System.currentTimeMillis() + timeoutMs);
    }

    synchronized boolean promotePendingStartToServiceAck(long generation, long timeoutMs, TimeoutCallback onTimeout) {
        ensureReceiverRegistered();
        boolean promoted = store.promotePendingStartToServiceAck(generation, System.currentTimeMillis() + timeoutMs);
        if (promoted) {
            scheduleStartTimeout(generation, timeoutMs, onTimeout);
        }
        return promoted;
    }

    synchronized long requestStop(int owner, long timeoutMs, TimeoutCallback onTimeout) {
        ensureReceiverRegistered();
        long generation = store.setPendingStopOwner(owner, System.currentTimeMillis() + timeoutMs);
        scheduleStopTimeout(generation, timeoutMs, onTimeout);
        return generation;
    }

    synchronized void clearPendingStart(long generation) {
        int pendingOwner = store.getPendingStartOwner();
        if (store.isPendingStartGeneration(generation)) {
            store.markFailedStart(generation, pendingOwner);
        }
        store.clearPendingStartOwnerIfGeneration(generation);
    }

    synchronized void clearPendingStartRequestOnly(long generation) {
        store.clearPendingStartOwnerIfGeneration(generation);
    }

    synchronized void clearPendingStart() {
        int pendingOwner = store.getPendingStartOwner();
        long pendingGeneration = store.getPendingStartGeneration();
        if (pendingOwner != TrackingOwnershipStore.OWNER_NONE && pendingGeneration != 0L) {
            store.markFailedStart(pendingGeneration, pendingOwner);
        }
        store.clearPendingStartOwner();
        clearStartTimeoutLocked();
    }

    synchronized void clearPendingStop(long generation) {
        store.clearPendingStopOwnerIfGeneration(generation);
    }

    synchronized void commitOwnerOnServiceStarted() {
        store.markServiceStartedAcknowledged();
        clearStartTimeoutLocked();
    }

    synchronized void clearOwnerOnServiceStopped() {
        store.onServiceStoppedAcknowledged();
        clearStopTimeoutLocked();
    }

    synchronized int getPendingStartOwner() {
        return store.getPendingStartOwner();
    }

    synchronized long getPendingStartGeneration() {
        return store.getPendingStartGeneration();
    }

    synchronized int getOwner() {
        return store.getOwner();
    }

    synchronized void clearOwner() {
        store.clearOwner();
    }

    synchronized int getPendingStopOwner() {
        return store.getPendingStopOwner();
    }

    synchronized void clearPendingStop() {
        store.clearPendingStopOwner();
        clearStopTimeoutLocked();
    }

    synchronized boolean isServiceStartedPersisted() {
        return store.isServiceStartedPersisted();
    }

    synchronized void handleServiceLifecycleAction(int action) {
        handleServiceLifecycleAction(action, 0L);
    }

    synchronized void handleServiceLifecycleAction(int action, long requestGeneration) {
        if (action == LocationServiceImpl.MSG_ON_SERVICE_STARTED) {
            int pendingStartOwner = store.getPendingStartOwner();
            long pendingStartGeneration = store.getPendingStartGeneration();
            if (pendingStartOwner != TrackingOwnershipStore.OWNER_NONE
                    && (requestGeneration == 0L
                    || requestGeneration == pendingStartGeneration
                    || requestGeneration < pendingStartGeneration)) {
                store.onServiceStartedAcknowledged();
                store.clearFailedStartOwnerIfGeneration(requestGeneration);
                clearStartTimeoutLocked();
                return;
            }

            store.onServiceStartedAcknowledged();
            clearStartTimeoutLocked();

            if (requestGeneration != 0L
                    && store.isFailedStartGenerationForOwner(
                    requestGeneration,
                    TrackingOwnershipStore.OWNER_GEOFENCE
            )) {
                TrackingOwnershipStore.ReconciledState state = store.reconcileWithServiceState(true, System.currentTimeMillis());
                if (state.pendingStartOwner == TrackingOwnershipStore.OWNER_NONE
                        && state.owner == TrackingOwnershipStore.OWNER_NONE) {
                    requestStop(TrackingOwnershipStore.OWNER_GEOFENCE, DEFAULT_ACK_TIMEOUT_MS, null);
                    try {
                        serviceProxy.stop();
                    } catch (RuntimeException ignored) {
                        // keep pending STOP reconciliation state; timeout/ack will resolve
                    }
                }
                store.clearFailedStartOwnerIfGeneration(requestGeneration);
            }
        } else if (action == LocationServiceImpl.MSG_ON_SERVICE_STOPPED) {
            clearOwnerOnServiceStopped();
        }
    }

    private void ensureReceiverRegistered() {
        if (receiverRegistered) {
            return;
        }
        localBroadcastManager.registerReceiver(receiver, new IntentFilter(LocationServiceImpl.ACTION_BROADCAST));
        receiverRegistered = true;
    }

    private void scheduleStartTimeout(final long generation, long timeoutMs, final TimeoutCallback callback) {
        clearStartTimeoutLocked();
        pendingStartTimeout = new Runnable() {
            @Override
            public void run() {
                boolean shouldNotify = false;
                synchronized (TrackingLifecycleCoordinator.this) {
                    if (!store.isPendingStartGeneration(generation)) {
                        return;
                    }

                    TrackingOwnershipStore.ReconciledState state = store.reconcileWithServiceState(
                            serviceProxy.isStarted(),
                            System.currentTimeMillis()
                    );

                    if (store.isPendingStartGeneration(generation)) {
                        if (store.isServiceStartedPersisted()) {
                            store.commitOwnerOnServiceStarted();
                        } else {
                            store.markFailedStart(generation, store.getPendingStartOwner());
                            store.clearPendingStartOwnerIfGeneration(generation);
                            shouldNotify = true;
                        }
                    }
                }

                if (shouldNotify && callback != null) {
                    callback.onTimeout(generation);
                }
            }
        };
        handler.postDelayed(pendingStartTimeout, timeoutMs <= 0L ? DEFAULT_ACK_TIMEOUT_MS : timeoutMs);
    }

    private void scheduleStopTimeout(final long generation, long timeoutMs, final TimeoutCallback callback) {
        clearStopTimeoutLocked();
        pendingStopTimeout = new Runnable() {
            @Override
            public void run() {
                boolean shouldNotify = false;
                synchronized (TrackingLifecycleCoordinator.this) {
                    if (!store.isPendingStopGeneration(generation)) {
                        return;
                    }

                    TrackingOwnershipStore.ReconciledState state = store.reconcileWithServiceState(
                            serviceProxy.isStarted(),
                            System.currentTimeMillis()
                    );

                    if (store.isPendingStopGeneration(generation)) {
                        if (!state.serviceStarted) {
                            store.clearOwnerOnServiceStopped();
                        } else {
                            store.clearPendingStopOwnerIfGeneration(generation);
                            shouldNotify = true;
                        }
                    }
                }

                if (shouldNotify && callback != null) {
                    callback.onTimeout(generation);
                }
            }
        };
        handler.postDelayed(pendingStopTimeout, timeoutMs <= 0L ? DEFAULT_ACK_TIMEOUT_MS : timeoutMs);
    }

    private void clearStartTimeoutLocked() {
        if (pendingStartTimeout != null) {
            handler.removeCallbacks(pendingStartTimeout);
            pendingStartTimeout = null;
        }
    }

    private void clearStopTimeoutLocked() {
        if (pendingStopTimeout != null) {
            handler.removeCallbacks(pendingStopTimeout);
            pendingStopTimeout = null;
        }
    }
}
