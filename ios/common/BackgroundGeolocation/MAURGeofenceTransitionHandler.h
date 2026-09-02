#ifndef MAURGeofenceTransitionHandler_h
#define MAURGeofenceTransitionHandler_h

#import <Foundation/Foundation.h>

FOUNDATION_EXPORT NSString * const MAURGeofenceTrackingTransitionNotification;
FOUNDATION_EXPORT NSString * const MAURGeofenceTrackingTransitionTypeKey;
FOUNDATION_EXPORT NSString * const MAURGeofenceTrackingHasActiveInsideGeofenceKey;

typedef NS_ENUM(NSInteger, MAURGeofenceTransitionAction) {
    MAURGeofenceTransitionActionIgnore = 0,
    MAURGeofenceTransitionActionStart = 1,
    MAURGeofenceTransitionActionStop = 2
};

@interface MAURGeofenceTransitionHandler : NSObject
+ (MAURGeofenceTransitionAction)actionForTransitionType:(NSInteger)transitionType
                               hasActiveInsideGeofence:(BOOL)hasActiveInsideGeofence
                                          trackingOwner:(NSInteger)trackingOwner
                                            hasValidUrl:(BOOL)hasValidUrl
                                        stopOnTerminate:(BOOL)stopOnTerminate;
@end

#endif /* MAURGeofenceTransitionHandler_h */
