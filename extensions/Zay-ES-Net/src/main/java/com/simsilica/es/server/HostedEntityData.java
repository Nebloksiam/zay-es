/*
 * $Id$
 *
 * Copyright (c) 2013 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.simsilica.es.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.jme3.network.HostedConnection;
import com.simsilica.es.Entity;
import com.simsilica.es.EntityChange;
import com.simsilica.es.EntityId;
import com.simsilica.es.EntitySet;
import com.simsilica.es.ObservableEntityData;
import com.simsilica.es.net.ComponentChangeMessage;
import com.simsilica.es.net.EntityDataMessage;
import com.simsilica.es.net.EntityDataMessage.ComponentData;
import com.simsilica.es.net.EntityIdsMessage;
import com.simsilica.es.net.FindEntitiesMessage;
import com.simsilica.es.net.FindEntityMessage;
import com.simsilica.es.net.GetComponentsMessage;
import com.simsilica.es.net.GetEntitySetMessage;
import com.simsilica.es.net.ReleaseEntitySetMessage;
import com.simsilica.es.net.ReleaseWatchedEntityMessage;
import com.simsilica.es.net.ResetEntitySetFilterMessage;
import com.simsilica.es.net.ResultComponentsMessage;
import com.simsilica.es.net.StringIdMessage;
import com.simsilica.es.net.WatchEntityMessage;


/**
 *  Provides the per-connection access and book-keeping to
 *  the server's EntityData.  Instances of this class are created
 *  and managed by the EntityDataHostService and users should
 *  not be required to do anything specific with this class.
 *
 *  @author    Paul Speed
 */
public class HostedEntityData {

    public static final String ATTRIBUTE_NAME = "hostedEntityData";
 
    static Logger log = Logger.getLogger(HostedEntityData.class.getName());  
 
    private final EntityHostSettings settings;   
    private final HostedConnection conn;
    
    /**
     *  An EntityData passthrough that holds entity change events so that
     *  we can process them and collect them all at once during sendUpdates().
     */
    private final EntityDataWrapper ed;
    
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private final Map<Integer, EntitySet> activeSets = new ConcurrentHashMap<>();
    private final Map<Integer, EntityInfo> activeEntities = new ConcurrentHashMap<>();
 
    /**
     *  Used to lock against sending updates in specific cases where
     *  the EntitySet updates would cause issues for other code.  So far
     *  there is only one use-case.
     *  This lock will block the whole sendUpdates() method and should
     *  only be issued for really short-lived operations like resetting
     *  a filter.
     */
    private final Lock updateLock = new ReentrantLock();
 
    /**
     *  A data structure to do 'mark and sweep' style interest tracking
     *  for components that the client is currently interested in.
     */
    private final ComponentUsageTracker tracker = new ComponentUsageTracker();
 
    /**
     *  A frame counter that is used during 'mark and sweep' to tell what
     *  is stale and not. 
     */
    private long sendFrameCounter = 0;    
 
    /**
     *  Used during sendUpdates() to track the changes that were
     *  actually applied to our local wrapper.  Reused to avoid
     *  unnecessary GC.
     */
    private final List<EntityChange> frameChanges = new ArrayList<>();
 
    /**
     *  Reused during update sending to capture the latest entity
     *  set updates. 
     */   
    //private final Set<EntityChange> changeBuffer = new HashSet<EntityChange>();

    /**
     *  Set to true any time a filter is reset.  This is a special case
     *  where we potentially have to flush new entity data.
     */
    private final AtomicBoolean filtersReset = new AtomicBoolean();
    
    /**
     *  Reused during update sending to collect full entity changes before
     *  sending them on.
     */
    private final List<ComponentData> entityBuffer = new ArrayList<ComponentData>();

    /**
     *  Reused during update sending to collect batches of component changes
     *  before sending them on. 
     */
    private final List<EntityChange> changeList = new ArrayList<>();
    
    public HostedEntityData( EntityHostSettings settings, HostedConnection conn, ObservableEntityData ed ) {
        this.settings = settings;
        this.ed = new EntityDataWrapper(ed);
        this.conn = conn;
        log.finer("Created HostedEntityData:" + this);    
    }
    
    public void close() {
        if( !closing.compareAndSet(false, true) ) {
            return;
        } 
   
        log.finer("Closing HostedEntityData:" + this);
        
        // Release all of the active sets
        for( EntitySet set : activeSets.values() ) {
            log.finer("Releasing: EntitySet@" + System.identityHashCode(set));        
            set.release();
        }
        activeSets.clear();
        
        // Release all of the active entities
        /*
        Nothing to release anymore as we don't track real ones here
        for( WatchedEntity e : activeEntities.values() ) {
            log.finer("Releasing: WatchedEntity@" + System.identityHashCode(e));        
            e.release();
        }*/
        activeEntities.clear();
        
        // And release our local view (which technically kind of does all of the
        // above anyway)
        ed.close();    
    }    
    
    public void getComponents( HostedConnection source, GetComponentsMessage msg ) {
        if( log.isLoggable(Level.FINER) ) {
            log.finer("getComponents:" + msg);
        }    
        Entity e = ed.getEntity(msg.getEntityId(), msg.getComponentTypes());
        if( log.isLoggable(Level.FINER) ) {
            log.finer("Sending back entity data:" + e);
        }        
        source.send(settings.getChannel(), 
                    new ResultComponentsMessage(msg.getRequestId(), e));
    }
  
    public void findEntities( HostedConnection source, FindEntitiesMessage msg ) {
        if( log.isLoggable(Level.FINER) ) {
            log.finer("findEntities:" + msg);
        }    
        Set<EntityId> result = ed.findEntities(msg.getFilter(), msg.getComponentTypes());
        if( log.isLoggable(Level.FINER) ) {        
            log.finer("Sending back entity ID data:" + result);
        }        
        source.send(settings.getChannel(),
                    new EntityIdsMessage(msg.getRequestId(), result));                    
    }

    public void findEntity( HostedConnection source, FindEntityMessage msg ) {        
        if( log.isLoggable(Level.FINER) ) {
            log.finer("findEntity:" + msg);
        }    
        EntityId result = ed.findEntity(msg.getFilter(), msg.getComponentTypes());
        if( log.isLoggable(Level.FINER) ) {
            log.finer("Sending back entity ID data:" + result);
        }        
        source.send(settings.getChannel(),
                    new EntityIdsMessage(msg.getRequestId(), result));                    
    }
    
    public void watchEntity( HostedConnection source, WatchEntityMessage msg ) {
        
        if( log.isLoggable(Level.FINER) ) {
            log.finer("watchEntity:" + msg);
        } 
        int watchId = msg.getWatchId();
        //WatchedEntity result = activeEntities.get(watchId);
        EntityInfo existing = activeEntities.get(watchId);
        if( existing != null ) {
            throw new RuntimeException("WatchedEntity already exists for watch ID:" + watchId);
        }        
        
        //result = ed.watchEntity(msg.getEntityId(), msg.getComponentTypes());
        
        // Grab a regular entity just to send the data
        Entity result = ed.getEntity(msg.getEntityId(), msg.getComponentTypes());
 
        // We only need the id and types for tracking        
        activeEntities.put(watchId, new EntityInfo(msg.getEntityId(), msg.getComponentTypes()));
        
        // We can reuse the result components message        
        if( log.isLoggable(Level.FINER) ) {
            log.finer("Sending back entity data:" + result);
        }        
        source.send(settings.getChannel(), 
                    new ResultComponentsMessage(msg.getRequestId(), result));
    }
    
    public void releaseEntity( HostedConnection source, ReleaseWatchedEntityMessage msg ) {
        if( log.isLoggable(Level.FINER) ) {
            log.finer("releaseEntity:" + msg);
        }
        int watchId = msg.getWatchId();
        activeEntities.remove(watchId);
        //WatchedEntity e = activeEntities.remove(watchId);
        //e.release();
    }
   
    public void getEntitySet( HostedConnection source, GetEntitySetMessage msg ) {
        if( log.isLoggable(Level.FINER) ) {
            log.finer("getEntitySet:" + msg);
        }    
        // Just send the results back directly       
        // Need to send the entity set ID that the client
        // will recognize and the entity data which is
        // entity ID plus an array of components.
        // This may need to be broken into several messages to fit.
        // Since components are relatively small, it might be ok to just
        // pick an arbitrary maximum that should always fit.  Besides,
        // there is a nice balance when breaking them up with keeping
        // the message pipe moving.

        int setId = msg.getSetId();        
        EntitySet set = activeSets.get(setId);
        
        // We should be the first or there is an error.
        if( set != null ) {
            throw new RuntimeException("Set already exists for ID:" + setId);
        }
        
        if( log.isLoggable(Level.FINER) ) {
            log.finer("Creating set for ID:" + msg.getSetId());
        }
            
        set = ed.getEntities(msg.getFilter(), msg.getComponentTypes());
        
        int batchMax = settings.getMaxEntityBatchSize();
        List<ComponentData> data = new ArrayList<>();
        for( Entity e : set ) {
            data.add(new ComponentData(e));
            if( data.size() > batchMax ) {
                sendAndClear(setId, data);
            }
        }
        
        if( !data.isEmpty() ) {
            sendAndClear(setId, data);
        }
        
        // Put the EntitySet into the active sets after we have
        // iterated over its data.  This prevents one case where
        // an eager sendUpdates() could applyChanges() on top of
        // us while doing a full data flush to the client.  After
        // this, all updates will be sent to the client via the
        // sendUpdates() method and there won't be a thread conflict
        // (except with filter resets which will be dealt with 
        //  using a short-lived lock for that case.)
        activeSets.put(setId, set);
    }
 
    public void resetEntitySetFilter( HostedConnection source, ResetEntitySetFilterMessage msg ) {
        if( log.isLoggable(Level.FINER) )
            log.finer( "resetEntitySetFilter:" + msg );
        
        // Note: we could avoid the lock by queuing a command that applies
        //       the filter in sendUpdates() but we don't really avoid much
        //       threading overhead that way.
        updateLock.lock();
        try {
            EntitySet set = activeSets.get(msg.getSetId());
            set.resetFilter(msg.getFilter());
            filtersReset.set(true);
        } finally {
            updateLock.unlock();
        }        
    } 
 
    public void releaseEntitySet( HostedConnection source, ReleaseEntitySetMessage msg ) {
        if( log.isLoggable(Level.FINER) ) {
            log.finer("releaseEntitySet:" + msg);
        }
        
        // Releasing an entity set is (currently) a safe operation
        // to perform even if the set is in use at the time.  The client
        // already has to deal with the race condition of continuing to
        // get updates for a (from their perspective) released set anyway.        
        EntitySet set = activeSets.remove(msg.getSetId());
        set.release();            
    }
    
    public void getStringInfo( HostedConnection source, StringIdMessage msg ) {
        if( msg.getId() != null ) {
            source.send(new StringIdMessage(msg.getRequestId(), 
                                            ed.getStrings().getString(msg.getId())));   
        } else if( msg.getString() != null ) {
            source.send(new StringIdMessage(msg.getRequestId(), 
                                            ed.getStrings().getStringId(msg.getString(), false)));   
        } else {
            throw new RuntimeException("Bad StringIdMessage:" + msg);
        }
    }

    protected void sendAndClear( int setId, List<ComponentData> buffer ) {
        conn.send(settings.getChannel(), new EntityDataMessage(setId, buffer));
        buffer.clear();
    }
 
    protected void sendAndClear( List<EntityChange> buffer ) {
        conn.send(settings.getChannel(), new ComponentChangeMessage(buffer));
        buffer.clear(); 
    }
 
    /**
     *  Periodically called by the EntityDataHostService to send any relevant changes
     *  to the client.
     */
    public void sendUpdates() {
    
        if( closing.get() ) {
            return;
        }

        int entityMax = settings.getMaxEntityBatchSize(); 
            
        // Basic steps to figuring out what to send the client are as
        // follows:
        // 1) apply changes to our local wrapper view 
        // 2) update the entity sets
        // 3) 'mark' the component tracker with entity set info
        // 3.5) 'mark' the component tracker with watched entity info 
        // 4) go through the applied change events and send them along,
        //    'sweeping' the component tracker as we go.      
        //
        // Threading-wise, the things we really care about are if the active
        // set list changes underneath us... and in this case by 'change' we
        // mean has its filters updated while we are iterating.
        //
        // Thus, I think we only need the lock during the update and 'mark' phase.
        // We can combine those into one loop, even.
 
 
        // Establish the current frame... we do it once to avoid repeated
        // autoboxing but I'd assume the JVM is smart about that.  Maybe.
        Long frame = sendFrameCounter++;

        // Clear the buffers just in case
        frameChanges.clear();
        entityBuffer.clear();
                
        // Step 1: Apply the changes and collect them
        boolean newFilters = filtersReset.getAndSet(false); 
        if( !ed.applyChanges(frameChanges) && !newFilters ) {
            // Hey, no change... we can early out (a nice optimization over the
            // old version)
            return;
        } 

        // One lock per update is better than locking per entity set
        // even if it makes message handling methods wait a little longer.
        // They can afford to wait.
        updateLock.lock();        
        try {
            // Step 2 and 3: update the entity sets and mark usage
            for( Map.Entry<Integer,EntitySet> e : activeSets.entrySet() ) {
                EntitySet set = e.getValue();
 
                // Step 2: apply the changes
                if( set.applyChanges() ) {
                    // For adds, we still need to send the whole entity or
                    // the client won't get it.
                    for( Entity entity : set.getAddedEntities() ) {
                        // Note: we could technically be smarter about this
                        // and send only the components we know that the client
                        // doesn't know about.  We track interest, so we know.
                        entityBuffer.add(new ComponentData(entity));                
                        if( entityBuffer.size() > entityMax ) {
                            sendAndClear(e.getKey(), entityBuffer);
                        }
                    }
                    
                    // Follow up with anything remaining in the buffer 
                    if( !entityBuffer.isEmpty() ) {
                        sendAndClear(e.getKey(), entityBuffer);
                    } 
                }
                set.clearChangeSets();  // we don't need them
 
                // Step 3: 'mark' usage in the tracker
                Class[] types = ed.getTypes(set);
                for( Class type : types ) {
                    tracker.set(set.getEntityIds(), type, frame);
                }
            }            
        } finally {
            updateLock.unlock();
        }
        
        // Step 3.5: Now mark the watched entities
        for( EntityInfo e : activeEntities.values() ) {
            for( Class type : e.types ) {
                tracker.set(e.id, type, frame);
            }            
        }
 
        // Step 4: Sweep and fill outbound change buffers
        int changeMax = settings.getMaxChangeBatchSize(); 
        for( EntityChange change : frameChanges ) {
            
            Long last = tracker.getAndExpire(change.getEntityId(), change.getComponentType(),
                                             frame);
            
            // Three cases:
            // a) last and frame are the same and we need to send the change
            // b) last and frame are different... we need to send the change
            //    but we also need to sweep it (which we just did)
            // c) last is null meaning we don't watch this combo... skip it.
            if( last == null ) {
                // Skip it as we don't track this particular ID + type combo
                continue;
            }
            
            // Buffer the updates            
            changeList.add(change);
            if( changeList.size() > changeMax ) {
                sendAndClear(changeList);
            } 
        }

        // Send any final pending updates
        if( !changeList.isEmpty() ) {             
            sendAndClear(changeList);
        }
        
        // Periodically we should do a more thorough sweep to catch the
        // stuff we aren't watching and also hasn't changed.  Could keep a
        // flag that we set when filters or active sets change       
    }
 
    // We do this a different way now but I'm leaving this here and commented
    // out for temporary reference.  Many years of pain and sweat went into
    // honing it and it's hard to carve it out without at least leaving some of
    // the comments in for one more GIT version.
    /*   
    public void sendUpdatesOld() {
        if( closing.get() ) {
            return;
        }
        
        // Clear the last change buffer and last data buffer just in case
        changeBuffer.clear();
        entityBuffer.clear();
 
        // One lock per update is better than locking per entity set
        // even if it makes message handling methods wait a little longer.
        // They can afford to wait.
        updateLock.lock();
        try {
            // Go through all of the active sets 
            int entityMax = settings.getMaxEntityBatchSize();
            for( Map.Entry<Integer,EntitySet> e : activeSets.entrySet() ) {
            
                EntitySet set = e.getValue();
                if( !set.applyChanges(changeBuffer) ) {
                    // No changes to this set since last time
                    continue;
                }
                            
                // In theory we could just send the raw component changes
                // and let the client sort out the adds, removes, etc. for
                // their entity sets.
                // However, in the case of adds, we potentially make the
                // client do a lot more network comms just to sort it out.
                // For example, if the client only sees one change that
                // causes the entity to get added then it will have to
                // retrieve all of the other components for that entity.
                // When we detect an add, it's in our best interest to go
                // ahead and send the whole thing.
                // Removes are a little different since the client will
                // instantly know to remove it just from the component
                // change.
                
                // So, send adds specifically
                for( Entity entity : set.getAddedEntities() ) {
                    entityBuffer.add( new ComponentData(entity) );                
                    if( entityBuffer.size() > entityMax ) {
                        sendAndClear(e.getKey(), entityBuffer);
                    }
                }
 
                // Note to self: I'm trying to decide if sending the removes
                // gets around some problems or creates new ones.  Right now
                // there are a bunch of component sends that should not be 
                // sent and there are a bunch of retrievals on the client trying
                // to complete entities that will never complete.  Since adds
                // are kind of forced then we could force removes also and lockstep
                // the remote entity sets (removing some of the automatic processing).
                // The fear is that we might create an ordering problem but I think
                // if we make the remote version of entity set 'dumber' then it's 
                // not an issue.  It will then only pay attention to changes that
                // directly affect it (pass all filters) and ignore everything else.
                // Adds and removes would come in explicitly so it doesn't need to
                // detect them.
                
if( !set.getRemovedEntities().isEmpty() ) {
    System.out.println("HostedEntityData.removed entities:" + set.getRemovedEntities());
    System.out.println("  changes so far:" + changeBuffer);
}                
                if( !entityBuffer.isEmpty() ) {
                    sendAndClear(e.getKey(), entityBuffer);
                } 
            }
        } finally {
            updateLock.unlock();
        }
        
        // Collect changes for any active entities
        for( WatchedEntity e : activeEntities.values() ) {
            e.applyChanges(changeBuffer);
        }
        
        if( !changeBuffer.isEmpty() ) {
            // Send the component changes themselves...
            // Note: it's possible some of these are redundant with
            //       the adds above but there is no easy way to
            //       safely detect that.            
            int changeMax = settings.getMaxChangeBatchSize(); 
            for( EntityChange c : changeBuffer ) {
                changeList.add(c);
                if( changeList.size() > changeMax ) {
                    sendAndClear(changeList);
                } 
            }
            
            if( !changeList.isEmpty() ) {             
                sendAndClear(changeList);
            }
        }
    }*/
    
    private static class EntityInfo {
        EntityId id;
        Class[] types;
        
        public EntityInfo( EntityId id, Class[] types ) {
            this.id = id;
            this.types = types;
        }
    }          
}


