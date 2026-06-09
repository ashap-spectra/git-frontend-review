package com.spectralogic.s3.dataplanner.backend.frmwrk;

import com.spectralogic.s3.common.dao.domain.ds3.BlobStoreTaskPriority;
import com.spectralogic.s3.common.dao.domain.ds3.IomType;
import com.spectralogic.s3.common.dao.domain.ds3.JobRequestType;

import java.util.UUID;

public record TapeWorkAggregationKey(
        JobRequestType requestType,
        BlobStoreTaskPriority priority,
        UUID bucketId,
        UUID readFromTapeId,
        UUID storageDomainId,
        IomType iomType,
        boolean minimizeSpanningAcrossMedia
) {}
