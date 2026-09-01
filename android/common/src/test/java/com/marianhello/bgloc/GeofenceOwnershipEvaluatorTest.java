package com.marianhello.bgloc;

import org.junit.Assert;
import org.junit.Test;

public class GeofenceOwnershipEvaluatorTest {

    @Test
    public void manualOwnerExitDoesNotStopTracking() {
        GeofenceOwnershipEvaluator.Result result = GeofenceOwnershipEvaluator.evaluate(
                GeofenceOwnershipEvaluator.TRANSITION_EXIT,
                false,
                true,
                TrackingOwnershipStore.OWNER_MANUAL
        );

        Assert.assertFalse(result.shouldStart);
        Assert.assertFalse(result.shouldStop);
        Assert.assertFalse(result.shouldClearOwner);
    }

    @Test
    public void geofenceOwnerFinalExitStopsTracking() {
        GeofenceOwnershipEvaluator.Result result = GeofenceOwnershipEvaluator.evaluate(
                GeofenceOwnershipEvaluator.TRANSITION_EXIT,
                false,
                true,
                TrackingOwnershipStore.OWNER_GEOFENCE
        );

        Assert.assertTrue(result.shouldStop);
        Assert.assertFalse(result.shouldStart);
        Assert.assertFalse(result.shouldClearOwner);
    }

    @Test
    public void exitInsideAnotherGeofenceDoesNotStop() {
        GeofenceOwnershipEvaluator.Result result = GeofenceOwnershipEvaluator.evaluate(
                GeofenceOwnershipEvaluator.TRANSITION_EXIT,
                true,
                true,
                TrackingOwnershipStore.OWNER_GEOFENCE
        );

        Assert.assertFalse(result.shouldStart);
        Assert.assertFalse(result.shouldStop);
    }

    @Test
    public void enterStartsWhenServiceIsStopped() {
        GeofenceOwnershipEvaluator.Result result = GeofenceOwnershipEvaluator.evaluate(
                GeofenceOwnershipEvaluator.TRANSITION_ENTER,
                true,
                false,
                TrackingOwnershipStore.OWNER_NONE
        );

        Assert.assertTrue(result.shouldStart);
        Assert.assertFalse(result.shouldStop);
        Assert.assertFalse(result.shouldClearOwner);
    }

    @Test
    public void staleOwnerIsClearedAfterProcessRestart() {
        GeofenceOwnershipEvaluator.Result result = GeofenceOwnershipEvaluator.evaluate(
                GeofenceOwnershipEvaluator.TRANSITION_ENTER,
                true,
                false,
                TrackingOwnershipStore.OWNER_MANUAL
        );

        Assert.assertTrue(result.shouldStart);
        Assert.assertFalse(result.shouldStop);
        Assert.assertTrue(result.shouldClearOwner);
    }

    @Test
    public void repeatedEnterWhileRunningDoesNotStartAgain() {
        GeofenceOwnershipEvaluator.Result result = GeofenceOwnershipEvaluator.evaluate(
                GeofenceOwnershipEvaluator.TRANSITION_DWELL,
                true,
                true,
                TrackingOwnershipStore.OWNER_GEOFENCE
        );

        Assert.assertFalse(result.shouldStart);
        Assert.assertFalse(result.shouldStop);
    }
}
