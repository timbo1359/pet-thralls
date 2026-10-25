/*
 * Copyright (c) 2026, Ox Timmy
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.petthralls;

import net.runelite.api.Animation;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.RuneLiteObjectController;
import net.runelite.api.coords.LocalPoint;

/**
 * A model drawn in the world that, like an NPC, plays a looping pose animation
 * (idle or walk) with a one-off action animation (an attack) layered on top.
 * <p>
 * Like the game does for NPCs, animations are applied to the full size model and
 * the result is then scaled; animating an already scaled model would apply the
 * animation's full size movements to it and tear it apart.
 * Must only be used from the client thread.
 */
class PetObject extends RuneLiteObjectController
{
	private static final int DEFAULT_SCALE = 128;

	private final Client client;
	// Full size: what animations are applied to
	private final Model model;
	// Scaled: shown when nothing is animating
	private final Model scaledModel;
	private final int widthScale;
	private final int heightScale;

	private AnimationController pose;
	private AnimationController action;

	/**
	 * @param model       the unscaled model
	 * @param scaledModel the same model already scaled, for when nothing is animating
	 */
	PetObject(Client client, Model model, Model scaledModel, int widthScale, int heightScale)
	{
		this.client = client;
		this.model = model;
		this.scaledModel = scaledModel;
		this.widthScale = widthScale;
		this.heightScale = heightScale;
	}

	/**
	 * Sets the looping pose animation, or clears it with {@code null}.
	 */
	void setPose(Animation animation)
	{
		pose = animation == null ? null : new AnimationController(client, animation);
	}

	/**
	 * Plays an animation once over the pose, restarting it if it is already playing.
	 */
	void playAction(Animation animation)
	{
		if (animation == null)
		{
			return;
		}

		AnimationController controller = new AnimationController(client, animation);
		controller.setOnFinished(finished ->
		{
			if (action == finished)
			{
				action = null;
			}
		});
		action = controller;
	}

	void moveTo(LocalPoint location, int level)
	{
		// Objects belong to a world view, so move it across if the thrall changed world view
		boolean reregister = isActive() && location.getWorldView() != getWorldView();
		if (reregister)
		{
			setActive(false);
		}

		setLocation(location, level);
		setZ(Perspective.getTileHeight(client, location, level));

		if (reregister)
		{
			setActive(true);
		}
	}

	boolean isActive()
	{
		return client.isRuneLiteObjectRegistered(this);
	}

	void setActive(boolean active)
	{
		if (active)
		{
			client.registerRuneLiteObject(this);
		}
		else
		{
			client.removeRuneLiteObject(this);
		}
	}

	@Override
	public void tick(int ticksSinceLastFrame)
	{
		if (pose != null)
		{
			pose.tick(ticksSinceLastFrame);
		}
		if (action != null)
		{
			action.tick(ticksSinceLastFrame);
		}
	}

	@Override
	public Model getModel()
	{
		Model animated = null;
		if (action != null && action.getAnimation() != null)
		{
			animated = action.animate(model, pose != null && pose.getAnimation() != null ? pose : null);
		}
		else if (pose != null && pose.getAnimation() != null)
		{
			animated = pose.animate(model);
		}

		// The animated model has its own copy of the vertices, so it can be scaled without
		// touching the stored model; if the client handed back the stored model itself, don't
		if (animated == null || animated == model)
		{
			return scaledModel;
		}

		if (widthScale != DEFAULT_SCALE || heightScale != DEFAULT_SCALE)
		{
			animated.scale(widthScale, heightScale, widthScale);
			animated.calculateBoundsCylinder();
		}
		return animated;
	}
}
