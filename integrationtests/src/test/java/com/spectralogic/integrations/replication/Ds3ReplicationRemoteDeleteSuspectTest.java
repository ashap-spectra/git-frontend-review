/**
 * Companion to {@link Ds3ReplicationReadFailureSuspectTest}. Covers the scenario
 * where the only remaining read source for a blob (BP-B over a DS3 target) has
 * the object deleted out from under BP-A.
 *
 * <p>Test steps:
 * <ol>
 *   <li>Set up a uni-directional DS3 replication chain BP-A &rarr; BP-B.</li>
 *   <li>PUT objects on BP-A and wait for replication to complete on BP-B.</li>
 *   <li>Clear cache and quiesce tape on BP-A so reads must source from BP-B.</li>
 *   <li>Delete one object on BP-B via the DS3 DeleteObject API.</li>
 *   <li>Issue a bulk GET on BP-A for just that deleted object and observe.</li>
 * </ol>
 *
 * <p><b>How the suspect marking lands here:</b> with a clean DeleteObject on
 * BP-B, the cascade removes BP-B's {@code ds3.s3_object}, {@code ds3.blob},
 * and {@code tape.blob_tape} rows. When BP-A's
 * {@code Ds3TargetBlobPhysicalPlacementImpl} asks BP-B's
 * {@code getBlobPersistence} for the blob, BP-B reports it as not available.
 * BP-A's strategy detects the disagreement between its own
 * {@code target.blob_ds3_target} record (which still claims BP-B has the blob)
 * and BP-B's response, and writes a {@code target.suspect_blob_ds3_target}
 * row directly. This mirrors the cloud-side
 * {@code BasePublicCloudConnection.isBlobAvailableOnCloud} behaviour.
 */
package com.spectralogic.integrations.replication;

import com.spectralogic.ds3client.Ds3Client;
import com.spectralogic.ds3client.Ds3ClientBuilder;
import com.spectralogic.ds3client.commands.DeleteObjectRequest;
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

import static com.spectralogic.integrations.DatabaseUtils.countSuspectBlobsOnDs3Target;
import static com.spectralogic.integrations.DatabaseUtils.getFirstBlobIdForObject;
import static com.spectralogic.integrations.Ds3ApiHelpers.*;
import static com.spectralogic.integrations.Ds3ReplicationUtils.*;
import static com.spectralogic.integrations.TestConstants.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("LocalDevelopment")
@Tag("iomtest")
public class Ds3ReplicationRemoteDeleteSuspectTest {

    private final static Logger LOG = Logger.getLogger(Ds3ReplicationRemoteDeleteSuspectTest.class);
    private final String bucketName = "ds3-replication-remote-delete-bucket";
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
            remote_client.formatAllTapesSpectraS3(new FormatAllTapesSpectraS3Request());
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
        assertTrue(dualCopyDP.isPresent(),
                "Data policy '" + DATA_POLICY_TAPE_DUAL_COPY_NAME + "' not found on BP-A");

        client.modifyUserSpectraS3(new ModifyUserSpectraS3Request(authId)
                .withDefaultDataPolicyId(dualCopyDP.get().getId()));

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
        assertTrue(singleCopyDP.isPresent(),
                "Data policy '" + DATA_POLICY_TAPE_SINGLE_COPY_NAME + "' not found on BP-B");
        remote_client.modifyUserSpectraS3(new ModifyUserSpectraS3Request(authId)
                .withDefaultDataPolicyId(singleCopyDP.get().getId()));
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.MINUTES)
    public void testRemoteDeleteCausesReadFailureOnBpA() throws IOException, InterruptedException {
        LOG.info("Starting test: Ds3ReplicationRemoteDeleteSuspectTest");
        try {
            // 1. Set up uni-directional DS3 replication chain BP-A -> BP-B.
            final Ds3ClientHelpers helper = Ds3ClientHelpers.wrap(client);
            Ds3Target target = setupDs3ReplicationToRemote();
            setupRemoteDataPolicy();
            helper.ensureBucketExists(bucketName);
            TestUtil.sleep(1000);

            // 2. PUT objects to BP-A.
            final URL testFilesUrl = getClass().getClassLoader().getResource(inputPath);
            if (testFilesUrl == null) {
                throw new RuntimeException("Could not find testFiles directory in resources.");
            }
            final Path inputFilePath = Paths.get(testFilesUrl.toURI());
            final Iterable<Ds3Object> objects = helper.listObjectsForDirectory(inputFilePath);

            final Ds3ClientHelpers.Job putJob = helper.startWriteJob(bucketName, objects);
            putJob.transfer(new FileObjectPutter(inputFilePath));
            UUID putJobId = putJob.getJobId();
            addJobName(client, "Ds3ReplicationRemoteDeleteSuspectTest", putJobId);
            LOG.info("Put job started on BP-A: " + putJobId);

            // 3. Wait for jobs on both BPs.
            isJobCompleted(client, putJobId);
            waitForJobsToComplete(remote_client);
            LOG.info("Put job completed on BP-A and replication completed on BP-B.");

            // Sanity: each object has a tape copy on BP-A and a DS3 target copy on BP-A
            // (pointing to BP-B).
            final GetBucketResponse bucketResponse = client.getBucket(new GetBucketRequest(bucketName));
            final List<Contents> bucketContents = bucketResponse.getListBucketResult().getObjects();
            assertEquals(5, bucketContents.size(), "Expected 5 objects in bucket on BP-A");
            final List<Ds3Object> objectList = new ArrayList<>();
            for (final Contents contents : bucketContents) {
                objectList.add(new Ds3Object(contents.getKey(), contents.getSize()));
            }
            GetPhysicalPlacementForObjectsWithFullDetailsSpectraS3Response placementResponse =
                    client.getPhysicalPlacementForObjectsWithFullDetailsSpectraS3(
                            new GetPhysicalPlacementForObjectsWithFullDetailsSpectraS3Request(bucketName, objectList));
            for (BulkObject detail : placementResponse.getBulkObjectListResult().getObjects()) {
                assertFalse(detail.getPhysicalPlacement().getTapes().isEmpty(),
                        "Each object should have a tape copy on BP-A");
                assertFalse(detail.getPhysicalPlacement().getDs3Targets().isEmpty(),
                        "Each object should have a DS3 target copy on BP-A");
            }

            // Pick the first object to single out for deletion + read.
            final String deletedKey = bucketContents.get(0).getKey();
            final UUID deletedBlobId = getFirstBlobIdForObject(bucketName, deletedKey);
            LOG.info("Targeting object '" + deletedKey + "' (blob " + deletedBlobId + ") for deletion on BP-B.");

            // 4. Clear cache and quiesce tape on BP-A so reads must source from BP-B.
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

            // 5. Delete the chosen object on BP-B. The cascade removes BP-B's
            //    s3_object/blob/blob_tape rows for it, so subsequent
            //    getBlobPersistence calls from BP-A will report it as unavailable.
            remote_client.deleteObject(new DeleteObjectRequest(bucketName, deletedKey));
            LOG.info("Deleted object '" + deletedKey + "' from BP-B.");

            // Sanity: confirm BP-B no longer reports the object.
            final GetBucketResponse remoteBucketResponse =
                    remote_client.getBucket(new GetBucketRequest(bucketName));
            for (final Contents contents : remoteBucketResponse.getListBucketResult().getObjects()) {
                assertNotEquals(deletedKey, contents.getKey(),
                        "Object '" + deletedKey + "' should no longer exist on BP-B after delete.");
            }

            assertEquals(0, countSuspectBlobsOnDs3Target(deletedBlobId),
                    "Pre-condition: blob should not be marked suspect yet");

            // 6. Issue a bulk GET on BP-A for ONLY the deleted object. With tape
            //    quiesced and BP-B reporting the blob as unavailable, no source
            //    can be selected. The job will not progress past creation.
            final Path outputPath = Paths.get("output-ds3-replication-remote-delete");
            final List<Ds3Object> filesToGet = Collections.singletonList(
                    new Ds3Object(deletedKey, bucketContents.get(0).getSize()));
            final List<Throwable> getFailure = new ArrayList<>();
            final Thread getThread = new Thread(() -> {
                try {
                    final Ds3ClientHelpers.Job getJob = helper.startReadJob(bucketName, filesToGet);
                    getJob.transfer(new FileObjectGetter(outputPath));
                    LOG.info("Bulk GET completed in background (unexpected for deleted-from-remote scenario).");
                } catch (final Throwable t) {
                    getFailure.add(t);
                    LOG.info("Bulk GET surfaced exception (expected): " + t.getMessage());
                }
            }, "Ds3ReplicationRemoteDeleteSuspectTest-GET");
            getThread.setDaemon(true);
            getThread.start();

            // 7. Poll target.suspect_blob_ds3_target until the row appears. The
            //    strategy detects the disagreement between BP-A's blob_ds3_target
            //    record and BP-B's getBlobPersistence response, and writes the
            //    suspect row before the GET ever surfaces an error.
            final long pollDeadlineMs = System.currentTimeMillis() + 120_000;
            int suspectCount = 0;
            while (System.currentTimeMillis() < pollDeadlineMs) {
                suspectCount = countSuspectBlobsOnDs3Target(deletedBlobId);
                if (suspectCount > 0) {
                    break;
                }
                TestUtil.sleep(1000);
            }

            assertTrue(suspectCount > 0,
                    "Expected blob " + deletedBlobId + " to be marked suspect on DS3 target "
                    + target.getId() + " within 120s of the bulk GET starting, but "
                    + "suspect_blob_ds3_target had no row.");
            LOG.info("Confirmed: blob " + deletedBlobId + " has " + suspectCount
                    + " suspect_blob_ds3_target row(s) on BP-A after BP-B reported it missing.");

            // The GET itself should not have completed successfully against a remote that
            // no longer has the data. Verify it stalled or threw.
            getThread.join(1000);
            final boolean stalledOrFailed = getThread.isAlive() || !getFailure.isEmpty();
            assertTrue(stalledOrFailed,
                    "Expected bulk GET for '" + deletedKey + "' to stall or fail after the "
                    + "object was deleted on BP-B, but it returned successfully.");

            try {
                client.cancelAllJobsSpectraS3(new CancelAllJobsSpectraS3Request());
            } catch (final Exception ignored) {
                // tearDown will clean up jobs anyway.
            }
        } catch (IOException | URISyntaxException | SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
