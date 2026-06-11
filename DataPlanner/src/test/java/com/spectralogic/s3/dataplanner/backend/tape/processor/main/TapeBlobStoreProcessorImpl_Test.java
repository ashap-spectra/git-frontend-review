package com.spectralogic.s3.dataplanner.backend.tape.processor.main;

import com.spectralogic.s3.common.dao.domain.DaoDomainsSeed;
import com.spectralogic.s3.common.dao.domain.ds3.*;
import com.spectralogic.s3.common.dao.domain.notification.JobCompletedNotificationRegistration;
import com.spectralogic.s3.common.dao.domain.notification.TapeFailureNotificationRegistration;
import com.spectralogic.s3.common.dao.domain.shared.PersistenceTarget;
import com.spectralogic.s3.common.dao.domain.tape.*;
import com.spectralogic.s3.common.dao.service.DaoServicesSeed;
import com.spectralogic.s3.common.dao.service.ds3.JobProgressManager;
import com.spectralogic.s3.common.dao.service.ds3.JobProgressManagerImpl;
import com.spectralogic.s3.common.dao.service.tape.TapeService;
import com.spectralogic.s3.common.platform.api.TapeEjector;
import com.spectralogic.s3.common.platform.cache.DiskManager;
import com.spectralogic.s3.common.platform.cache.MockDiskManager;
import com.spectralogic.s3.common.rpc.dataplanner.domain.BlobStoreTaskState;
import com.spectralogic.s3.common.testfrmwrk.MockDaoDriver;
import com.spectralogic.s3.common.rpc.tape.TapeDriveResource;
import com.spectralogic.s3.dataplanner.backend.api.BlobStoreTaskNoLongerValidException;
import com.spectralogic.s3.dataplanner.backend.frmwrk.WorkAggregationUtils;
import com.spectralogic.s3.dataplanner.backend.tape.TapeLockSupportImpl;
import com.spectralogic.s3.dataplanner.backend.tape.api.StaticTapeTask;
import com.spectralogic.s3.dataplanner.backend.tape.api.TapeAvailability;
import com.spectralogic.s3.dataplanner.backend.tape.api.TapeLockSupport;
import com.spectralogic.s3.dataplanner.backend.tape.api.TapeTask;
import com.spectralogic.s3.dataplanner.backend.tape.processor.main.api.internal.Refresh;
import com.spectralogic.s3.dataplanner.backend.tape.processor.main.api.internal.TapeEnvironment;
import com.spectralogic.s3.dataplanner.backend.tape.task.*;
import com.spectralogic.util.bean.BeanFactory;
import com.spectralogic.util.http.RequestType;
import com.spectralogic.util.lang.CollectionFactory;
import com.spectralogic.util.mock.ConstantResponseInvocationHandler;
import com.spectralogic.util.mock.InterfaceProxyFactory;
import com.spectralogic.util.mock.MockInvocationHandler;
import com.spectralogic.util.net.rpc.client.RpcClient;
import com.spectralogic.util.net.rpc.domain.Request;
import com.spectralogic.util.net.rpc.frmwrk.RpcResource;
import com.spectralogic.util.notification.domain.NotificationPayload;
import com.spectralogic.util.notification.domain.NotificationPayloadGenerator;
import com.spectralogic.util.notification.domain.bean.HttpNotificationRegistration;
import com.spectralogic.util.testfrmwrk.DatabaseSupport;
import com.spectralogic.util.testfrmwrk.DatabaseSupportFactory;
import com.spectralogic.util.testfrmwrk.TestBean;
import com.spectralogic.util.testfrmwrk.TestUtil;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.lang.reflect.InvocationHandler;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


public class TapeBlobStoreProcessorImpl_Test {

    @Test
    public void testWriteTaskCreation() throws Exception {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver( dbSupport );

        final DataPolicy dp = mockDaoDriver.createABMConfigSingleCopyOnTape();
        final Tape tape = mockDaoDriver.createTape( TapeState.NORMAL );
        final Bucket bucket = mockDaoDriver.createBucket( null, dp.getId(),"bucket1" );
        final TapePartition partition = mockDaoDriver.attainOneAndOnly(TapePartition.class);

        final MockDiskManager mockDiskManager = new MockDiskManager( dbSupport.getServiceManager() );
        final S3Object o1 = mockDaoDriver.createObject( bucket.getId(), "o1" );
        final Blob b1 = mockDaoDriver.getBlobFor( o1.getId() );
        final Tape tape2 = mockDaoDriver.createTape( TapeState.NORMAL );

        final Job job = mockDaoDriver.createJob( bucket.getId(), null, JobRequestType.PUT );
        mockDaoDriver.updateBean(job.setPriority(BlobStoreTaskPriority.URGENT), Job.PRIORITY);
        final JobEntry entry1 = mockDaoDriver.createJobEntry( job.getId(), b1 );


        List<JobEntry> entries = Arrays.asList(entry1);
        final List<DataPersistenceRule> rules = CollectionFactory.toList(
                mockDaoDriver.attainOneAndOnly(DataPersistenceRule.class));
        mockDaoDriver.createLocalBlobDestinations(entries, rules, bucket.getId() );
        mockDaoDriver.markBlobInCache(b1.getId());
        mockDiskManager.blobLoadedToCache(b1.getId());
        mockTapeLockSupport = mock(TapeLockSupport.class);


        when(mockTapeEnvironment.ensurePhysicalTapeEnvironmentUpToDate(Mockito.any()))
                .thenReturn(true);
        when(mockTapeEnvironment.getTapesInPartition(Mockito.any()))
                .thenReturn(Collections.singleton(tape.getId()));
        TapeDrive drive1 = mockDaoDriver.createTapeDrive(partition.getId(), "tdsn1");
        TapeDrive drive2 =mockDaoDriver.createTapeDrive( partition.getId(), "tdsn2" );
        mockDaoDriver.updateBean(drive1.setTapeId(tape.getId()), TapeDrive.TAPE_ID);
        mockDaoDriver.updateBean(drive2.setTapeId(tape2.getId()), TapeDrive.TAPE_ID);
        Set<UUID> uuidSet = new HashSet<>();
        uuidSet.add(drive1.getId());
        uuidSet.add(drive2.getId());
        when(mockTapeLockSupport.getAvailableTapeDrives())
                .thenReturn(uuidSet);

        tapeBlobStoreProcessor = new TapeBlobStoreProcessorImpl(
                mockTapeLockSupport, dbSupport.getServiceManager(), mockTapeEnvironment, tapeFailureManagement, getDiskManager(), getJobProgressManager());
        tapeBlobStoreProcessor.taskSchedulingRequired();
        TestUtil.sleep(1600);

        TestUtil.sleep(2*60*60);
        assertEquals(1, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
    }
    @Test
    public void testScheduleInspectionFailedCannotContainData() {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver(dbSupport);

        final Tape tape = mockDaoDriver.createTape(null, TapeState.NORMAL, TapeType.LTO_CLEANING_TAPE);


        final String result = tapeBlobStoreProcessor.scheduleInspection(BlobStoreTaskPriority.LOW, tape.getId(), false);
        assertNotNull(result);
        assertTrue(result.contains("cannot contain data"));
        assertEquals(0, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
    }

    @Test
    public void testScheduleInspectionFailedTapeEjected() {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver(dbSupport);

        final Tape tape = mockDaoDriver.createTape(null, TapeState.EJECTED);

        final String result = tapeBlobStoreProcessor.scheduleInspection(BlobStoreTaskPriority.LOW, tape.getId(), false);
        assertNotNull(result);
        assertEquals(result, "tape is in state " + TapeState.EJECTED);
        assertEquals(0, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
    }

    @Test
    public void testScheduleInspectionFailedWhenInspectionNotAllowed() {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver(dbSupport);

        final Tape tape = mockDaoDriver.createTape(TapeState.SERIAL_NUMBER_MISMATCH);

        final String result = tapeBlobStoreProcessor.scheduleInspection(BlobStoreTaskPriority.LOW, tape.getId(), false);
        assertNotNull(result);
        assertEquals(result, "tape is in state " + TapeState.SERIAL_NUMBER_MISMATCH);
        assertEquals(0, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
    }

    private InvocationHandler getRpcClientIh()
    {
        return MockInvocationHandler.forReturnType(
                RpcResource.class,
                new ConstantResponseInvocationHandler( new MockTapeDriveResource() ),
                null );
    }

    @Test
    public void testScheduleInspectionTapeAlreadyInDrive() throws InterruptedException {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver(dbSupport);

        final Tape tape = mockDaoDriver.createTape(TapeState.PENDING_INSPECTION);
        final TapeDrive tapeDrive = mockDaoDriver.createTapeDrive(null, "testDrive", tape.getId());

        when(mockTapeEnvironment.ensurePhysicalTapeEnvironmentUpToDate(Refresh.ALWAYS_CHECK_GEN_NUM)).thenReturn(true);
        when(mockTapeEnvironment.getTapesInPartition(tapeDrive.getPartitionId())).thenReturn(Collections.singleton(tape.getId()));


        // Schedule an inspection
        tapeBlobStoreProcessor.scheduleInspection(BlobStoreTaskPriority.LOW, tape.getId(), false);
        assertEquals(1, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
        assertEquals(InspectTapeTask.class, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().get(0).getClass());

        tapeBlobStoreProcessor.start();
        tapeBlobStoreProcessor.taskSchedulingRequired();

        // wait for the task to run for a maximum of one second
        long timOut = 0;
        while (timOut < 10000) {
            if (tapeBlobStoreProcessor.getTapeTasks().get(tape.getId()).isEmpty()) {
                break;
            }
            Thread.sleep(10);
            timOut+=10;
        }

        // Verify the inspection task ran
        final Tape dbTape = dbSupport.getServiceManager().getRetriever( Tape.class ).retrieve(tape.getId());
        assertNotNull(dbTape);
        assertEquals(TapeState.NORMAL, dbTape.getState());

        assertEquals(0, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
    }

    @Test
    public void testScheduleCleaningFailedNoCleaningTape() {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver(dbSupport);

        final TapeDrive tapeDrive = mockDaoDriver.createTapeDrive(null, "testDrive");

        final String result = tapeBlobStoreProcessor.scheduleCleaning(tapeDrive.getId());
        assertNotNull(result);
        assertTrue(result.contains("No eligible tapes"));
        assertEquals(0, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
    }

    @Test
    public void testScheduleCleaningFailedNoAvailableTapes() {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver(dbSupport);

        final Tape tape = mockDaoDriver.createTape(null, TapeState.NORMAL, TapeType.LTO_CLEANING_TAPE);

        final TapeDrive tapeDrive1 = mockDaoDriver.createTapeDrive(tape.getPartitionId(), "testDrive1");
        final TapeDrive tapeDrive2 = mockDaoDriver.createTapeDrive(tape.getPartitionId(), "testDrive2");


        String result = tapeBlobStoreProcessor.scheduleCleaning(tapeDrive1.getId());
        assertNull(result);
        assertEquals(tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size(), 1);
        final TapeTask firstClean = tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().get(0);
        assertEquals(CleanTapeDriveTask.class, firstClean.getClass());

        result = tapeBlobStoreProcessor.scheduleCleaning(tapeDrive2.getId());
        assertNotNull(result);
        assertTrue(result.contains("All eligible cleaning tapes have been scheduled to clean drives."));
        assertEquals(1, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
        assertEquals(firstClean, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().get(0));
    }

    @Test
    public void testScheduleCleaningDoesNotDoubleScheduleSameDrive() {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver(dbSupport);

        final Tape tape = mockDaoDriver.createTape(null, TapeState.NORMAL, TapeType.LTO_CLEANING_TAPE);

        final TapeDrive tapeDrive = mockDaoDriver.createTapeDrive(tape.getPartitionId(), "testDrive");


        String result = tapeBlobStoreProcessor.scheduleCleaning(tapeDrive.getId());
        assertNull(result);
        assertEquals(1, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
        final TapeTask firstCleaning = tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().get(0);
        assertEquals(CleanTapeDriveTask.class, firstCleaning.getClass());

        result = tapeBlobStoreProcessor.scheduleCleaning(tapeDrive.getId());
        assertNull(result);
        assertEquals(1, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
        assertEquals(firstCleaning, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().get(0));
    }

    @Test
    public void testScheduleCleaningSucceeds() throws InterruptedException {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver(dbSupport);

        final Tape tape = mockDaoDriver.createTape(null, TapeState.NORMAL, TapeType.LTO_CLEANING_TAPE);

        final TapeDrive tapeDrive = mockDaoDriver.createTapeDrive(tape.getPartitionId(), "testDrive", tape.getId());
        assertNull(tapeDrive.getLastCleaned());



        when(mockTapeEnvironment.ensurePhysicalTapeEnvironmentUpToDate(Refresh.ALWAYS_CHECK_GEN_NUM)).thenReturn(true);
        when(mockTapeEnvironment.getTapesInPartition(tapeDrive.getPartitionId())).thenReturn(Collections.singleton(tape.getId()));



        String result = tapeBlobStoreProcessor.scheduleCleaning(tapeDrive.getId());
        assertNull(result);
        assertEquals(1, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
        assertEquals(CleanTapeDriveTask.class, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().get(0).getClass());

        tapeBlobStoreProcessor.start();
        tapeBlobStoreProcessor.taskSchedulingRequired();

        // wait for the task to run for a maximum of ten seconds
        long timOut = 0;
        while (timOut < 10000) {
            if (tapeBlobStoreProcessor.getTapeTasks().get(tape.getId()).isEmpty()) {
                break;
            }
            Thread.sleep(10);
            timOut+=10;
        }

        // Verify the clean task ran
        final TapeDrive dbTapeDrive = dbSupport.getServiceManager().getRetriever( TapeDrive.class ).retrieve(tapeDrive.getId());
        assertNotNull(dbTapeDrive);
        assertNotNull(dbTapeDrive.getLastCleaned());

        assertEquals(0, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
    }

    @Test
    public void testScheduleCleanAndThenTestTapeDriveSucceeds() throws InterruptedException {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver(dbSupport);

        final Tape cleaningTape = mockDaoDriver.createTape(null, TapeState.NORMAL, TapeType.LTO_CLEANING_TAPE);
        final Tape testTape = mockDaoDriver.createTape(null, TapeState.NORMAL);
        mockDaoDriver.updateBean(testTape.setRole(TapeRole.TEST), Tape.ROLE);

        final TapeDrive tapeDrive = mockDaoDriver.createTapeDrive(cleaningTape.getPartitionId(), "testDrive", cleaningTape.getId());

        when(mockTapeEnvironment.ensurePhysicalTapeEnvironmentUpToDate(Refresh.ALWAYS_CHECK_GEN_NUM)).thenReturn(true);
        when(mockTapeEnvironment.getTapesInPartition(tapeDrive.getPartitionId())).thenReturn(CollectionFactory.toSet(cleaningTape.getId(),testTape.getId()));



        String result = tapeBlobStoreProcessor.scheduleTest(tapeDrive.getId(), testTape.getId(), true);
        assertNull(result);
        assertEquals(1, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
        assertEquals(CleanTapeDriveTask.class, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().get(0).getClass());

        tapeBlobStoreProcessor.start();
        tapeBlobStoreProcessor.taskSchedulingRequired();

        // wait for the task to run for a maximum of ten seconds
        long timOut = 0;
        while (timOut < 10000) {
            if (tapeBlobStoreProcessor.getTapeTasks().get(cleaningTape.getId()).isEmpty()) {
                break;
            }
            Thread.sleep(10);
            timOut+=10;
        }

        Thread.sleep(100); //wait 100 ms for test to be scheduled;
        assertEquals(1, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
        assertEquals(TestTapeDriveTask.class, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().get(0).getClass());

        mockDaoDriver.updateBean(tapeDrive.setTapeId(testTape.getId()), TapeDrive.TAPE_ID);
        tapeBlobStoreProcessor.taskSchedulingRequired();

        // wait for the test task to run for a maximum of ten seconds
        timOut = 0;
        while (timOut < 10000) {
            if (tapeBlobStoreProcessor.getTapeTasks().get(testTape.getId()).isEmpty()) {
                break;
            }
            Thread.sleep(10);
            timOut+=10;
        }
        assertEquals(0, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
    }

    @Test
    public void testScheduleTestTapeDriveSucceedsWhenNoCleaningTape() throws InterruptedException {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver(dbSupport);

        final Tape testTape = mockDaoDriver.createTape(null, TapeState.NORMAL);
        mockDaoDriver.updateBean(testTape.setRole(TapeRole.TEST), Tape.ROLE);

        final TapeDrive tapeDrive = mockDaoDriver.createTapeDrive(testTape.getPartitionId(), "testDrive", testTape.getId());

        when(mockTapeEnvironment.ensurePhysicalTapeEnvironmentUpToDate(Refresh.ALWAYS_CHECK_GEN_NUM)).thenReturn(true);
        when(mockTapeEnvironment.getTapesInPartition(tapeDrive.getPartitionId())).thenReturn(CollectionFactory.toSet(testTape.getId()));


        String result = tapeBlobStoreProcessor.scheduleTest(tapeDrive.getId(), testTape.getId(), true);
        assertNull(result);
        assertEquals(1, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
        assertEquals(TestTapeDriveTask.class, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().get(0).getClass());

        tapeBlobStoreProcessor.start();
        tapeBlobStoreProcessor.taskSchedulingRequired();

        // wait for the task to run for a maximum of ten seconds
        long timOut = 0;
        while (timOut < 10000) {
            if (tapeBlobStoreProcessor.getTapeTasks().get(testTape.getId()).isEmpty()) {
                break;
            }
            Thread.sleep(10);
            timOut+=10;
        }

        assertEquals(0, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
    }

    @Test
    public void testScheduleTestTapeDriveFailsWhenNoCleaningOrTestTape() throws InterruptedException {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver(dbSupport);

        final TapeDrive tapeDrive = mockDaoDriver.createTapeDrive(null, "testDrive");


        when(mockTapeEnvironment.ensurePhysicalTapeEnvironmentUpToDate(Refresh.ALWAYS_CHECK_GEN_NUM)).thenReturn(true);
        when(mockTapeEnvironment.getTapesInPartition(tapeDrive.getPartitionId())).thenReturn(Collections.emptySet());

        String result = tapeBlobStoreProcessor.scheduleTest(tapeDrive.getId(), null, true);
        assertNotNull(result);
    }

    @Test
    public void testFailToPrepareToStartImmediatelyUnlocksDrive() throws InterruptedException {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver(dbSupport);

        final Tape tape = mockDaoDriver.createTape(null, TapeState.NORMAL);

        final TapeDrive tapeDrive = mockDaoDriver.createTapeDrive(tape.getPartitionId(), "testDrive", tape.getId());


        mockTapeLockSupport = mock(TapeLockSupport.class);
        doReturn(null).when(mockTapeLockSupport).getTapeLockHolder(any());
        doReturn(CollectionFactory.toSet(tapeDrive.getId())).when(mockTapeLockSupport).getAvailableTapeDrives();

        when(mockTapeEnvironment.ensurePhysicalTapeEnvironmentUpToDate(Refresh.ALWAYS_CHECK_GEN_NUM)).thenReturn(true);
        when(mockTapeEnvironment.getTapesInPartition(tapeDrive.getPartitionId())).thenReturn(Collections.singleton(tape.getId()));

        tapeBlobStoreProcessor = new TapeBlobStoreProcessorImpl(
                mockTapeLockSupport, dbSupport.getServiceManager(), mockTapeEnvironment, tapeFailureManagement, getDiskManager(), getJobProgressManager());

        final StaticTapeTask mockTask = mock(StaticTapeTask.class);
        when(mockTask.getTapeId()).thenReturn(tape.getId());
        when(mockTask.canUseAvailableTape(any())).thenReturn(true);
        when(mockTask.canUseTapeAlreadyInDrive(any())).thenReturn(true);
        when(mockTask.canUseDrive(any())).thenReturn(true);
        when(mockTask.getState()).thenReturn(BlobStoreTaskState.READY);
        when(mockTask.getPriority()).thenReturn(BlobStoreTaskPriority.NORMAL);
        final CountDownLatch latch = new CountDownLatch(1);
        doAnswer( invocation -> {
            latch.countDown();
            throw new IllegalStateException("kaboom");
        }).when(mockTask).prepareForExecutionIfPossible(any(), any());
        tapeBlobStoreProcessor.getTapeTasks().addStaticTask(mockTask);
        assertEquals(tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size(), 1);

        tapeBlobStoreProcessor.start();
        tapeBlobStoreProcessor.taskSchedulingRequired();

        // wait for the task to run for a maximum of ten seconds
        verify(mockTapeLockSupport, never()).unlock(any());
        if (!latch.await(10, TimeUnit.SECONDS)) {
            fail("Never prepared task for execution");
        }

        assertEquals(1, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
        verify(mockTapeLockSupport, times(1)).unlock(eq(mockTask));
    }

    /**
     * If a WriteChunkToTapeTask gets removed from the queue via the processor's deleteTask path
     * (here triggered naturally by TapeLockSupport.addTapeLock throwing inside startTask's
     * post-prepare block — see TapeBlobStoreProcessorImpl.java:1208/1244, faithful to a real
     * IllegalStateException from TapeLockSupportImpl.addTapeLock line 410 when the tape was
     * concurrently locked by another holder), the LocalBlobDestinations it was driving must be
     * transitioned back to PENDING. Otherwise they're orphaned IN_PROGRESS with no live task and
     * stay that way until the dataplanner restarts and BlobStoreDriverImpl.resetEntriesThatWereBeingWorkedOn
     * sweeps them.
     *
     * In 5.x deleteTask had an `else if (t instanceof WriteChunkToTapeTask)` branch that called
     * m_aggregator.returnTasksForReaggregation; that branch was not ported to 6.0.
     */
    @Test
    public void testDeleteTaskResetsWriteDestinationsToPending() throws Exception {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver(dbSupport);
        final DataPolicy dp = mockDaoDriver.createABMConfigSingleCopyOnTape();
        final Tape tape = mockDaoDriver.createTape(TapeState.NORMAL);
        final TapePartition partition = mockDaoDriver.attainOneAndOnly(TapePartition.class);
        final TapeDrive drive = mockDaoDriver.createTapeDrive(partition.getId(), "drv", tape.getId());
        final Bucket bucket = mockDaoDriver.createBucket(null, dp.getId(), "bucket1");
        final S3Object o = mockDaoDriver.createObject(bucket.getId(), "o");
        final Blob blob = mockDaoDriver.getBlobFor(o.getId());
        final Job job = mockDaoDriver.createJob(bucket.getId(), null, JobRequestType.PUT);
        mockDaoDriver.updateBean(job.setPriority(BlobStoreTaskPriority.URGENT), Job.PRIORITY);
        final JobEntry entry = mockDaoDriver.createJobEntry(job.getId(), blob);
        final Set<LocalBlobDestination> destinations =
                mockDaoDriver.createPersistenceTargetsForChunks(CollectionFactory.toSet(entry));
        mockDaoDriver.markBlobInCache(blob.getId());

        // Reproduce the state TapeBlobStoreProcessorImpl.write() would have committed before
        // enqueueing the task — destinations and entries IN_PROGRESS.
        WorkAggregationUtils.markWriteChunksInProgress(
                CollectionFactory.toSet(entry), dbSupport.getServiceManager());
        WorkAggregationUtils.markLocalDestinationsInProgress(
                destinations, dbSupport.getServiceManager());

        // Spy a real TapeLockSupport so it answers honestly to the dozens of queries
        // TapeAvailabilityImpl and the scheduler will make, but make addTapeLock throw the same
        // IllegalStateException the real implementation throws when the target tape is already
        // locked by another holder (TapeLockSupportImpl.java:410).
        mockTapeLockSupport = Mockito.spy(new TapeLockSupportImpl<>(
                InterfaceProxyFactory.getProxy(RpcClient.class, getRpcClientIh()),
                dbSupport.getServiceManager()));
        Mockito.doThrow(new IllegalStateException(
                        "Tape " + tape.getId() + " is already locked by another holder"))
                .when(mockTapeLockSupport).addTapeLock(any(), eq(tape.getId()));

        when(mockTapeEnvironment.ensurePhysicalTapeEnvironmentUpToDate(any())).thenReturn(true);
        when(mockTapeEnvironment.getTapesInPartition(partition.getId()))
                .thenReturn(Collections.singleton(tape.getId()));
        when(mockTapeEnvironment.tryPartitionLock(any())).thenReturn(true);

        tapeBlobStoreProcessor = new TapeBlobStoreProcessorImpl(
                mockTapeLockSupport, dbSupport.getServiceManager(), mockTapeEnvironment,
                tapeFailureManagement, getDiskManager(), getJobProgressManager());

        final WriteChunkToTapeTask task = new WriteChunkToTapeTask(
                BlobStoreTaskPriority.URGENT,
                destinations,
                mock(TapeEjector.class),
                new MockDiskManager(dbSupport.getServiceManager()),
                new JobProgressManagerImpl(dbSupport.getServiceManager()),
                new TapeFailureManagement(dbSupport.getServiceManager()),
                dbSupport.getServiceManager());
        tapeBlobStoreProcessor.getTapeTasks().addDynamicTask(task);

        assertTrue(mockDaoDriver.retrieveAll(LocalBlobDestination.class).stream()
                        .allMatch(d -> d.getBlobStoreState() == JobChunkBlobStoreState.IN_PROGRESS),
                "Destinations should start IN_PROGRESS");

        tapeBlobStoreProcessor.start();
        tapeBlobStoreProcessor.taskSchedulingRequired();

        // The fix recovers the failed task by transitioning it out of PENDING_EXECUTION and
        // resetting its destinations to PENDING for re-aggregation. Once destinations are PENDING,
        // discoverTapeWorkAggregated immediately re-aggregates them into a new write task, which
        // also fails at addTapeLock (the mock throws unconditionally), starting the cycle again.
        // In production the lock-already-held condition would eventually clear; in this test we
        // just need to observe that destinations reach PENDING at least once — that's the moment
        // the orphan invariant is restored.
        long elapsed = 0;
        boolean observedPending = false;
        while (elapsed < 10000) {
            if (mockDaoDriver.retrieveAll(LocalBlobDestination.class).stream()
                    .allMatch(d -> d.getBlobStoreState() == JobChunkBlobStoreState.PENDING)) {
                observedPending = true;
                break;
            }
            Thread.sleep(5);
            elapsed += 5;
        }
        Mockito.verify(mockTapeLockSupport, atLeastOnce()).addTapeLock(any(), eq(tape.getId()));

        // Without the fix the test would fail to observe PENDING because:
        //   1. deleteTask's call to TapeTaskQueueImpl.remove hits validateDequeue
        //      (TapeTaskQueueImpl.java:135), which forbids dequeueing a task in PENDING_EXECUTION,
        //      so the task is stuck in the queue dead (READY-filtered scheduler skips it).
        //   2. No code path resets the LocalBlobDestinations, so they stay IN_PROGRESS forever.
        // With the fix the outer catch transitions the task to READY (via executionFailed) so
        // deleteTask can actually dequeue it, then resetForReaggregation puts destinations back
        // to PENDING.
        assertTrue(observedPending,
                "Destinations must reach PENDING at some point after the write task fails to "
                        + "start; otherwise they're orphaned IN_PROGRESS with a dead task and no "
                        + "path to recovery.");
    }

    /**
     * If invalidateTaskAndThrow fires on a write task because TapeAvailability.verifyAvailable
     * throws (the real TapeAvailabilityImpl throws IllegalStateException at lines 164/174 when
     * the selected tape has transitioned to a permanently-unavailable state between getNextTasks
     * picking it and startTask validating it), the task transitions straight to COMPLETED via
     * BaseTapeTask.prepareForExecutionIfPossible line 82 without runInternal ever running.
     * cleanUpCompletedTasks later silently removes it from the queue.
     *
     * Because runInternal never ran, the LocalBlobDestinations are still IN_PROGRESS (set by
     * TapeBlobStoreProcessorImpl.write before the task was enqueued) and never get reset.
     *
     * Test calls task.prepareForExecutionIfPossible directly (it's public) with a TapeAvailability
     * mock whose verifyAvailable throws — faithful to the real failure mode.
     */
    @Test
    public void testInvalidateWriteTaskResetsDestinationsToPending() throws Exception {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver(dbSupport);
        final DataPolicy dp = mockDaoDriver.createABMConfigSingleCopyOnTape();
        final Tape tape = mockDaoDriver.createTape(TapeState.NORMAL);
        final TapePartition partition = mockDaoDriver.attainOneAndOnly(TapePartition.class);
        final TapeDrive drive = mockDaoDriver.createTapeDrive(partition.getId(), "drv", tape.getId());
        final Bucket bucket = mockDaoDriver.createBucket(null, dp.getId(), "bucket1");
        final S3Object o = mockDaoDriver.createObject(bucket.getId(), "o");
        final Blob blob = mockDaoDriver.getBlobFor(o.getId());
        final Job job = mockDaoDriver.createJob(bucket.getId(), null, JobRequestType.PUT);
        final JobEntry entry = mockDaoDriver.createJobEntry(job.getId(), blob);
        final Set<LocalBlobDestination> destinations =
                mockDaoDriver.createPersistenceTargetsForChunks(CollectionFactory.toSet(entry));
        mockDaoDriver.markBlobInCache(blob.getId());

        WorkAggregationUtils.markWriteChunksInProgress(
                CollectionFactory.toSet(entry), dbSupport.getServiceManager());
        WorkAggregationUtils.markLocalDestinationsInProgress(
                destinations, dbSupport.getServiceManager());

        final WriteChunkToTapeTask task = new WriteChunkToTapeTask(
                BlobStoreTaskPriority.NORMAL,
                destinations,
                mock(TapeEjector.class),
                new MockDiskManager(dbSupport.getServiceManager()),
                new JobProgressManagerImpl(dbSupport.getServiceManager()),
                new TapeFailureManagement(dbSupport.getServiceManager()),
                dbSupport.getServiceManager());

        assertTrue(mockDaoDriver.retrieveAll(LocalBlobDestination.class).stream()
                        .allMatch(d -> d.getBlobStoreState() == JobChunkBlobStoreState.IN_PROGRESS),
                "Destinations should start IN_PROGRESS");

        // Mock TapeAvailability. The non-throwing answers feed selectTape (which uses the
        // partition id and tape-in-drive to find candidates); verifyAvailable throws the same
        // IllegalStateException the real TapeAvailabilityImpl throws at line 164 for a tape
        // that has just become permanently unavailable.
        final TapeAvailability mockAvailability = mock(TapeAvailability.class);
        when(mockAvailability.getAllUnavailableTapes()).thenReturn(Collections.emptySet());
        when(mockAvailability.getTapePartitionId()).thenReturn(partition.getId());
        when(mockAvailability.getTapeInDrive()).thenReturn(tape.getId());
        when(mockAvailability.getDriveId()).thenReturn(drive.getId());
        when(mockAvailability.verifyAvailable(any())).thenThrow(
                new IllegalStateException("Tape " + tape.getId() + " is permanently unavailable."));

        final TapeDriveResource mockDriveResource = mock(TapeDriveResource.class);

        try {
            task.prepareForExecutionIfPossible(mockDriveResource, mockAvailability);
            fail("prepareForExecutionIfPossible should have thrown");
        } catch (final BlobStoreTaskNoLongerValidException expected) {
            // expected — invalidateTaskAndThrow always rethrows
        }

        assertEquals(BlobStoreTaskState.COMPLETED, task.getState(),
                "invalidateTaskAndThrow must leave the task in COMPLETED state");

        assertTrue(mockDaoDriver.retrieveAll(LocalBlobDestination.class).stream()
                        .allMatch(d -> d.getBlobStoreState() == JobChunkBlobStoreState.PENDING),
                "Destinations must be reset to PENDING when the task is invalidated before "
                        + "runInternal runs; otherwise cleanUpCompletedTasks will silently drop the "
                        + "task and leave destinations orphaned IN_PROGRESS.");
    }


    @Test
    public void testScheduleTestTapeDriveFailsWhenTapeIsAlreadyInTest() throws InterruptedException {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver(dbSupport);

        final Tape cleaningTape = mockDaoDriver.createTape(null, TapeState.NORMAL, TapeType.LTO_CLEANING_TAPE);

        final Tape testTape = mockDaoDriver.createTape(null, TapeState.NORMAL);
        mockDaoDriver.updateBean(testTape.setRole(TapeRole.TEST), Tape.ROLE);

        final TapeDrive tapeDrive = mockDaoDriver.createTapeDrive(cleaningTape.getPartitionId(), "testDrive", cleaningTape.getId());
        final TapeDrive tapeDrive2 = mockDaoDriver.createTapeDrive(cleaningTape.getPartitionId(), "testDrive2", cleaningTape.getId());

        //final TapeLockSupport< Object > mockTapeLockSupport = new TapeLockSupportImpl<>(
        //InterfaceProxyFactory.getProxy( RpcClient.class, getRpcClientIh() ),
        //dbSupport.getServiceManager() );

        //final TapeFailureManagement tapeFailureManagement = new TapeFailureManagement(dbSupport.getServiceManager());

        //final TapeEnvironment mockTapeEnvironment = mock(TapeEnvironment.class);
        when(mockTapeEnvironment.ensurePhysicalTapeEnvironmentUpToDate(Refresh.ALWAYS_CHECK_GEN_NUM)).thenReturn(true);
        when(mockTapeEnvironment.getTapesInPartition(tapeDrive.getPartitionId())).thenReturn(CollectionFactory.toSet(cleaningTape.getId(),testTape.getId()));
        when(mockTapeEnvironment.getTapesInPartition(tapeDrive2.getPartitionId())).thenReturn(CollectionFactory.toSet(cleaningTape.getId(),testTape.getId()));

        //final TapeBlobStoreProcessorImpl tapeBlobStoreProcessor = new TapeBlobStoreProcessorImpl(
        //mockTapeLockSupport, dbSupport.getServiceManager(), mockTapeEnvironment, tapeFailureManagement, getDiskManager(), getJobProgressManager());

        String result = tapeBlobStoreProcessor.scheduleTest(tapeDrive.getId(), testTape.getId(), true);
        assertNull(result);
        assertEquals(1, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
        assertEquals(CleanTapeDriveTask.class, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().get(0).getClass());

        tapeBlobStoreProcessor.start();
        tapeBlobStoreProcessor.taskSchedulingRequired();

        // wait for the task to run for a maximum of ten seconds
        long timOut = 0;
        while (timOut < 10000) {
            if (tapeBlobStoreProcessor.getTapeTasks().get(cleaningTape.getId()).isEmpty()) {
                break;
            }
            Thread.sleep(10);
            timOut+=10;
        }

        Thread.sleep(100); //wait 100 ms for test to be scheduled;
        assertEquals(1, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
        assertEquals(TestTapeDriveTask.class, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().get(0).getClass());


        result = tapeBlobStoreProcessor.scheduleTest(tapeDrive2.getId(), testTape.getId(), true);
        assertNull(result);
        mockDaoDriver.updateBean(tapeDrive.setTapeId(testTape.getId()), TapeDrive.TAPE_ID);
        tapeBlobStoreProcessor.taskSchedulingRequired();
        TestUtil.sleep(1000);
        // wait for the test task to run for a maximum of ten seconds
        timOut = 0;
        while (timOut < 50000) {
            if (tapeBlobStoreProcessor.getTapeTasks().get(testTape.getId()).isEmpty()) {
                break;
            }
            Thread.sleep(10);
            timOut+=10;
        }
        assertEquals(0, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size());
        System.out.println(dbSupport.getServiceManager().getRetriever( TapeFailure.class ).getCount() );

    }


    @Test
    public void testNoFailuresFromTapesWithoutPartitionIds() throws InterruptedException {
        final MockDaoDriver mockDaoDriver = new MockDaoDriver(dbSupport);

        mockDaoDriver.createABMConfigSingleCopyOnTape();
        final Tape tape = mockDaoDriver.createTape(TapeState.NORMAL);
        final UUID partitionId = tape.getPartitionId();
        mockDaoDriver.createTapeDrive(partitionId, "drive_sn");

        mockTapeLockSupport = new TapeLockSupportImpl<>(
                InterfaceProxyFactory.getProxy( RpcClient.class, getRpcClientIh() ),
                dbSupport.getServiceManager() );

        //final TapeFailureManagement tapeFailureManagement = new TapeFailureManagement(dbSupport.getServiceManager());

        // final TapeEnvironment mockTapeEnvironment = mock(TapeEnvironment.class);
        final Phaser phaser = new Phaser(1);
        when(mockTapeEnvironment.ensurePhysicalTapeEnvironmentUpToDate(eq(Refresh.ALWAYS_CHECK_GEN_NUM))).thenAnswer( (x) -> {
            phaser.arrive();
            return true;
        });
        when(mockTapeEnvironment.tryPartitionLock(any())).thenReturn(true);
        when(mockTapeEnvironment.getTapesInPartition(partitionId)).thenReturn(Collections.singleton(tape.getId()));
        //report tape not in partition so we clear the association

        tapeBlobStoreProcessor = new TapeBlobStoreProcessorImpl(
                mockTapeLockSupport, dbSupport.getServiceManager(), mockTapeEnvironment, tapeFailureManagement, getDiskManager(), getJobProgressManager());
        final S3Object o1 = mockDaoDriver.createObject(null, "o1");
        final Blob blob = mockDaoDriver.getBlobFor(o1.getId());
        mockDaoDriver.putBlobOnTape(tape.getId(), blob.getId());
        final Job job = mockDaoDriver.createJob(null, null, JobRequestType.GET);
        final JobEntry entry = mockDaoDriver.createJobEntry(job.getId(), blob);
        mockDaoDriver.updateBean(entry.setReadFromTapeId(tape.getId()), JobEntry.READ_FROM_TAPE_ID);

        final ReadChunkFromTapeTask task =
                new ReadChunkFromTapeTask( BlobStoreTaskPriority.values()[ 0 ],
                        CollectionFactory.toList(entry),
                        new MockDiskManager(dbSupport.getServiceManager()),
                        new JobProgressManagerImpl(dbSupport.getServiceManager()),
                        new TapeFailureManagement(dbSupport.getServiceManager()),
                        dbSupport.getServiceManager());
        tapeBlobStoreProcessor.getTapeTasks().addStaticTask(task);
        tapeBlobStoreProcessor.taskSchedulingRequired();
        assertEquals(tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size(), 1);
        assertEquals(ReadChunkFromTapeTask.class, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().get(0).getClass());

        mockDaoDriver.updateBean(
                tape.setPartitionId(null).setStorageDomainMemberId(mockDaoDriver.retrieveAll(StorageDomainMember.class).iterator().next().getId()),
                Tape.PARTITION_ID,
                Tape.STORAGE_DOMAIN_MEMBER_ID);

        tapeBlobStoreProcessor.start();
        tapeBlobStoreProcessor.taskSchedulingRequired();

        phaser.awaitAdvance(0); //we have started the first run but may not have hit our error yet
        tapeBlobStoreProcessor.taskSchedulingRequired();
        phaser.awaitAdvance(1); //we have started our second run so we should have hit our error by now
        assertEquals(0, tapeBlobStoreProcessor.getTapeTasks().getAllTapeTasks().size(), "Task should have been deleted");
    }
    private final static class MockNotificationEventGenerator implements NotificationPayloadGenerator
    {
        public NotificationPayload generateNotificationPayload()
        {
            m_callCount.incrementAndGet();
            return BeanFactory.newBean( TestBean.class ).setIntProp( 999 );
        }

        private final AtomicInteger m_callCount = new AtomicInteger();
    } // end inner class def

    private DiskManager getDiskManager() {
        return InterfaceProxyFactory.getProxy( DiskManager.class, null );
    }

    private JobProgressManager getJobProgressManager() {
        return InterfaceProxyFactory.getProxy( JobProgressManager.class, null );
    }

    static DatabaseSupport dbSupport = DatabaseSupportFactory.getSupport(DaoDomainsSeed.class, DaoServicesSeed.class);
    final TapeFailureManagement tapeFailureManagement = new TapeFailureManagement(dbSupport.getServiceManager());

    TapeEnvironment mockTapeEnvironment = mock(TapeEnvironment.class);
    TapeLockSupport< Object > mockTapeLockSupport = new TapeLockSupportImpl<>(
            InterfaceProxyFactory.getProxy( RpcClient.class, getRpcClientIh() ),
            dbSupport.getServiceManager() );

    TapeBlobStoreProcessorImpl tapeBlobStoreProcessor = new TapeBlobStoreProcessorImpl(
            mockTapeLockSupport, dbSupport.getServiceManager(), mockTapeEnvironment, tapeFailureManagement, getDiskManager(), getJobProgressManager());


    @BeforeEach
    public void setUp() {
        mockTapeEnvironment = mock(TapeEnvironment.class);
        mockTapeLockSupport = new TapeLockSupportImpl<>(
                InterfaceProxyFactory.getProxy( RpcClient.class, getRpcClientIh() ),
                dbSupport.getServiceManager() );
        tapeBlobStoreProcessor = new TapeBlobStoreProcessorImpl(
                mockTapeLockSupport, dbSupport.getServiceManager(), mockTapeEnvironment, tapeFailureManagement, getDiskManager(), getJobProgressManager());


    }


    @BeforeAll
    public static void setupDBFactory() {
        dbSupport = DatabaseSupportFactory.getSupport(DaoDomainsSeed.class, DaoServicesSeed.class);
    }


    @AfterEach
    public void cleanupThreads() {
        tapeBlobStoreProcessor.shutdown();
        dbSupport.reset();
    }
}

