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
import com.jme3.bounding.BoundingBox;
import com.jme3.material.Material;
import com.jme3.renderer.queue.RenderQueue.ShadowMode;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import com.simsilica.es.Entity;


/**
 *
 *  @author    Paul Speed
 */
public class TrapModelFactory implements ModelFactory
{
    public static final ModelType TYPE_MONKEY = new ModelType("Monkey");
    public static final ModelType TYPE_OGRE = new ModelType("Ogre");

    private ModelState state;

    public void setState(ModelState state) {
        this.state = state;
    }

    public Spatial createModel(Entity e) {

        ModelType type = e.get(ModelType.class);
        
        if( TYPE_MONKEY.equals(type) ) {
            Spatial monkey = state.getApplication().getAssetManager().loadModel( "Models/Jaime/Jaime.j3o" );
            
            AnimControl anim = monkey.getControl(AnimControl.class);
            AnimChannel channel = anim.createChannel();
            channel.setAnim("Idle");
 
            // The monkey's shadow box is strangley off center so we
            // probably need to do something similar as what was done for Sinbad
            monkey.setShadowMode( ShadowMode.Cast );
            monkey.addControl(new InterpolationControl(MonkeyTrapConstants.MONKEY_SPEED));
            return monkey;
        } else if( TYPE_OGRE.equals(type) ) {
            Spatial ogre = state.getApplication().getAssetManager().loadModel( "Models/Sinbad/Sinbad.mesh.j3o" );
            
            // Normalize the ogre to be 1.8 meters tall
            BoundingBox bounds = (BoundingBox)ogre.getWorldBound();                 
            ogre.setLocalScale( 1.8f / (bounds.getYExtent() * 2) );
            bounds = (BoundingBox)ogre.getWorldBound();
            ogre.setLocalTranslation(0, bounds.getYExtent() - bounds.getCenter().y, 0);
 
            AnimControl anim = ogre.getControl(AnimControl.class);
            AnimChannel channel = anim.createChannel();
            channel.setAnim("IdleTop");
            channel = anim.createChannel();
            channel.setAnim("IdleBase");
            
            // Wrap it in a node to keep its local translation adjustment
            Node wrapper = new Node("Ogre");
            wrapper.attachChild(ogre);
 
            // Because Sinbad is made up of lots of objects and the 
            // zExtent is fairly thin, his shadow looks strange so we
            // will tweak it.
            Box box = new Box(bounds.getXExtent(), bounds.getYExtent(), bounds.getZExtent() * 1.5f);
            Geometry shadowBox = new Geometry("shadowBounds", box);
            shadowBox.move(0,bounds.getYExtent(),0);
            
            Material m = new Material(state.getApplication().getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
            m.setFloat("AlphaDiscardThreshold", 1.1f);
            shadowBox.setMaterial(m);
            shadowBox.setShadowMode( ShadowMode.Cast );
            wrapper.attachChild(shadowBox);
            
            //wrapper.setShadowMode( ShadowMode.Cast );
            wrapper.addControl(new InterpolationControl(MonkeyTrapConstants.OGRE_SPEED));            
            return wrapper;         
        } 
        
        return null;
    }
}
