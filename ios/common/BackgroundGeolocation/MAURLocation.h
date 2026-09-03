//
//  MAURLocation.h
//  BackgroundGeolocation
//
//  Created by Marian Hello on 10/06/16.
//

#ifndef MAURLocation_h
#define MAURLocation_h

#import <Foundation/Foundation.h>
#import <CoreLocation/CoreLocation.h>

@class MAURLocation;

typedef MAURLocation * _Nullable (^ MAURLocationTransform)(MAURLocation * _Nonnull location);

typedef NS_ENUM(NSInteger, MAURLocationStatus) {
    MAURLocationDeleted = 0,
    MAURLocationPostPending = 1,
    MAURLocationSyncPending = 2,
};

NS_ASSUME_NONNULL_BEGIN

@interface MAURLocation : NSObject <NSCopying>

@property (nonatomic, retain, nullable) NSNumber *locationId;
@property (nonatomic, retain, nullable) NSDate *time;
@property (nonatomic, retain, nullable) NSNumber *accuracy;
@property (nonatomic, retain, nullable) NSNumber *altitudeAccuracy;
@property (nonatomic, retain, nullable) NSNumber *speed;
@property (nonatomic, retain, nullable) NSNumber *heading;
@property (nonatomic, retain, nullable) NSNumber *altitude;
@property (nonatomic, retain, nullable) NSNumber *latitude;
@property (nonatomic, retain, nullable) NSNumber *longitude;
@property (nonatomic, retain, nullable) NSString *provider;
@property (nonatomic, retain, nullable) NSNumber *locationProvider;
@property (nonatomic, retain, nullable) NSNumber *radius; //only for stationary locations
@property (nonatomic) BOOL isValid;
@property (nonatomic, retain, nullable) NSDate *recordedAt;

+ (instancetype) fromCLLocation:(CLLocation *)location;
+ (NSTimeInterval) locationAge:(CLLocation *)location;
+ (NSMutableDictionary *) toDictionary:(CLLocation *)location;
- (NSTimeInterval) locationAge;
- (NSDictionary *) toDictionary;
- (NSDictionary *) toDictionaryWithId;
- (id) toResultFromTemplate:(id _Nullable)locationTemplate;
- (CLLocationCoordinate2D) coordinate;
- (BOOL) hasAccuracy;
- (BOOL) hasTime;
- (double) distanceFromLocation:(MAURLocation *)location;
- (BOOL) isBetterLocation:(MAURLocation * _Nullable)location;
- (BOOL) isBeyond:(MAURLocation *)location radius:(NSInteger)radius;
- (id) copyWithZone:(NSZone * _Nullable)zone;
- (id _Nullable) getValueForKey:(id _Nullable)key;

@end

NS_ASSUME_NONNULL_END

#endif /* MAURLocation_h */
