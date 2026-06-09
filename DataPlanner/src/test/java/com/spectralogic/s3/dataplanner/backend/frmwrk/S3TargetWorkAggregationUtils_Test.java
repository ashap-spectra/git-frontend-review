package com.spectralogic.s3.dataplanner.backend.frmwrk;

import com.spectralogic.s3.common.dao.domain.DaoDomainsSeed;
import com.spectralogic.s3.common.dao.domain.ds3.*;
import com.spectralogic.s3.common.dao.domain.target.S3Target;
import com.spectralogic.s3.common.dao.service.DaoServicesSeed;
import com.spectralogic.s3.common.platform.cache.MockDiskManager;
import com.spectralogic.s3.common.testfrmwrk.MockDaoDriver;
import com.spectralogic.util.testfrmwrk.DatabaseSupport;
import com.spectralogic.util.testfrmwrk.DatabaseSupportFactory;
import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class S3TargetWorkAggregationUtils_Test {

    @Test
    public void testDiscoverWorkAggregated_PutGet() {
        final S3Target target = mockDaoDriver.createS3Target("s3target");
        final MockDiskManager mockDiskManager = new MockDiskManager( dbSupport.getServiceManager() );

        final Job job = mockDaoDriver.createJob( bucket.getId(), null, JobRequestType.PUT );
        mockDaoDriver.updateBean(job.setPriority(BlobStoreTaskPriority.NORMAL), Job.PRIORITY);
        final JobEntry entry1 = mockDaoDriver.createJobEntry( job.getId(), b1 );
        final JobEntry entry2 = mockDaoDriver.createJobEntry( job.getId(), b2 );
        mockDaoDriver.markBlobInCache(b1.getId());
        mockDaoDriver.markBlobInCache(b2.getId());
        mockDiskManager.blobLoadedToCache(b1.getId());
        mockDiskManager.blobLoadedToCache(b1.getId());
        mockDaoDriver.createReplicationTargetsForChunk(S3DataReplicationRule.class, S3BlobDestination.class, entry1.getId());
        mockDaoDriver.createReplicationTargetsForChunk(S3DataReplicationRule.class, S3BlobDestination.class, entry2.getId());

        final Job job2 = mockDaoDriver.createJob( bucket.getId(), null, JobRequestType.GET );
        S3Object o3 = mockDaoDriver.createObject( bucket.getId(), "o3" );
        Blob b3 = mockDaoDriver.getBlobFor( o3.getId() );
        mockDaoDriver.updateBean(job2.setPriority(BlobStoreTaskPriority.HIGH), Job.PRIORITY);
        final JobEntry entry3 = mockDaoDriver.createJobEntry( job2.getId(), b3 );
        List<JobEntry> entries = Arrays.asList(entry1, entry2, entry3);
        mockDaoDriver.createS3BlobDestinations(entries, Arrays.asList(rule), bucket.getId(), target.getId() );
        mockDaoDriver.updateBean( entry3.setReadFromS3TargetId(target.getId()), JobEntry.READ_FROM_S3_TARGET_ID);


        List<IODirective> result = S3TargetWorkAggregationUtils.discoverS3TargetWorkAggregated(dbSupport.getServiceManager());
        assertNotNull(result, "LocalReadDirectives result should not be null");
        assertEquals(2, result.size(), "There should be 2 LocalWriteDirectives returned");
        assertEquals(BlobStoreTaskPriority.HIGH, result.get(0).getPriority(), "LocalWriteDirective should be sorted");
    }

    @Test
    public void testDiscoverS3TargetWorkAggregatedTwoTargetsSplitIntoTwoDirectives() {
        // Two PERMANENT replication rules each pointing to a different S3 target must produce
        // two separate TargetWriteDirectives. WriteChunkToS3TargetTask connects to a single
        // target, so aggregating across targets would lose one copy.
        final S3Target target1 = mockDaoDriver.createS3Target("s3target1");
        final S3Target target2 = mockDaoDriver.createS3Target("s3target2");
        final MockDiskManager mockDiskManager = new MockDiskManager( dbSupport.getServiceManager() );
        mockDaoDriver.createS3DataReplicationRule(
                dp.getId(), DataReplicationRuleType.PERMANENT, target1.getId() );
        mockDaoDriver.createS3DataReplicationRule(
                dp.getId(), DataReplicationRuleType.PERMANENT, target2.getId() );

        final Job job = mockDaoDriver.createJob( bucket.getId(), null, JobRequestType.PUT );
        mockDaoDriver.updateBean( job.setPriority( BlobStoreTaskPriority.NORMAL ), Job.PRIORITY );
        final JobEntry entry = mockDaoDriver.createJobEntry( job.getId(), b1 );

        mockDaoDriver.createReplicationTargetsForChunk(
                S3DataReplicationRule.class, S3BlobDestination.class, entry.getId() );
        mockDaoDriver.markBlobInCache( b1.getId() );
        mockDiskManager.blobLoadedToCache( b1.getId() );

        final List<IODirective> result =
                S3TargetWorkAggregationUtils.discoverS3TargetWorkAggregated( dbSupport.getServiceManager() );
        assertNotNull( result );
        assertEquals( 2, result.size(),
                "Two replication rules to different targets should produce two directives" );
        final Set<UUID> targetIds = new HashSet<>();
        for ( final IODirective directive : result ) {
            assertTrue( directive instanceof TargetWriteDirective,
                    "Each directive should be a TargetWriteDirective" );
            @SuppressWarnings("unchecked")
            final TargetWriteDirective<S3Target, S3BlobDestination> wd =
                    (TargetWriteDirective<S3Target, S3BlobDestination>) directive;
            assertEquals( 1, wd.getEntries().size(), "Each directive should reference the single job entry" );
            assertEquals( entry.getId(), wd.getEntries().iterator().next().getId() );
            assertEquals( 1, wd.getBlobDestinations().size(),
                    "Each directive should target exactly one S3BlobDestination" );
            assertEquals( wd.getTarget().getId(),
                    wd.getBlobDestinations().iterator().next().getTargetId(),
                    "Destination's targetId should match the directive's target" );
            targetIds.add( wd.getTarget().getId() );
        }
        assertEquals( Set.of( target1.getId(), target2.getId() ), targetIds,
                "The two directives should target the two distinct S3 targets" );
    }

    @Test
    public void testDiscoverS3TargetWorkAggregatedSingleOversizedEntryStillReturnsDirective() {
        final S3Target target = mockDaoDriver.createS3Target("s3target");
        final MockDiskManager mockDiskManager = new MockDiskManager( dbSupport.getServiceManager() );
        mockDaoDriver.createS3DataReplicationRule(
                dp.getId(), DataReplicationRuleType.PERMANENT, target.getId() );

        // A single blob larger than MAX_BYTES_PER_TASK (100 GB).
        final long oversizedLength = 100L * 1024L * 1024L * 1024L + 1L;
        final S3Object bigObj = mockDaoDriver.createObject( bucket.getId(), "bigobj", oversizedLength );
        final Blob bigBlob = mockDaoDriver.getBlobFor( bigObj.getId() );

        final Job job = mockDaoDriver.createJob( bucket.getId(), null, JobRequestType.PUT );
        mockDaoDriver.updateBean(job.setPriority(BlobStoreTaskPriority.NORMAL), Job.PRIORITY);
        final JobEntry entry = mockDaoDriver.createJobEntry( job.getId(), bigBlob );

        mockDaoDriver.createReplicationTargetsForChunk(S3DataReplicationRule.class, S3BlobDestination.class, entry.getId());
        mockDaoDriver.markBlobInCache( bigBlob.getId() );
        mockDiskManager.blobLoadedToCache( bigBlob.getId() );

        final List<IODirective> result = S3TargetWorkAggregationUtils.discoverS3TargetWorkAggregated(dbSupport.getServiceManager());

        assertNotNull(result);
        assertEquals(1, result.size(), "A single oversized entry should still produce a directive");
        assertEquals(1, result.get(0).getEntries().size(), "Directive should contain the oversized entry");
        assertEquals(entry.getId(), result.get(0).getEntries().iterator().next().getId());
    }

    private static DatabaseSupport dbSupport = DatabaseSupportFactory.getSupport(DaoDomainsSeed.class, DaoServicesSeed.class);
    private MockDaoDriver mockDaoDriver = new MockDaoDriver( dbSupport );
    private static final String DATA_POLICY_TAPE_DUAL_COPY_NAME = "Dual Copy on Tape";
    private Bucket bucket;
    private Blob b1;
    private Blob b2;
    private StorageDomain sd;
    private DataPolicy dp;
    private DataPersistenceRule rule;

    @BeforeEach
    public void setUp() {
        dp = mockDaoDriver.createDataPolicy( DATA_POLICY_TAPE_DUAL_COPY_NAME );
        bucket = mockDaoDriver.createBucket( null, dp.getId(),"bucket1" );
        S3Object o1 = mockDaoDriver.createObject( bucket.getId(), "o1" );
        S3Object o2 = mockDaoDriver.createObject( bucket.getId(), "o2" );
        b1 = mockDaoDriver.getBlobFor( o1.getId() );
        b2 = mockDaoDriver.getBlobFor( o2.getId() );
        sd = mockDaoDriver.createStorageDomain( "sd1" );
        rule = mockDaoDriver.createDataPersistenceRule(
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
