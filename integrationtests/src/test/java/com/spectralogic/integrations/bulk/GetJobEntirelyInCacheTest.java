package com.spectralogic.integrations.bulk;

import com.spectralogic.ds3client.Ds3Client;
import com.spectralogic.ds3client.commands.spectrads3.*;
import com.spectralogic.ds3client.helpers.Ds3ClientHelpers;
import com.spectralogic.ds3client.helpers.FileObjectPutter;
import com.spectralogic.ds3client.models.DataPolicy;
import com.spectralogic.ds3client.models.bulk.Ds3Object;
import com.spectralogic.integrations.TestUtils;
import org.apache.log4j.Logger;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.spectralogic.integrations.Ds3ApiHelpers.*;
import static com.spectralogic.integrations.TestConstants.*;
import static org.junit.jupiter.api.Assertions.*;


public class GetJobEntirelyInCacheTest {
    private static final Logger LOG = Logger.getLogger(GetJobEntirelyInCacheTest.class);
    private static final String BUCKET_NAME = "entirely-in-cache-test";
    private static final String OBJECT_NAME = "test-object.txt";
    private static final long OBJECT_SIZE_BYTES = 1024;

    private Ds3Client client;

    @BeforeEach
    public void setUp() throws IOException, InterruptedException, SQLException {
        client = TestUtils.setTestParams();
        if (client != null) {
            TestUtils.cleanSetUp(client);
        }
    }

    @AfterEach
    public void tearDown() throws IOException, InterruptedException, SQLException {
        if (client != null) {
            unQuiescePartitions(client);
            cleanupBuckets(client, BUCKET_NAME);
            clearAllJobs(client);
            TestUtils.cleanSetUp(client);
            client.close();
        }
    }

    private void setTapeDataPolicy() throws IOException {
        final GetDataPoliciesSpectraS3Response responsePolicy =
                client.getDataPoliciesSpectraS3(new GetDataPoliciesSpectraS3Request());
        final List<DataPolicy> dataPolicies = responsePolicy.getDataPolicyListResult().getDataPolicies();
        final Optional<DataPolicy> tapeDP = dataPolicies.stream()
                .filter(dp -> dp.getName().equals(DATA_POLICY_TAPE_SINGLE_COPY_NAME))
                .findFirst();
        assertTrue(tapeDP.isPresent(), "'" + DATA_POLICY_TAPE_SINGLE_COPY_NAME + "' data policy must exist");
        client.modifyUserSpectraS3(
                new ModifyUserSpectraS3Request(authId).withDefaultDataPolicyId(tapeDP.get().getId()));
    }

    /**
     * Verifies the full EntirelyInCache lifecycle for a tape GET job:
     *
     * Phase 1 (bug regression): immediately after GET job creation, before staging,
     *   EntirelyInCache must be false. The original bug returned true here because
     *   JobResponseBuilder.build() initialized entirelyInCache=true and the empty-set
     *   loop never ran (vacuous truth).
     *
     * Phase 2 (positive case): once all blobs are staged to cache (job entries become
     *   COMPLETED with BlobCache state IN_CACHE), EntirelyInCache must be true.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    public void testEntirelyInCacheLifecycleForTapeGetJob()
            throws IOException, InterruptedException {
        LOG.info("Starting test: GetJobEntirelyInCacheTest");

        setTapeDataPolicy();

        final Ds3ClientHelpers helper = Ds3ClientHelpers.wrap(client);
        helper.ensureBucketExists(BUCKET_NAME);

        // Create a temp file for PUT
        final Path tempDir = Files.createTempDirectory("entirely-in-cache-test");
        tempDir.toFile().deleteOnExit();
        final File tempFile = new File(tempDir.toFile(), OBJECT_NAME);
        tempFile.deleteOnExit();
        try (final RandomAccessFile raf = new RandomAccessFile(tempFile, "rw")) {
            raf.setLength(OBJECT_SIZE_BYTES);
        }

        final List<Ds3Object> objects = new ArrayList<>();
        objects.add(new Ds3Object(OBJECT_NAME, OBJECT_SIZE_BYTES));

        // Step 1: PUT object and wait for data to be persisted to tape
        final Ds3ClientHelpers.Job putJob = helper.startWriteJob(BUCKET_NAME, objects);
        putJob.transfer(new FileObjectPutter(tempDir));
        final UUID putJobId = putJob.getJobId();
        LOG.info("PUT job started: " + putJobId);

        isJobCompleted(client, putJobId);
        waitForJobsToComplete(client);
        LOG.info("PUT and IOM jobs completed - data is on tape.");

        assertEquals(1, getBlobCountOnTape(client, BUCKET_NAME),
                "Object blob must be persisted to tape before proceeding");

        // Step 2: Clear cache so no blob data remains
        reclaimCache(client);
        LOG.info("Cache reclaimed - no data in cache.");

        // Step 3: Create GET job. The tape is loaded but staging has not started.
        final GetBulkJobSpectraS3Response getResponse =
                client.getBulkJobSpectraS3(new GetBulkJobSpectraS3Request(BUCKET_NAME, objects));
        final UUID getJobId = getResponse.getMasterObjectList().getJobId();
        LOG.info("GET job created: " + getJobId);

        // Phase 1: EntirelyInCache must be false immediately after job creation.
        // All entries are PENDING — DataPlanner has not staged any data yet — so
        // GetJobChunksReadyForClientProcessing returns an empty COMPLETED set.
        final GetJobChunksReadyForClientProcessingSpectraS3Response initialResponse =
                client.getJobChunksReadyForClientProcessingSpectraS3(
                        new GetJobChunksReadyForClientProcessingSpectraS3Request(getJobId));

        assertNotNull(initialResponse.getMasterObjectListResult(),
                "Response must contain a MasterObjectList");
        assertFalse(initialResponse.getMasterObjectListResult().getEntirelyInCache(),
                "EntirelyInCache must be false: data is on tape, cache is empty, no staging has occurred");
        LOG.info("Phase 1 passed: EntirelyInCache=false before staging.");

        // Phase 2: Wait for DataPlanner to stage the data from tape to cache.
        // Poll GetJobChunksReadyForClientProcessing until at least one object is
        // returned (meaning at least one entry has transitioned to COMPLETED).
        // Once all entries are COMPLETED and blobs are IN_CACHE, EntirelyInCache=true.
        LOG.info("Waiting for tape staging to complete...");
        GetJobChunksReadyForClientProcessingSpectraS3Response stagedResponse = null;
        final long pollDeadlineMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(10);
        while (System.currentTimeMillis() < pollDeadlineMs) {
            final GetJobChunksReadyForClientProcessingSpectraS3Response pollResponse =
                    client.getJobChunksReadyForClientProcessingSpectraS3(
                            new GetJobChunksReadyForClientProcessingSpectraS3Request(getJobId));
            final var mol = pollResponse.getMasterObjectListResult();
            if (mol != null && mol.getObjects() != null && mol.getObjects().size() > 0) {
                stagedResponse = pollResponse;
                break;
            }
            Thread.sleep(5000);
        }

        assertNotNull(stagedResponse,
                "Timed out waiting for tape staging to complete - no COMPLETED entries returned");
        assertTrue(stagedResponse.getMasterObjectListResult().getEntirelyInCache(),
                "EntirelyInCache must be true: all blobs are staged to cache and IN_CACHE");
        LOG.info("Phase 2 passed: EntirelyInCache=true after staging.");

        LOG.info("Test completed.");
    }
}
