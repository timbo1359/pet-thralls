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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.NpcID;

/**
 * The thrall NPCs the player can summon, with the "tier type" text used in the
 * "You resurrect a ... thrall." message and the combat style each type attacks with.
 */
final class Thralls
{
	enum Style
	{
		MELEE,
		RANGED,
		MAGIC
	}

	static final Map<Integer, String> TYPES = ImmutableMap.<Integer, String>builder()
		.put(NpcID.ARCEUUS_THRALL_GHOST_LESSER, "lesser ghost")
		.put(NpcID.ARCEUUS_THRALL_SKELETON_LESSER, "lesser skeleton")
		.put(NpcID.ARCEUUS_THRALL_ZOMBIE_LESSER, "lesser zombie")
		.put(NpcID.ARCEUUS_THRALL_GHOST_SUPERIOR, "superior ghost")
		.put(NpcID.ARCEUUS_THRALL_SKELETON_SUPERIOR, "superior skeleton")
		.put(NpcID.ARCEUUS_THRALL_ZOMBIE_SUPERIOR, "superior zombie")
		.put(NpcID.ARCEUUS_THRALL_GHOST_GREATER, "greater ghost")
		.put(NpcID.ARCEUUS_THRALL_SKELETON_GREATER, "greater skeleton")
		.put(NpcID.ARCEUUS_THRALL_ZOMBIE_GREATER, "greater zombie")
		// Leagues cosmetic overrides: magic = ghost, ranged = skeleton, melee = zombie
		.put(NpcID.THRALL_IMP_MAGIC_LESSER, "lesser ghost")
		.put(NpcID.THRALL_IMP_RANGED_LESSER, "lesser skeleton")
		.put(NpcID.THRALL_IMP_MELEE_LESSER, "lesser zombie")
		.put(NpcID.THRALL_IMP_MAGIC_SUPERIOR, "superior ghost")
		.put(NpcID.THRALL_IMP_RANGED_SUPERIOR, "superior skeleton")
		.put(NpcID.THRALL_IMP_MELEE_SUPERIOR, "superior zombie")
		.put(NpcID.THRALL_IMP_MAGIC_GREATER, "greater ghost")
		.put(NpcID.THRALL_IMP_RANGED_GREATER, "greater skeleton")
		.put(NpcID.THRALL_IMP_MELEE_GREATER, "greater zombie")
		.build();

	/**
	 * The animation the player plays when casting any Resurrect Thrall spell.
	 */
	static final int CAST_ANIMATION = AnimationID.HUMAN_SPELLCAST_RESURRECT;

	/**
	 * The animations a thrall plays when it attacks.
	 */
	static final Set<Integer> ATTACK_ANIMATIONS = ImmutableSet.of(
		AnimationID.GHOST_UPDATE_TENDRILL_ATTACK_THRALL,
		AnimationID.SKELETON_UPDATE_CHAMPION_ATTACK_THRALL,
		AnimationID.ZOMBIE_UPDATE_ATTACK_NORMAL_THRALL,
		AnimationID.LEAGUES_6_THRALL_IMP_MAGIC_ATTACK,
		AnimationID.LEAGUES_6_THRALL_IMP_MAGIC_ATTACK_02,
		AnimationID.LEAGUES_6_THRALL_IMP_RANGED_ATTACK,
		AnimationID.LEAGUES_6_THRALL_IMP_MELEE_ATTACK,
		AnimationID.LEAGUES_6_THRALL_IMP_MELEE_ATTACK_SILENT
	);

	private Thralls()
	{
	}

	static boolean isThrall(int npcId)
	{
		return TYPES.containsKey(npcId);
	}

	/**
	 * The Jagex HSL colours of the thralls' own outlines (the single-coloured shell
	 * around each thrall's model in the game cache): purple for ghosts, green for
	 * skeletons and red for zombies.
	 */
	static short outlineColor(Style style)
	{
		switch (style)
		{
			case MAGIC:
				return (short) 49630;
			case RANGED:
				return (short) 13272;
			case MELEE:
			default:
				return (short) 984;
		}
	}

	/**
	 * @return how a thrall attacks: ghosts use magic, skeletons ranged and zombies melee
	 */
	static Style styleOf(int npcId)
	{
		String type = TYPES.get(npcId);
		if (type == null || type.endsWith("zombie"))
		{
			return Style.MELEE;
		}
		return type.endsWith("ghost") ? Style.MAGIC : Style.RANGED;
	}
}
