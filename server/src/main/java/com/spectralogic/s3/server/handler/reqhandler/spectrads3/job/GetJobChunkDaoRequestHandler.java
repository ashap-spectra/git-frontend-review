/*******************************************************************************
 *
 * Copyright C 2016, Spectra Logic Corporation and/or its affiliates.  
 * All rights reserved.
 *
 ******************************************************************************/
package com.spectralogic.s3.server.handler.reqhandler.spectrads3.job;

import com.spectralogic.s3.common.dao.domain.ds3.BucketAclPermission;
import com.spectralogic.s3.common.dao.domain.ds3.DataPathBackend;
import com.spectralogic.s3.common.dao.domain.ds3.JobEntry;
import com.spectralogic.s3.common.platform.security.BucketAclAuthorizationService.AdministratorOverride;
import com.spectralogic.s3.server.domain.FakeJobChunkApiBean;
import com.spectralogic.s3.server.handler.auth.BucketAuthorization.SystemBucketAccess;
import com.spectralogic.s3.server.handler.auth.BucketAuthorizationStrategy;
import com.spectralogic.s3.server.handler.command.api.CommandExecutionParams;
import com.spectralogic.s3.server.handler.reqhandler.frmwk.BaseGetBeanRequestHandler;
import com.spectralogic.s3.server.request.api.DS3Request;
import com.spectralogic.s3.server.request.rest.RestDomainType;
import com.spectralogic.s3.server.servlet.BeanServlet;
import com.spectralogic.s3.server.servlet.api.ServletResponseStrategy;
import com.spectralogic.util.bean.BeanFactory;
import com.spectralogic.util.db.query.Require;
import com.spectralogic.util.exception.FailureTypeObservableException;
import com.spectralogic.util.exception.GenericFailure;
import com.spectralogic.util.net.rpc.frmwrk.RpcFuture.Timeout;

public final class GetJobChunkDaoRequestHandler extends BaseGetBeanRequestHandler<JobEntry>
{
    public GetJobChunkDaoRequestHandler()
    {
        super( JobEntry.class,
                new BucketAuthorizationStrategy( 
                        SystemBucketAccess.STANDARD,
                        BucketAclPermission.JOB,
                        AdministratorOverride.YES ),
               RestDomainType.JOB_CHUNK_DAO );
    }

    
    @Override
    protected ServletResponseStrategy handleRequestInternal(
            final DS3Request request,
            final CommandExecutionParams params )
    {
        if (params.getServiceManager().getRetriever( DataPathBackend.class ).attain( Require.nothing() ).getEmulateChunks()) {
            throw new FailureTypeObservableException(
                    GenericFailure.CONFLICT,
                    "GetJobChunkDaoRequestHandler is not supported in emulate chunks mode.");
        }

        params.getPlannerResource().cleanUpCompletedJobsAndJobChunks().get( Timeout.DEFAULT );

        final JobEntry retval = request.getRestRequest().getBean(
                params.getServiceManager().getRetriever( JobEntry.class ) );

        final FakeJobChunkApiBean apiBean = convertToFakeJobChunk( retval );

        return BeanServlet.serviceGet( params, apiBean );

    }

    private static FakeJobChunkApiBean convertToFakeJobChunk(final JobEntry entry )
    {
        if ( entry == null )
        {
            return null;
        }
        final FakeJobChunkApiBean fakeJobChunkApiBean = BeanFactory.newBean(FakeJobChunkApiBean.class);
        fakeJobChunkApiBean.setId( entry.getId() ); // chunkId = entry.id when chunk emulation is disabled.
        fakeJobChunkApiBean.setJobId( entry.getJobId() );
        fakeJobChunkApiBean.setNodeId( entry.getNodeId() );
        fakeJobChunkApiBean.setChunkNumber( entry.getChunkNumber() );
        fakeJobChunkApiBean.setBlobStoreState( entry.getBlobStoreState() );
        fakeJobChunkApiBean.setPendingTargetCommit( entry.isPendingTargetCommit() );
        fakeJobChunkApiBean.setReadFromTapeId( entry.getReadFromTapeId() );
        fakeJobChunkApiBean.setReadFromPoolId( entry.getReadFromPoolId() );
        fakeJobChunkApiBean.setReadFromS3TargetId( entry.getReadFromS3TargetId() );
        fakeJobChunkApiBean.setReadFromAzureTargetId( entry.getReadFromAzureTargetId() );
        fakeJobChunkApiBean.setReadFromDs3TargetId( entry.getReadFromDs3TargetId() );

        // The job creation date is intentionally left as null since JobEntry does not provide this information.
        // This matches the pre-chunk removal API and behavior.

        return fakeJobChunkApiBean;
    }
}
