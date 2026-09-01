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

        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, generation);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getPendingStopOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getOwner());
    }

    @Test
    public void lateOldGenerationStartDoesNotOverrideNewerGeneration() {
        long staleGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 50L, null);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);

        long newerGeneration = coordinator.requestStart(TrackingOwnershipStore.OWNER_MANUAL, 15000L, null);
        Assert.assertTrue(newerGeneration > staleGeneration);

        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, staleGeneration);

        Assert.assertEquals(TrackingOwnershipStore.OWNER_MANUAL, coordinator.getOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getPendingStartOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getPendingStopOwner());
    }

    @Test
    public void duplicateAndOutOfOrderLifecycleAcksRemainIdempotent() {
        final AtomicInteger timeoutCount = new AtomicInteger(0);
        long generation = coordinator.requestStart(TrackingOwnershipStore.OWNER_GEOFENCE, 50L, new TrackingLifecycleCoordinator.TimeoutCallback() {
            @Override
            public void onTimeout(long timedOutGeneration) {
                timeoutCount.incrementAndGet();
            }
        });

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS);
        Assert.assertEquals(1, timeoutCount.get());

        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, generation);
        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STARTED, generation);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_GEOFENCE, coordinator.getPendingStopOwner());

        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STOPPED, generation);
        coordinator.handleServiceLifecycleAction(LocationServiceImpl.MSG_ON_SERVICE_STOPPED, generation);
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getPendingStopOwner());
        Assert.assertEquals(TrackingOwnershipStore.OWNER_NONE, coordinator.getOwner());
    }
}
