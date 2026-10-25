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

import java.util.Objects;
import net.runelite.client.party.messages.PartyMemberMessage;

/**
 * Sent to the RuneLite party: the pet a player's thrall is drawn as, and which NPC their
 * thrall is. Each player's own plugin knows which thrall is theirs, and NPC indexes are
 * the same for everyone on a world, so party members can find the thrall without guessing.
 */
public class PetThrallsPartyUpdate extends PartyMemberMessage
{
	// The ThrallPet's name, or null for none
	private String pet;
	// The world the thrall is on, 0 if none
	private int world;
	// The thrall's NPC index and NPC id, -1 if no thrall is out
	private int thrallIndex = -1;
	private int thrallId = -1;

	public PetThrallsPartyUpdate()
	{
	}

	PetThrallsPartyUpdate(ThrallPet pet, int world, int thrallIndex, int thrallId)
	{
		this.pet = pet == null || pet == ThrallPet.NONE ? null : pet.name();
		this.world = world;
		this.thrallIndex = thrallIndex;
		this.thrallId = thrallId;
	}

	/**
	 * @return the pet, or {@link ThrallPet#NONE} if none or not one this version knows
	 */
	ThrallPet getPet()
	{
		return ThrallPet.fromName(pet);
	}

	int getWorld()
	{
		return world;
	}

	int getThrallIndex()
	{
		return thrallIndex;
	}

	int getThrallId()
	{
		return thrallId;
	}

	/**
	 * @return true if both describe the same pet and thrall, ignoring who sent them
	 */
	boolean sameAs(PetThrallsPartyUpdate other)
	{
		return other != null
			&& Objects.equals(pet, other.pet)
			&& world == other.world
			&& thrallIndex == other.thrallIndex
			&& thrallId == other.thrallId;
	}

	@Override
	public String toString()
	{
		return "PetThrallsPartyUpdate(member=" + getMemberId() + ", pet=" + pet + ", world=" + world
			+ ", thrallIndex=" + thrallIndex + ", thrallId=" + thrallId + ")";
	}
}
