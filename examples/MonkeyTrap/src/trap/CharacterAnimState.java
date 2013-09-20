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

package trap;

import com.jme3.animation.AnimChannel;
import com.jme3.animation.AnimControl;
import com.jme3.app.Application;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.simsilica.es.Entity;
import com.simsilica.es.EntityData;
import com.simsilica.es.EntitySet;
import com.simsilica.lemur.event.BaseAppState;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 *  Applies animation to an entity's model based on
 *  game state.  Requires that a ModelState be present
 *  and active.
 *
 *  The presumption is that all character models will be
 *  animated in a similar way based on game state.
 *
 *  @author    Paul Speed
 */
public class CharacterAnimState extends BaseAppState {

    static Logger log = LoggerFactory.getLogger( CharacterAnimState.class );

    private EntityData ed;
    private EntitySet entities;
    private ModelState modelState;

    //private Set<Spatial> walking = new HashSet<Spatial>();
    private Map<Spatial, Entity> walking = new HashMap<Spatial, Entity>();

    public CharacterAnimState() {
    }

    protected void addModels( Set<Entity> set ) {

        for( Entity e : set ) {
            Spatial s = modelState.getSpatial(e.getId());
            if( s == null ) {
                log.warn("No model found for added entity:" + e);
                continue;
            }

            updateModelSpatial(e, s);
        }
    }

    protected void removeModels( Set<Entity> set ) {
    }

    protected void updateModelSpatial( Entity e, Spatial s ) {
        // Cheat for a second
        InterpolationControl ic = s.getControl(InterpolationControl.class);
        if( ic == null )
            return;
 
        ModelType type = e.get(ModelType.class);
        if( TrapModelFactory.TYPE_MONKEY.equals(type) ) {       
            AnimControl anim = s.getControl(AnimControl.class);
            AnimChannel channel = anim.getChannel(0);
            if( ic.getStep() < 1.0 ) {
                // Walk
                if( !"Walk".equals(channel.getAnimationName()) ) {
                    channel.setAnim("Walk");
                    channel.setSpeed(1.55f * (float)MonkeyTrapConstants.MONKEY_SPEED);
                    //channel.setSpeed(1.25f * (float)MonkeyTrapConstants.MONKEY_SPEED);
                    walking.put(s, e);
                }                
                /*if( !"Run".equals(channel.getAnimationName()) ) {
                    channel.setAnim("Run");
                    channel.setSpeed(0.75f * (float)MonkeyTrapConstants.MONKEY_SPEED);
                    walking.add(s);
                }*/                
            } else {
                // Stand
                if( !"Idle".equals(channel.getAnimationName()) ) {
                    channel.setAnim("Idle");
                    channel.setSpeed(1);
                    walking.remove(s);
                }                
            }
        } else if( TrapModelFactory.TYPE_OGRE.equals(type) ) {
            AnimControl anim = ((Node)s).getChild(0).getControl(AnimControl.class);
            AnimChannel channel1 = anim.getChannel(0);
            AnimChannel channel2 = anim.getChannel(1);
            if( ic.getStep() < 1.0 ) {
                // Walk
                if( !"RunBase".equals(channel1.getAnimationName()) ) {
                    channel1.setAnim("RunBase");
                    channel1.setSpeed(0.2f * (float)MonkeyTrapConstants.OGRE_SPEED);
                    channel2.setAnim("RunTop");
                    channel2.setSpeed(0.2f * (float)MonkeyTrapConstants.OGRE_SPEED);
                    walking.put(s, e);
                }                
                /*if( !"Run".equals(channel.getAnimationName()) ) {
                    channel.setAnim("Run");
                    channel.setSpeed(0.75f * (float)MonkeyTrapConstants.MONKEY_SPEED);
                    walking.add(s);
                }*/                
            } else {
                // Stand
                if( !"IdleBase".equals(channel1.getAnimationName()) ) {
                    channel1.setAnim("IdleBase");
                    channel1.setSpeed(1);
                    channel2.setAnim("IdleTop");
                    channel2.setSpeed(1);
                    walking.remove(s);
                }                
            }
        }             
    }

    protected void updateModels( Set<Entity> set ) {

        for( Entity e : set ) {
            Spatial s = modelState.getSpatial(e.getId());
            if( s == null ) {
                log.error("Model not found for updated entity:" + e);
                continue;
            }

            updateModelSpatial(e, s);
        }
    }

    @Override
    protected void initialize( Application app ) {

        modelState = getState(ModelState.class);

        // Grab the set of entities we are interested in
        ed = getState(EntityDataState.class).getEntityData();
        entities = ed.getEntities(Position.class, ModelType.class);
    }

    @Override
    protected void cleanup( Application app ) {

        // Release the entity set we grabbed previously
        entities.release();
        entities = null;
    }

    @Override
    protected void enable() {
        entities.applyChanges();
        addModels(entities);
    }

    @Override
    public void update( float tpf ) {
        if( entities.applyChanges() ) {
            removeModels(entities.getRemovedEntities());
            addModels(entities.getAddedEntities());
            updateModels(entities.getChangedEntities());
        }
        
        // Temporary
        if( !walking.isEmpty() ) {
            for( Map.Entry<Spatial,Entity> e : new HashMap<Spatial,Entity>(walking).entrySet() ) { 
                updateModelSpatial(e.getValue(), e.getKey());
            }
        }
    }

    @Override
    protected void disable() {
        removeModels(entities);
    }

}
