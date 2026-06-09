/*
 *
 * Copyright C 2024, Spectra Logic Corporation and/or its affiliates.
 * All rights reserved.
 *
 */
package com.spectralogic.s3.dataplanner.backend.frmwrk;

import com.spectralogic.s3.common.dao.domain.DaoDomainsSeed;
import com.spectralogic.s3.common.dao.domain.ds3.*;
import com.spectralogic.s3.common.dao.domain.target.Ds3Target;
import com.spectralogic.s3.common.dao.service.DaoServicesSeed;
import com.spectralogic.s3.common.platform.cache.MockDiskManager;
import com.spectralogic.s3.common.testfrmwrk.MockDaoDriver;
import com.spectralogic.util.testfrmwrk.DatabaseSupport;
import com.spectralogic.util.testfrmwrk.DatabaseSupportFactory;
import org.junit.jupiter.api.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class Ds3TargetWorkAggregationUtils_Test {

    @Test
    public void testDiscoverDs3TargetWorkAggregatedSingleOversizedEntryStillReturnsDirective() {
        final Ds3Target target = mockDaoDriver.createDs3Target("ds3target");
        final MockDiskManager mockDiskManager = new MockDiskManager( dbSupport.getServiceManager() );
        mockDaoDriver.createDs3DataReplicationRule(
                dp.getId(), DataReplicationRuleType.PERMANENT, target.getId() );

        // A single blob larger than MAX_BYTES_PER_TASK (100 GB).
        final long oversizedLength = 100L * 1024L * 1024L * 1024L + 1L;
        final S3Object bigObj = mockDaoDriver.createObject( bucket.getId(), "bigobj", oversizedLength );
        final Blob bigBlob = mockDaoDriver.getBlobFor( bigObj.getId() );

        final Job job = mockDaoDriver.createJob( bucket.getId(), null, JobRequestType.PUT );
        mockDaoDriver.updateBean(job.setPriority(BlobStoreTaskPriority.NORMAL), Job.PRIORITY);
        final JobEntry entry = mockDaoDriver.createJobEntry( job.getId(), bigBlob );

        mockDaoDriver.createReplicationTargetsForChunk(Ds3DataReplicationRule.class, Ds3BlobDestination.class, entry.getId());
        mockDaoDriver.markBlobInCache( bigBlob.getId() );
        mockDiskManager.blobLoadedToCache( bigBlob.getId() );

        final List<IODirective> result = Ds3TargetWorkAggregationUtils.discoverDs3TargetWorkAggregated(dbSupport.getServiceManager());

        assertNotNull(result);
        assertEquals(1, result.size(), "A single oversized entry should still produce a directive");
        assertEquals(1, result.get(0).getEntries().size(), "Directive should contain the oversized entry");
        assertEquals(entry.getId(), result.get(0).getEntries().iterator().next().getId());
    }

    @Test
    public void testAggregatingPutJobIsNotReplicatedUntilClosed() {
        final Ds3Target target = mockDaoDriver.createDs3Target("ds3target");
        final MockDiskManager mockDiskManager = new MockDiskManager( dbSupport.getServiceManager() );
        mockDaoDriver.createDs3DataReplicationRule(
                dp.getId(), DataReplicationRuleType.PERMANENT, target.getId() );

        final S3Object obj = mockDaoDriver.createObject( bucket.getId(), "obj1", 100L );
        final Blob blob = mockDaoDriver.getBlobFor( obj.getId() );

        final Job job = mockDaoDriver.createJob( bucket.getId(), null, JobRequestType.PUT );
        mockDaoDriver.updateBean( job.setAggregating( true ), Job.AGGREGATING );
        final JobEntry entry = mockDaoDriver.createJobEntry( job.getId(), blob );
        mockDaoDriver.createReplicationTargetsForChunk(
                Ds3DataReplicationRule.class, Ds3BlobDestination.class, entry.getId() );
        mockDaoDriver.markBlobInCache( blob.getId() );
        mockDiskManager.blobLoadedToCache( blob.getId() );

        assertTrue(
                Ds3TargetWorkAggregationUtils.discoverDs3TargetWorkAggregated(dbSupport.getServiceManager()).isEmpty(),
                "Aggregating PUT should not be replicated; destinations would otherwise be stranded IN_PROGRESS." );

        mockDaoDriver.updateBean( job.setAggregating( false ), Job.AGGREGATING );

        final List<IODirective> afterClose =
                Ds3TargetWorkAggregationUtils.discoverDs3TargetWorkAggregated(dbSupport.getServiceManager());
        assertEquals( 1, afterClose.size(), "Closed PUT should be picked up for replication." );
        assertEquals( entry.getId(), afterClose.get(0).getEntries().iterator().next().getId() );
    }

    @Test
    public void testDiscoverDs3TargetWorkAggregatedTwoTargetsSplitIntoTwoDirectives() {
        // Two PERMANENT replication rules each pointing to a different Ds3 target must produce
        // two separate TargetWriteDirectives. Each WriteChunkToDs3TargetTask connects to a single
        // target, so aggregating across targets would lose one copy.
        final Ds3Target target1 = mockDaoDriver.createDs3Target("ds3target1");
        final Ds3Target target2 = mockDaoDriver.createDs3Target("ds3target2");
        final MockDiskManager mockDiskManager = new MockDiskManager( dbSupport.getServiceManager() );
        mockDaoDriver.createDs3DataReplicationRule(
                dp.getId(), DataReplicationRuleType.PERMANENT, target1.getId() );
        mockDaoDriver.createDs3DataReplicationRule(
                dp.getId(), DataReplicationRuleType.PERMANENT, target2.getId() );

        final S3Object obj = mockDaoDriver.createObject( bucket.getId(), "obj1", 100L );
        final Blob blob = mockDaoDriver.getBlobFor( obj.getId() );

        final Job job = mockDaoDriver.createJob( bucket.getId(), null, JobRequestType.PUT );
        mockDaoDriver.updateBean( job.setPriority( BlobStoreTaskPriority.NORMAL ), Job.PRIORITY );
        final JobEntry entry = mockDaoDriver.createJobEntry( job.getId(), blob );

        mockDaoDriver.createReplicationTargetsForChunk(
                Ds3DataReplicationRule.class, Ds3BlobDestination.class, entry.getId() );
        mockDaoDriver.markBlobInCache( blob.getId() );
        mockDiskManager.blobLoadedToCache( blob.getId() );

        final List<IODirective> result =
                Ds3TargetWorkAggregationUtils.discoverDs3TargetWorkAggregated( dbSupport.getServiceManager() );
        assertNotNull( result );
        assertEquals( 2, result.size(),
                "Two replication rules to different targets should produce two directives" );
        final Set<UUID> targetIds = new HashSet<>();
        for ( final IODirective directive : result ) {
            assertTrue( directive instanceof TargetWriteDirective,
                    "Each directive should be a TargetWriteDirective" );
            @SuppressWarnings("unchecked")
            final TargetWriteDirective<Ds3Target, Ds3BlobDestination> wd =
                    (TargetWriteDirective<Ds3Target, Ds3BlobDestination>) directive;
            assertEquals( 1, wd.getEntries().size(), "Each directive should reference the single job entry" );
            assertEquals( entry.getId(), wd.getEntries().iterator().next().getId() );
            assertEquals( 1, wd.getBlobDestinations().size(),
                    "Each directive should target exactly one Ds3BlobDestination" );
            assertEquals( wd.getTarget().getId(),
                    wd.getBlobDestinations().iterator().next().getTargetId(),
                    "Destination's targetId should match the directive's target" );
            targetIds.add( wd.getTarget().getId() );
        }
        assertEquals( Set.of( target1.getId(), target2.getId() ), targetIds,
                "The two directives should target the two distinct Ds3 targets" );
    }

    @Test
    public void testAggregatingGetJobIsNotReplicatedUntilClosed() {
        final Ds3Target target = mockDaoDriver.createDs3Target("ds3target");
        mockDaoDriver.createDs3DataReplicationRule(
                dp.getId(), DataReplicationRuleType.PERMANENT, target.getId() );

        final S3Object obj = mockDaoDriver.createObject( bucket.getId(), "obj1", 100L );
        final Blob blob = mockDaoDriver.getBlobFor( obj.getId() );

        final Job job = mockDaoDriver.createJob( bucket.getId(), null, JobRequestType.GET );
        mockDaoDriver.updateBean( job.setAggregating( true ), Job.AGGREGATING );
        final JobEntry entry = mockDaoDriver.createJobEntry( job.getId(), blob );
        mockDaoDriver.updateBean(
                entry.setReadFromDs3TargetId( target.getId() ), JobEntry.READ_FROM_DS3_TARGET_ID );

        assertTrue(
                Ds3TargetWorkAggregationUtils.discoverDs3TargetWorkAggregated(dbSupport.getServiceManager()).isEmpty(),
                "Aggregating GET should not be replicated; later-aggregated entries would not be announced." );

        mockDaoDriver.updateBean( job.setAggregating( false ), Job.AGGREGATING );

        final List<IODirective> afterClose =
                Ds3TargetWorkAggregationUtils.discoverDs3TargetWorkAggregated(dbSupport.getServiceManager());
        assertEquals( 1, afterClose.size(), "Closed GET should be picked up for replication." );
        assertEquals( entry.getId(), afterClose.get(0).getEntries().iterator().next().getId() );
    }

    private static DatabaseSupport dbSupport = DatabaseSupportFactory.getSupport(DaoDomainsSeed.class, DaoServicesSeed.class);
    private final MockDaoDriver mockDaoDriver = new MockDaoDriver( dbSupport );
    private static final String DATA_POLICY_NAME = "Ds3 Policy";
    private Bucket bucket;
    private StorageDomain sd;
    private DataPolicy dp;

    @BeforeEach
    public void setUp() {
        dbSupport.reset();
        dp = mockDaoDriver.createDataPolicy( DATA_POLICY_NAME );
        bucket = mockDaoDriver.createBucket( null, dp.getId(),"bucket1" );
        sd = mockDaoDriver.createStorageDomain( "sd1" );
        mockDaoDriver.createDataPersistenceRule(
                dp.getId(), DataPersistenceRuleType.PERMANENT, sd.getId() );
    }

    @BeforeAll
    public static void setupDBFactory() {
        dbSupport = DatabaseSupportFactory.getSupport(DaoDomainsSeed.class, DaoServicesSeed.class);
    }

    @AfterEach
    public void resetDB() {
        dbSupport.reset();
    }
}
