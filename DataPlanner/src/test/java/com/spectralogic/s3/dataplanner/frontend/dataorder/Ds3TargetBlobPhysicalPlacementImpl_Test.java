/*******************************************************************************
 *
 * Copyright C 2016, Spectra Logic Corporation and/or its affiliates.  
 * All rights reserved.
 *
 ******************************************************************************/
package com.spectralogic.s3.dataplanner.frontend.dataorder;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.UUID;



import com.spectralogic.s3.common.dao.domain.DaoDomainsSeed;
import com.spectralogic.s3.common.dao.domain.ds3.Blob;
import com.spectralogic.s3.common.dao.domain.ds3.S3Object;
import com.spectralogic.s3.common.dao.domain.shared.BlobObservable;
import com.spectralogic.s3.common.dao.domain.shared.Quiesced;
import com.spectralogic.s3.common.dao.domain.target.BlobTarget;
import com.spectralogic.s3.common.dao.domain.target.Ds3Target;
import com.spectralogic.s3.common.dao.domain.target.ReplicationTarget;
import com.spectralogic.s3.common.dao.domain.target.SuspectBlobDs3Target;
import com.spectralogic.s3.common.dao.domain.target.TargetReadPreferenceType;
import com.spectralogic.s3.common.dao.domain.target.TargetState;
import com.spectralogic.util.db.query.Require;
import com.spectralogic.s3.common.dao.service.DaoServicesSeed;
import com.spectralogic.s3.common.platform.spectrads3.BlobPersistence;
import com.spectralogic.s3.common.platform.spectrads3.BlobPersistenceContainer;
import com.spectralogic.s3.common.rpc.target.Ds3ConnectionFactory;
import com.spectralogic.s3.common.testfrmwrk.MockDaoDriver;
import com.spectralogic.s3.dataplanner.backend.target.task.MockDs3ConnectionFactory;
import com.spectralogic.util.bean.BeanFactory;
import com.spectralogic.util.lang.CollectionFactory;
import com.spectralogic.util.mock.InterfaceProxyFactory;
import com.spectralogic.util.testfrmwrk.DatabaseSupport;
import com.spectralogic.util.testfrmwrk.DatabaseSupportFactory;
import com.spectralogic.util.testfrmwrk.MockBeansServiceManager;
import com.spectralogic.util.testfrmwrk.TestUtil;
import com.spectralogic.util.testfrmwrk.TestUtil.BlastContainer;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class Ds3TargetBlobPhysicalPlacementImpl_Test 
{
    @Test
    public void testConstructorNullBlobIdsNotAllowed()
    {
        TestUtil.assertThrows( null, IllegalArgumentException.class, new BlastContainer()
        {

        public void test()
            {
                new Ds3TargetBlobPhysicalPlacementImpl( 
                        null,
                        new MockBeansServiceManager(),
                        new MockDs3ConnectionFactory() );
            }
        } );
    }
    
    
     @Test
    public void testConstructorEmptyBlobIdsNotAllowed()
    {
        TestUtil.assertThrows( null, IllegalArgumentException.class, new BlastContainer()
        {

            public void test()
            {
                new Ds3TargetBlobPhysicalPlacementImpl( 
                        new HashSet< UUID >(),
                        new MockBeansServiceManager(),
                        new MockDs3ConnectionFactory() );
            }
        } );
    }
    
    
     @Test
    public void testPersistenceReportedCorrectlyWhenDataPersisted()
    {
        
        final MockDaoDriver mockDaoDriver = new MockDaoDriver( dbSupport );
        
        final S3Object o1 = mockDaoDriver.createObject( null, "o1" );
        final S3Object o2 = mockDaoDriver.createObject( null, "o2" );
        final S3Object o3 = mockDaoDriver.createObject( null, "o3" );
        final S3Object o4 = mockDaoDriver.createObject( null, "o4" );
        final Blob b1 = mockDaoDriver.getBlobFor( o1.getId() );
        final Blob b2 = mockDaoDriver.getBlobFor( o2.getId() );
        final Blob b3 = mockDaoDriver.getBlobFor( o3.getId() );
        final Blob b4 = mockDaoDriver.getBlobFor( o4.getId() );
        
        final Ds3Target t1 = mockDaoDriver.createDs3Target( "t1" );
        final Ds3Target t2 = mockDaoDriver.createDs3Target( "t2" );
        final Ds3Target t3 = mockDaoDriver.createDs3Target( "t3" );
        final Ds3Target t4 = mockDaoDriver.createDs3Target( "t4" );
        mockDaoDriver.updateBean( t3.setState( TargetState.OFFLINE ), ReplicationTarget.STATE );
        mockDaoDriver.updateBean( t4.setQuiesced( Quiesced.PENDING ), ReplicationTarget.QUIESCED );
        mockDaoDriver.putBlobOnDs3Target( t1.getId(), b1.getId() );
        mockDaoDriver.putBlobOnDs3Target( t2.getId(), b2.getId() );
        mockDaoDriver.putBlobOnDs3Target( t1.getId(), b3.getId() );
        mockDaoDriver.putBlobOnDs3Target( t2.getId(), b3.getId() );
        mockDaoDriver.putBlobOnDs3Target( t3.getId(), b3.getId() );
        mockDaoDriver.putBlobOnDs3Target( t4.getId(), b3.getId() );

        mockDaoDriver.updateBean( 
                t1.setDefaultReadPreference( TargetReadPreferenceType.MINIMUM_LATENCY ),
                ReplicationTarget.DEFAULT_READ_PREFERENCE );
        mockDaoDriver.createDs3TargetReadPreference( 
                t1.getId(),
                o1.getBucketId(), 
                TargetReadPreferenceType.NEVER );
        mockDaoDriver.createDs3TargetReadPreference( 
                t1.getId(), 
                mockDaoDriver.createBucket( null, "b2" ).getId(),
                TargetReadPreferenceType.MINIMUM_LATENCY );
        mockDaoDriver.updateBean( 
                t2.setDefaultReadPreference( TargetReadPreferenceType.LAST_RESORT ),
                ReplicationTarget.DEFAULT_READ_PREFERENCE );
        
        final MockDs3ConnectionFactory ds3ConnectionFactory = new MockDs3ConnectionFactory();
        final BlobPersistence bp1 = BeanFactory.newBean( BlobPersistence.class );
        bp1.setId( b1.getId() );
        bp1.setAvailableOnTapeNow( true );
        final BlobPersistence bp2 = BeanFactory.newBean( BlobPersistence.class );
        bp2.setId( b2.getId() );
        bp2.setAvailableOnPoolNow( true );
        final BlobPersistence bp3 = BeanFactory.newBean( BlobPersistence.class );
        bp3.setId( b3.getId() );
        bp3.setAvailableOnTapeNow( true );
        bp3.setAvailableOnPoolNow( true );
        final BlobPersistenceContainer blobPersistence =
                BeanFactory.newBean( BlobPersistenceContainer.class ).setBlobs( 
                        new BlobPersistence [] { bp1, bp2, bp3 } );
        ds3ConnectionFactory.setGetBlobPersistenceResponse( blobPersistence );
        
        final Ds3TargetBlobPhysicalPlacement placement = new Ds3TargetBlobPhysicalPlacementImpl( 
                CollectionFactory.toSet( b1.getId(), b2.getId(), b3.getId(), b4.getId() ),
                dbSupport.getServiceManager(),
                ds3ConnectionFactory );
        assertEquals(2,  placement.getCandidateTargets().size(), "Shoulda reported all candidate targets.");
        assertEquals(2,  placement.getBlobsOnTape(t1.getId()).size(), "Shoulda reported correct blobs on tape.");
        assertEquals(2,  placement.getBlobsOnTape(t2.getId()).size(), "Shoulda reported correct blobs on tape.");
        assertTrue(placement.getBlobsOnTape( t2.getId() ).contains( b1.getId() ), "Shoulda reported correct blobs on tape.");
        assertTrue(placement.getBlobsOnTape( t2.getId() ).contains( b3.getId() ), "Shoulda reported correct blobs on tape.");
        assertEquals(2,  placement.getBlobsOnPool(t1.getId()).size(), "Shoulda reported correct blobs on pool.");
        assertEquals(2,  placement.getBlobsOnPool(t2.getId()).size(), "Shoulda reported correct blobs on pool.");
        assertTrue(placement.getBlobsOnPool( t2.getId() ).contains( b2.getId() ), "Shoulda reported correct blobs on pool.");
        assertTrue(placement.getBlobsOnPool( t2.getId() ).contains( b3.getId() ), "Shoulda reported correct blobs on pool.");

        assertEquals(TargetReadPreferenceType.NEVER, placement.getReadPreference( t1.getId() ), "Shoulda used custom read preference since one was defined.");
        assertEquals(TargetReadPreferenceType.LAST_RESORT, placement.getReadPreference( t2.getId() ), "Shoulda used default read preference since no custom one.");
    }
    
    
     @Test
    public void testPersistenceReportedCorrectlyWhenNoDataPersistedAccordingToSource()
    {
        
        final MockDaoDriver mockDaoDriver = new MockDaoDriver( dbSupport );

        final S3Object o1 = mockDaoDriver.createObject( null, "o1" );
        final S3Object o2 = mockDaoDriver.createObject( null, "o2" );
        final S3Object o3 = mockDaoDriver.createObject( null, "o3" );
        final S3Object o4 = mockDaoDriver.createObject( null, "o4" );
        final Blob b1 = mockDaoDriver.getBlobFor( o1.getId() );
        final Blob b2 = mockDaoDriver.getBlobFor( o2.getId() );
        final Blob b3 = mockDaoDriver.getBlobFor( o3.getId() );
        final Blob b4 = mockDaoDriver.getBlobFor( o4.getId() );
        
        mockDaoDriver.createDs3Target( "t1" );
        
        final MockDs3ConnectionFactory ds3ConnectionFactory = new MockDs3ConnectionFactory();
        
        final Ds3TargetBlobPhysicalPlacement placement = new Ds3TargetBlobPhysicalPlacementImpl( 
                CollectionFactory.toSet( b1.getId(), b2.getId(), b3.getId(), b4.getId() ),
                dbSupport.getServiceManager(),
                ds3ConnectionFactory );
        assertEquals(0,  placement.getCandidateTargets().size(), "Shoulda reported no candidate targets.");
    }
    
    
     @Test
    public void testPersistenceReportedCorrectlyWhenAllTargetDataIsSuspect()
    {
        
        final MockDaoDriver mockDaoDriver = new MockDaoDriver( dbSupport );
        
        final S3Object o1 = mockDaoDriver.createObject( null, "o1" );
        final S3Object o2 = mockDaoDriver.createObject( null, "o2" );
        final S3Object o3 = mockDaoDriver.createObject( null, "o3" );
        final S3Object o4 = mockDaoDriver.createObject( null, "o4" );
        final Blob b1 = mockDaoDriver.getBlobFor( o1.getId() );
        final Blob b2 = mockDaoDriver.getBlobFor( o2.getId() );
        final Blob b3 = mockDaoDriver.getBlobFor( o3.getId() );
        final Blob b4 = mockDaoDriver.getBlobFor( o4.getId() );
        
        final Ds3Target t1 = mockDaoDriver.createDs3Target( "t1" );
        final Ds3Target t2 = mockDaoDriver.createDs3Target( "t2" );
        final Ds3Target t3 = mockDaoDriver.createDs3Target( "t3" );
        final Ds3Target t4 = mockDaoDriver.createDs3Target( "t4" );
        mockDaoDriver.updateBean( t3.setState( TargetState.OFFLINE ), ReplicationTarget.STATE );
        mockDaoDriver.updateBean( t4.setQuiesced( Quiesced.PENDING ), ReplicationTarget.QUIESCED );
        mockDaoDriver.makeSuspect( mockDaoDriver.putBlobOnDs3Target( t1.getId(), b1.getId() ) );
        mockDaoDriver.makeSuspect( mockDaoDriver.putBlobOnDs3Target( t2.getId(), b2.getId() ) );
        mockDaoDriver.makeSuspect( mockDaoDriver.putBlobOnDs3Target( t1.getId(), b3.getId() ) );
        mockDaoDriver.makeSuspect( mockDaoDriver.putBlobOnDs3Target( t2.getId(), b3.getId() ) );
        mockDaoDriver.makeSuspect( mockDaoDriver.putBlobOnDs3Target( t3.getId(), b3.getId() ) );
        mockDaoDriver.makeSuspect( mockDaoDriver.putBlobOnDs3Target( t4.getId(), b3.getId() ) );

        mockDaoDriver.updateBean( 
                t1.setDefaultReadPreference( TargetReadPreferenceType.MINIMUM_LATENCY ),
                ReplicationTarget.DEFAULT_READ_PREFERENCE );
        mockDaoDriver.createDs3TargetReadPreference( 
                t1.getId(),
                o1.getBucketId(), 
                TargetReadPreferenceType.NEVER );
        mockDaoDriver.createDs3TargetReadPreference( 
                t1.getId(), 
                mockDaoDriver.createBucket( null, "b2" ).getId(),
                TargetReadPreferenceType.MINIMUM_LATENCY );
        mockDaoDriver.updateBean( 
                t2.setDefaultReadPreference( TargetReadPreferenceType.LAST_RESORT ),
                ReplicationTarget.DEFAULT_READ_PREFERENCE );
        
        final MockDs3ConnectionFactory ds3ConnectionFactory = new MockDs3ConnectionFactory();
        final BlobPersistence bp1 = BeanFactory.newBean( BlobPersistence.class );
        bp1.setId( b1.getId() );
        bp1.setAvailableOnTapeNow( true );
        final BlobPersistence bp2 = BeanFactory.newBean( BlobPersistence.class );
        bp2.setId( b2.getId() );
        bp2.setAvailableOnPoolNow( true );
        final BlobPersistence bp3 = BeanFactory.newBean( BlobPersistence.class );
        bp3.setId( b3.getId() );
        bp3.setAvailableOnTapeNow( true );
        bp3.setAvailableOnPoolNow( true );
        final BlobPersistenceContainer blobPersistence =
                BeanFactory.newBean( BlobPersistenceContainer.class ).setBlobs( 
                        new BlobPersistence [] { bp1, bp2, bp3 } );
        ds3ConnectionFactory.setGetBlobPersistenceResponse( blobPersistence );
        
        final Ds3TargetBlobPhysicalPlacement placement = new Ds3TargetBlobPhysicalPlacementImpl( 
                CollectionFactory.toSet( b1.getId(), b2.getId(), b3.getId(), b4.getId() ),
                dbSupport.getServiceManager(),
                ds3ConnectionFactory );
        assertEquals(0,  placement.getCandidateTargets().size(), "Shoulda reported no candidate targets.");
    }
    
    
     @Test
    public void testPersistenceReportedCorrectlyWhenNoDataPersistedAccordingToTarget()
    {
        
        final MockDaoDriver mockDaoDriver = new MockDaoDriver( dbSupport );

        final S3Object o1 = mockDaoDriver.createObject( null, "o1" );
        final S3Object o2 = mockDaoDriver.createObject( null, "o2" );
        final S3Object o3 = mockDaoDriver.createObject( null, "o3" );
        final S3Object o4 = mockDaoDriver.createObject( null, "o4" );
        final Blob b1 = mockDaoDriver.getBlobFor( o1.getId() );
        final Blob b2 = mockDaoDriver.getBlobFor( o2.getId() );
        final Blob b3 = mockDaoDriver.getBlobFor( o3.getId() );
        final Blob b4 = mockDaoDriver.getBlobFor( o4.getId() );
        
        final Ds3Target t1 = mockDaoDriver.createDs3Target( "t1" );
        final Ds3Target t2 = mockDaoDriver.createDs3Target( "t2" );
        final Ds3Target t3 = mockDaoDriver.createDs3Target( "t3" );
        final Ds3Target t4 = mockDaoDriver.createDs3Target( "t4" );
        mockDaoDriver.updateBean( t3.setState( TargetState.OFFLINE ), ReplicationTarget.STATE );
        mockDaoDriver.updateBean( t4.setQuiesced( Quiesced.PENDING ), ReplicationTarget.QUIESCED );
        mockDaoDriver.putBlobOnDs3Target( t1.getId(), b1.getId() );
        mockDaoDriver.putBlobOnDs3Target( t2.getId(), b2.getId() );
        mockDaoDriver.putBlobOnDs3Target( t1.getId(), b3.getId() );
        mockDaoDriver.putBlobOnDs3Target( t2.getId(), b3.getId() );
        mockDaoDriver.putBlobOnDs3Target( t3.getId(), b3.getId() );
        mockDaoDriver.putBlobOnDs3Target( t4.getId(), b3.getId() );
        
        final MockDs3ConnectionFactory ds3ConnectionFactory = new MockDs3ConnectionFactory();
        ds3ConnectionFactory.setGetBlobPersistenceResponse(
                BeanFactory.newBean( BlobPersistenceContainer.class ).setBlobs( 
                        (BlobPersistence[])Array.newInstance( BlobPersistence.class, 0 ) ) );
        
        final Ds3TargetBlobPhysicalPlacement placement = new Ds3TargetBlobPhysicalPlacementImpl( 
                CollectionFactory.toSet( b1.getId(), b2.getId(), b3.getId(), b4.getId() ),
                dbSupport.getServiceManager(),
                ds3ConnectionFactory );
        assertEquals(0,  placement.getCandidateTargets().size(), "Shoulda reported no candidate targets.");
    }
    
    
     @Test
    public void testPersistenceReportedWhenFailToConnectToTarget()
    {
        
        final MockDaoDriver mockDaoDriver = new MockDaoDriver( dbSupport );

        final S3Object o1 = mockDaoDriver.createObject( null, "o1" );
        final S3Object o2 = mockDaoDriver.createObject( null, "o2" );
        final S3Object o3 = mockDaoDriver.createObject( null, "o3" );
        final S3Object o4 = mockDaoDriver.createObject( null, "o4" );
        final Blob b1 = mockDaoDriver.getBlobFor( o1.getId() );
        final Blob b2 = mockDaoDriver.getBlobFor( o2.getId() );
        final Blob b3 = mockDaoDriver.getBlobFor( o3.getId() );
        final Blob b4 = mockDaoDriver.getBlobFor( o4.getId() );
        
        final Ds3Target t1 = mockDaoDriver.createDs3Target( "t1" );
        final Ds3Target t2 = mockDaoDriver.createDs3Target( "t2" );
        final Ds3Target t3 = mockDaoDriver.createDs3Target( "t3" );
        final Ds3Target t4 = mockDaoDriver.createDs3Target( "t4" );
        mockDaoDriver.updateBean( t3.setState( TargetState.OFFLINE ), ReplicationTarget.STATE );
        mockDaoDriver.updateBean( t4.setQuiesced( Quiesced.PENDING ), ReplicationTarget.QUIESCED );
        mockDaoDriver.putBlobOnDs3Target( t1.getId(), b1.getId() );
        mockDaoDriver.putBlobOnDs3Target( t2.getId(), b2.getId() );
        mockDaoDriver.putBlobOnDs3Target( t1.getId(), b3.getId() );
        mockDaoDriver.putBlobOnDs3Target( t2.getId(), b3.getId() );
        mockDaoDriver.putBlobOnDs3Target( t3.getId(), b3.getId() );
        mockDaoDriver.putBlobOnDs3Target( t4.getId(), b3.getId() );
        
        final Ds3ConnectionFactory ds3ConnectionFactory = 
                InterfaceProxyFactory.getProxy( Ds3ConnectionFactory.class, new InvocationHandler()
                {
                    public Object invoke( final Object proxy, final Method method, final Object[] args ) 
                            throws Throwable
                    {
                        throw new RuntimeException( "Oops." );
                    }
                } );
        
        final Ds3TargetBlobPhysicalPlacement placement = new Ds3TargetBlobPhysicalPlacementImpl( 
                CollectionFactory.toSet( b1.getId(), b2.getId(), b3.getId(), b4.getId() ),
                dbSupport.getServiceManager(),
                ds3ConnectionFactory );
        assertEquals(0,  placement.getCandidateTargets().size(), "Shoulda reported no candidate targets.");
    }
    
    
     @Test
    public void testPersistenceReportedWhenFailToGetBlobPersistence()
    {
        
        final MockDaoDriver mockDaoDriver = new MockDaoDriver( dbSupport );

        final S3Object o1 = mockDaoDriver.createObject( null, "o1" );
        final S3Object o2 = mockDaoDriver.createObject( null, "o2" );
        final S3Object o3 = mockDaoDriver.createObject( null, "o3" );
        final S3Object o4 = mockDaoDriver.createObject( null, "o4" );
        final Blob b1 = mockDaoDriver.getBlobFor( o1.getId() );
        final Blob b2 = mockDaoDriver.getBlobFor( o2.getId() );
        final Blob b3 = mockDaoDriver.getBlobFor( o3.getId() );
        final Blob b4 = mockDaoDriver.getBlobFor( o4.getId() );
        
        final Ds3Target t1 = mockDaoDriver.createDs3Target( "t1" );
        final Ds3Target t2 = mockDaoDriver.createDs3Target( "t2" );
        final Ds3Target t3 = mockDaoDriver.createDs3Target( "t3" );
        final Ds3Target t4 = mockDaoDriver.createDs3Target( "t4" );
        mockDaoDriver.updateBean( t3.setState( TargetState.OFFLINE ), ReplicationTarget.STATE );
        mockDaoDriver.updateBean( t4.setQuiesced( Quiesced.PENDING ), ReplicationTarget.QUIESCED );
        mockDaoDriver.putBlobOnDs3Target( t1.getId(), b1.getId() );
        mockDaoDriver.putBlobOnDs3Target( t2.getId(), b2.getId() );
        mockDaoDriver.putBlobOnDs3Target( t1.getId(), b3.getId() );
        mockDaoDriver.putBlobOnDs3Target( t2.getId(), b3.getId() );
        mockDaoDriver.putBlobOnDs3Target( t3.getId(), b3.getId() );
        mockDaoDriver.putBlobOnDs3Target( t4.getId(), b3.getId() );
        
        final MockDs3ConnectionFactory ds3ConnectionFactory = new MockDs3ConnectionFactory();
        
        final Ds3TargetBlobPhysicalPlacement placement = new Ds3TargetBlobPhysicalPlacementImpl( 
                CollectionFactory.toSet( b1.getId(), b2.getId(), b3.getId(), b4.getId() ),
                dbSupport.getServiceManager(),
                ds3ConnectionFactory );
        assertEquals(0,  placement.getCandidateTargets().size(), "Shoulda reported no candidate targets.");
    }

    @Test
    public void testMarksBlobSuspectWhenRemoteOmitsBlob()
    {
        // BP-A's blob_ds3_target table claims the remote has both b1 and b2, but
        // the remote's getBlobPersistence response only returns b1. b2 should
        // therefore land in target.suspect_blob_ds3_target.
        final MockDaoDriver mockDaoDriver = new MockDaoDriver( dbSupport );

        final S3Object o1 = mockDaoDriver.createObject( null, "o1" );
        final S3Object o2 = mockDaoDriver.createObject( null, "o2" );
        final Blob b1 = mockDaoDriver.getBlobFor( o1.getId() );
        final Blob b2 = mockDaoDriver.getBlobFor( o2.getId() );

        final Ds3Target t1 = mockDaoDriver.createDs3Target( "t1" );
        mockDaoDriver.putBlobOnDs3Target( t1.getId(), b1.getId() );
        mockDaoDriver.putBlobOnDs3Target( t1.getId(), b2.getId() );

        final MockDs3ConnectionFactory ds3ConnectionFactory = new MockDs3ConnectionFactory();
        final BlobPersistence bp1 = BeanFactory.newBean( BlobPersistence.class );
        bp1.setId( b1.getId() );
        bp1.setAvailableOnTapeNow( true );
        ds3ConnectionFactory.setGetBlobPersistenceResponse(
                BeanFactory.newBean( BlobPersistenceContainer.class ).setBlobs(
                        new BlobPersistence[] { bp1 } ) );

        new Ds3TargetBlobPhysicalPlacementImpl(
                CollectionFactory.toSet( b1.getId(), b2.getId() ),
                dbSupport.getServiceManager(),
                ds3ConnectionFactory );

        assertEquals( 0, countSuspectRows( t1.getId(), b1.getId() ),
                "b1 was reported as available; should NOT be suspect." );
        assertEquals( 1, countSuspectRows( t1.getId(), b2.getId() ),
                "b2 was omitted from remote's response; should be suspect on this target." );
    }


    @Test
    public void testDoesNotMarkBlobSuspectWhenRemoteFlagsAreAllFalse()
    {
        // The remote returned an entry for the blob but with both
        // isAvailableOnTapeNow and isAvailableOnPoolNow set to false. This
        // state is transient (remote is mid-ingest or mid-eviction), so we
        // intentionally do NOT mark suspect — only blobs the remote omits
        // entirely from its response are treated as definitively missing.
        final MockDaoDriver mockDaoDriver = new MockDaoDriver( dbSupport );

        final S3Object o1 = mockDaoDriver.createObject( null, "o1" );
        final Blob b1 = mockDaoDriver.getBlobFor( o1.getId() );

        final Ds3Target t1 = mockDaoDriver.createDs3Target( "t1" );
        mockDaoDriver.putBlobOnDs3Target( t1.getId(), b1.getId() );

        final MockDs3ConnectionFactory ds3ConnectionFactory = new MockDs3ConnectionFactory();
        final BlobPersistence bp1 = BeanFactory.newBean( BlobPersistence.class );
        bp1.setId( b1.getId() );
        // both flags left false intentionally
        ds3ConnectionFactory.setGetBlobPersistenceResponse(
                BeanFactory.newBean( BlobPersistenceContainer.class ).setBlobs(
                        new BlobPersistence[] { bp1 } ) );

        new Ds3TargetBlobPhysicalPlacementImpl(
                CollectionFactory.toSet( b1.getId() ),
                dbSupport.getServiceManager(),
                ds3ConnectionFactory );

        assertEquals( 0, countSuspectRows( t1.getId(), b1.getId() ),
                "Remote returned the blob (even without availability flags); "
                + "this is transient, not a definitive missing-data signal, so no suspect mark." );
    }


    @Test
    public void testDoesNotMarkBlobSuspectWhenRemoteReportsAvailable()
    {
        // Happy path: remote reports all blobs available. No suspect rows should be
        // written.
        final MockDaoDriver mockDaoDriver = new MockDaoDriver( dbSupport );

        final S3Object o1 = mockDaoDriver.createObject( null, "o1" );
        final S3Object o2 = mockDaoDriver.createObject( null, "o2" );
        final Blob b1 = mockDaoDriver.getBlobFor( o1.getId() );
        final Blob b2 = mockDaoDriver.getBlobFor( o2.getId() );

        final Ds3Target t1 = mockDaoDriver.createDs3Target( "t1" );
        mockDaoDriver.putBlobOnDs3Target( t1.getId(), b1.getId() );
        mockDaoDriver.putBlobOnDs3Target( t1.getId(), b2.getId() );

        final MockDs3ConnectionFactory ds3ConnectionFactory = new MockDs3ConnectionFactory();
        final BlobPersistence bp1 = BeanFactory.newBean( BlobPersistence.class );
        bp1.setId( b1.getId() );
        bp1.setAvailableOnTapeNow( true );
        final BlobPersistence bp2 = BeanFactory.newBean( BlobPersistence.class );
        bp2.setId( b2.getId() );
        bp2.setAvailableOnPoolNow( true );
        ds3ConnectionFactory.setGetBlobPersistenceResponse(
                BeanFactory.newBean( BlobPersistenceContainer.class ).setBlobs(
                        new BlobPersistence[] { bp1, bp2 } ) );

        new Ds3TargetBlobPhysicalPlacementImpl(
                CollectionFactory.toSet( b1.getId(), b2.getId() ),
                dbSupport.getServiceManager(),
                ds3ConnectionFactory );

        assertEquals( 0, countSuspectRows( t1.getId(), b1.getId() ),
                "b1 was reported available; should NOT be suspect." );
        assertEquals( 0, countSuspectRows( t1.getId(), b2.getId() ),
                "b2 was reported available; should NOT be suspect." );
    }


    @Test
    public void testDoesNotMarkBlobSuspectOnConnectionFailure()
    {
        // Connection failures are transient and must not produce suspect marks.
        // Suspect-marking is reserved for cases where the remote explicitly
        // answered "I don't have this blob."
        final MockDaoDriver mockDaoDriver = new MockDaoDriver( dbSupport );

        final S3Object o1 = mockDaoDriver.createObject( null, "o1" );
        final Blob b1 = mockDaoDriver.getBlobFor( o1.getId() );

        final Ds3Target t1 = mockDaoDriver.createDs3Target( "t1" );
        mockDaoDriver.putBlobOnDs3Target( t1.getId(), b1.getId() );

        final Ds3ConnectionFactory throwingFactory =
                InterfaceProxyFactory.getProxy( Ds3ConnectionFactory.class, new InvocationHandler()
                {
                    public Object invoke( final Object proxy, final Method method, final Object[] args )
                            throws Throwable
                    {
                        throw new RuntimeException( "Simulated network failure." );
                    }
                } );

        new Ds3TargetBlobPhysicalPlacementImpl(
                CollectionFactory.toSet( b1.getId() ),
                dbSupport.getServiceManager(),
                throwingFactory );

        assertEquals( 0, countSuspectRows( t1.getId(), b1.getId() ),
                "Connection failure must not mark blobs suspect." );
    }


    private int countSuspectRows( final UUID targetId, final UUID blobId )
    {
        return dbSupport.getServiceManager().getRetriever( SuspectBlobDs3Target.class ).retrieveAll(
                Require.all(
                        Require.beanPropertyEquals( BlobTarget.TARGET_ID, targetId ),
                        Require.beanPropertyEquals( BlobObservable.BLOB_ID, blobId ) ) ).toSet().size();
    }


    private static DatabaseSupport dbSupport = DatabaseSupportFactory.getSupport(DaoDomainsSeed.class, DaoServicesSeed.class);
    @BeforeAll
    public static void setUp() {
        dbSupport = DatabaseSupportFactory.getSupport(DaoDomainsSeed.class, DaoServicesSeed.class);

    }

    @AfterEach
    public void resetDB() {
        dbSupport.reset();
    }
}
