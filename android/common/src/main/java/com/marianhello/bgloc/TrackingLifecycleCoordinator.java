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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

final class TrackingLifecycleCoordinator {
    interface TimeoutCallback {
        void onTimeout(long generation);
    }

    interface LifecycleResultListener {
        void onLifecycleResult(LifecycleActionResult result);
    }

    static final class LifecycleActionResult {
        final int eventAction;
        final long acknowledgedGeneration;
        final long committedGeneration;
        final int committedOwner;
        final long replayGeneration;
        final int replayOwner;
        final boolean stale;
        final boolean late;
        final boolean cancelled;

        LifecycleActionResult(
                int eventAction,
                long acknowledgedGeneration,
                long committedGeneration,
                int committedOwner,
                boolean stale,
                boolean late,
                boolean cancelled
        ) {
            this(
                    eventAction,
                    acknowledgedGeneration,
                    committedGeneration,
                    committedOwner,
                    0L,
                    TrackingOwnershipStore.OWNER_NONE,
                    stale,
                    late,
                    cancelled
            );
        }

        LifecycleActionResult(
                int eventAction,
                long acknowledgedGeneration,
                long committedGeneration,
                int committedOwner,
                long replayGeneration,
                int replayOwner,
                boolean stale,
                boolean late,
                boolean cancelled
        ) {
            this.eventAction = eventAction;
            this.acknowledgedGeneration = acknowledgedGeneration;
            this.committedGeneration = committedGeneration;
            this.committedOwner = committedOwner;
            this.replayGeneration = replayGeneration;
            this.replayOwner = replayOwner;
            this.stale = stale;
            this.late = late;
            this.cancelled = cancelled;
        }
    }

    private static final Object LOCK = new Object();
    private static final long DEFAULT_ACK_TIMEOUT_MS = 15000L;
    private static final int STOP_TIMEOUT_MAX_RETRIES = 1;

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
    private final Map<Long, TimeoutCallback> startTimeoutCallbacks = new HashMap<>();
    private final Map<Long, Integer> stopTimeoutRetries = new HashMap<>();
    private final Map<LifecycleResultListener, Boolean> lifecycleResultListeners = new WeakHashMap<>();

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
            LifecycleActionResult result = handleServiceLifecycleAction(action, requestGeneration);
            notifyLifecycleResultListeners(result);
        }
    };

    private TrackingLifecycleCoordinator(Context context) {
        this(
                context,
                new TrackingOwnershipStore(context),
                new LocationServiceProxy(context),
                LocalBroadcastManager.getInstance(context),
                new Handler(Looper.getMainLooper())
        );
    }

    TrackingLifecycleCoordinator(
            Context context,
            TrackingOwnershipStore store,
            LocationServiceProxy serviceProxy,
            LocalBroadcastManager localBroadcastManager,
            Handler handler
    ) {
        this.context = context;
        this.store = store;
        this.serviceProxy = serviceProxy;
        this.localBroadcastManager = localBroadcastManager;
        this.handler = handler;
    }

    synchronized TrackingOwnershipStore.ReconciledState reconcileState() {
        ensureReceiverRegistered();
        TrackingOwnershipStore.ReconciledState state = store.reconcileWithServiceState(
                serviceProxy.isStarted(),
                System.currentTimeMillis()
        );
        if (!state.serviceStarted) {
            long pendingStopGeneration = store.getPendingStopGeneration();
            if (pendingStopGeneration != 0L && store.getPendingStopOwner() != TrackingOwnershipStore.OWNER_NONE) {
                handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STOPPED, pendingStopGeneration);
                state = store.reconcileWithServiceState(serviceProxy.isStarted(), System.currentTimeMillis());
            } else {
                long pendingStartGeneration = store.getPendingStartGeneration();
                int pendingStartOwner = store.getPendingStartOwner();
                if (pendingStartOwner != TrackingOwnershipStore.OWNER_NONE
                        && pendingStartGeneration != 0L
                        && store.isPendingStartQueuedForReplay(pendingStartGeneration)) {
                    replayQueuedPendingStartLocked(pendingStartGeneration, pendingStartOwner);
                    state = store.reconcileWithServiceState(serviceProxy.isStarted(), System.currentTimeMillis());
                } else if (pendingStartOwner != TrackingOwnershipStore.OWNER_NONE
                        && pendingStartGeneration != 0L
                        && store.isPendingStartAwaitingServiceAck(pendingStartGeneration)) {
                    dispatchPendingStartAwaitingServiceAckLocked(pendingStartGeneration, pendingStartOwner);
                    state = store.reconcileWithServiceState(serviceProxy.isStarted(), System.currentTimeMillis());
                }
            }
        }
        return state;
    }

    synchronized void addLifecycleResultListener(LifecycleResultListener listener) {
        if (listener == null) {
            return;
        }
        lifecycleResultListeners.put(listener, Boolean.TRUE);
    }

    synchronized void removeLifecycleResultListener(LifecycleResultListener listener) {
        if (listener == null) {
            return;
        }
        lifecycleResultListeners.remove(listener);
    }

    synchronized long requestStart(int owner, long timeoutMs, TimeoutCallback onTimeout) {
        ensureReceiverRegistered();
        long generation = store.setPendingStartOwner(owner, System.currentTimeMillis() + timeoutMs);
        if (generation != 0L) {
            putStartTimeoutCallback(generation, onTimeout);
        }
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
        clearStartTimeoutCallback(generation);
    }

    synchronized void clearPendingStartRequestOnly(long generation) {
        store.clearPendingStartOwnerIfGeneration(generation);
        clearStartTimeoutCallback(generation);
    }

    synchronized void clearPendingStart() {
        int pendingOwner = store.getPendingStartOwner();
        long pendingGeneration = store.getPendingStartGeneration();
        if (pendingOwner != TrackingOwnershipStore.OWNER_NONE && pendingGeneration != 0L) {
            store.markFailedStart(pendingGeneration, pendingOwner);
        }
        store.clearPendingStartOwner();
        clearStartTimeoutCallback(pendingGeneration);
        clearStartTimeoutLocked();
    }

    synchronized void cancelPendingStart() {
        int pendingOwner = store.getPendingStartOwner();
        long pendingGeneration = store.getPendingStartGeneration();
        if (pendingOwner != TrackingOwnershipStore.OWNER_NONE && pendingGeneration != 0L) {
            store.markCancelledStart(pendingGeneration, pendingOwner);
        }
        store.clearPendingStartOwner();
        clearStartTimeoutCallback(pendingGeneration);
        clearStartTimeoutLocked();
    }

    synchronized void clearPendingStop(long generation) {
        store.clearPendingStopOwnerIfGeneration(generation);
        stopTimeoutRetries.remove(generation);
    }

    synchronized void commitOwnerOnServiceStarted() {
        store.markServiceStartedAcknowledged();
        clearStartTimeoutLocked();
    }

    synchronized void clearOwnerOnServiceStopped() {
        store.onServiceStoppedAcknowledged();
        stopTimeoutRetries.clear();
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

    synchronized long getPendingStopGeneration() {
        return store.getPendingStopGeneration();
    }

    synchronized void clearPendingStop() {
        long pendingGeneration = store.getPendingStopGeneration();
        store.clearPendingStopOwner();
        stopTimeoutRetries.remove(pendingGeneration);
        clearStopTimeoutLocked();
    }

    synchronized boolean resumeQueuedPendingStart(long generation, long timeoutMs, TimeoutCallback onTimeout) {
        if (!store.isPendingStartQueuedForReplay(generation)) {
            return false;
        }
        boolean promoted = store.promotePendingStartToServiceAck(generation, System.currentTimeMillis() + timeoutMs);
        if (!promoted) {
            return false;
        }
        putStartTimeoutCallback(generation, onTimeout);
        scheduleStartTimeout(generation, timeoutMs, onTimeout);
        return true;
    }

    synchronized boolean isServiceStartedPersisted() {
        return store.isServiceStartedPersisted();
    }

    synchronized boolean isTerminalStartGenerationForOwner(long generation, int owner) {
        return store.isTerminalStartGenerationForOwner(generation, owner);
    }

    synchronized LifecycleActionResult handleServiceLifecycleAction(int action) {
        return handleServiceLifecycleAction(action, 0L);
    }

    synchronized LifecycleActionResult handleServiceLifecycleAction(int action, long requestGeneration) {
        if (action == LocationServiceImpl.MSG_ON_SERVICE_STARTED) {
            int pendingStartOwner = store.getPendingStartOwner();
            long pendingStartGeneration = store.getPendingStartGeneration();

            if (pendingStartOwner != TrackingOwnershipStore.OWNER_NONE
                    && requestGeneration != 0L
                    && requestGeneration == pendingStartGeneration) {
                store.onServiceStartedAcknowledged();
                store.clearFailedStartOwnerIfGeneration(requestGeneration);
                clearStartTimeoutCallback(requestGeneration);
                clearStartTimeoutLocked();
                return new LifecycleActionResult(
                        action,
                        requestGeneration,
                        requestGeneration,
                        pendingStartOwner,
                        false,
                        false,
                        false
                );
            }

            boolean stale = pendingStartOwner != TrackingOwnershipStore.OWNER_NONE;
            store.markServiceStartedAcknowledged();

            TrackingOwnershipStore.StartGenerationStatus status =
                    requestGeneration == 0L ? null : store.getStartGenerationStatus(requestGeneration);
            if (status != null) {
                boolean cancelled = status != null && status.cancelled;
                long replayGeneration = 0L;
                int replayOwner = TrackingOwnershipStore.OWNER_NONE;
                if (pendingStartOwner != TrackingOwnershipStore.OWNER_NONE
                        && pendingStartGeneration != 0L
                        && pendingStartGeneration != requestGeneration
                        && store.queuePendingStartForReplay(pendingStartGeneration)) {
                    replayGeneration = pendingStartGeneration;
                    replayOwner = pendingStartOwner;
                    clearStartTimeoutLocked();
                }
                boolean shouldStop = shouldIssueOrderlyStopForLateStart(replayGeneration != 0L);
                if (shouldStop) {
                    long stopGeneration = requestStop(status.owner, DEFAULT_ACK_TIMEOUT_MS, null);
                    try {
                        serviceProxy.stop(stopGeneration);
                    } catch (RuntimeException ignored) {
                        // keep pending STOP reconciliation state; timeout/ack will resolve
                    }
                }
                store.clearFailedStartOwnerIfGeneration(requestGeneration);
                return new LifecycleActionResult(
                        action,
                        requestGeneration,
                        0L,
                        TrackingOwnershipStore.OWNER_NONE,
                        0L,
                        TrackingOwnershipStore.OWNER_NONE,
                        true,
                        true,
                        cancelled
                );
            }

            if (requestGeneration != 0L) {
                store.clearFailedStartOwnerIfGeneration(requestGeneration);
            }

            return new LifecycleActionResult(
                    action,
                    requestGeneration,
                    0L,
                    TrackingOwnershipStore.OWNER_NONE,
                    stale,
                    false,
                    false
            );
        } else if (action == LocationServiceImpl.MSG_ON_SERVICE_STOPPED) {
            int pendingStopOwner = store.getPendingStopOwner();
            long pendingStopGeneration = store.getPendingStopGeneration();
            if (pendingStopOwner != TrackingOwnershipStore.OWNER_NONE
                    && requestGeneration != 0L
                    && requestGeneration == pendingStopGeneration) {
                long replayGeneration = 0L;
                int replayOwner = TrackingOwnershipStore.OWNER_NONE;
                int pendingStartOwner = store.getPendingStartOwner();
                long pendingStartGeneration = store.getPendingStartGeneration();
                if (pendingStartOwner != TrackingOwnershipStore.OWNER_NONE
                        && pendingStartGeneration != 0L
                        && store.isPendingStartQueuedForReplay(pendingStartGeneration)) {
                    replayGeneration = pendingStartGeneration;
                    replayOwner = pendingStartOwner;
                    store.onServiceStoppedAcknowledgedPreservingPendingStart();
                    stopTimeoutRetries.remove(requestGeneration);
                    clearStopTimeoutLocked();
                    replayQueuedPendingStartLocked(replayGeneration, replayOwner);
                } else {
                    clearOwnerOnServiceStopped();
                }
                return new LifecycleActionResult(
                        action,
                        requestGeneration,
                        requestGeneration,
                        pendingStopOwner,
                        replayGeneration,
                        replayOwner,
                        false,
                        false,
                        false
                );
            }
            if (pendingStopOwner == TrackingOwnershipStore.OWNER_NONE && requestGeneration == 0L) {
                clearOwnerOnServiceStopped();
                return new LifecycleActionResult(
                        action,
                        requestGeneration,
                        0L,
                        TrackingOwnershipStore.OWNER_NONE,
                        false,
                        false,
                        false
                );
            }
            return new LifecycleActionResult(
                    action,
                    requestGeneration,
                    0L,
                    TrackingOwnershipStore.OWNER_NONE,
                    true,
                    false,
                    false
            );
        }
        return new LifecycleActionResult(
                action,
                requestGeneration,
                0L,
                TrackingOwnershipStore.OWNER_NONE,
                false,
                false,
                false
        );
    }

    private boolean shouldIssueOrderlyStopForLateStart() {
        return shouldIssueOrderlyStopForLateStart(false);
    }

    private boolean shouldIssueOrderlyStopForLateStart(boolean allowQueuedPendingStart) {
        TrackingOwnershipStore.ReconciledState state = store.reconcileWithServiceState(true, System.currentTimeMillis());
        return state.serviceStarted
                && state.pendingStopOwner == TrackingOwnershipStore.OWNER_NONE
                && state.owner == TrackingOwnershipStore.OWNER_NONE
                && (allowQueuedPendingStart || state.pendingStartOwner == TrackingOwnershipStore.OWNER_NONE);
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

                    store.reconcileWithServiceState(
                            serviceProxy.isStarted(),
                            System.currentTimeMillis()
                    );

                    if (store.isPendingStartGeneration(generation)) {
                        store.markFailedStart(generation, store.getPendingStartOwner());
                        store.clearPendingStartOwnerIfGeneration(generation);
                        clearStartTimeoutCallback(generation);
                        shouldNotify = true;
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
        final long requestedTimeoutMs = timeoutMs;
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
                            int pendingStartOwner = store.getPendingStartOwner();
                            long pendingStartGeneration = store.getPendingStartGeneration();
                            boolean hasReplayCandidate = pendingStartOwner != TrackingOwnershipStore.OWNER_NONE
                                    && pendingStartGeneration != 0L;
                            boolean queuedForReplay = hasReplayCandidate
                                    && store.isPendingStartQueuedForReplay(pendingStartGeneration);
                            if (queuedForReplay) {
                                store.onServiceStoppedAcknowledgedPreservingPendingStart();
                                stopTimeoutRetries.remove(generation);
                                clearStopTimeoutLocked();
                                replayQueuedPendingStartLocked(pendingStartGeneration, pendingStartOwner);
                            } else {
                                store.clearOwnerOnServiceStopped();
                                stopTimeoutRetries.remove(generation);
                            }
                        } else {
                            int retryCount = getStopRetryCount(generation);
                            if (retryCount < STOP_TIMEOUT_MAX_RETRIES) {
                                stopTimeoutRetries.put(generation, retryCount + 1);
                                try {
                                    serviceProxy.stop(generation);
                                } catch (RuntimeException ignored) {
                                    // keep pending stop state; retry/failure path remains deterministic
                                }
                                long remainingTimeoutMs = resolveRemainingStopTimeoutMs(generation, requestedTimeoutMs);
                                if (remainingTimeoutMs > 0L) {
                                    scheduleStopTimeout(generation, remainingTimeoutMs, callback);
                                } else {
                                    int pendingStopOwner = store.getPendingStopOwner();
                                    if (store.getOwner() == TrackingOwnershipStore.OWNER_NONE
                                            && pendingStopOwner != TrackingOwnershipStore.OWNER_NONE) {
                                        store.setOwner(pendingStopOwner);
                                    }
                                    failQueuedPendingStartLocked();
                                    store.clearPendingStopOwnerIfGeneration(generation);
                                    stopTimeoutRetries.remove(generation);
                                    shouldNotify = true;
                                }
                                return;
                            }
                            int pendingStopOwner = store.getPendingStopOwner();
                            if (store.getOwner() == TrackingOwnershipStore.OWNER_NONE
                                    && pendingStopOwner != TrackingOwnershipStore.OWNER_NONE) {
                                store.setOwner(pendingStopOwner);
                            }
                            failQueuedPendingStartLocked();
                            store.clearPendingStopOwnerIfGeneration(generation);
                            stopTimeoutRetries.remove(generation);
                            shouldNotify = true;
                        }
                    }
                }

                if (shouldNotify && callback != null) {
                    callback.onTimeout(generation);
                }
            }
        };
        long effectiveTimeoutMs = resolveRemainingStopTimeoutMs(generation, timeoutMs);
        long delayMs;
        if (generation != 0L) {
            delayMs = Math.max(0L, effectiveTimeoutMs);
        } else {
            delayMs = effectiveTimeoutMs <= 0L ? DEFAULT_ACK_TIMEOUT_MS : effectiveTimeoutMs;
        }
        handler.postDelayed(
                pendingStopTimeout,
                delayMs
        );
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

    private void replayQueuedPendingStartLocked(long replayGeneration, int replayOwner) {
        TimeoutCallback timeoutCallback = getStartTimeoutCallback(replayGeneration);
        boolean resumed = resumeQueuedPendingStart(replayGeneration, DEFAULT_ACK_TIMEOUT_MS, timeoutCallback);
        if (!resumed) {
            failQueuedPendingStartLocked();
            return;
        }
        dispatchStartForOwnerLocked(replayOwner, replayGeneration, true);
    }

    private void failQueuedPendingStartLocked() {
        long pendingStartGeneration = store.getPendingStartGeneration();
        int pendingStartOwner = store.getPendingStartOwner();
        if (pendingStartOwner == TrackingOwnershipStore.OWNER_NONE || pendingStartGeneration == 0L) {
            return;
        }
        store.markFailedStart(pendingStartGeneration, pendingStartOwner);
        store.clearPendingStartOwnerIfGeneration(pendingStartGeneration);
        notifyStartFailureLocked(pendingStartGeneration);
    }

    private void notifyStartFailureLocked(long generation) {
        TimeoutCallback callback = getStartTimeoutCallback(generation);
        if (callback != null) {
            callback.onTimeout(generation);
        }
        clearStartTimeoutCallback(generation);
    }

    private void dispatchPendingStartAwaitingServiceAckLocked(long generation, int owner) {
        scheduleStartTimeout(generation, DEFAULT_ACK_TIMEOUT_MS, getStartTimeoutCallback(generation));
        dispatchStartForOwnerLocked(owner, generation, false);
    }

    private void dispatchStartForOwnerLocked(int owner, long generation, boolean failQueuedOnDispatchError) {
        try {
            if (owner == TrackingOwnershipStore.OWNER_GEOFENCE) {
                serviceProxy.startForegroundService(generation);
            } else {
                serviceProxy.start(generation);
            }
        } catch (RuntimeException e) {
            if (failQueuedOnDispatchError) {
                failQueuedPendingStartLocked();
                return;
            }
            failPendingStartGenerationLocked(generation);
        }
    }

    private TimeoutCallback getStartTimeoutCallback(long generation) {
        if (generation == 0L) {
            return null;
        }
        return startTimeoutCallbacks.get(generation);
    }

    private void putStartTimeoutCallback(long generation, TimeoutCallback callback) {
        if (generation == 0L) {
            return;
        }
        if (callback == null) {
            startTimeoutCallbacks.remove(generation);
        } else {
            startTimeoutCallbacks.put(generation, callback);
        }
    }

    private void clearStartTimeoutCallback(long generation) {
        if (generation == 0L) {
            return;
        }
        startTimeoutCallbacks.remove(generation);
    }

    private int getStopRetryCount(long generation) {
        Integer count = stopTimeoutRetries.get(generation);
        return count == null ? 0 : count.intValue();
    }

    private long resolveRemainingStopTimeoutMs(long generation, long fallbackTimeoutMs) {
        long persistedDeadline = store.getPendingStopDeadline();
        if (generation == 0L || persistedDeadline <= 0L) {
            return fallbackTimeoutMs <= 0L ? DEFAULT_ACK_TIMEOUT_MS : fallbackTimeoutMs;
        }
        long remaining = persistedDeadline - System.currentTimeMillis();
        if (remaining <= 0L) {
            return 0L;
        }
        return remaining;
    }

    private void failPendingStartGenerationLocked(long generation) {
        if (!store.isPendingStartGeneration(generation)) {
            return;
        }
        int pendingOwner = store.getPendingStartOwner();
        if (pendingOwner != TrackingOwnershipStore.OWNER_NONE) {
            store.markFailedStart(generation, pendingOwner);
        }
        store.clearPendingStartOwnerIfGeneration(generation);
        notifyStartFailureLocked(generation);
    }

    private void notifyLifecycleResultListeners(LifecycleActionResult result) {
        ArrayList<LifecycleResultListener> listeners;
        synchronized (this) {
            if (result == null || lifecycleResultListeners.isEmpty()) {
                return;
            }
            listeners = new ArrayList<>(lifecycleResultListeners.keySet());
        }
        for (LifecycleResultListener listener : listeners) {
            if (listener != null) {
                listener.onLifecycleResult(result);
            }
        }
    }
}
