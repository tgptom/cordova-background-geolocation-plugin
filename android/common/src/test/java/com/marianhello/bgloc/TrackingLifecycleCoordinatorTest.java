package com.marianhello.bgloc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.marianhello.bgloc.service.LocationServiceImpl;
import com.marianhello.bgloc.service.LocationServiceProxy;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
public class TrackingLifecycleCoordinatorTest {
    private Context context;
    private TrackingLifecycleCoordinator coordinator;
    private TrackingOwnershipStore store;
    private TestLocationServiceProxy serviceProxy;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.application.getApplicationContext();
        context.getSharedPreferences("com.marianhello.bgloc.geofence", Context.MODE_PRIVATE).edit().clear().commit();
        store = new TrackingOwnershipStore(context);
        serviceProxy = new TestLocationServiceProxy(context);
        coordinator = new TrackingLifecycleCoordinator(
                context,
                store,
                serviceProxy,
                LocalBroadcastManager.getInstance(context),
                new Handler(Looper.getMainLooper())
        );
        coordinator.clearPendingStart();
        coordinator.clearPendingStop();
        coordinator.clearOwner();
    }

    @Test
    public void startTimeoutThenLateStartRequestsOrderlyStop() {
        final AtomicInteger timeoutCount = new AtomicInteger(0);
        long generation = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 50L, new TrackingLifecycleCoordinator.TimeoutCallback() {
            @Override
            public void onTimeout(long timedOutGeneration) {
                timeoutCount.incrementAndGet();
            }
        });

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);

        Assert.assertEquals(1, timeoutCount.get());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getPendingStartOwner());

        TrackingLifecycleCoordinator.LifecycleActionResult result =
                coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, generation);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getPendingStopOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getOwner());
        Assert.assertTrue(result.late);
        Assert.assertFalse(result.cancelled);
        Assert.assertEquals(1, serviceProxy.stopCallCount);
    }

    @Test
    public void matchingStopAutomaticallyReplaysQueuedPendingStartWithoutFacade() {
        long staleGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 50L, null);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);

        long newerGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 15000L, null);
        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, staleGeneration);
        long stopGeneration = coordinator.getPendingStopGeneration();

        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STOPPED, stopGeneration);

        Assert.assertEquals(1, serviceProxy.startForegroundCallCount);
        Assert.assertEquals(newerGeneration, serviceProxy.lastStartForegroundGeneration);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getPendingStartOwner());
        Assert.assertEquals(newerGeneration, coordinator.getPendingStartGeneration());
    }

    @Test
    public void duplicateStopAcksDoNotReplayTwice() {
        long staleGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 50L, null);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 15000L, null);

        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, staleGeneration);
        long stopGeneration = coordinator.getPendingStopGeneration();

        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STOPPED, stopGeneration);
        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STOPPED, stopGeneration);

        Assert.assertEquals(1, serviceProxy.startForegroundCallCount);
    }

    @Test
    public void stopAckMissingTimeoutWithStoppedServiceAutomaticallyReplaysQueuedStart() {
        long staleGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 50L, null);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        long newerGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 15000L, null);
        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, staleGeneration);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getPendingStopOwner());

        serviceProxy.started = false;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.SECONDS);

        Assert.assertEquals(1, serviceProxy.startForegroundCallCount);
        Assert.assertEquals(newerGeneration, serviceProxy.lastStartForegroundGeneration);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getPendingStartOwner());
    }

    @Test
    public void stopDispatchFailureDoesNotLoseQueuedNextGeneration() {
        serviceProxy.failStop = true;
        long staleGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 50L, null);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        long newerGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 15000L, null);

        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, staleGeneration);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getPendingStartOwner());
        Assert.assertEquals(newerGeneration, coordinator.getPendingStartGeneration());

        serviceProxy.started = false;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.SECONDS);

        Assert.assertEquals(newerGeneration, serviceProxy.lastStartForegroundGeneration);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getPendingStartOwner());
    }

    @Test
    public void replayDispatchFailureTerminatesQueuedStartAndInvokesCallbackOnce() {
        final AtomicInteger timeoutCount = new AtomicInteger(0);
        long staleGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 50L, null);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        long newerGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 15000L, new TrackingLifecycleCoordinator.TimeoutCallback() {
            @Override
            public void onTimeout(long generation) {
                timeoutCount.incrementAndGet();
            }
        });

        serviceProxy.failStartForeground = true;
        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, staleGeneration);
        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STOPPED, coordinator.getPendingStopGeneration());

        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getPendingStartOwner());
        Assert.assertTrue(coordinator.isTerminalStartGenerationForOwner(newerGeneration, TrackingOwnershipStore.OWNER_GEOFENCE));
        Assert.assertEquals(1, timeoutCount.get());
    }

    @Test
    public void processRecreationReconcilesQueuedReplayAndDispatchesStart() {
        long staleGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 50L, null);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        long newerGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 15000L, null);
        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, staleGeneration);

        TestLocationServiceProxy recreatedProxy = new TestLocationServiceProxy(context);
        recreatedProxy.started = false;
        TrackingLifecycleCoordinator recreatedCoordinator = new TrackingLifecycleCoordinator(
                context,
                new TrackingOwnershipStore(context),
                recreatedProxy,
                LocalBroadcastManager.getInstance(context),
                new Handler(Looper.getMainLooper())
        );

        recreatedCoordinator.reconcileState();

        Assert.assertEquals(1, recreatedProxy.startForegroundCallCount);
        Assert.assertEquals(newerGeneration, recreatedProxy.lastStartForegroundGeneration);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, recreatedCoordinator.getPendingStartOwner());
    }

    @Test
    public void processRecreationResumesPendingServiceAckDispatchWithExactGeneration() {
        long generation = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 15000L, null);
        Assert.assertEquals(generation, coordinator.getPendingStartGeneration());

        TestLocationServiceProxy recreatedProxy = new TestLocationServiceProxy(context);
        recreatedProxy.started = false;
        TrackingLifecycleCoordinator recreatedCoordinator = new TrackingLifecycleCoordinator(
                context,
                new TrackingOwnershipStore(context),
                recreatedProxy,
                LocalBroadcastManager.getInstance(context),
                new Handler(Looper.getMainLooper())
        );

        recreatedCoordinator.reconcileState();

        Assert.assertEquals(1, recreatedProxy.startForegroundCallCount);
        Assert.assertEquals(generation, recreatedProxy.lastStartForegroundGeneration);
        Assert.assertEquals(generation, recreatedCoordinator.getPendingStartGeneration());
    }

    @Test
    public void stopTimeoutWithStillRunningServiceUsesDeterministicRetryThenFailure() {
        long staleGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 50L, null);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        long newerGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 15000L, null);
        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, staleGeneration);

        serviceProxy.started = true;
        serviceProxy.failStop = true;

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.SECONDS);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getPendingStartOwner());
        Assert.assertEquals(newerGeneration, coordinator.getPendingStartGeneration());

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.SECONDS);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getPendingStartOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getOwner());
    }

    private static class TestLocationServiceProxy extends LocationServiceProxy {
        boolean started = true;
        boolean failStop = false;
        boolean failStartForeground = false;
        int stopCallCount = 0;
        int startForegroundCallCount = 0;
        long lastStartForegroundGeneration = 0L;

        TestLocationServiceProxy(Context context) {
            super(context);
        }

        @Override
        public void stop(long requestGeneration) {
            stopCallCount += 1;
            if (failStop) {
                throw new RuntimeException("Simulated stop dispatch failure");
            }
            started = false;
        }

        @Override
        public void startForegroundService(long requestGeneration) {
            startForegroundCallCount += 1;
            lastStartForegroundGeneration = requestGeneration;
            if (failStartForeground) {
                throw new RuntimeException("Simulated replay dispatch failure");
            }
            started = true;
        }

        @Override
        public void start(long requestGeneration) {
            started = true;
        }

        @Override
        public boolean isStarted() {
            return started;
        }

        @Override
        public boolean isRunning() {
            return started;
        }
    }
}
