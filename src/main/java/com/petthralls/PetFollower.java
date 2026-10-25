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

import java.util.EnumMap;
import java.util.Map;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Draws a pet's model on top of a thrall, copying the thrall's position and
 * facing every frame, switching between the pet's idle and walk animations and
 * playing the pet's attack when the thrall attacks. Optionally gives the pet the
 * thrall's type markings: its ground symbol and coloured outline.
 * Must only be used from the client thread.
 */
class PetFollower
{
	private static final Logger log = LoggerFactory.getLogger(PetFollower.class);

	private static final int DEFAULT_SCALE = 128;
	// How NPC models are lit by the game client
	private static final int NPC_AMBIENT_BASE = 64;
	private static final int NPC_CONTRAST_BASE = 850;
	private static final int NPC_CONTRAST_MULTIPLIER = 5;
	private static final int NPC_LIGHT_X = -30;
	private static final int NPC_LIGHT_Y = -50;
	private static final int NPC_LIGHT_Z = -30;
	// Give up building a model after this many frames of it not being available
	private static final int MAX_BUILD_ATTEMPTS = 100;
	// Outline thickness on the pet as drawn, in model units (the thralls' own is about 1.4)
	private static final float OUTLINE_THICKNESS = 1.5f;

	private final Client client;

	private ThrallPet pet = ThrallPet.NONE;
	private PetObject object;
	private Animation idleAnimation;
	private Animation walkAnimation;
	private final Map<Thralls.Style, Animation> attackAnimations = new EnumMap<>(Thralls.Style.class);
	private boolean walking;
	private int buildAttempts;

	private boolean showSymbol = true;
	private boolean showOutline = true;
	// What the current pet object was built with
	private boolean builtWithOutline;
	private Thralls.Style builtStyle;

	// The thrall's ground symbol, cut from its own model. Drawn still: it doesn't move in the
	// game, and playing the thrall's animation on it can hide it
	private PetObject symbol;
	private int symbolThrallId = -1;
	private int symbolAttempts;

	PetFollower(Client client)
	{
		this.client = client;
	}

	ThrallPet getPet()
	{
		return pet;
	}

	/**
	 * Changes which pet is drawn. Takes effect on the next {@link #update(NPC)}.
	 */
	void setPet(ThrallPet newPet)
	{
		if (newPet == pet)
		{
			return;
		}

		remove();
		pet = newPet;
		idleAnimation = null;
		walkAnimation = null;
		attackAnimations.clear();
		buildAttempts = 0;
	}

	/**
	 * Turns the thrall markings on the pet on or off. Takes effect on the next {@link #update(NPC)}.
	 */
	void setMarkings(boolean symbolOn, boolean outlineOn)
	{
		showSymbol = symbolOn;
		if (outlineOn != showOutline)
		{
			showOutline = outlineOn;
			remove();
			buildAttempts = 0;
		}
	}

	/**
	 * @return true if the pet is currently drawn in the world
	 */
	boolean isShowing()
	{
		return object != null && object.isActive();
	}

	/**
	 * Moves the pet onto the thrall, building and showing it if needed.
	 *
	 * @return true if the pet is now drawn in place of the thrall
	 */
	boolean update(NPC thrall)
	{
		Thralls.Style style = Thralls.styleOf(thrall.getId());
		if (object != null && builtWithOutline && builtStyle != style)
		{
			// Recast as another type: the outline colour changes
			remove();
			buildAttempts = 0;
		}

		if (pet == ThrallPet.NONE || !ensureObject(style))
		{
			hide();
			return false;
		}

		LocalPoint location = thrall.getLocalLocation();
		WorldView worldView = thrall.getWorldView();
		if (location == null || worldView == null)
		{
			hide();
			return false;
		}

		int plane = worldView.getPlane();
		int orientation = thrall.getCurrentOrientation();
		object.moveTo(location, plane);
		object.setOrientation(orientation);
		updateSymbol(thrall, location, plane, orientation);

		boolean moving = thrall.getPoseAnimation() != thrall.getIdlePoseAnimation();
		if (moving != walking)
		{
			walking = moving;
			object.setPose(moving ? walkAnimation : idleAnimation);
		}

		if (!object.isActive())
		{
			object.setActive(true);
		}
		return true;
	}

	/**
	 * Plays the pet's attack matching the thrall's combat style, if the pet has one.
	 */
	void playAttack(Thralls.Style style)
	{
		if (!isShowing())
		{
			return;
		}

		Animation attack = attackAnimations.get(style);
		if (attack == null)
		{
			int id = pet.getAttackAnimation(style);
			if (id == -1)
			{
				return;
			}
			attack = client.loadAnimation(id);
			if (attack == null)
			{
				return;
			}
			attackAnimations.put(style, attack);
		}
		object.playAction(attack);
	}

	/**
	 * Stops drawing the pet, keeping the built model for when it is shown again.
	 */
	void hide()
	{
		if (object != null && object.isActive())
		{
			object.setActive(false);
		}
		hideSymbol();
	}

	/**
	 * Stops drawing the pet and drops the drawn object.
	 */
	void remove()
	{
		hide();
		object = null;
		symbol = null;
		symbolThrallId = -1;
	}

	private void hideSymbol()
	{
		if (symbol != null && symbol.isActive())
		{
			symbol.setActive(false);
		}
	}

	/**
	 * Draws the thrall's ground symbol under the pet, if turned on.
	 */
	private void updateSymbol(NPC thrall, LocalPoint location, int plane, int orientation)
	{
		int thrallId = thrall.getId();
		boolean firstShow = symbol == null || !symbol.isActive();
		if (!showSymbol)
		{
			hideSymbol();
			return;
		}

		if (thrallId != symbolThrallId)
		{
			// Each thrall type has its own symbol
			hideSymbol();
			symbol = null;
			symbolThrallId = thrallId;
			symbolAttempts = 0;
		}

		if (symbol == null)
		{
			if (symbolAttempts >= MAX_BUILD_ATTEMPTS)
			{
				return;
			}
			symbolAttempts++;

			ModelData data;
			try
			{
				data = ThrallMarkings.groundSymbol(client, thrallId);
			}
			catch (RuntimeException e)
			{
				// Never let the symbol stop the pet itself from showing
				log.debug("Couldn't build the ground symbol of thrall {}", thrallId, e);
				symbolAttempts = MAX_BUILD_ATTEMPTS;
				return;
			}
			if (data == null)
			{
				return;
			}
			Model model = data.light(NPC_AMBIENT_BASE, NPC_CONTRAST_BASE, NPC_LIGHT_X, NPC_LIGHT_Y, NPC_LIGHT_Z);
			symbol = new PetObject(client, model, model, DEFAULT_SCALE, DEFAULT_SCALE);
			log.debug("Built the ground symbol of thrall {}", thrallId);
		}

		symbol.moveTo(location, plane);
		symbol.setOrientation(orientation);
		if (!symbol.isActive())
		{
			symbol.setActive(true);
		}
		if (firstShow)
		{
			log.debug("Showing the ground symbol of thrall {} at {},{} height {} (active: {})",
				thrallId, symbol.getX(), symbol.getY(), symbol.getZ(), symbol.isActive());
		}
	}

	private boolean ensureObject(Thralls.Style style)
	{
		if (object != null)
		{
			return true;
		}

		if (buildAttempts >= MAX_BUILD_ATTEMPTS)
		{
			return false;
		}

		buildAttempts++;
		NPCComposition composition = client.getNpcDefinition(pet.getNpcId());
		ModelData data = composition == null ? null : buildModelData(composition);
		if (data == null)
		{
			if (buildAttempts >= MAX_BUILD_ATTEMPTS)
			{
				log.debug("Unable to build a model for {}", pet.name());
			}
			return false;
		}

		idleAnimation = loadAnimation(pet.getIdleAnimation());
		walkAnimation = loadAnimation(pet.getWalkAnimation());
		if (walkAnimation == null)
		{
			walkAnimation = idleAnimation;
		}

		int widthScale = composition.getWidthScale();
		int heightScale = composition.getHeightScale();

		builtWithOutline = false;
		builtStyle = style;
		if (showOutline)
		{
			// The pet is scaled after animating, so make the outline thicker by as much as it shrinks
			float scale = Math.max(1, (widthScale + heightScale) / 2f) / DEFAULT_SCALE;
			try
			{
				ModelData outlined = ThrallMarkings.withOutline(client, data, Thralls.outlineColor(style), OUTLINE_THICKNESS / scale);
				if (outlined != null)
				{
					data = outlined;
					builtWithOutline = true;
				}
			}
			catch (RuntimeException e)
			{
				// Never let the outline stop the pet itself from showing
				log.debug("Couldn't build the outline for {}", pet.name(), e);
			}
		}

		Model model = light(data);
		Model scaledModel = model;
		if (widthScale != DEFAULT_SCALE || heightScale != DEFAULT_SCALE)
		{
			// Own copy of the vertices, so scaling leaves the full size model alone
			scaledModel = light(data.cloneVertices().scale(widthScale, heightScale, widthScale));
		}

		object = new PetObject(client, model, scaledModel, widthScale, heightScale);
		walking = false;
		object.setPose(idleAnimation);
		log.debug("Built {} (idle animation {}, walk animation {}, {} outline)", pet.name(),
			idleAnimation == null ? "missing" : idleAnimation.getId(),
			walkAnimation == null ? "missing" : walkAnimation.getId(),
			builtWithOutline ? style : "no");
		return true;
	}

	/**
	 * Builds the pet's unlit, unscaled model with the NPC's recolours applied.
	 *
	 * @return the model, or null if it isn't loaded yet
	 */
	private ModelData buildModelData(NPCComposition composition)
	{
		if (composition.getModels() == null || composition.getModels().length == 0)
		{
			return null;
		}

		int[] modelIds = composition.getModels();
		ModelData[] parts = new ModelData[modelIds.length];
		for (int i = 0; i < modelIds.length; i++)
		{
			parts[i] = client.loadModelData(modelIds[i]);
			if (parts[i] == null)
			{
				// Not loaded yet, try again next frame
				return null;
			}
		}

		// Copy before recolouring so the cached models are left untouched
		ModelData data = client.mergeModels(parts, parts.length)
			.cloneVertices()
			.cloneColors();

		short[] find = composition.getColorToReplace();
		short[] replace = composition.getColorToReplaceWith();
		if (find != null && replace != null)
		{
			for (int i = 0; i < Math.min(find.length, replace.length); i++)
			{
				data.recolor(find[i], replace[i]);
			}
		}

		return data;
	}

	private Model light(ModelData data)
	{
		return data.light(
			NPC_AMBIENT_BASE + pet.getAmbient(),
			NPC_CONTRAST_BASE + pet.getContrast() * NPC_CONTRAST_MULTIPLIER,
			NPC_LIGHT_X, NPC_LIGHT_Y, NPC_LIGHT_Z);
	}

	private Animation loadAnimation(int id)
	{
		return id == -1 ? null : client.loadAnimation(id);
	}
}
