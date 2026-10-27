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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.runelite.api.NPC;

/**
 * Works out which pet a thrall is drawn as: the one pet chosen for every thrall, or the
 * one chosen for its type (ghostly, skeletal or zombified), as long as it's unlocked.
 * "Random" picks one of the unlocked pets, and one of its looks, for each thrall summoned,
 * and keeps it for as long as that thrall is out. Must only be used from the client thread.
 */
class PetChooser
{
	private final Random random;
	// The unlocked pets' collection log item ids; read whenever a pet is chosen
	private Set<Integer> unlocked = Collections.emptySet();

	private ThrallPet main = ThrallPet.NONE;
	private boolean perType;
	private ThrallPet ghostly = ThrallPet.NONE;
	private ThrallPet skeletal = ThrallPet.NONE;
	private ThrallPet zombified = ThrallPet.NONE;

	// The random pick for the current thrall, which is told apart by its NPC index and id
	private ThrallPet randomPick;
	private int randomThrallIndex = -1;
	private int randomThrallId = -1;
	// The pet picked last time, so the next random pick can be a different one
	private int lastRandomItem = -1;

	PetChooser(Random random)
	{
		this.random = random;
	}

	void setUnlocked(Set<Integer> unlockedItems)
	{
		unlocked = unlockedItems == null ? Collections.emptySet() : unlockedItems;
	}

	/**
	 * Updates what was chosen in the settings.
	 */
	void setSelection(ThrallPet mainPet, boolean petPerType, ThrallPet ghostlyPet, ThrallPet skeletalPet, ThrallPet zombifiedPet)
	{
		main = orNone(mainPet);
		perType = petPerType;
		ghostly = orNone(ghostlyPet);
		skeletal = orNone(skeletalPet);
		zombified = orNone(zombifiedPet);
	}

	/**
	 * Forgets the random pick, so the current thrall gets a new one.
	 */
	void reroll()
	{
		randomPick = null;
		randomThrallIndex = -1;
		randomThrallId = -1;
	}

	/**
	 * @return the choice from the settings that applies to this kind of thrall: a pet,
	 * {@link ThrallPet#RANDOM} or {@link ThrallPet#NONE}
	 */
	ThrallPet choiceFor(Thralls.Style style)
	{
		if (!perType)
		{
			return main;
		}

		switch (style)
		{
			case MAGIC:
				return ghostly;
			case RANGED:
				return skeletal;
			case MELEE:
			default:
				return zombified;
		}
	}

	/**
	 * @return the choices in use: the one pet, or the pet for each thrall type
	 */
	List<ThrallPet> activeChoices()
	{
		List<ThrallPet> choices = new ArrayList<>();
		if (perType)
		{
			choices.add(ghostly);
			choices.add(skeletal);
			choices.add(zombified);
		}
		else
		{
			choices.add(main);
		}
		return choices;
	}

	/**
	 * @return the pet to draw this thrall as, or {@link ThrallPet#NONE} to leave it normal
	 */
	ThrallPet petFor(NPC thrall)
	{
		ThrallPet choice = choiceFor(Thralls.styleOf(thrall.getId()));
		if (choice == ThrallPet.RANDOM)
		{
			return randomFor(thrall);
		}
		return choice.isPet() && unlocked.contains(choice.getItemId()) ? choice : ThrallPet.NONE;
	}

	/**
	 * @return true if there is at least one unlocked pet for "Random" to pick
	 */
	boolean canPickRandom()
	{
		for (int item : unlocked)
		{
			if (ThrallPet.isPetItem(item))
			{
				return true;
			}
		}
		return false;
	}

	private ThrallPet randomFor(NPC thrall)
	{
		// Keep the pick for as long as this thrall is out (and the pet is still unlocked, e.g. on the same
		// account). With nothing unlocked yet, keep trying, so a pet unlocked meanwhile is used
		if (randomPick != null && randomPick != ThrallPet.NONE && randomThrallIndex == thrall.getIndex()
			&& randomThrallId == thrall.getId() && unlocked.contains(randomPick.getItemId()))
		{
			return randomPick;
		}

		randomPick = pickRandom();
		randomThrallIndex = thrall.getIndex();
		randomThrallId = thrall.getId();
		return randomPick;
	}

	/**
	 * Picks an unlocked pet, each pet as likely as any other however many looks it has,
	 * then one of its looks. Avoids the pet picked last time when there is a choice.
	 */
	private ThrallPet pickRandom()
	{
		List<Integer> pets = new ArrayList<>();
		for (int item : unlocked)
		{
			if (ThrallPet.isPetItem(item))
			{
				pets.add(item);
			}
		}
		if (pets.isEmpty())
		{
			return ThrallPet.NONE;
		}

		// The unlocked set has no order, so sort it to make picks depend only on the random numbers
		Collections.sort(pets);
		if (pets.size() > 1)
		{
			pets.remove(Integer.valueOf(lastRandomItem));
		}

		int item = pets.get(random.nextInt(pets.size()));
		lastRandomItem = item;
		List<ThrallPet> looks = ThrallPet.looksOf(item);
		return looks.get(random.nextInt(looks.size()));
	}

	private static ThrallPet orNone(ThrallPet pet)
	{
		return pet == null ? ThrallPet.NONE : pet;
	}
}
