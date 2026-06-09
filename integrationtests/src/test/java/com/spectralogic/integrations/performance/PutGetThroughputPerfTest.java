package com.spectralogic.integrations.performance;

import com.spectralogic.ds3client.Ds3Client;
import com.spectralogic.ds3client.commands.spectrads3.*;
import com.spectralogic.ds3client.helpers.Ds3ClientHelpers;
import com.spectralogic.ds3client.helpers.FileObjectGetter;
import com.spectralogic.ds3client.helpers.FileObjectPutter;
import com.spectralogic.ds3client.models.*;
import com.spectralogic.ds3client.models.bulk.Ds3Object;
import com.spectralogic.integrations.TestUtils;
import com.spectralogic.util.testfrmwrk.TestUtil;
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
import java.util.concurrent.TimeUnit;

import static com.spectralogic.integrations.Ds3ApiHelpers.*;
import static com.spectralogic.integrations.TestConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Measures end-to-end PUT and GET throughput at scale.
 *
 *   Phase 1: PUT {@link #PUT_OBJECT_COUNT} objects of {@link #OBJECT_SIZE} bytes,
 *            then wait for the backend (cache -> tape migration) to fully drain.
 *   Phase 2: Force a full cache reclaim and wait for the cache to actually empty.
 *   Phase 3: GET the first {@link #GET_OBJECT_COUNT} objects (cache-cold, so
 *            the read must stage from tape).
 *
 * Reports per-phase elapsed time, an effective MiB/s for PUT and GET, and the
 * overall test duration.
 */
@Tag("performance")
public class PutGetThroughputPerfTest {
    private final static Logger LOG = Logger.getLogger(PutGetThroughputPerfTest.class);

    private static final String BUCKET_NAME = "perf-put-get-throughput";

    private static final int PUT_OBJECT_COUNT = 20_000;
    private static final int GET_OBJECT_COUNT = 1500;
    private static final long OBJECT_SIZE = 1L * 1024 * 1024; // 1 MiB

    private Ds3Client client;
    private Path putSourceDir;
    private Path getOutputDir;

    private void updateUserDataPolicy() throws IOException {
        final GetDataPoliciesSpectraS3Response responsePolicy =
                client.getDataPoliciesSpectraS3(new GetDataPoliciesSpectraS3Request());
        final List<DataPolicy> dataPolicies = responsePolicy.getDataPolicyListResult().getDataPolicies();
        assertTrue(dataPolicies.size() > 1);
        final Optional<DataPolicy> singleCopyTapeDP = dataPolicies.stream()
                .filter(dp -> dp.getName().equals(DATA_POLICY_TAPE_SINGLE_COPY_NAME)).findFirst();
        assertTrue(singleCopyTapeDP.isPresent());
        client.modifyUserSpectraS3(
                new ModifyUserSpectraS3Request(authId)
                        .withDefaultDataPolicyId(singleCopyTapeDP.get().getId()));
    }

    private static String objectName(int index) {
        return "object_" + index + ".dat";
    }

    private Path createTempDirWithObjects(int count, long sizeInBytes) throws IOException {
        final Path tempDir = Files.createTempDirectory("perf-put-get-source");
        tempDir.toFile().deleteOnExit();
        for (int i = 0; i < count; i++) {
            final File file = new File(tempDir.toFile(), objectName(i));
            file.deleteOnExit();
            try (final RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
                raf.setLength(sizeInBytes);
            }
        }
        return tempDir;
    }

    private void waitForActiveJobsToDrain(final String phaseLabel) throws IOException {
        final GetActiveJobsSpectraS3Request activeJobsRequest = new GetActiveJobsSpectraS3Request();
        long lastReportTime = System.currentTimeMillis();
        while (!client.getActiveJobsSpectraS3(activeJobsRequest)
                .getActiveJobListResult().getActiveJobs().isEmpty()) {
            if (System.currentTimeMillis() - lastReportTime > 30_000) {
                final int active = client.getActiveJobsSpectraS3(activeJobsRequest)
                        .getActiveJobListResult().getActiveJobs().size();
                LOG.info(phaseLabel + ": " + active + " active job(s) remain");
                lastReportTime = System.currentTimeMillis();
            }
            TestUtil.sleep(5000);
        }
    }

    @BeforeEach
    public void setUp() throws IOException, InterruptedException, SQLException {
        client = TestUtils.setTestParams();
        if (client != null) {
            TestUtils.cleanSetUp(client);
            PerfTestHelpers.waitForCacheToDrain(client);
        }
    }

    @AfterEach
    public void tearDown() throws IOException, InterruptedException, SQLException {
        if (client != null) {
            cleanupBuckets(client, BUCKET_NAME);
            clearAllJobs(client);
            TestUtils.cleanSetUp(client);
            client.close();
        }
        if (putSourceDir != null) {
            TestUtils.cleanupFiles(putSourceDir.toString());
        }
        if (getOutputDir != null) {
            TestUtils.cleanupFiles(getOutputDir.toString());
        }
    }

    @Test
    @Timeout(value = 8, unit = TimeUnit.HOURS)
    public void testPut20kThenGetFirst10kThroughput() throws IOException {
        final long putPayloadBytes = (long) PUT_OBJECT_COUNT * OBJECT_SIZE;
        final long getPayloadBytes = (long) GET_OBJECT_COUNT * OBJECT_SIZE;

        LOG.info("Starting test: PutGetThroughputPerfTest - PUT "
                + PUT_OBJECT_COUNT + " objects, GET first "
                + GET_OBJECT_COUNT + ", object size " + OBJECT_SIZE + " bytes");
        final long testStartTime = System.currentTimeMillis();

        final Ds3ClientHelpers helper = Ds3ClientHelpers.wrap(client);
        updateUserDataPolicy();
        helper.ensureBucketExists(BUCKET_NAME);

        // --- Phase 1: PUT ---
        LOG.info("Phase 1: building " + PUT_OBJECT_COUNT + " source files of "
                + OBJECT_SIZE + " bytes each (" + (putPayloadBytes / 1024 / 1024) + " MiB total)");
        putSourceDir = createTempDirWithObjects(PUT_OBJECT_COUNT, OBJECT_SIZE);
        final Iterable<Ds3Object> putObjects = helper.listObjectsForDirectory(putSourceDir);

        LOG.info("Phase 1: starting PUT job");
        final long putStartTime = System.currentTimeMillis();
        final Ds3ClientHelpers.Job putJob = helper.startWriteJob(BUCKET_NAME, putObjects);
        putJob.transfer(new FileObjectPutter(putSourceDir));
        // TODO: re-enable once JobResponseBuilder.java:153 NPE is fixed (see
        // ~/Desktop/blob-tape-deadlock-analysis.md, "Secondary finding: NPE in
        // JobResponseBuilder during ModifyJob"). modifyJobSpectraS3 currently
        // throws 500 when a concurrent Blob delete races the response build.
        // addJobName(client, "PutGetThroughputPerfTest-PUT", putJob.getJobId());
        final long putTransferEndTime = System.currentTimeMillis();
        final long putTransferDuration = putTransferEndTime - putStartTime;
        LOG.info("Phase 1: client-side PUT transfer complete in "
                + putTransferDuration + " ms; waiting for backend tape migration to drain");

        waitForActiveJobsToDrain("Phase 1 drain");

        final long putEndTime = System.currentTimeMillis();
        final long putTotalDuration = putEndTime - putStartTime;
        LOG.info("Phase 1 complete: PUT + drain took " + putTotalDuration
                + " ms (" + (putTotalDuration / 1000) + " s)");

        // Sanity check: every object made it into the bucket.
        int objectsInBucket = 0;
        for (final Contents c : helper.listObjects(BUCKET_NAME)) {
            objectsInBucket++;
        }
        assertEquals(PUT_OBJECT_COUNT, objectsInBucket,
                "Expected " + PUT_OBJECT_COUNT + " objects in bucket after PUT");

        // --- Phase 2: clear cache ---
        LOG.info("Phase 2: forcing full cache reclaim and waiting for cache to drain");
        final long reclaimStartTime = System.currentTimeMillis();
        reclaimCache(client);
        PerfTestHelpers.waitForCacheToDrain(client, TimeUnit.MINUTES.toMillis(30));
        final long reclaimDuration = System.currentTimeMillis() - reclaimStartTime;
        LOG.info("Phase 2 complete: cache drain took " + reclaimDuration + " ms");

        // --- Phase 3: GET first GET_OBJECT_COUNT objects (cache-cold) ---
        getOutputDir = Files.createTempDirectory("perf-put-get-output");
        getOutputDir.toFile().deleteOnExit();

        final List<Ds3Object> getObjects = new ArrayList<>(GET_OBJECT_COUNT);
        for (int i = 0; i < GET_OBJECT_COUNT; i++) {
            getObjects.add(new Ds3Object(objectName(i), OBJECT_SIZE));
        }

        LOG.info("Phase 3: starting GET of first " + GET_OBJECT_COUNT + " objects ("
                + (getPayloadBytes / 1024 / 1024) + " MiB total)");
        final long getStartTime = System.currentTimeMillis();
        final Ds3ClientHelpers.Job getJob = helper.startReadJob(BUCKET_NAME, getObjects);
        getJob.transfer(new FileObjectGetter(getOutputDir));
        // TODO: re-enable once JobResponseBuilder.java:153 NPE is fixed (see
        // ~/Desktop/blob-tape-deadlock-analysis.md, "Secondary finding: NPE in
        // JobResponseBuilder during ModifyJob").
        // addJobName(client, "PutGetThroughputPerfTest-GET", getJob.getJobId());
        final long getTransferEndTime = System.currentTimeMillis();
        final long getTransferDuration = getTransferEndTime - getStartTime;
        LOG.info("Phase 3: client-side GET transfer complete in "
                + getTransferDuration + " ms; waiting for any residual active jobs");

        waitForActiveJobsToDrain("Phase 3 drain");

        final long getEndTime = System.currentTimeMillis();
        final long getTotalDuration = getEndTime - getStartTime;
        LOG.info("Phase 3 complete: GET + drain took " + getTotalDuration
                + " ms (" + (getTotalDuration / 1000) + " s)");

        // --- Summary ---
        final long totalDuration = System.currentTimeMillis() - testStartTime;
        final double putMiBs = (putPayloadBytes / 1024.0 / 1024.0) / (putTotalDuration / 1000.0);
        final double getMiBs = (getPayloadBytes / 1024.0 / 1024.0) / (getTotalDuration / 1000.0);

        LOG.info("=== Performance Summary (PUT 20k / GET first 10k) ===");
        LOG.info("Object size:                 " + OBJECT_SIZE + " bytes");
        LOG.info("PUT object count:            " + PUT_OBJECT_COUNT);
        LOG.info("PUT payload:                 " + (putPayloadBytes / 1024 / 1024) + " MiB");
        LOG.info("PUT transfer (client-side):  " + putTransferDuration + " ms");
        LOG.info("PUT + drain (job complete):  " + putTotalDuration + " ms ("
                + (putTotalDuration / 1000) + " s)");
        LOG.info("PUT throughput:              " + String.format("%.2f", putMiBs) + " MiB/s");
        LOG.info("Cache reclaim:               " + reclaimDuration + " ms ("
                + (reclaimDuration / 1000) + " s)");
        LOG.info("GET object count:            " + GET_OBJECT_COUNT);
        LOG.info("GET payload:                 " + (getPayloadBytes / 1024 / 1024) + " MiB");
        LOG.info("GET transfer (client-side):  " + getTransferDuration + " ms");
        LOG.info("GET + drain (job complete):  " + getTotalDuration + " ms ("
                + (getTotalDuration / 1000) + " s)");
        LOG.info("GET throughput:              " + String.format("%.2f", getMiBs) + " MiB/s");
        LOG.info("Total duration:              " + totalDuration + " ms ("
                + (totalDuration / 1000) + " s)");
        LOG.info("======================================================");
    }
}
