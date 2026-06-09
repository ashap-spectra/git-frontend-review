/*
 *
 * Copyright C 2026, Spectra Logic Corporation and/or its affiliates.
 * All rights reserved.
 *
 */
package com.spectralogic.util.healthmon;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;

import org.apache.log4j.Logger;

import com.spectralogic.util.exception.ExceptionUtil;
import com.spectralogic.util.lang.Platform;

/**
 * Formats stack traces for threads that may be blocked on locks, augmenting them with
 * information about the lock holder and (best-effort) the source-code site at which
 * the holder acquired the lock.
 *
 * For intrinsic {@code synchronized} locks the acquisition frame is recoverable from
 * {@link MonitorInfo#getLockedStackDepth()}.  For {@code java.util.concurrent} locks
 * the holder is identifiable but the acquisition site is not — the JVM does not record
 * a stack frame when an {@code AbstractOwnableSynchronizer} is acquired.
 *
 * Capability flags are read once at class-init and warnings (if any) are logged once;
 * per-call code paths degrade silently so that stuck-work and deadlock logging never
 * gets noisier on JVMs that lack the optional thread-management features.
 */
public final class LockTracing
{
    private LockTracing() {}


    /**
     * Returns a limited stack trace for {@code thread}, suffixed (where supported by
     * the JVM and applicable to the thread's current state) with a section describing
     * the lock the thread is blocked on, the thread that holds it, and the frame at
     * which the holder acquired it.  Falls back to a plain stack trace if the thread
     * has died or no relevant lock owner can be found.
     */
    public static String formatStackWithLockHolder( final Thread thread, final int maxDepth )
    {
        if ( maxDepth <= 0 || null == thread )
        {
            return "";
        }

        final ThreadInfo info = getThreadInfo( thread.getId(), maxDepth );
        if ( null == info )
        {
            return ExceptionUtil.getLimitedStackTrace( thread.getStackTrace(), maxDepth );
        }
        return formatStackWithLockHolder( info, maxDepth );
    }


    /**
     * Returns the thread's limited stack trace alone, with no lock-holder annotation.
     * Use this when the caller wants the cheaper, quieter output -- e.g. for early /
     * frequent ticks of a recurring stuck-work logger where the cost of one extra log
     * stanza per tick is not yet warranted.
     */
    public static String formatStack( final Thread thread, final int maxDepth )
    {
        if ( maxDepth <= 0 || null == thread )
        {
            return "";
        }
        return ExceptionUtil.getLimitedStackTrace( thread.getStackTrace(), maxDepth );
    }


    /**
     * As {@link #formatStackWithLockHolder(Thread, int)}, but for a pre-fetched
     * {@link ThreadInfo}.  Use this when you already have ThreadInfos in hand (e.g.
     * from {@link #getThreadInfosWithLockDetails(long[], int)} or
     * {@link java.lang.management.ThreadMXBean#findDeadlockedThreads()} workflows)
     * to avoid a second {@code getThreadInfo} round-trip for the same thread.
     */
    public static String formatStackWithLockHolder( final ThreadInfo info, final int maxDepth )
    {
        if ( maxDepth <= 0 || null == info )
        {
            return "";
        }

        final StringBuilder sb = new StringBuilder();
        sb.append( ExceptionUtil.getLimitedStackTrace( info.getStackTrace(), maxDepth ) );
        appendLockHolder( sb, info, maxDepth );
        return sb.toString();
    }


    /**
     * Fetches ThreadInfos for the given thread ids with locked-monitor and
     * locked-synchronizer details populated where the JVM supports them.  Returns the
     * raw array from {@link java.lang.management.ThreadMXBean#getThreadInfo(long[],
     * boolean, boolean, int)}, including {@code null} entries for threads that have
     * died.
     */
    public static ThreadInfo[] getThreadInfosWithLockDetails(
            final long[] threadIds, final int maxDepth )
    {
        return THREAD_MX_BEAN.getThreadInfo(
                threadIds,
                OBJECT_MONITOR_USAGE_SUPPORTED,
                SYNCHRONIZER_USAGE_SUPPORTED,
                maxDepth );
    }


    private static void appendLockHolder(
            final StringBuilder sb,
            final ThreadInfo blocked,
            final int maxDepth )
    {
        final LockInfo waitingOn = blocked.getLockInfo();
        final long ownerId = blocked.getLockOwnerId();
        if ( null == waitingOn || 0 > ownerId )
        {
            return;
        }

        final ThreadInfo owner = getThreadInfo( ownerId, maxDepth );
        if ( null == owner )
        {
            sb.append( Platform.NEWLINE )
              .append( "Lock " ).append( describeLock( waitingOn ) )
              .append( ": owner thread is no longer alive." );
            return;
        }

        sb.append( Platform.NEWLINE )
          .append( "Blocked on lock " ).append( describeLock( waitingOn ) )
          .append( " held by thread [" ).append( owner.getThreadName() ).append( "]" )
          .append( " (state " ).append( owner.getThreadState() ).append( ")" );

        final StackTraceElement acquisitionFrame = findAcquisitionFrame( owner, waitingOn );
        if ( null != acquisitionFrame )
        {
            sb.append( Platform.NEWLINE )
              .append( "Lock acquired at: " ).append( acquisitionFrame );
        }
        else if ( Thread.State.BLOCKED == blocked.getThreadState() )
        {
            sb.append( Platform.NEWLINE )
              .append( "(lock acquisition frame not attributable; possibly JIT-inlined)" );
        }
        else
        {
            sb.append( Platform.NEWLINE )
              .append( "(lock acquisition frame unavailable for j.u.c. locks)" );
        }
        sb.append( ":" )
          .append( ExceptionUtil.getLimitedStackTrace( owner.getStackTrace(), maxDepth ) );
    }


    private static StackTraceElement findAcquisitionFrame(
            final ThreadInfo owner,
            final LockInfo waitingOn )
    {
        if ( !OBJECT_MONITOR_USAGE_SUPPORTED )
        {
            return null;
        }

        final MonitorInfo[] monitors = owner.getLockedMonitors();
        final StackTraceElement[] stack = owner.getStackTrace();
        for ( final MonitorInfo m : monitors )
        {
            // Match by (className, identityHashCode) -- LockInfo and MonitorInfo are
            // snapshots, not live references, and identityHashCode alone can collide.
            if ( m.getIdentityHashCode() == waitingOn.getIdentityHashCode()
                 && m.getClassName().equals( waitingOn.getClassName() )
                 && 0 <= m.getLockedStackDepth()
                 && stack.length > m.getLockedStackDepth() )
            {
                return stack[ m.getLockedStackDepth() ];
            }
        }
        return null;
    }


    private static String describeLock( final LockInfo info )
    {
        return info.getClassName() + "@" + Integer.toHexString( info.getIdentityHashCode() );
    }


    private static ThreadInfo getThreadInfo( final long threadId, final int maxDepth )
    {
        final ThreadInfo[] infos = THREAD_MX_BEAN.getThreadInfo(
                new long[] { threadId },
                OBJECT_MONITOR_USAGE_SUPPORTED,
                SYNCHRONIZER_USAGE_SUPPORTED,
                maxDepth );
        return ( null == infos || 0 == infos.length ) ? null : infos[ 0 ];
    }


    private final static ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();
    private final static boolean OBJECT_MONITOR_USAGE_SUPPORTED;
    private final static boolean SYNCHRONIZER_USAGE_SUPPORTED;
    private final static Logger LOG = Logger.getLogger( LockTracing.class );

    static
    {
        OBJECT_MONITOR_USAGE_SUPPORTED = THREAD_MX_BEAN.isObjectMonitorUsageSupported();
        SYNCHRONIZER_USAGE_SUPPORTED = THREAD_MX_BEAN.isSynchronizerUsageSupported();
        if ( !OBJECT_MONITOR_USAGE_SUPPORTED )
        {
            LOG.warn( "Object monitor usage is not supported on this JVM; "
                + "lock-holder tracing for synchronized locks will be unavailable.  "
                + "Plain stack traces will still be logged." );
        }
        if ( !SYNCHRONIZER_USAGE_SUPPORTED )
        {
            LOG.warn( "Synchronizer usage is not supported on this JVM; "
                + "lock-holder tracing for j.u.c. locks will be unavailable." );
        }
    }
}
