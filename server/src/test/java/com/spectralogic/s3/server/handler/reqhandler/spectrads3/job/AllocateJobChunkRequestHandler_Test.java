/*******************************************************************************
 *
 * Copyright C 2014, Spectra Logic Corporation and/or its affiliates.  
 * All rights reserved.
 *
 ******************************************************************************/
package com.spectralogic.s3.server.handler.reqhandler.spectrads3.job;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.spectralogic.s3.common.dao.domain.ds3.DataPathBackend;
import com.spectralogic.s3.common.dao.domain.ds3.JobEntry;
import com.spectralogic.s3.common.rpc.dataplanner.domain.BlobsInCacheInformation;
import com.spectralogic.s3.common.rpc.dataplanner.domain.EntriesInCacheInformation;
import com.spectralogic.util.bean.BeanFactory;
import com.spectralogic.util.lang.CollectionFactory;
import com.spectralogic.util.mock.ConstantResponseInvocationHandler;
import com.spectralogic.util.net.rpc.server.RpcResponse;
import org.junit.jupiter.api.Test;

import com.spectralogic.s3.common.dao.domain.ds3.Blob;
import com.spectralogic.s3.common.dao.domain.ds3.Job;
import com.spectralogic.s3.common.dao.domain.ds3.JobRequestType;
import com.spectralogic.s3.common.dao.service.ds3.NodeService;
import com.spectralogic.s3.common.rpc.dataplanner.DataPlannerResource;
import com.spectralogic.s3.common.testfrmwrk.MockDaoDriver;
import com.spectralogic.s3.server.mock.MockHttpRequestDriver;
import com.spectralogic.s3.server.mock.MockHttpRequestSupport;
import com.spectralogic.s3.server.mock.MockInternalRequestAuthorizationStrategy;
import com.spectralogic.s3.server.request.rest.RestOperationType;
import com.spectralogic.util.db.service.api.BeansServiceManager;
import com.spectralogic.util.http.RequestType;
import com.spectralogic.util.lang.reflect.ReflectUtil;
import com.spectralogic.util.mock.InterfaceProxyFactory;
import com.spectralogic.util.mock.MockInvocationHandler;
import com.spectralogic.util.net.rpc.frmwrk.RpcFuture;
import com.spectralogic.util.net.rpc.frmwrk.RpcFuture.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class AllocateJobChunkRequestHandler_Test 
{
    @Test
    public void testAllocateChunkWaitsForDataPlannerToSetNodeId()
    {
        final MockHttpRequestSupport support = new MockHttpRequestSupport();
        
        final MockDaoDriver mockDaoDriver = new MockDaoDriver( support.getDatabaseSupport() );
        final UUID objectId = mockDaoDriver.createObject( null, "foo", 1024L ).getId();
        final Blob blob = mockDaoDriver.getBlobFor( objectId );
        final JobEntry chunk =
                mockDaoDriver.createJobWithEntry( JobRequestType.PUT, blob );
        final BeansServiceManager serviceManager = support.getDatabaseSupport().getServiceManager();
        support.setPlannerInterfaceIh( buildStartBlobWriteInvocationHandler( chunk, serviceManager ) );
        
        final MockHttpRequestDriver driver = new MockHttpRequestDriver( 
                support,
                true,
                new MockInternalRequestAuthorizationStrategy(),
                RequestType.PUT, 
                "_rest_/job_chunk/" + chunk.getId().toString() )
                        .addParameter( "operation", RestOperationType.ALLOCATE.toString() );
        driver.run();
        driver.assertHttpResponseCodeEquals( 200 );

        final Method methodAllocate =
                ReflectUtil.getMethod( DataPlannerResource.class, "allocateEntry" );
        final Method methodGetBlobsInCache =
                ReflectUtil.getMethod( DataPlannerResource.class, "getBlobsInCache" );
        final Method methodCleanUp =
                ReflectUtil.getMethod( DataPlannerResource.class, "cleanUpCompletedJobsAndJobChunks" );
        final Method methodStillActive =
                ReflectUtil.getMethod( DataPlannerResource.class, "jobStillActive" );
        final Map< Method, Integer > expectedMethodCalls = new HashMap<>();
        expectedMethodCalls.put( methodAllocate, Integer.valueOf( 1 ) );
        expectedMethodCalls.put( methodGetBlobsInCache, Integer.valueOf( 1 ) );
        expectedMethodCalls.put( methodCleanUp, Integer.valueOf( 1 ) );
        expectedMethodCalls.put( methodStillActive, Integer.valueOf( 1 ) );
        support.getPlannerInterfaceBtih().verifyMethodInvocations( expectedMethodCalls );
    }
    
    
    @Test
    public void testAllocateChunkReturns400WhenJobIsGetJob()
    {
        final MockHttpRequestSupport support = new MockHttpRequestSupport();
        
        final MockDaoDriver mockDaoDriver = new MockDaoDriver( support.getDatabaseSupport() );
        final UUID objectId = mockDaoDriver.createObject( null, "foo", 1024L ).getId();
        final Blob blob = mockDaoDriver.getBlobFor( objectId );
        final JobEntry chunk =
                mockDaoDriver.createJobWithEntry( JobRequestType.GET, blob );
        final BeansServiceManager serviceManager = support.getDatabaseSupport().getServiceManager();
        support.setPlannerInterfaceIh( buildStartBlobWriteInvocationHandler( chunk, serviceManager ) );
        
        final MockHttpRequestDriver driver = new MockHttpRequestDriver( 
                support,
                true,
                new MockInternalRequestAuthorizationStrategy(),
                RequestType.PUT, 
                "_rest_/job_chunk/" + chunk.getId().toString() )
                        .addParameter( "operation", RestOperationType.ALLOCATE.toString() );
        driver.run();
        driver.assertHttpResponseCodeEquals( 400 );
    }
    
    
    @Test
    public void testAllocateChunkReturns400WhenJobIsAggregatingPutJobOr200ForBlkp2728()
    {
        final MockHttpRequestSupport support = new MockHttpRequestSupport();
        
        final MockDaoDriver mockDaoDriver = new MockDaoDriver( support.getDatabaseSupport() );
        final UUID objectId = mockDaoDriver.createObject( null, "foo", 1024L ).getId();
        final Blob blob = mockDaoDriver.getBlobFor( objectId );
        final JobEntry chunk =
                mockDaoDriver.createJobWithEntry( JobRequestType.PUT, blob );
        mockDaoDriver.updateBean(
                mockDaoDriver.attainOneAndOnly( Job.class ).setAggregating( true ), 
                Job.AGGREGATING );
        final BeansServiceManager serviceManager = support.getDatabaseSupport().getServiceManager();
        support.setPlannerInterfaceIh( buildStartBlobWriteInvocationHandler( chunk, serviceManager ) );
        
        final MockHttpRequestDriver driver = new MockHttpRequestDriver( 
                support,
                true,
                new MockInternalRequestAuthorizationStrategy(),
                RequestType.PUT, 
                "_rest_/job_chunk/" + chunk.getId().toString() )
                        .addParameter( "operation", RestOperationType.ALLOCATE.toString() );
        driver.run();
        driver.assertHttpResponseCodeEquals( 200 );
    }


    @Test
    public void testAllocateChunkReturns404WhenChunkDoesNotExist()
    {
        final MockHttpRequestSupport support = new MockHttpRequestSupport();

        final MockHttpRequestDriver driver = new MockHttpRequestDriver(
                support,
                true,
                new MockInternalRequestAuthorizationStrategy(),
                RequestType.PUT,
                "_rest_/job_chunk/5bb8883b-9d9f-4750-b613-808feb294aeb" )
                        .addParameter( "operation", RestOperationType.ALLOCATE.toString() );
        driver.run();
        driver.assertHttpResponseCodeEquals( 404 );
    }


    @Test
    public void testAllocateEmulatedChunkAtomicallyAllocatesAllEntriesSharingChunkId()
    {
        final MockHttpRequestSupport support = new MockHttpRequestSupport();

        final MockDaoDriver mockDaoDriver = new MockDaoDriver( support.getDatabaseSupport() );
        final DataPathBackend backend = mockDaoDriver.attainOneAndOnly( DataPathBackend.class );
        mockDaoDriver.updateBean(
                backend.setEmulateChunks( true ), DataPathBackend.EMULATE_CHUNKS );

        final UUID sharedChunkId = UUID.randomUUID();
        final Set< UUID > expectedEntryIds = createEmulatedChunk( mockDaoDriver, 3, sharedChunkId );
        final Set< UUID > observedEntryIds = new HashSet<>();
        support.setPlannerInterfaceIh( buildEmulatedChunkInvocationHandler(
                observedEntryIds, /* allocateAll= */ true ) );

        final MockHttpRequestDriver driver = new MockHttpRequestDriver(
                support,
                true,
                new MockInternalRequestAuthorizationStrategy(),
                RequestType.PUT,
                "_rest_/job_chunk/" + sharedChunkId.toString() )
                        .addParameter( "operation", RestOperationType.ALLOCATE.toString() );
        driver.run();
        driver.assertHttpResponseCodeEquals( 200 );
        assertEquals( expectedEntryIds, observedEntryIds );

        final Method methodAllocateEntries =
                ReflectUtil.getMethod( DataPlannerResource.class, "allocateEntries" );
        final Method methodAllocateEntry =
                ReflectUtil.getMethod( DataPlannerResource.class, "allocateEntry" );
        final Method methodGetBlobsInCache =
                ReflectUtil.getMethod( DataPlannerResource.class, "getBlobsInCache" );
        final Method methodCleanUp =
                ReflectUtil.getMethod( DataPlannerResource.class, "cleanUpCompletedJobsAndJobChunks" );
        final Method methodStillActive =
                ReflectUtil.getMethod( DataPlannerResource.class, "jobStillActive" );
        final Map< Method, Integer > expectedMethodCalls = new HashMap<>();
        expectedMethodCalls.put( methodAllocateEntries, Integer.valueOf( 1 ) );
        expectedMethodCalls.put( methodAllocateEntry, Integer.valueOf( 0 ) );
        expectedMethodCalls.put( methodGetBlobsInCache, Integer.valueOf( 1 ) );
        expectedMethodCalls.put( methodCleanUp, Integer.valueOf( 1 ) );
        expectedMethodCalls.put( methodStillActive, Integer.valueOf( 1 ) );
        support.getPlannerInterfaceBtih().verifyMethodInvocations( expectedMethodCalls );
    }


    @Test
    public void testAllocateEmulatedChunkReturns503WhenPlannerCannotAtomicallyAllocate()
    {
        final MockHttpRequestSupport support = new MockHttpRequestSupport();

        final MockDaoDriver mockDaoDriver = new MockDaoDriver( support.getDatabaseSupport() );
        final DataPathBackend backend = mockDaoDriver.attainOneAndOnly( DataPathBackend.class );
        mockDaoDriver.updateBean(
                backend.setEmulateChunks( true ), DataPathBackend.EMULATE_CHUNKS );

        final UUID sharedChunkId = UUID.randomUUID();
        createEmulatedChunk( mockDaoDriver, 3, sharedChunkId );
        support.setPlannerInterfaceIh( buildEmulatedChunkInvocationHandler(
                new HashSet<>(), /* allocateAll= */ false ) );

        final MockHttpRequestDriver driver = new MockHttpRequestDriver(
                support,
                true,
                new MockInternalRequestAuthorizationStrategy(),
                RequestType.PUT,
                "_rest_/job_chunk/" + sharedChunkId.toString() )
                        .addParameter( "operation", RestOperationType.ALLOCATE.toString() );
        driver.run();
        // RETRY_WITH_ASYNCHRONOUS_WAIT = 503.
        driver.assertHttpResponseCodeEquals( 503 );
    }


    @Test
    public void testAllocateEmulatedChunkReturns404WhenChunkIdMatchesNoEntries()
    {
        final MockHttpRequestSupport support = new MockHttpRequestSupport();

        final MockDaoDriver mockDaoDriver = new MockDaoDriver( support.getDatabaseSupport() );
        final DataPathBackend backend = mockDaoDriver.attainOneAndOnly( DataPathBackend.class );
        mockDaoDriver.updateBean(
                backend.setEmulateChunks( true ), DataPathBackend.EMULATE_CHUNKS );

        final MockHttpRequestDriver driver = new MockHttpRequestDriver(
                support,
                true,
                new MockInternalRequestAuthorizationStrategy(),
                RequestType.PUT,
                "_rest_/job_chunk/" + UUID.randomUUID().toString() )
                        .addParameter( "operation", RestOperationType.ALLOCATE.toString() );
        driver.run();
        driver.assertHttpResponseCodeEquals( 404 );
    }


    /**
     * Creates a PUT job with {@code entryCount} JobEntries (each with its own blob) and rewrites
     * their chunkId to {@code sharedChunkId} so they form a single emulated chunk. Returns the set
     * of entry ids that share the chunk id.
     */
    private static Set< UUID > createEmulatedChunk(
            final MockDaoDriver mockDaoDriver,
            final int entryCount,
            final UUID sharedChunkId )
    {
        final Job job = mockDaoDriver.createJob( null, null, JobRequestType.PUT );
        final Set< UUID > entryIds = new HashSet<>();
        for ( int i = 0; i < entryCount; ++i )
        {
            final Blob blob = mockDaoDriver.getBlobFor(
                    mockDaoDriver.createObject( null, "emulated-" + i, 1024L ).getId() );
            final JobEntry entry = mockDaoDriver.createJobEntry( job.getId(), blob );
            mockDaoDriver.updateBean(
                    entry.setChunkId( sharedChunkId ), JobEntry.CHUNK_ID );
            entryIds.add( entry.getId() );
        }
        return entryIds;
    }


    /**
     * Stubs the planner for the emulated-chunk allocation path. The {@code observedEntryIds} set
     * is populated with the IDs the handler passed to {@code allocateEntries}; when
     * {@code allocateAll} is false the stub returns an empty {@code entriesInCache}, simulating an
     * atomic-allocation failure.
     */
    private static InvocationHandler buildEmulatedChunkInvocationHandler(
            final Set< UUID > observedEntryIds,
            final boolean allocateAll )
    {
        return MockInvocationHandler.forMethod(
                ReflectUtil.getMethod( DataPlannerResource.class, "getBlobsInCache" ),
                new ConstantResponseInvocationHandler( new RpcResponse<>(
                        BeanFactory.newBean( BlobsInCacheInformation.class ) ) ),
                MockInvocationHandler.forMethod(
                        ReflectUtil.getMethod( DataPlannerResource.class, "allocateEntries" ),
                        new InvocationHandler()
                        {
                            @Override
                            public Object invoke(
                                    final Object proxy,
                                    final Method method,
                                    final Object[] args ) throws Throwable
                            {
                                final UUID[] requestedIds = (UUID[]) args[ 1 ];
                                observedEntryIds.addAll( CollectionFactory.toSet( requestedIds ) );
                                final EntriesInCacheInformation response =
                                        BeanFactory.newBean( EntriesInCacheInformation.class );
                                response.setEntriesInCache(
                                        allocateAll ? requestedIds : new UUID[ 0 ] );
                                return new RpcResponse<>( response );
                            }
                        },
                        null ) );
    }


    private static UUID getNodeId( final BeansServiceManager serviceManager )
    {
        return serviceManager
                .getService( NodeService.class )
                .getThisNode()
                .getId();
    }


    private static InvocationHandler buildStartBlobWriteInvocationHandler(
            final JobEntry chunk,
            final BeansServiceManager serviceManager)
    {
        final Method startBlobWriteMethod;
        try
        {
            startBlobWriteMethod = DataPlannerResource.class.getMethod(
                    "allocateEntry",
                    UUID.class );
        }
        catch ( final NoSuchMethodException | SecurityException ex )
        {
            throw new RuntimeException( ex );
        }
        return MockInvocationHandler.forMethod(
                startBlobWriteMethod,
                new InvocationHandler() {
                    @Override
                    public Object invoke(final Object proxy, final Method method, final Object[] args)
                            throws Throwable {
                        if (!chunk.getId().equals(args[0])) {
                            throw new IllegalArgumentException(
                                    "Shoulda provided the chunk id.");
                        }
                        return buildMockDelayFuture(new Runnable() {
                            @Override
                            @Test
                            public void run() {

                            }
                        });
                    }
                },
                null );
    }
    
    
    private static RpcFuture< ? > buildMockDelayFuture( final Runnable runnable )
    {
        try
        {
            return InterfaceProxyFactory.getProxy(
                    RpcFuture.class,
                    MockInvocationHandler.forMethod(
                            RpcFuture.class.getMethod( "get", Timeout.class ),
                            new InvocationHandler()
                            {
                                @Override
                                public Object invoke(
                                        final Object proxy,
                                        final Method method,
                                        final Object[] args ) throws Throwable
                                {
                                    Thread.sleep( 50 );
                                    runnable.run();
                                    return null;
                                }
                            },
                            null ) );
        }
        catch ( final NoSuchMethodException | SecurityException ex )
        {
            throw new RuntimeException( ex );
        }
    }
}
