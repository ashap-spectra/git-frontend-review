/*
 *
 * Copyright C 2018, Spectra Logic Corporation and/or its affiliates.
 * All rights reserved.
 *
 */
package com.spectralogic.util.thread.workmon;

import java.util.HashSet;
import java.util.Set;

import org.apache.log4j.Logger;

import com.spectralogic.util.healthmon.LockTracing;
import com.spectralogic.util.lang.Duration;
import com.spectralogic.util.lang.Platform;
import com.spectralogic.util.lang.Validations;
import com.spectralogic.util.thread.RecurringRunnableExecutor;

public final class MonitoredWorkManager
{
    private MonitoredWorkManager()
    {
        m_executor.start();
    }
    
    
    public void submit( final MonitoredWork work )
    {
        Validations.verifyNotNull( "Work", work );
        synchronized ( m_work )
        {
            m_work.add( work );
        }
        work.configureWorkSet( m_work );
    }
    
    
    public static MonitoredWorkManager getInstance()
    {
        return INSTANCE;
    }
    
    
    private final class WorkLogger implements Runnable
    {
        public void run()
        {
            final Set< MonitoredWork > works;
            synchronized ( m_work )
            {
                works = new HashSet<>( m_work );
            }
            
            final Set< MonitoredWork > deletes = new HashSet<>();
            for ( final MonitoredWork work : works )
            {
                final Duration duration = work.getDuration();
                if ( null == duration )
                {
                    deletes.add( work );
                    continue;
                }
                
                final int secs = duration.getElapsedSeconds();
                if ( 5 < secs )
                {
                    final int logEvery = getLogEvery( secs );
                    final int shouldLogAt = ( secs / logEvery ) * logEvery;
                    if ( work.getLoggedAt() < shouldLogAt )
                    {
                        work.setLoggedAt( shouldLogAt );
                        Logger log = work.getCustomLogger();
                        if ( null == log )
                        {
                            log = LOG;
                        }
                        String message = work.getCustomMessage( duration );
                        if ( null == message )
                        {
                            message = "Still in progress after " + duration;
                        }
                        message += ": " + getLogMessage( work, secs );
                        log.info( message );
                        if ( work.shouldStopMonitoring() && ( ( 24 * 60 ) < duration.getElapsedMinutes() ) )
                        {
                            log.warn( "Stopped monitoring: " + getLogMessage( work, secs ) );
                            work.completed();
                        }
                    }
                }
                
                synchronized ( m_work )
                {
                    m_work.removeAll( deletes );
                }
            }
        }
        
        private String getLogMessage( final MonitoredWork work, final int elapsedSecs )
        {
            final int maxDepth = work.getStackTraceLogging().getMaxDepth();
            if ( maxDepth <= 0 )
            {
                return "[" + work.getThread().getName() + "] " + work.getDescription();
            }

            // For short stalls, log the work's own stack trace only
			// Once a work has been stuck long enough to be suspicious, log
			// the blocked stack trace as well as the stack trace of the lock holder
            final String stack = ( LOCK_HOLDER_LOGGING_THRESHOLD_SECS <= elapsedSecs )
                    ? LockTracing.formatStackWithLockHolder( work.getThread(), maxDepth )
                    : LockTracing.formatStack( work.getThread(), maxDepth );

            return work.getDescription()
                    + Platform.NEWLINE + "Thread " + work.getThread().getName() + " is "
                    + work.getThread().getState() + ":"
                    + stack;
        }
        
        private int getLogEvery( final int elapsedSecs )
        {
            if ( 15 > elapsedSecs )
            {
                return 10;
            }
            return 60;
        }
    } // end inner class def
    
    
    private final Set< MonitoredWork > m_work = new HashSet<>();
    private final RecurringRunnableExecutor m_executor =
            new RecurringRunnableExecutor( new WorkLogger(), 1000 );

    private final static int LOCK_HOLDER_LOGGING_THRESHOLD_SECS = 5 * 60;
    private final static MonitoredWorkManager INSTANCE = new MonitoredWorkManager();
    private final static Logger LOG = Logger.getLogger( MonitoredWorkManager.class );
}
