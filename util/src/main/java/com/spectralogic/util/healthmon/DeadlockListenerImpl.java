/*******************************************************************************
 *
 * Copyright C 2013, Spectra Logic Corporation and/or its affiliates.
 * All rights reserved.
 *
 ******************************************************************************/
package com.spectralogic.util.healthmon;

import java.lang.management.ThreadInfo;
import java.util.Set;

import org.apache.log4j.Logger;

import com.spectralogic.util.lang.Platform;

public final class DeadlockListenerImpl implements DeadlockListener
{
    public void deadlockOccurred( final Set< ThreadInfo > deadlockedThreads )
    {
        LOG.error( getLogStatement( deadlockedThreads ) );
    }


    public String getLogStatement( final Set< ThreadInfo > deadlockedThreads )
    {
        if ( null == deadlockedThreads )
        {
            throw new IllegalArgumentException(
                    "Deadlocked threads cannot be null." );
        }

        final StringBuilder msg = new StringBuilder( 1000 );
        msg.append( Platform.NEWLINE ).append( Platform.NEWLINE )
           .append( "!!!!!!!!!!!!!! DEADLOCK DETECTED !!!!!!!!!!!!!!" )
           .append( Platform.NEWLINE )
           .append( this.getClass().getSimpleName() )
           .append( " received notification that a deadlock has occurred between the following " )
           .append( deadlockedThreads.size() )
           .append( " threads:" ).append( Platform.NEWLINE );

        for ( final ThreadInfo thread : deadlockedThreads )
        {
            msg.append( Platform.NEWLINE )
               .append( "Thread [" ).append( thread.getThreadName() ).append( "]" )
               .append( " (id " ).append( thread.getThreadId() )
               .append( ", state " ).append( thread.getThreadState() ).append( "):" )
               .append( LockTracing.formatStackWithLockHolder( thread, Integer.MAX_VALUE ) )
               .append( Platform.NEWLINE ).append( Platform.NEWLINE )
               .append( Platform.NEWLINE ).append( Platform.NEWLINE );
        }
        msg.append( "!!!!!!!!!!!!!! END OF DEADLOCK DETECTED REPORT !!!!!!!!!!!!!!" )
           .append( Platform.NEWLINE );
        return msg.toString();
    }


    private final static Logger LOG = Logger.getLogger( DeadlockListenerImpl.class );
}
