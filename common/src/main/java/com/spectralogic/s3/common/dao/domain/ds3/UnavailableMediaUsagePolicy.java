/*******************************************************************************
 *
 * Copyright C 2016, Spectra Logic Corporation and/or its affiliates.  
 * All rights reserved.
 *
 ******************************************************************************/
package com.spectralogic.s3.common.dao.domain.ds3;

/**
 * Controls how aggressively a GET/restore job will try to satisfy a read when some of the data lives on
 * media that isn't currently online. Strict permissiveness ladder: {@link #ALLOW} &sup; {@link #DISCOURAGED}
 * &sup; {@link #DISALLOW}. In all modes that permit unavailable media at all, available media is preferred
 * via a multi-pass selection in {@code GetByPhysicalPlacementDataOrderingStrategy}.
 */
public enum UnavailableMediaUsagePolicy
{
    /**
     * Most permissive. Available media is preferred when it can satisfy the read; otherwise the strategy
     * falls back to media on a quiesced or offline tape partition, and finally to ejected or lost tapes.
     * Jobs whose only copies live on ejected/lost tapes are still created and remain pending until the
     * media is brought back online. Use when the caller would rather hold a job open than fail it
     * (e.g. Vail's restore semantics).
     */
    ALLOW,


    /**
     * Default. Available media is preferred; the strategy will fall back to media on a quiesced or
     * offline tape partition only when no available copy can satisfy the read. Ejected and lost tapes
     * are not considered &mdash; jobs that need only such media will fail at creation time.
     */
    DISCOURAGED,


    /**
     * Most restrictive. Only currently-available media is used. Any blob whose only copies live on
     * unavailable media (quiesced/offline partitions or ejected/lost tapes) causes the job to fail.
     */
    DISALLOW,
}
