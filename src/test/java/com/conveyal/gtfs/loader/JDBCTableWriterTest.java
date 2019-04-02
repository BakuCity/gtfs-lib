package com.conveyal.gtfs.loader;

import com.conveyal.gtfs.TestUtils;
import com.conveyal.gtfs.dto.CalendarDTO;
import com.conveyal.gtfs.dto.FareDTO;
import com.conveyal.gtfs.dto.FareRuleDTO;
import com.conveyal.gtfs.dto.FeedInfoDTO;
import com.conveyal.gtfs.dto.FrequencyDTO;
import com.conveyal.gtfs.dto.ScheduleExceptionDTO;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.util.InvalidNamespaceException;
import com.conveyal.gtfs.dto.PatternDTO;
import com.conveyal.gtfs.dto.PatternStopDTO;
import com.conveyal.gtfs.dto.RouteDTO;
import com.conveyal.gtfs.dto.ShapePointDTO;
import com.conveyal.gtfs.dto.StopDTO;
import com.conveyal.gtfs.dto.StopTimeDTO;
import com.conveyal.gtfs.dto.TripDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static com.conveyal.gtfs.GTFS.createDataSource;
import static com.conveyal.gtfs.GTFS.load;
import static com.conveyal.gtfs.GTFS.makeSnapshot;
import static com.conveyal.gtfs.GTFS.validate;
import static com.conveyal.gtfs.TestUtils.getResourceFileName;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;

/**
 * This class contains CRUD tests for {@link JdbcTableWriter} (i.e., editing GTFS entities in the RDBMS). Set up
 * consists of creating a scratch database and an empty feed snapshot, which is the necessary starting condition
 * for building a GTFS feed from scratch. It then runs the various CRUD tests and finishes by dropping the database
 * (even if tests fail).
 */
public class JDBCTableWriterTest {

    private static final Logger LOG = LoggerFactory.getLogger(JDBCTableWriterTest.class);

    private static String testDBName;
    private static DataSource testDataSource;
    private static String testNamespace;
    private static String testGtfsGLSnapshotNamespace;
    private static String simpleServiceId = "1";
    private static String firstStopId = "1";
    private static String lastStopId = "2";
    private static double firstStopLat = 34.2222;
    private static double firstStopLon = -87.333;
    private static double lastStopLat = 34.2233;
    private static double lastStopLon = -87.334;
    private static String sharedShapeId = "shared_shape_id";
    private static final ObjectMapper mapper = new ObjectMapper();

    private static JdbcTableWriter createTestTableWriter (Table table) throws InvalidNamespaceException {
        return new JdbcTableWriter(table, testDataSource, testNamespace);
    }

    @BeforeClass
    public static void setUpClass() throws SQLException, IOException, InvalidNamespaceException {
        // Create a new database
        testDBName = TestUtils.generateNewDB();
        String dbConnectionUrl = String.format("jdbc:postgresql://localhost/%s", testDBName);
        testDataSource = createDataSource (dbConnectionUrl, null, null);
        LOG.info("creating feeds table because it isn't automatically generated unless you import a feed");
        Connection connection = testDataSource.getConnection();
        connection.createStatement()
            .execute("create table if not exists feeds (namespace varchar primary key, md5 varchar, " +
                "sha1 varchar, feed_id varchar, feed_version varchar, filename varchar, loaded_date timestamp, " +
                "snapshot_of varchar)");
        connection.commit();
        LOG.info("feeds table created");
        // Create an empty snapshot to create a new namespace and all the tables
        FeedLoadResult result = makeSnapshot(null, testDataSource);
        testNamespace = result.uniqueIdentifier;
        // Create a service calendar and two stops, both of which are necessary to perform pattern and trip tests.
        createWeekdayCalendar(simpleServiceId, "20180103", "20180104");
        createSimpleStop(firstStopId, "First Stop", firstStopLat, firstStopLon);
        createSimpleStop(lastStopId, "Last Stop", lastStopLat, lastStopLon);

        /** Load the following real-life GTFS for use with {@link JDBCTableWriterTest#canUpdateServiceId()}  **/
        // load feed into db
        FeedLoadResult feedLoadResult = load(getResourceFileName("real-world-gtfs-feeds/gtfs_GL.zip"), testDataSource);
        String testGtfsGLNamespace = feedLoadResult.uniqueIdentifier;
        // validate feed to create additional tables
        validate(testGtfsGLNamespace, testDataSource);
        // load into editor via snapshot
        JdbcGtfsSnapshotter snapshotter = new JdbcGtfsSnapshotter(testGtfsGLNamespace, testDataSource);
        SnapshotResult snapshotResult = snapshotter.copyTables();
        testGtfsGLSnapshotNamespace = snapshotResult.uniqueIdentifier;
    }

    @AfterClass
    public static void tearDownClass() {
        TestUtils.dropDB(testDBName);
    }

    @Test
    public void canCreateUpdateAndDeleteFeedInfoEntities() throws IOException, SQLException, InvalidNamespaceException {
        // Store Table and Class values for use in test.
        final Table feedInfoTable = Table.FEED_INFO;
        final Class<FeedInfoDTO> feedInfoDTOClass = FeedInfoDTO.class;

        // create new object to be saved
        FeedInfoDTO feedInfoInput = new FeedInfoDTO();
        String publisherName = "test-publisher";
        feedInfoInput.feed_publisher_name = publisherName;
        feedInfoInput.feed_publisher_url = "example.com";
        feedInfoInput.feed_lang = "en";
        feedInfoInput.default_route_color = "1c8edb";
        feedInfoInput.default_route_type = "3";

        // convert object to json and save it
        JdbcTableWriter createTableWriter = createTestTableWriter(feedInfoTable);
        String createOutput = createTableWriter.create(mapper.writeValueAsString(feedInfoInput), true);
        LOG.info("create {} output:", feedInfoTable.name);
        LOG.info(createOutput);

        // parse output
        FeedInfoDTO createdFeedInfo = mapper.readValue(createOutput, feedInfoDTOClass);

        // make sure saved data matches expected data
        assertThat(createdFeedInfo.feed_publisher_name, equalTo(publisherName));

        // try to update record
        String updatedPublisherName = "test-publisher-updated";
        createdFeedInfo.feed_publisher_name = updatedPublisherName;

        // covert object to json and save it
        JdbcTableWriter updateTableWriter = createTestTableWriter(feedInfoTable);
        String updateOutput = updateTableWriter.update(
            createdFeedInfo.id,
            mapper.writeValueAsString(createdFeedInfo),
            true
        );
        LOG.info("update {} output:", feedInfoTable.name);
        LOG.info(updateOutput);

        FeedInfoDTO updatedFeedInfoDTO = mapper.readValue(updateOutput, feedInfoDTOClass);

        // make sure saved data matches expected data
        assertThat(updatedFeedInfoDTO.feed_publisher_name, equalTo(updatedPublisherName));

        // try to delete record
        JdbcTableWriter deleteTableWriter = createTestTableWriter(feedInfoTable);
        int deleteOutput = deleteTableWriter.delete(
            createdFeedInfo.id,
            true
        );
        LOG.info("deleted {} records from {}", deleteOutput, feedInfoTable.name);

        // make sure record does not exist in DB
        assertThatSqlQueryYieldsZeroRows(String.format(
            "select * from %s.%s where id=%d",
            testNamespace,
            feedInfoTable.name,
            createdFeedInfo.id
        ));
    }

    /**
     * Ensure that potentially malicious SQL injection is sanitized properly during create operations.
     * TODO: We might should perform this check on multiple entities and for update and/or delete operations.
     */
    @Test
    public void canPreventSQLInjection() throws IOException, SQLException, InvalidNamespaceException {
        // create new object to be saved
        FeedInfoDTO feedInfoInput = new FeedInfoDTO();
        String publisherName = "' OR 1 = 1; SELECT '1";
        feedInfoInput.feed_publisher_name = publisherName;
        feedInfoInput.feed_publisher_url = "example.com";
        feedInfoInput.feed_lang = "en";
        feedInfoInput.default_route_color = "1c8edb";
        feedInfoInput.default_route_type = "3";

        // convert object to json and save it
        JdbcTableWriter createTableWriter = createTestTableWriter(Table.FEED_INFO);
        String createOutput = createTableWriter.create(mapper.writeValueAsString(feedInfoInput), true);
        LOG.info("create output:");
        LOG.info(createOutput);

        // parse output
        FeedInfoDTO createdFeedInfo = mapper.readValue(createOutput, FeedInfoDTO.class);

        // make sure saved data matches expected data
        assertThat(createdFeedInfo.feed_publisher_name, equalTo(publisherName));
    }

    @Test
    public void canCreateUpdateAndDeleteFares() throws IOException, SQLException, InvalidNamespaceException {
        // Store Table and Class values for use in test.
        final Table fareTable = Table.FARE_ATTRIBUTES;
        final Class<FareDTO> fareDTOClass = FareDTO.class;

        // create new object to be saved
        FareDTO fareInput = new FareDTO();
        String fareId = "2A";
        fareInput.fare_id = fareId;
        fareInput.currency_type = "USD";
        fareInput.price = 2.50;
        fareInput.agency_id = "RTA";
        fareInput.payment_method = 0;
        // Empty value should be permitted for transfers and transfer_duration
        fareInput.transfers = null;
        fareInput.transfer_duration = null;
        FareRuleDTO fareRuleInput = new FareRuleDTO();
        // Fare ID should be assigned to "child entity" by editor automatically.
        fareRuleInput.fare_id = null;
        fareRuleInput.route_id = null;
        // FIXME There is currently no check for valid zone_id values in contains_id, origin_id, and destination_id.
        fareRuleInput.contains_id = "any";
        fareRuleInput.origin_id = "value";
        fareRuleInput.destination_id = "permitted";
        fareInput.fare_rules = new FareRuleDTO[]{fareRuleInput};

        // convert object to json and save it
        JdbcTableWriter createTableWriter = createTestTableWriter(fareTable);
        String createOutput = createTableWriter.create(mapper.writeValueAsString(fareInput), true);
        LOG.info("create {} output:", fareTable.name);
        LOG.info(createOutput);

        // parse output
        FareDTO createdFare = mapper.readValue(createOutput, fareDTOClass);

        // make sure saved data matches expected data
        assertThat(createdFare.fare_id, equalTo(fareId));
        assertThat(createdFare.fare_rules[0].fare_id, equalTo(fareId));

        // try to update record
        String updatedFareId = "3B";
        createdFare.fare_id = updatedFareId;

        // covert object to json and save it
        JdbcTableWriter updateTableWriter = createTestTableWriter(fareTable);
        String updateOutput = updateTableWriter.update(
                createdFare.id,
                mapper.writeValueAsString(createdFare),
                true
        );
        LOG.info("update {} output:", fareTable.name);
        LOG.info(updateOutput);

        FareDTO updatedFareDTO = mapper.readValue(updateOutput, fareDTOClass);

        // make sure saved data matches expected data
        assertThat(updatedFareDTO.fare_id, equalTo(updatedFareId));
        assertThat(updatedFareDTO.fare_rules[0].fare_id, equalTo(updatedFareId));

        // try to delete record
        JdbcTableWriter deleteTableWriter = createTestTableWriter(fareTable);
        int deleteOutput = deleteTableWriter.delete(
                createdFare.id,
                true
        );
        LOG.info("deleted {} records from {}", deleteOutput, fareTable.name);

        // make sure fare_attributes record does not exist in DB
        assertThatSqlQueryYieldsZeroRows(String.format(
                "select * from %s.%s where id=%d",
                testNamespace,
                fareTable.name,
                createdFare.id
        ));

        // make sure fare_rules record does not exist in DB
        assertThatSqlQueryYieldsZeroRows(String.format(
                "select * from %s.%s where id=%d",
                testNamespace,
                Table.FARE_RULES.name,
                createdFare.fare_rules[0].id
        ));
    }

    @Test
    public void canCreateUpdateAndDeleteRoutes() throws IOException, SQLException, InvalidNamespaceException {
        // Store Table and Class values for use in test.
        final Table routeTable = Table.ROUTES;
        final Class<RouteDTO> routeDTOClass = RouteDTO.class;

        // create new object to be saved
        String routeId = "500";
        RouteDTO createdRoute = createSimpleTestRoute(routeId, "RTA", "500", "Hollingsworth", 3);

        // make sure saved data matches expected data
        assertThat(createdRoute.route_id, equalTo(routeId));
        // TODO: Verify with a SQL query that the database now contains the created data (we may need to use the same
        //       db connection to do this successfully?)

        // try to update record
        String updatedRouteId = "600";
        createdRoute.route_id = updatedRouteId;

        // covert object to json and save it
        JdbcTableWriter updateTableWriter = createTestTableWriter(routeTable);
        String updateOutput = updateTableWriter.update(
                createdRoute.id,
                mapper.writeValueAsString(createdRoute),
                true
        );
        LOG.info("update {} output:", routeTable.name);
        LOG.info(updateOutput);

        RouteDTO updatedRouteDTO = mapper.readValue(updateOutput, routeDTOClass);

        // make sure saved data matches expected data
        assertThat(updatedRouteDTO.route_id, equalTo(updatedRouteId));
        // TODO: Verify with a SQL query that the database now contains the updated data (we may need to use the same
        //       db connection to do this successfully?)

        // try to delete record
        JdbcTableWriter deleteTableWriter = createTestTableWriter(routeTable);
        int deleteOutput = deleteTableWriter.delete(
                createdRoute.id,
                true
        );
        LOG.info("deleted {} records from {}", deleteOutput, routeTable.name);

        // make sure route record does not exist in DB
        assertThatSqlQueryYieldsZeroRows(String.format(
                "select * from %s.%s where id=%d",
                testNamespace,
                routeTable.name,
                createdRoute.id
        ));
    }

    /**
     * This test verifies that stop_times#shape_dist_traveled (and other "linked fields") are updated when a pattern
     * is updated.
     */
    @Test
    public void shouldUpdateStopTimeShapeDistTraveledOnPatternStopUpdate() throws IOException, SQLException, InvalidNamespaceException {
        String routeId = newUUID();
        String patternId = newUUID();
        int startTime = 6 * 60 * 60; // 6 AM
        PatternDTO pattern = createRouteAndPattern(
            routeId,
            patternId,
            "pattern name",
            null,
            new ShapePointDTO[]{},
            new PatternStopDTO[]{
                new PatternStopDTO(patternId, firstStopId, 0),
                new PatternStopDTO(patternId, lastStopId, 1)
            },
            0
        );
        // Make sure saved data matches expected data.
        assertThat(pattern.route_id, equalTo(routeId));
        // Create trip so we can check that the stop_time values are updated after the patter update.
        TripDTO tripInput = constructTimetableTrip(pattern.pattern_id, pattern.route_id, startTime, 60);
        JdbcTableWriter createTripWriter = createTestTableWriter(Table.TRIPS);
        String createdTripOutput = createTripWriter.create(mapper.writeValueAsString(tripInput), true);
        TripDTO createdTrip = mapper.readValue(createdTripOutput, TripDTO.class);
        // Check the stop_time's initial shape_dist_traveled value. TODO test that other linked fields are updated?
        PreparedStatement statement = testDataSource.getConnection().prepareStatement(
            String.format(
                "select shape_dist_traveled from %s.stop_times where stop_sequence=1 and trip_id='%s'",
                testNamespace,
                createdTrip.trip_id
            )
        );
        ResultSet resultSet = statement.executeQuery();
        while (resultSet.next()) {
            // First stop_time shape_dist_traveled should be zero.
            assertThat(resultSet.getInt(1), equalTo(0));
        }
        // Update pattern_stop#shape_dist_traveled and check that the stop_time's shape_dist value is updated.
        final double updatedShapeDistTraveled = 45.5;
        pattern.pattern_stops[1].shape_dist_traveled = updatedShapeDistTraveled;
        JdbcTableWriter patternUpdater = createTestTableWriter(Table.PATTERNS);
        String updatedPatternOutput = patternUpdater.update(pattern.id, mapper.writeValueAsString(pattern), true);
        LOG.info("Updated pattern: {}", updatedPatternOutput);
        ResultSet resultSet2 = statement.executeQuery();
        while (resultSet2.next()) {
            // First stop_time shape_dist_traveled should be updated.
            assertThat(resultSet2.getDouble(1), equalTo(updatedShapeDistTraveled));
        }
    }

    @Test
    public void shouldDeleteReferencingTripsAndStopTimesOnPatternDelete() throws IOException, SQLException, InvalidNamespaceException {
        String routeId = "9834914";
        int startTime = 6 * 60 * 60; // 6 AM
        PatternDTO pattern = createRouteAndSimplePattern(routeId, "9901900", "The Line");
        // make sure saved data matches expected data
        assertThat(pattern.route_id, equalTo(routeId));
        TripDTO tripInput = constructTimetableTrip(pattern.pattern_id, pattern.route_id, startTime, 60);
        JdbcTableWriter createTripWriter = createTestTableWriter(Table.TRIPS);
        String createdTripOutput = createTripWriter.create(mapper.writeValueAsString(tripInput), true);
        TripDTO createdTrip = mapper.readValue(createdTripOutput, TripDTO.class);
        assertThatSqlQueryYieldsRowCount(
            String.format(
                "select * from %s.%s where id=%d",
                testNamespace,
                Table.TRIPS.name,
                createdTrip.id
            ),
            1
        );
        // Delete pattern record
        JdbcTableWriter deletePatternWriter = createTestTableWriter(Table.PATTERNS);
        int deleteOutput = deletePatternWriter.delete(pattern.id, true);
        LOG.info("deleted {} records from {}", deleteOutput, Table.PATTERNS.name);
        // Check that pattern record does not exist in DB
        assertThatSqlQueryYieldsZeroRows(
            String.format(
                "select * from %s.%s where id=%d",
                testNamespace,
                Table.PATTERNS.name,
                pattern.id
            ));
        // Check that trip records for pattern do not exist in DB
        assertThatSqlQueryYieldsZeroRows(
            String.format(
                "select * from %s.%s where pattern_id='%s'",
                testNamespace,
                Table.TRIPS.name,
                pattern.pattern_id
            ));
        // Check that stop_times records for trip do not exist in DB
        assertThatSqlQueryYieldsZeroRows(
            String.format(
                "select * from %s.%s where trip_id='%s'",
                testNamespace,
                Table.STOP_TIMES.name,
                createdTrip.trip_id
            ));
    }

    /**
     * Test that a frequency trip entry CANNOT be added for a timetable-based pattern. Expects an exception to be thrown.
     */
    @Test(expected = IllegalStateException.class)
    public void cannotCreateFrequencyForTimetablePattern() throws InvalidNamespaceException, IOException, SQLException {
        PatternDTO simplePattern = createRouteAndSimplePattern("900", "8", "The Loop");
        TripDTO tripInput = constructFrequencyTrip(simplePattern.pattern_id, simplePattern.route_id, 6 * 60 * 60);
        JdbcTableWriter createTripWriter = createTestTableWriter(Table.TRIPS);
        createTripWriter.create(mapper.writeValueAsString(tripInput), true);
    }

    /**
     * When multiple patterns reference a single shape_id, the returned JSON from an update to any of these patterns
     * (whether the shape points were updated or not) should have a new shape_id because of the "copy on update" logic
     * that ensures the shared shape is not modified.
     */
    @Test
    public void shouldChangeShapeIdOnPatternUpdate() throws IOException, SQLException, InvalidNamespaceException {
        String patternId = "10";
        ShapePointDTO[] shapes = new ShapePointDTO[]{
            new ShapePointDTO(2, 0.0, sharedShapeId, firstStopLat, firstStopLon, 0),
            new ShapePointDTO(2, 150.0, sharedShapeId, lastStopLat, lastStopLon, 1)
        };
        PatternStopDTO[] patternStops = new PatternStopDTO[]{
            new PatternStopDTO(patternId, firstStopId, 0),
            new PatternStopDTO(patternId, lastStopId, 1)
        };
        PatternDTO simplePattern = createRouteAndPattern("1001", patternId, "The Line", sharedShapeId, shapes, patternStops, 0);
        assertThat(simplePattern.shape_id, equalTo(sharedShapeId));
        // Create pattern with shared shape. Note: typically we would encounter shared shapes on imported feeds (e.g.,
        // BART), but this should simulate the situation well enough.
        String secondPatternId = "11";
        patternStops[0].pattern_id = secondPatternId;
        patternStops[1].pattern_id = secondPatternId;
        PatternDTO patternWithSharedShape = createRouteAndPattern("1002", secondPatternId, "The Line 2", sharedShapeId, shapes, patternStops, 0);
        // Verify that shape_id is shared.
        assertThat(patternWithSharedShape.shape_id, equalTo(sharedShapeId));
        // Update any field on one of the patterns.
        JdbcTableWriter patternUpdater = createTestTableWriter(Table.PATTERNS);
        patternWithSharedShape.name = "The shape_id should update";
        String sharedPatternOutput = patternUpdater.update(patternWithSharedShape.id, mapper.writeValueAsString(patternWithSharedShape), true);
        // The output should contain a new backend-generated shape_id.
        PatternDTO updatedSharedPattern = mapper.readValue(sharedPatternOutput, PatternDTO.class);
        LOG.info("Updated pattern output: {}", sharedPatternOutput);
        String newShapeId = updatedSharedPattern.shape_id;
        assertThat(newShapeId, not(equalTo(sharedShapeId)));
        // Ensure that pattern record in database reflects updated shape ID.
        assertThatSqlQueryYieldsRowCount(String.format(
            "select * from %s.%s where shape_id='%s' and pattern_id='%s'",
            testNamespace,
            Table.PATTERNS.name,
            newShapeId,
            secondPatternId
        ), 1);
    }

    /**
     * Checks that creating a frequency trip functions properly. This also updates the pattern to include pattern stops,
     * which is a prerequisite for creating a frequency trip with stop times.
     */
    @Test
    public void canCreateUpdateAndDeleteFrequencyTripForFrequencyPattern() throws IOException, SQLException, InvalidNamespaceException {
        // Store Table and Class values for use in test.
        final Table tripsTable = Table.TRIPS;
        int startTime = 6 * 60 * 60;
        PatternDTO simplePattern = createRouteAndSimplePattern("1000", "9", "The Line");
        TripDTO tripInput = constructFrequencyTrip(simplePattern.pattern_id, simplePattern.route_id, startTime);
        JdbcTableWriter createTripWriter = createTestTableWriter(tripsTable);
        // Update pattern with pattern stops, set to use frequencies, and TODO shape points
        JdbcTableWriter patternUpdater = createTestTableWriter(Table.PATTERNS);
        simplePattern.use_frequency = 1;
        simplePattern.pattern_stops = new PatternStopDTO[]{
            new PatternStopDTO(simplePattern.pattern_id, firstStopId, 0),
            new PatternStopDTO(simplePattern.pattern_id, lastStopId, 1)
        };
        String updatedPatternOutput = patternUpdater.update(simplePattern.id, mapper.writeValueAsString(simplePattern), true);
        LOG.info("Updated pattern output: {}", updatedPatternOutput);
        // Create new trip for the pattern
        String createTripOutput = createTripWriter.create(mapper.writeValueAsString(tripInput), true);
        LOG.info(createTripOutput);
        TripDTO createdTrip = mapper.readValue(createTripOutput, TripDTO.class);
        // Update trip
        // TODO: Add update and delete tests for updating pattern stops, stop_times, and frequencies.
        String updatedTripId = "100A";
        createdTrip.trip_id = updatedTripId;
        JdbcTableWriter updateTripWriter = createTestTableWriter(tripsTable);
        String updateTripOutput = updateTripWriter.update(createdTrip.id, mapper.writeValueAsString(createdTrip), true);
        LOG.info(updateTripOutput);
        TripDTO updatedTrip = mapper.readValue(updateTripOutput, TripDTO.class);
        // Check that saved data matches expected data
        assertThat(updatedTrip.frequencies[0].start_time, equalTo(startTime));
        assertThat(updatedTrip.trip_id, equalTo(updatedTripId));
        // Delete trip record
        JdbcTableWriter deleteTripWriter = createTestTableWriter(tripsTable);
        int deleteOutput = deleteTripWriter.delete(createdTrip.id, true);
        LOG.info("deleted {} records from {}", deleteOutput, tripsTable.name);
        // Check that trip record does not exist in DB
        assertThatSqlQueryYieldsZeroRows(
            String.format(
                "select * from %s.%s where id=%d",
                testNamespace,
                tripsTable.name,
                updatedTrip.id
            ));
        // Check that stop_times records do not exist in DB
        assertThatSqlQueryYieldsZeroRows(
            String.format(
                "select * from %s.%s where trip_id='%s'",
                testNamespace,
                Table.STOP_TIMES.name,
                updatedTrip.trip_id
            ));
    }

    /**
     * Checks that {@link JdbcTableWriter#normalizeStopTimesForPattern(int, int)} can normalize stop times to a pattern's
     * default travel times.
     */
    @Test
    public void canNormalizePatternStopTimes() throws IOException, SQLException, InvalidNamespaceException {
        // Store Table and Class values for use in test.
        final Table tripsTable = Table.TRIPS;
        int initialTravelTime = 60; // one minute
        int startTime = 6 * 60 * 60; // 6AM
        String patternId = "123456";
        PatternStopDTO[] patternStops = new PatternStopDTO[]{
            new PatternStopDTO(patternId, firstStopId, 0),
            new PatternStopDTO(patternId, lastStopId, 1)
        };
        patternStops[1].default_travel_time = initialTravelTime;
        PatternDTO pattern = createRouteAndPattern(newUUID(),
                                                   patternId,
                                                   "Pattern A",
                                                   null,
                                                   new ShapePointDTO[]{},
                                                   patternStops,
                                                   0);
        // Create trip with travel times that match pattern stops.
        TripDTO tripInput = constructTimetableTrip(pattern.pattern_id, pattern.route_id, startTime, initialTravelTime);
        JdbcTableWriter createTripWriter = createTestTableWriter(tripsTable);
        String createTripOutput = createTripWriter.create(mapper.writeValueAsString(tripInput), true);
        LOG.info(createTripOutput);
        TripDTO createdTrip = mapper.readValue(createTripOutput, TripDTO.class);
        // Update pattern stop with new travel time.
        JdbcTableWriter patternUpdater = createTestTableWriter(Table.PATTERNS);
        int updatedTravelTime = 3600; // one hour
        pattern.pattern_stops[1].default_travel_time = updatedTravelTime;
        String updatedPatternOutput = patternUpdater.update(pattern.id, mapper.writeValueAsString(pattern), true);
        LOG.info("Updated pattern output: {}", updatedPatternOutput);
        // Normalize stop times.
        JdbcTableWriter updateTripWriter = createTestTableWriter(tripsTable);
        updateTripWriter.normalizeStopTimesForPattern(pattern.id, 0);
        // Read pattern stops from database and check that the arrivals/departures have been updated.
        JDBCTableReader<StopTime> stopTimesTable = new JDBCTableReader(Table.STOP_TIMES,
                                                                       testDataSource,
                                                                       testNamespace + ".",
                                                                       EntityPopulator.STOP_TIME);
        int index = 0;
        for (StopTime stopTime : stopTimesTable.getOrdered(createdTrip.trip_id)) {
            LOG.info("stop times i={} arrival={} departure={}", index, stopTime.arrival_time, stopTime.departure_time);
            assertThat(stopTime.arrival_time, equalTo(startTime + index * updatedTravelTime));
            index++;
        }
        // Ensure that updated stop times equals pattern stops length
        assertThat(index, equalTo(patternStops.length));
    }

    /**
     * This test makes sure that updated the service_id will properly update affected referenced entities properly.
     * This test case was initially developed to prove that https://github.com/conveyal/gtfs-lib/issues/203 is
     * happening.
     */
    @Test
    public void canUpdateServiceId() throws InvalidNamespaceException, IOException, SQLException {
        // change the service id
        JdbcTableWriter tableWriter = new JdbcTableWriter(Table.CALENDAR, testDataSource, testGtfsGLSnapshotNamespace);
        tableWriter.update(
            2,
            "{\"id\":2,\"service_id\":\"test\",\"description\":\"MoTuWeThFrSaSu\",\"monday\":1,\"tuesday\":1,\"wednesday\":1,\"thursday\":1,\"friday\":1,\"saturday\":1,\"sunday\":1,\"start_date\":\"20180526\",\"end_date\":\"20201231\"}",
            true
        );

        // assert that the amount of stop times equals the original amount of stop times in the feed
        assertThatSqlQueryYieldsRowCount(
            String.format(
                "select * from %s.%s",
                testGtfsGLSnapshotNamespace,
                Table.STOP_TIMES.name
            ),
            53
        );
    }

    /**
     * Tests whether schedule exceptions can be created, updated and deleted. This particular test creates a schedule
     * exception with a custom schedule, so more tests may be needed to verify the functionality with the other types of
     * schedule exceptions.
     */
    @Test
    public void canCreateUpdateAndDeleteScheduleExceptions () throws IOException, SQLException, InvalidNamespaceException {
        // create an associated calendar record
        String weekdayCalendarServiceId = "weekday-exception-test";
        createWeekdayCalendar(weekdayCalendarServiceId, "20190103", "20190104");

        // Store Table and Class values for use in test.
        final Table scheduleExceptionTable = Table.SCHEDULE_EXCEPTIONS;
        final Class<ScheduleExceptionDTO> scheduleExceptionDTOClass = ScheduleExceptionDTO.class;

        // create new object to be saved
        ScheduleExceptionDTO scheduleExceptionInput = new ScheduleExceptionDTO();
        String exceptionName = "test-exception";
        scheduleExceptionInput.name = exceptionName;
        scheduleExceptionInput.id = -2;
        scheduleExceptionInput.exemplar = 8;
        scheduleExceptionInput.custom_schedule = new String[]{weekdayCalendarServiceId};
        scheduleExceptionInput.dates = new String[]{"20190105"};

        // convert object to json and save it
        JdbcTableWriter createTableWriter = createTestTableWriter(scheduleExceptionTable);
        String createOutput = createTableWriter.create(mapper.writeValueAsString(scheduleExceptionInput), true);
        LOG.info("create {} output:", scheduleExceptionTable.name);
        LOG.info(createOutput);

        // parse output
        ScheduleExceptionDTO createdScheduleException = mapper.readValue(createOutput, scheduleExceptionDTOClass);

        // make sure saved data matches expected data
        assertThat(createdScheduleException.name, equalTo(exceptionName));

        // make sure record exists in database
        assertThatSqlQueryYieldsRowCount(
            String.format(
                "select * from %s.%s where name='%s'",
                testNamespace,
                scheduleExceptionTable.name,
                exceptionName
            ),
            1
        );

        // try to update record
        String updatedExceptionName = "test-exception-updated";
        createdScheduleException.name = updatedExceptionName;

        // covert object to json and save it
        JdbcTableWriter updateTableWriter = createTestTableWriter(scheduleExceptionTable);
        String updateOutput = updateTableWriter.update(
            createdScheduleException.id,
            mapper.writeValueAsString(createdScheduleException),
            true
        );
        LOG.info("update {} output:", scheduleExceptionTable.name);
        LOG.info(updateOutput);

        ScheduleExceptionDTO updatedScheduleExceptionDTO = mapper.readValue(updateOutput, scheduleExceptionDTOClass);

        // make sure saved data matches expected data
        assertThat(updatedScheduleExceptionDTO.name, equalTo(updatedExceptionName));

        // verify that record was updated in database
        assertThatSqlQueryYieldsZeroRows(String.format(
            "select * from %s.%s where name='%s'",
            testNamespace,
            scheduleExceptionTable.name,
            exceptionName
        ));
        assertThatSqlQueryYieldsRowCount(
            String.format(
                "select * from %s.%s where name='%s'",
                testNamespace,
                scheduleExceptionTable.name,
                updatedExceptionName
            ),
            1
        );

        // try to delete record
        JdbcTableWriter deleteTableWriter = createTestTableWriter(scheduleExceptionTable);
        int deleteOutput = deleteTableWriter.delete(
            createdScheduleException.id,
            true
        );
        LOG.info("deleted {} records from {}", deleteOutput, scheduleExceptionTable.name);

        // make sure record does not exist in DB
        assertThatSqlQueryYieldsZeroRows(String.format(
            "select * from %s.%s where id=%d",
            testNamespace,
            scheduleExceptionTable.name,
            createdScheduleException.id
        ));
    }

    /*****************************************************************************************************************
     * End tests, begin helpers
     ****************************************************************************************************************/

    private static String newUUID() {
        return UUID.randomUUID().toString();
    }

    private void assertThatSqlQueryYieldsRowCount(String sql, int expectedRowCount) throws SQLException {
        LOG.info(sql);
        int recordCount = 0;
        ResultSet rs = testDataSource.getConnection().prepareStatement(sql).executeQuery();
        while (rs.next()) recordCount++;
        assertThat("Records matching query should equal expected count.", recordCount, equalTo(expectedRowCount));
    }

    void assertThatSqlQueryYieldsZeroRows(String sql) throws SQLException {
        assertThatSqlQueryYieldsRowCount(sql, 0);
    }

    /**
     * Construct (without writing to the database) a trip with a frequency entry.
     */
    private TripDTO constructFrequencyTrip(String patternId, String routeId, int startTime) {
        TripDTO tripInput = new TripDTO();
        tripInput.pattern_id = patternId;
        tripInput.route_id = routeId;
        tripInput.service_id = simpleServiceId;
        tripInput.stop_times = new StopTimeDTO[]{
            new StopTimeDTO(firstStopId, 0, 0, 0),
            new StopTimeDTO(lastStopId, 60, 60, 1)
        };
        FrequencyDTO frequency = new FrequencyDTO();
        frequency.start_time = startTime;
        frequency.end_time = 9 * 60 * 60;
        frequency.headway_secs = 15 * 60;
        tripInput.frequencies = new FrequencyDTO[]{frequency};
        return tripInput;
    }

    /**
     * Construct (without writing to the database) a timetable trip.
     */
    private TripDTO constructTimetableTrip(String patternId, String routeId, int startTime, int travelTime) {
        TripDTO tripInput = new TripDTO();
        tripInput.pattern_id = patternId;
        tripInput.route_id = routeId;
        tripInput.service_id = simpleServiceId;
        tripInput.stop_times = new StopTimeDTO[]{
            new StopTimeDTO(firstStopId, startTime, startTime, 0),
            new StopTimeDTO(lastStopId, startTime + travelTime, startTime + travelTime, 1)
        };
        tripInput.frequencies = new FrequencyDTO[]{};
        return tripInput;
    }

    /**
     * Creates a pattern by first creating a route and then a pattern for that route.
     */
    private static PatternDTO createRouteAndPattern(String routeId, String patternId, String name, String shapeId, ShapePointDTO[] shapes, PatternStopDTO[] patternStops, int useFrequency) throws InvalidNamespaceException, SQLException, IOException {
        // Create new route
        createSimpleTestRoute(routeId, "RTA", "500", "Hollingsworth", 3);
        // Create new pattern for route
        PatternDTO input = new PatternDTO();
        input.pattern_id = patternId;
        input.route_id = routeId;
        input.name = name;
        input.use_frequency = useFrequency;
        input.shape_id = shapeId;
        input.shapes = shapes;
        input.pattern_stops = patternStops;
        // Write the pattern to the database
        JdbcTableWriter createPatternWriter = createTestTableWriter(Table.PATTERNS);
        String output = createPatternWriter.create(mapper.writeValueAsString(input), true);
        LOG.info("create {} output:", Table.PATTERNS.name);
        LOG.info(output);
        // Parse output
        return mapper.readValue(output, PatternDTO.class);
    }

    /**
     * Creates a pattern by first creating a route and then a pattern for that route.
     */
    private static PatternDTO createRouteAndSimplePattern(String routeId, String patternId, String name) throws InvalidNamespaceException, SQLException, IOException {
        return createRouteAndPattern(routeId, patternId, name, null, new ShapePointDTO[]{}, new PatternStopDTO[]{}, 0);
    }

    /**
     * Create and store a simple stop entity.
     */
    private static StopDTO createSimpleStop(String stopId, String stopName, double latitude, double longitude) throws InvalidNamespaceException, IOException, SQLException {
        JdbcTableWriter createStopWriter = new JdbcTableWriter(Table.STOPS, testDataSource, testNamespace);
        StopDTO input = new StopDTO();
        input.stop_id = stopId;
        input.stop_name = stopName;
        input.stop_lat = latitude;
        input.stop_lon = longitude;
        String output = createStopWriter.create(mapper.writeValueAsString(input), true);
        LOG.info("create {} output:", Table.STOPS.name);
        LOG.info(output);
        return mapper.readValue(output, StopDTO.class);
    }

    /**
     * Create and store a simple route for testing.
     */
    private static RouteDTO createSimpleTestRoute(String routeId, String agencyId, String shortName, String longName, int routeType) throws InvalidNamespaceException, IOException, SQLException {
        RouteDTO input = new RouteDTO();
        input.route_id = routeId;
        input.agency_id = agencyId;
        // Empty value should be permitted for transfers and transfer_duration
        input.route_short_name = shortName;
        input.route_long_name = longName;
        input.route_type = routeType;
        // convert object to json and save it
        JdbcTableWriter createTableWriter = createTestTableWriter(Table.ROUTES);
        String output = createTableWriter.create(mapper.writeValueAsString(input), true);
        LOG.info("create {} output:", Table.ROUTES.name);
        LOG.info(output);
        // parse output
        return mapper.readValue(output, RouteDTO.class);
    }

    /**
     * Create and store a simple calendar that runs on each weekday.
     */
    private static CalendarDTO createWeekdayCalendar(String serviceId, String startDate, String endDate) throws IOException, SQLException, InvalidNamespaceException {
        JdbcTableWriter createCalendarWriter = new JdbcTableWriter(Table.CALENDAR, testDataSource, testNamespace);
        CalendarDTO calendarInput = new CalendarDTO();
        calendarInput.service_id = serviceId;
        calendarInput.monday = 1;
        calendarInput.tuesday = 1;
        calendarInput.wednesday = 1;
        calendarInput.thursday = 1;
        calendarInput.friday = 1;
        calendarInput.saturday = 0;
        calendarInput.sunday = 0;
        calendarInput.start_date = startDate;
        calendarInput.end_date = endDate;
        String output = createCalendarWriter.create(mapper.writeValueAsString(calendarInput), true);
        LOG.info("create {} output:", Table.CALENDAR.name);
        LOG.info(output);
        return mapper.readValue(output, CalendarDTO.class);
    }
}
