package com.marianhello.bgloc;

import android.content.Context;
import android.os.Looper;

import com.marianhello.bgloc.service.LocationServiceImpl;

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

    @Before
    public void setUp() {
        context = RuntimeEnvironment.application.getApplicationContext();
        context.getSharedPreferences("com.marianhello.bgloc.geofence", Context.MODE_PRIVATE).edit().clear().commit();
        coordinator = TrackingLifecycleCoordinator.getInstance(context);
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
    }

    @Test
    public void cancelledGenerationThenLateStartRequestsOrderlyStopAndMarksCancelled() {
        long generation = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 15000L, null);
        coordinator.cancelPendingStart();

        TrackingLifecycleCoordinator.LifecycleActionResult result =
                coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, generation);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getPendingStopOwner());
        Assert.assertTrue(result.late);
        Assert.assertTrue(result.cancelled);
    }

    @Test
    public void timedOutGenerationThenNewPendingStartIgnoresLateOldAck() {
        long staleGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 50L, null);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);

        long newerGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 15000L, null);
        Assert.assertTrue(newerGeneration > staleGeneration);

        TrackingLifecycleCoordinator.LifecycleActionResult result =
                coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, staleGeneration);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getPendingStartOwner());
        Assert.assertEquals(newerGeneration, coordinator.getPendingStartGeneration());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getPendingStopOwner());
        Assert.assertTrue(result.stale);
        Assert.assertTrue(result.late);
    }

    @Test
    public void oldGenerationDoesNotCommitOrStopNewerManualGeneration() {
        long staleGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 50L, null);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);

        long manualGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_MANUAL, 15000L, null);
        TrackingLifecycleCoordinator.LifecycleActionResult staleResult =
                coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, staleGeneration);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, coordinator.getPendingStartOwner());
        Assert.assertEquals(manualGeneration, coordinator.getPendingStartGeneration());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getPendingStopOwner());
        Assert.assertTrue(staleResult.stale);

        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, manualGeneration);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, coordinator.getOwner());
    }

    @Test
    public void zeroGenerationStartDoesNotConsumeGeneratedPendingStart() {
        long pendingGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_MANUAL, 15000L, null);

        TrackingLifecycleCoordinator.LifecycleActionResult result =
                coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, 0L);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, coordinator.getPendingStartOwner());
        Assert.assertEquals(pendingGeneration, coordinator.getPendingStartGeneration());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getOwner());
        Assert.assertTrue(result.stale);
        Assert.assertEquals(0L, result.committedGeneration);
    }

    @Test
    public void newerOutOfOrderGenerationDoesNotCommitOlderPendingStart() {
        long pendingGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_MANUAL, 15000L, null);

        TrackingLifecycleCoordinator.LifecycleActionResult result =
                coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, pendingGeneration + 1L);

        Assert.assertTrue(result.stale);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, coordinator.getPendingStartOwner());
        Assert.assertEquals(pendingGeneration, coordinator.getPendingStartGeneration());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getOwner());
    }

    @Test
    public void lateFailedGenerationDoesNotStopCommittedNewerManualOwner() {
        long staleGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 50L, null);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);

        long manualGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_MANUAL, 15000L, null);
        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, manualGeneration);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, coordinator.getOwner());

        TrackingLifecycleCoordinator.LifecycleActionResult staleResult =
                coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, staleGeneration);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, coordinator.getOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getPendingStopOwner());
        Assert.assertTrue(staleResult.late);
    }

    @Test
    public void stopAcksRequireMatchingGeneration() {
        long manualGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_MANUAL, 15000L, null);
        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, manualGeneration);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, coordinator.getOwner());

        long stopGeneration = coordinator.requestStop(TrackingOwnershipStore.OWNER_MANUAL, 15000L, null);
        TrackingLifecycleCoordinator.LifecycleActionResult staleZeroStop =
                coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STOPPED, 0L);
        Assert.assertTrue(staleZeroStop.stale);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, coordinator.getOwner());

        TrackingLifecycleCoordinator.LifecycleActionResult staleOutOfOrderStop =
                coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STOPPED, stopGeneration + 1L);
        Assert.assertTrue(staleOutOfOrderStop.stale);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, coordinator.getOwner());

        TrackingLifecycleCoordinator.LifecycleActionResult committedStop =
                coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STOPPED, stopGeneration);
        Assert.assertFalse(committedStop.stale);
        Assert.assertEquals(stopGeneration, committedStop.committedGeneration);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getPendingStopOwner());
    }
}
