package com.marianhello.bgloc;

import com.google.android.gms.location.DetectedActivity;
import com.marianhello.bgloc.provider.ActivityRecognitionLocationProvider;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;

@RunWith(RobolectricTestRunner.class)
public class ActivityRecognitionLocationProviderTest {
    @Test
    public void getProbableActivityIgnoresTiltingAndUnknownWhenSelectingHighestConfidence() {
        ArrayList<DetectedActivity> activities = new ArrayList<>();
        activities.add(new DetectedActivity(DetectedActivity.TILTING, 99));
        activities.add(new DetectedActivity(DetectedActivity.UNKNOWN, 98));
        activities.add(new DetectedActivity(DetectedActivity.WALKING, 70));

        DetectedActivity probable = ActivityRecognitionLocationProvider.getProbableActivity(activities);
        Assert.assertEquals(DetectedActivity.WALKING, probable.getType());
    }
}
