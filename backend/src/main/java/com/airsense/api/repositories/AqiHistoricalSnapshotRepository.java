package com.airsense.api.repositories;

import com.airsense.api.entities.AqiHistoricalSnapshot;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@Repository
public interface AqiHistoricalSnapshotRepository extends MongoRepository<AqiHistoricalSnapshot, String> {
    Optional<AqiHistoricalSnapshot> findByLocationKeyAndProviderAndAqiStandardAndProviderObservedAt(
            String locationKey, String provider, String aqiStandard, Instant providerObservedAt);

    Optional<AqiHistoricalSnapshot> findFirstByLocationKeyInAndProviderAndAqiStandardAndProviderObservedAt(
            List<String> locationKeys, String provider, String aqiStandard, Instant providerObservedAt);

    Optional<AqiHistoricalSnapshot> findFirstByStationKeyAndProviderAndAqiStandardAndProviderObservedAt(
            String stationKey, String provider, String aqiStandard, Instant providerObservedAt);

    Optional<AqiHistoricalSnapshot> findFirstByStationKeyAndAqiStandardAndProviderObservedAtLessThanEqualOrderByProviderObservedAtDesc(
            String stationKey, String aqiStandard, Instant providerObservedAt);

    Optional<AqiHistoricalSnapshot> findFirstByStationKeyAndAqiStandardAndProviderObservedAtBetweenOrderByProviderObservedAtAsc(
            String stationKey, String aqiStandard, Instant start, Instant end);

    List<AqiHistoricalSnapshot> findByStationKeyAndAqiStandardOrderByProviderObservedAtAsc(
            String stationKey, String aqiStandard);

    Optional<AqiHistoricalSnapshot> findFirstByStationKeyAndAqiStandardOrderByProviderObservedAtAsc(
            String stationKey, String aqiStandard);

    Optional<AqiHistoricalSnapshot> findFirstByStationKeyAndAqiStandardOrderByProviderObservedAtDesc(
            String stationKey, String aqiStandard);

    @Query("{ 'aqiStandard': ?1, 'dataOrigin': ?2, '$or': [ " +
            "{ 'stationKey': { '$in': ?0 } }, " +
            "{ 'stationLocationKey': { '$in': ?0 } }, " +
            "{ 'locationKey': { '$in': ?0 } } " +
            "] }")
    List<AqiHistoricalSnapshot> findReplaySnapshotsByIdentityKeysAndStandard(
            List<String> identityKeys, String aqiStandard, String dataOrigin, Sort sort);

    @Query("{ 'aqiStandard': ?1, 'dataOrigin': ?3, 'providerObservedAt': { '$lte': ?2 }, '$or': [ " +
            "{ 'stationKey': { '$in': ?0 } }, " +
            "{ 'stationLocationKey': { '$in': ?0 } }, " +
            "{ 'locationKey': { '$in': ?0 } } " +
            "] }")
    List<AqiHistoricalSnapshot> findReplayIssueSnapshots(
            List<String> identityKeys, String aqiStandard, Instant issueTime, String dataOrigin);

    @Query("{ 'aqiStandard': ?1, 'providerObservedAt': { '$gte': ?2, '$lte': ?3 }, 'dataOrigin': ?4, '$or': [ " +
            "{ 'stationKey': { '$in': ?0 } }, " +
            "{ 'stationLocationKey': { '$in': ?0 } }, " +
            "{ 'locationKey': { '$in': ?0 } } " +
            "] }")
    List<AqiHistoricalSnapshot> findReplayActualSnapshots(
            List<String> identityKeys, String aqiStandard, Instant start, Instant end, String dataOrigin, Pageable pageable);

    List<AqiHistoricalSnapshot> findByLocationKeyAndProviderObservedAtBetweenOrderByProviderObservedAtAsc(
            String locationKey, Instant start, Instant end);

    List<AqiHistoricalSnapshot> findByLocationKeyInAndProviderObservedAtBetweenOrderByProviderObservedAtAsc(
            List<String> locationKeys, Instant start, Instant end);

    List<AqiHistoricalSnapshot> findByLocationKeyAndAqiStandardAndProviderObservedAtBetweenOrderByProviderObservedAtAsc(
            String locationKey, String aqiStandard, Instant start, Instant end);

    List<AqiHistoricalSnapshot> findByLocationKeyInAndAqiStandardAndProviderObservedAtBetweenOrderByProviderObservedAtAsc(
            List<String> locationKeys, String aqiStandard, Instant start, Instant end);

    List<AqiHistoricalSnapshot> findTop500ByLocationKeyInAndAqiStandardOrderByProviderObservedAtDesc(
            List<String> locationKeys, String aqiStandard);

    List<AqiHistoricalSnapshot> findTop1000ByProviderAndAqiStandardAndProviderObservedAtAfterOrderByProviderObservedAtDesc(
            String provider, String aqiStandard, Instant providerObservedAt);


    Optional<AqiHistoricalSnapshot> findFirstByLocationKeyOrderByProviderObservedAtDesc(String locationKey);

    Optional<AqiHistoricalSnapshot> findFirstByLocationKeyAndAqiStandardOrderByProviderObservedAtDesc(String locationKey, String aqiStandard);

    long countByLocationKeyIn(List<String> locationKeys);

    void deleteByProviderObservedAtBefore(Instant cutoff);

    void deleteByDataOriginNotAndProviderObservedAtBefore(String dataOrigin, Instant cutoff);
}
