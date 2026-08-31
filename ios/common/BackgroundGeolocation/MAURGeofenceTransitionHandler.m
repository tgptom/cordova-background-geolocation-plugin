#import <UIKit/UIKit.h>
#import "MAURGeofenceTransitionHandler.h"
#import "MAURBackgroundGeolocationFacade.h"
#import "MAURConfig.h"
#import "MAURLogging.h"

static NSString * const MAURGeofenceTrackingTransitionNotification = @"PAPAGeofenceTrackingTransition";
static NSInteger const MAURGeofenceTransitionEnter = 1;
static NSInteger const MAURGeofenceTransitionExit = 2;
static NSInteger const MAURGeofenceTransitionDwell = 4;
static NSString * const MAURGeofenceTransitionLogTag = @"BgGeoGeofence";

@implementation MAURGeofenceTransitionHandler

+ (void)load
{
    [[NSNotificationCenter defaultCenter] addObserver:self
                                             selector:@selector(onGeofenceTransition:)
                                                 name:MAURGeofenceTrackingTransitionNotification
                                               object:nil];
}

+ (void)onGeofenceTransition:(NSNotification *)notification
{
    NSDictionary *userInfo = notification.userInfo;
    NSInteger transitionType = [userInfo[@"transitionType"] integerValue];
    BOOL hasActiveInsideGeofence = [userInfo[@"hasActiveInsideGeofence"] boolValue];

    dispatch_async(dispatch_get_main_queue(), ^{
        MAURBackgroundGeolocationFacade *facade = [MAURBackgroundGeolocationFacade sharedInstance];

        if (transitionType == MAURGeofenceTransitionExit) {
            BOOL ownsTracking = [facade trackingOwner] == MAURTrackingOwnerGeofence;
            if (hasActiveInsideGeofence || !ownsTracking || ![facade isStarted]) {
                DDLogInfo(@"%@ ignored EXIT: activeInside=%d ownsTracking=%d started=%d",
                          MAURGeofenceTransitionLogTag,
                          hasActiveInsideGeofence,
                          ownsTracking,
                          [facade isStarted]);
                return;
            }

            NSError *error = nil;
            [facade stopForOwner:MAURTrackingOwnerGeofence error:&error];
            if (error != nil || [facade isStarted]) {
                DDLogError(@"%@ failed to stop after EXIT: %@",
                           MAURGeofenceTransitionLogTag,
                           error.localizedDescription ?: @"location provider remained active");
                return;
            }

            DDLogInfo(@"%@ stopped precise tracking after EXIT", MAURGeofenceTransitionLogTag);
            return;
        }

        if (transitionType != MAURGeofenceTransitionEnter &&
            transitionType != MAURGeofenceTransitionDwell) {
            return;
        }

        if ([facade isStarted]) {
            DDLogInfo(@"%@ ignored ENTER: precise tracking already active", MAURGeofenceTransitionLogTag);
            return;
        }

        MAURConfig *config = [facade getConfig];
        if (![config hasValidUrl] || [config stopOnTerminate]) {
            DDLogWarn(@"%@ ignored ENTER: persisted configuration cannot run after termination",
                      MAURGeofenceTransitionLogTag);
            return;
        }

        NSError *error = nil;
        BOOL started = [facade startWithOwner:MAURTrackingOwnerGeofence error:&error];
        if (!started || error != nil) {
            DDLogError(@"%@ failed to start after ENTER: %@",
                       MAURGeofenceTransitionLogTag,
                       error.localizedDescription ?: @"location provider did not start");
            return;
        }

        if ([UIApplication sharedApplication].applicationState != UIApplicationStateActive) {
            [facade switchMode:MAURBackgroundMode];
        }
        DDLogInfo(@"%@ started precise tracking after ENTER", MAURGeofenceTransitionLogTag);
    });
}

@end