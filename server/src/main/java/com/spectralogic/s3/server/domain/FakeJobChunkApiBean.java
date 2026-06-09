package com.spectralogic.s3.server.domain;

import com.spectralogic.s3.common.dao.domain.ds3.JobChunkBlobStoreState;
import com.spectralogic.s3.common.dao.domain.ds3.JobRequestType;
import com.spectralogic.s3.common.dao.domain.shared.ReadFromObservable;
import com.spectralogic.util.bean.lang.DefaultBooleanValue;
import com.spectralogic.util.bean.lang.DefaultEnumValue;
import com.spectralogic.util.bean.lang.Optional;

import java.util.Date;
import java.util.UUID;

public interface FakeJobChunkApiBean extends ReadFromObservable<FakeJobChunkApiBean>
{
    String ID = "id";

    UUID getId();

    FakeJobChunkApiBean setId(final UUID value );


    String JOB_ID = "jobId";

    UUID getJobId();

    FakeJobChunkApiBean setJobId(final UUID value );


    String NODE_ID = "nodeId";

    @Optional
    UUID getNodeId();

    FakeJobChunkApiBean setNodeId(final UUID value );


    String CHUNK_NUMBER = "chunkNumber";

    int getChunkNumber();

    FakeJobChunkApiBean setChunkNumber(final int value );


    String BLOB_STORE_STATE = "blobStoreState";

    @DefaultEnumValue( "PENDING" )
    JobChunkBlobStoreState getBlobStoreState();

    FakeJobChunkApiBean setBlobStoreState(final JobChunkBlobStoreState value );


    String PENDING_TARGET_COMMIT = "pendingTargetCommit";

    /**
     * @return TRUE if the chunk is part of a {@link JobRequestType#PUT} job and all local and replicated
     * copies required have been made, and the only thing we're waiting on to delete this chunk and mark it
     * as completed is for the target(s) to complete their local and replicated copies
     */
    @DefaultBooleanValue( false )
    boolean isPendingTargetCommit();

    FakeJobChunkApiBean setPendingTargetCommit(final boolean value );


    String JOB_CREATION_DATE = "jobCreationDate";

    Date getJobCreationDate();

    FakeJobChunkApiBean setJobCreationDate(final Date value );
}
