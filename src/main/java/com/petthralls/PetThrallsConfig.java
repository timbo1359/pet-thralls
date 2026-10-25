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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(PetThrallsConfig.GROUP)
public interface PetThrallsConfig extends Config
{
	String GROUP = "petthralls";

	@ConfigItem(
		keyName = "pet",
		name = "Pet",
		description = "The pet your own thrall is drawn as. Green pets are unlocked in your collection log and red ones"
			+ " aren't; each pet's other looks are listed under it and can be used once the pet is unlocked."
			+ " Open Collection log > Other > All Pets once so the plugin can see your pets.",
		position = 0
	)
	default ThrallPet pet()
	{
		return ThrallPet.NONE;
	}

	@ConfigItem(
		keyName = "showSymbol",
		name = "Thrall symbol",
		description = "Show the thrall's coloured symbol on the ground under the pet",
		position = 1
	)
	default boolean showSymbol()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showOutline",
		name = "Thrall outline",
		description = "Outline the pet in the thrall's colour: purple for ghostly, green for skeletal, red for zombified",
		position = 2
	)
	default boolean showOutline()
	{
		return true;
	}

	@ConfigItem(
		keyName = "partyPets",
		name = "Party pets",
		description = "In a RuneLite party, share your pet with party members who also use Pet Thralls,"
			+ " and see their thralls as their pets. Party members without the plugin, or with this off,"
			+ " see a normal thrall.",
		position = 3
	)
	default boolean partyPets()
	{
		return true;
	}
}
