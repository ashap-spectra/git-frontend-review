/*
 *
 * Copyright C 2024, Spectra Logic Corporation and/or its affiliates.
 * All rights reserved.
 *
 */
package com.spectralogic.s3.dataplanner.backend.frmwrk;

import com.spectralogic.s3.common.dao.domain.DaoDomainsSeed;
import com.spectralogic.s3.common.dao.domain.ds3.*;
import com.spectralogic.s3.common.dao.domain.shared.PersistenceTarget;
import com.spectralogic.s3.common.dao.service.DaoServicesSeed;
import com.spectralogic.s3.common.platform.cache.MockDiskManager;
import com.spectralogic.s3.common.testfrmwrk.MockDaoDriver;
import com.spectralogic.util.testfrmwrk.DatabaseSupport;
import com.spectralogic.util.testfrmwrk.DatabaseSupportFactory;
import org.junit.jupiter.api.*;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class PoolWorkAggregationUtils_Test {

    @Test
    public void testDiscoverPoolWorkAggregatedSingleOversizedEntryStillReturnsDirective() {
        final MockDiskManager mockDiskManager = new MockDiskManager( dbSupport.getServiceManager() );
        mockDaoDriver.addPoolPartitionToStorageDomain( sd.getId(), null );

        // A single blob larger than MAX_BYTES_PER_TASK (100 GB).
        final long oversizedLength = 100L * 1024L * 1024L * 1024L + 1L;
        final S3Object bigObj = mockDaoDriver.createObject( bucket.getId(), "bigobj", oversizedLength );
        final Blob bigBlob = mockDaoDriver.getBlobFor( bigObj.getId() );

        final Job job = mockDaoDriver.createJob( bucket.getId(), null, JobRequestType.PUT );
        mockDaoDriver.updateBean(job.setPriority(BlobStoreTaskPriority.NORMAL), Job.PRIORITY);
        final JobEntry entry = mockDaoDriver.createJobEntry( job.getId(), bigBlob );

        mockDaoDriver.createLocalBlobDestinations( Arrays.asList(entry), Arrays.asList(rule), bucket.getId() );
        mockDaoDriver.markBlobInCache( bigBlob.getId() );
        mockDiskManager.blobLoadedToCache( bigBlob.getId() );

        final List<IODirective> result = PoolWorkAggregationUtils.discoverPoolWorkAggregated( dbSupport.getServiceManager() );

        assertNotNull(result);
        assertEquals(1, result.size(), "A single oversized entry should still produce a directive");
        assertEquals(1, result.get(0).getEntries().size(), "Directive should contain the oversized entry");
        assertEquals(entry.getId(), result.get(0).getEntries().iterator().next().getId());
    }

    @Test
    public void testDiscoverPoolWorkAggregatedPermAndTempCopySplitIntoTwoDirectives() {
        // A blob targeting two pool storage domains (one PERMANENT, one TEMPORARY) must
        // produce two separate LocalWriteDirectives so that each is written by its own
        // WriteChunkToPoolTask. Aggregating them into a single task would lose one copy
        // since a task only writes to one selected pool.
        final MockDiskManager mockDiskManager = new MockDiskManager( dbSupport.getServiceManager() );
        final StorageDomain sdTemp = mockDaoDriver.createStorageDomain( "sd2" );
        final DataPersistenceRule tempRule = mockDaoDriver.createDataPersistenceRule(
                dp.getId(), DataPersistenceRuleType.TEMPORARY, sdTemp.getId() );
        mockDaoDriver.addPoolPartitionToStorageDomain( sd.getId(), null );
        mockDaoDriver.addPoolPartitionToStorageDomain( sdTemp.getId(), null );

        final S3Object obj = mockDaoDriver.createObject( bucket.getId(), "o1" );
        final Blob blob = mockDaoDriver.getBlobFor( obj.getId() );

        final Job job = mockDaoDriver.createJob( bucket.getId(), null, JobRequestType.PUT );
        mockDaoDriver.updateBean( job.setPriority(BlobStoreTaskPriority.NORMAL), Job.PRIORITY );
        final JobEntry entry = mockDaoDriver.createJobEntry( job.getId(), blob );

        mockDaoDriver.createLocalBlobDestinations(
                Arrays.asList(entry), Arrays.asList(rule, tempRule), bucket.getId() );
        mockDaoDriver.markBlobInCache( blob.getId() );
        mockDiskManager.blobLoadedToCache( blob.getId() );

        final List<IODirective> result =
                PoolWorkAggregationUtils.discoverPoolWorkAggregated( dbSupport.getServiceManager() );

        assertNotNull( result );
        assertEquals( 2, result.size(),
                "Perm+temp copies on different storage domains should produce two directives" );
        for ( final IODirective directive : result ) {
            assertTrue( directive instanceof LocalWriteDirective,
                    "Each directive should be a LocalWriteDirective" );
            final LocalWriteDirective wd = (LocalWriteDirective) directive;
            assertEquals( 1, wd.getEntries().size(),
                    "Each directive should reference the single job entry" );
            assertEquals( entry.getId(), wd.getEntries().iterator().next().getId() );
            assertEquals( 1, wd.getDestinations().size(),
                    "Each directive should target exactly one LocalBlobDestination" );
        }
        // Each directive's destination must belong to its own storage domain.
        final LocalWriteDirective d1 = (LocalWriteDirective) result.get( 0 );
        final LocalWriteDirective d2 = (LocalWriteDirective) result.get( 1 );
        final UUID sd1Id = d1.getStorageDomain().getId();
        final UUID sd2Id = d2.getStorageDomain().getId();
        assertNotEquals( sd1Id, sd2Id, "The two directives should target different storage domains" );
        assertEquals( sd1Id, d1.getDestinations().get( 0 ).getStorageDomainId(),
                "First directive's destination should belong to its declared storage domain" );
        assertEquals( sd2Id, d2.getDestinations().get( 0 ).getStorageDomainId(),
                "Second directive's destination should belong to its declared storage domain" );
    }

    private static DatabaseSupport dbSupport = DatabaseSupportFactory.getSupport(DaoDomainsSeed.class, DaoServicesSeed.class);
    private final MockDaoDriver mockDaoDriver = new MockDaoDriver( dbSupport );
    private static final String DATA_POLICY_NAME = "Pool Policy";
    private Bucket bucket;
    private StorageDomain sd;
    private DataPolicy dp;
    private DataPersistenceRule rule;

    @BeforeEach
    public void setUp() {
        dbSupport.reset();
        dp = mockDaoDriver.createDataPolicy( DATA_POLICY_NAME );
        bucket = mockDaoDriver.createBucket( null, dp.getId(),"bucket1" );
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
