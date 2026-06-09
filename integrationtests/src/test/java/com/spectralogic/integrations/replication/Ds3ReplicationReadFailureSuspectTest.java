/**
 * Verifies that when a bulk GET on BP-A reads a blob from a DS3 replication target (BP-B)
 * and the local checksum verification fails (we induce this by corrupting the blob's
 * checksum_type on BP-A so the recomputed digest will not match the stored value),
 * the read-side code in ReadChunkFromDs3TargetTask marks the blob suspect on the
 * DS3 target. We assert that by checking target.suspect_blob_ds3_target directly,
 * in addition to the LOG.info evidence emitted by markBlobSuspect().
 */
package com.spectralogic.integrations.replication;

import com.spectralogic.ds3client.Ds3Client;
import com.spectralogic.ds3client.Ds3ClientBuilder;
import com.spectralogic.ds3client.commands.GetBucketRequest;
import com.spectralogic.ds3client.commands.GetBucketResponse;
import com.spectralogic.ds3client.commands.spectrads3.*;
import com.spectralogic.ds3client.helpers.Ds3ClientHelpers;
import com.spectralogic.ds3client.helpers.FileObjectGetter;
import com.spectralogic.ds3client.helpers.FileObjectPutter;
import com.spectralogic.ds3client.models.*;
import com.spectralogic.ds3client.models.bulk.Ds3Object;
import com.spectralogic.ds3client.models.common.Credentials;
import com.spectralogic.integrations.TestUtils;
import com.spectralogic.util.testfrmwrk.TestUtil;
import org.apache.log4j.Logger;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.spectralogic.integrations.DatabaseUtils.corruptChecksumTypeForBucket;
import static com.spectralogic.integrations.DatabaseUtils.countSuspectBlobsOnDs3Target;
import static com.spectralogic.integrations.Ds3ApiHelpers.*;
import static com.spectralogic.integrations.Ds3ReplicationUtils.*;
import static com.spectralogic.integrations.TestConstants.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("LocalDevelopment")
@Tag("iomtest")
public class Ds3ReplicationReadFailureSuspectTest {

    private final static Logger LOG = Logger.getLogger(Ds3ReplicationReadFailureSuspectTest.class);
    private final String bucketName = "ds3-replication-read-suspect-bucket";
    private final String inputPath = "testFiles";
    private final Credentials remoteCreds = new Credentials(authId, secretKey);

    private Ds3Client client;
    private Ds3Client remote_client;

    @BeforeEach
    public void setUp() throws IOException, InterruptedException, SQLException {
        client = TestUtils.setTestParams();
        remote_client = Ds3ClientBuilder.create("http://localhost:8082", remoteCreds).build();
        if (client != null) {
            clearDs3ReplicationRules(client, DATA_POLICY_TAPE_DUAL_COPY_NAME);
            clearDs3Targets(client);
            TestUtils.cleanSetUp(client);
        }
        if (remote_client != null) {
            clearDs3ReplicationRules(remote_client, DATA_POLICY_TAPE_SINGLE_COPY_NAME);
            clearDs3Targets(remote_client);
            TestUtils.cleanSetUp(remote_client);
            FormatAllTapesSpectraS3Request formatAllTapesSpectraS3Request = new FormatAllTapesSpectraS3Request();
            remote_client.formatAllTapesSpectraS3(formatAllTapesSpectraS3Request);
            getTapesReady(remote_client);
        }
    }

    @AfterEach
    public void tearDown() throws IOException, InterruptedException, SQLException {
        if (client != null) {
            unQuiescePartitions(client);
            clearDs3ReplicationRules(client, DATA_POLICY_TAPE_DUAL_COPY_NAME);
            TestUtils.cleanSetUp(client);
            client.close();
        }
        if (remote_client != null) {
            clearDs3ReplicationRules(remote_client, DATA_POLICY_TAPE_SINGLE_COPY_NAME);
            TestUtils.cleanSetUp(remote_client);
            remote_client.close();
        }
    }

    private Ds3Target setupDs3ReplicationToRemote() throws IOException, SQLException {
        final GetDataPoliciesSpectraS3Response responsePolicy =
                client.getDataPoliciesSpectraS3(new GetDataPoliciesSpectraS3Request());
        List<DataPolicy> dataPolicies = responsePolicy.getDataPolicyListResult().getDataPolicies();
        Optional<DataPolicy> dualCopyDP = dataPolicies.stream()
                .filter(dp -> dp.getName().equals(DATA_POLICY_TAPE_DUAL_COPY_NAME)).findFirst();
        assertTrue(dualCopyDP.isPresent(), "Data policy '" + DATA_POLICY_TAPE_DUAL_COPY_NAME + "' not found on BP-A");

        client.modifyUserSpectraS3(new ModifyUserSpectraS3Request(authId)
                .withDefaultDataPolicyId(dualCopyDP.get().getId()));

        // AFTER_NON_EJECTABLE_TAPE so that once we quiesce tape on BP-A, the read
        // strategy picks BP-B directly in pass 1 of addObjectsOnMedia() rather than
        // relying on the LAST_RESORT default getting selected late in the same pass.
        Ds3Target target = registerDockerDs3Target(client, authId, secretKey,
                TargetReadPreferenceType.AFTER_NON_EJECTABLE_TAPE);
        PutDs3DataReplicationRuleSpectraS3Request ruleReq = new PutDs3DataReplicationRuleSpectraS3Request(
                dualCopyDP.get().getId(), target.getId(), DataReplicationRuleType.PERMANENT);
        client.putDs3DataReplicationRuleSpectraS3(ruleReq);
        return target;
    }

    private void setupRemoteDataPolicy() throws IOException {
        final GetDataPoliciesSpectraS3Response responsePolicy =
                remote_client.getDataPoliciesSpectraS3(new GetDataPoliciesSpectraS3Request());
        List<DataPolicy> dataPolicies = responsePolicy.getDataPolicyListResult().getDataPolicies();
        Optional<DataPolicy> singleCopyDP = dataPolicies.stream()
                .filter(dp -> dp.getName().equals(DATA_POLICY_TAPE_SINGLE_COPY_NAME)).findFirst();
        assertTrue(singleCopyDP.isPresent(), "Data policy '" + DATA_POLICY_TAPE_SINGLE_COPY_NAME + "' not found on BP-B");
        remote_client.modifyUserSpectraS3(new ModifyUserSpectraS3Request(authId)
                .withDefaultDataPolicyId(singleCopyDP.get().getId()));
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.MINUTES)
    public void testReadFailureMarksBlobSuspectOnDs3Target() throws IOException, InterruptedException {
        LOG.info("Starting test: Ds3ReplicationReadFailureSuspectTest");
        try {
            // 1. Set up uni-directional DS3 replication chain BP-A -> BP-B
            final Ds3ClientHelpers helper = Ds3ClientHelpers.wrap(client);
            Ds3Target target = setupDs3ReplicationToRemote();
            setupRemoteDataPolicy();
            helper.ensureBucketExists(bucketName);
            TestUtil.sleep(1000);

            // 2. PUT objects to the bucket on BP-A
            final URL testFilesUrl = getClass().getClassLoader().getResource(inputPath);
            if (testFilesUrl == null) {
                throw new RuntimeException("Could not find testFiles directory in resources.");
            }
            final Path inputFilePath = Paths.get(testFilesUrl.toURI());
            final Iterable<Ds3Object> objects = helper.listObjectsForDirectory(inputFilePath);

            final Ds3ClientHelpers.Job putJob = helper.startWriteJob(bucketName, objects);
            putJob.transfer(new FileObjectPutter(inputFilePath));
            UUID putJobId = putJob.getJobId();
            addJobName(client, "Ds3ReplicationReadFailureSuspectTest", putJobId);
            LOG.info("Put job started on BP-A: " + putJobId);

            // 3. Wait for jobs to complete on BP-A and BP-B
            isJobCompleted(client, putJobId);
            waitForJobsToComplete(remote_client);
            LOG.info("Put job completed on BP-A and replication completed on BP-B.");

            // Sanity: BP-A has tape copies for every blob, plus a DS3 target replica
            final GetBucketResponse bucketResponse = client.getBucket(new GetBucketRequest(bucketName));
            assertEquals(5, bucketResponse.getListBucketResult().getObjects().size(),
                    "Expected 5 objects in bucket on BP-A");
            final List<Ds3Object> objectList = new ArrayList<>();
            for (final Contents contents : bucketResponse.getListBucketResult().getObjects()) {
                objectList.add(new Ds3Object(contents.getKey(), contents.getSize()));
            }
            GetPhysicalPlacementForObjectsWithFullDetailsSpectraS3Response placementResponse =
                    client.getPhysicalPlacementForObjectsWithFullDetailsSpectraS3(
                            new GetPhysicalPlacementForObjectsWithFullDetailsSpectraS3Request(bucketName, objectList));
            List<BulkObject> details = placementResponse.getBulkObjectListResult().getObjects();
            for (BulkObject detail : details) {
                assertFalse(detail.getPhysicalPlacement().getTapes().isEmpty(),
                        "Each object should have at least one tape copy on BP-A");
                assertFalse(detail.getPhysicalPlacement().getDs3Targets().isEmpty(),
                        "Each object should have a DS3 target replica on BP-A");
            }

            // 4. Clear cache and quiesce tape backend on BP-A so the GET must source from BP-B
            reclaimCache(client);
            quiescePartitions(client);
            GetTapePartitionsSpectraS3Response partitionResp =
                    client.getTapePartitionsSpectraS3(new GetTapePartitionsSpectraS3Request());
            for (TapePartition partition : partitionResp.getTapePartitionListResult().getTapePartitions()) {
                while (!partition.getQuiesced().equals(Quiesced.YES)) {
                    TestUtil.sleep(500);
                    partition = client.getTapePartitionSpectraS3(
                            new GetTapePartitionSpectraS3Request(partition.getName()))
                            .getTapePartitionResult();
                }
            }
            LOG.info("Cache reclaimed and tape partitions quiesced on BP-A.");

            // 5. Corrupt one blob's checksum_type on BP-A so the read-side recompute will mismatch
            UUID corruptedBlobId = corruptChecksumTypeForBucket(bucketName);
            LOG.info("Corrupted checksum_type for blob " + corruptedBlobId + " on BP-A.");
            assertEquals(0, countSuspectBlobsOnDs3Target(corruptedBlobId),
                    "Pre-condition: blob should not be marked suspect yet");


            final Path outputPath = Paths.get("output-ds3-replication-read-suspect");
            final Thread getThread = new Thread(() -> {
                try {
                    final Ds3ClientHelpers.Job getJob = helper.startReadJob(bucketName, objectList);
                    getJob.transfer(new FileObjectGetter(outputPath));
                    LOG.info("Bulk GET completed in background.");
                } catch (final Exception ex) {
                    LOG.info("Bulk GET surfaced exception (expected for corrupted blob): " + ex.getMessage());
                }
            }, "Ds3ReplicationReadFailureSuspectTest-GET");
            getThread.setDaemon(true);
            getThread.start();

            final long pollDeadlineMs = System.currentTimeMillis() + 120_000;
            int suspectCount = 0;
            while (System.currentTimeMillis() < pollDeadlineMs) {
                suspectCount = countSuspectBlobsOnDs3Target(corruptedBlobId);
                if (suspectCount > 0) {
                    break;
                }
                TestUtil.sleep(1000);
            }

            assertTrue(suspectCount > 0,
                    "Expected blob " + corruptedBlobId + " to be marked suspect on DS3 target "
                    + target.getId() + " within 120s of the bulk GET starting, but "
                    + "suspect_blob_ds3_target had no row.");
            LOG.info("Confirmed: blob " + corruptedBlobId + " has " + suspectCount
                    + " suspect_blob_ds3_target row(s) on BP-A.");

            try {
                client.cancelAllJobsSpectraS3(new CancelAllJobsSpectraS3Request());
            } catch (final Exception ignored) {
                // tearDown's cleanSetUp will retry job cleanup; swallowing is fine.
            }
        } catch (IOException | URISyntaxException | SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
