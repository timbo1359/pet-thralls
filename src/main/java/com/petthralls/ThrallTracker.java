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

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Works out which thrall in the scene is the local player's.
 * <p>
 * Thralls have no owner the client can see, so several signals are combined:
 * <ol>
 * <li>A thrall appearing beside us on the tick we play the resurrect cast animation, or a
 * thrall of the type named in "You resurrect a greater zombified thrall." (which arrives
 * about 5 ticks after the thrall appears, once it has risen) that appeared around then.</li>
 * <li>While our thrall is out but unclaimed, a thrall of that type facing/following us.</li>
 * <li>Failing that, a thrall of that type appearing right beside us, e.g. coming back
 * into view after a scene reload.</li>
 * </ol>
 * The thrall is released when the game says it returns to the grave, when the
 * resurrection varbit turns off, or when it leaves the scene (it is picked up again
 * if it comes back). Must only be used from the client thread.
 */
class ThrallTracker
{
	private static final Logger log = LoggerFactory.getLogger(ThrallTracker.class);

	private static final String TIER = "(lesser|superior|greater)";
	private static final String KIND = "(ghostly|skeletal|zombified|ghost|skeleton|zombie)";
	static final Pattern RESURRECT_PATTERN = Pattern.compile("You resurrect an? " + TIER + " " + KIND + " thrall");
	static final Pattern RETURNS_PATTERN = Pattern.compile("Your " + TIER + " " + KIND + " thrall returns to the grave");

	// How many ticks after a cast signal it is still used to find the new thrall
	private static final int CAST_WINDOW_TICKS = 6;
	// The thrall appears this many ticks before the "You resurrect" message (it rises first)
	private static final int MESSAGE_AFTER_SPAWN_TICKS = 5;
	// How far before the expected tick the thrall's appearance can be
	private static final int SPAWN_TOLERANCE_TICKS = 3;
	// How far after the latest cast signal the thrall's appearance can be
	private static final int LATE_SPAWN_TICKS = 2;
	// How far (tiles) from us a freshly cast or following thrall can be
	private static final int CAST_MAX_DISTANCE = 6;
	// How far (tiles) a thrall can be when we take it only for being right beside us
	private static final int NEARBY_MAX_DISTANCE = 2;

	private final Client client;

	private static final class Seen
	{
		final int tick;
		final int distance;

		Seen(int tick, int distance)
		{
			this.tick = tick;
			this.distance = distance;
		}
	}

	// Every thrall in the scene, with when and how far from us it appeared
	private final Map<NPC, Seen> sceneThralls = new HashMap<>();

	private NPC ownedThrall;
	// Whether the local player has a thrall out at all
	private boolean thrallOut;
	// Tick of the latest cast signal (animation, message or varbit), -1 if none is pending
	private int castTick = -1;
	// Tick our new thrall is expected to have appeared on
	private int expectedSpawnTick = -1;
	// "greater zombie" etc, from the last cast message, or null if unknown
	private String castType;
	// Thralls appearing from this tick on may be ours (used by the "beside us" rule)
	private int searchSinceTick = -1;

	ThrallTracker(Client client)
	{
		this.client = client;
	}

	NPC getOwnedThrall()
	{
		return ownedThrall;
	}

	/**
	 * Picks up thralls already in the scene, e.g. when the plugin is turned on mid-thrall.
	 */
	void start()
	{
		int tick = client.getTickCount();
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (npc != null && Thralls.isThrall(npc.getId()))
			{
				sceneThralls.put(npc, new Seen(tick, distanceToLocalPlayer(npc)));
			}
		}

		if (client.getVarbitValue(VarbitID.ARCEUUS_RESURRECTION_ACTIVE) == 1)
		{
			thrallOut = true;
			searchSinceTick = tick;
			update(true);
		}
	}

	void reset()
	{
		sceneThralls.clear();
		release();
	}

	/**
	 * The local player started the resurrect thrall cast animation; the thrall appears this tick.
	 */
	void onCastAnimation()
	{
		int tick = client.getTickCount();
		if (castTick == tick)
		{
			return;
		}

		castTick = tick;
		expectedSpawnTick = tick;
		// The type is only known once the cast message arrives
		castType = null;
		log.debug("Cast animation on tick {}", tick);
		update(false);
	}

	/**
	 * @param message a game message with its formatting tags removed
	 */
	void onGameMessage(String message)
	{
		int tick = client.getTickCount();

		Matcher cast = RESURRECT_PATTERN.matcher(message);
		if (cast.find())
		{
			if (castTick == -1 || tick - castTick > MESSAGE_AFTER_SPAWN_TICKS + SPAWN_TOLERANCE_TICKS)
			{
				// No cast animation seen: the thrall appeared a few ticks ago
				expectedSpawnTick = tick - MESSAGE_AFTER_SPAWN_TICKS;
			}
			castTick = tick;
			castType = typeOf(cast.group(1), cast.group(2));
			if (!thrallOut || ownedThrall == null)
			{
				searchSinceTick = expectedSpawnTick - SPAWN_TOLERANCE_TICKS;
			}
			thrallOut = true;
			log.debug("Cast message for a {} thrall on tick {}, expecting it to have appeared on tick {}",
				castType, tick, expectedSpawnTick);
			update(false);
			return;
		}

		if (RETURNS_PATTERN.matcher(message).find())
		{
			log.debug("Our thrall returned to the grave on tick {}", tick);
			thrallEnded();
		}
	}

	void onResurrectionVarbit(int value)
	{
		int tick = client.getTickCount();
		if (value == 1)
		{
			if (!thrallOut)
			{
				thrallOut = true;
				searchSinceTick = tick - MESSAGE_AFTER_SPAWN_TICKS - SPAWN_TOLERANCE_TICKS;
				if (castTick == -1 || tick - castTick > MESSAGE_AFTER_SPAWN_TICKS + SPAWN_TOLERANCE_TICKS)
				{
					// Neither the cast animation nor the message seen, so the type isn't known
					castTick = tick;
					expectedSpawnTick = tick - MESSAGE_AFTER_SPAWN_TICKS;
					castType = null;
				}
				log.debug("Resurrection turned on on tick {}", tick);
				update(false);
			}
		}
		else
		{
			log.debug("Resurrection turned off on tick {}", tick);
			thrallEnded();
		}
	}

	void onNpcSpawned(NPC npc)
	{
		if (!Thralls.isThrall(npc.getId()))
		{
			return;
		}

		int tick = client.getTickCount();
		int distance = distanceToLocalPlayer(npc);
		sceneThralls.put(npc, new Seen(tick, distance));
		log.debug("Thrall {} ({}) appeared {} tiles away on tick {} (last cast tick {})",
			npc.getId(), Thralls.TYPES.get(npc.getId()), distance, tick, castTick);
		update(false);
	}

	void onNpcDespawned(NPC npc)
	{
		sceneThralls.remove(npc);
		if (npc == ownedThrall)
		{
			log.debug("Our thrall left the scene on tick {}", client.getTickCount());
			ownedThrall = null;
			searchSinceTick = client.getTickCount();
		}
	}

	void onGameTick()
	{
		update(true);
		if (castTick != -1 && client.getTickCount() - castTick > CAST_WINDOW_TICKS)
		{
			castTick = -1;
			expectedSpawnTick = -1;
		}
	}

	/**
	 * A thrall's end, which a recast in the same tick must not undo.
	 */
	private void thrallEnded()
	{
		int tick = client.getTickCount();
		if (castTick == tick)
		{
			// Recast: the thrall that ended is the old one, so keep looking for the new one
			if (ownedThrall != null && spawnTick(ownedThrall) < tick - 1)
			{
				ownedThrall = null;
			}
			return;
		}
		release();
	}

	private void release()
	{
		ownedThrall = null;
		thrallOut = false;
		castTick = -1;
		expectedSpawnTick = -1;
		castType = null;
		searchSinceTick = -1;
	}

	/**
	 * @param onTick true when called at the end of a game tick, once every message
	 *               and spawn for that tick has arrived
	 */
	private void update(boolean onTick)
	{
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			return;
		}

		int tick = client.getTickCount();
		boolean castPending = castTick != -1 && tick - castTick <= CAST_WINDOW_TICKS;

		// 1. A thrall that appeared beside us around our cast (of the type we cast, once the
		// message has told us). Without the type, wait for the tick to finish so a message
		// arriving the same tick is used.
		if (castPending && (castType != null || onTick))
		{
			if (ownedThrall != null && isFromThisCast(ownedThrall))
			{
				// Already following the right one
				castTick = castType != null ? -1 : castTick;
				return;
			}

			NPC fresh = bestFromThisCast(local);
			if (fresh != null)
			{
				claim(fresh, castType == null ? "appeared as we cast" : "matches our cast");
				if (castType != null)
				{
					castTick = -1;
				}
				return;
			}
		}

		if (ownedThrall != null || !thrallOut)
		{
			return;
		}

		// 2. A thrall of our type that is facing/following us
		NPC following = closest(local, CAST_MAX_DISTANCE,
			npc -> npc.getInteracting() == local && typeMatches(npc));
		if (following != null)
		{
			claim(following, "following us");
			return;
		}

		// 3. A thrall of our type appearing right beside us
		if (!castPending && onTick)
		{
			final int since = searchSinceTick;
			NPC nearby = closest(local, NEARBY_MAX_DISTANCE,
				npc -> spawnTick(npc) >= since && typeMatches(npc));
			if (nearby != null)
			{
				claim(nearby, "beside us");
			}
		}
	}

	private void claim(NPC npc, String reason)
	{
		log.debug("Claimed thrall {} ({}) {} tiles away: {}",
			npc.getId(), Thralls.TYPES.get(npc.getId()), distanceToLocalPlayer(npc), reason);
		ownedThrall = npc;
		thrallOut = true;
	}

	private NPC closest(Player local, int maxDistance, Predicate<NPC> filter)
	{
		WorldPoint from = local.getWorldLocation();
		if (from == null)
		{
			return null;
		}

		NPC best = null;
		int bestDistance = Integer.MAX_VALUE;
		for (NPC npc : sceneThralls.keySet())
		{
			WorldPoint at = npc.getWorldLocation();
			if (at == null || !filter.test(npc))
			{
				continue;
			}

			int distance = at.distanceTo(from);
			if (distance <= maxDistance && distance < bestDistance)
			{
				best = npc;
				bestDistance = distance;
			}
		}
		return best;
	}

	/**
	 * Whether a thrall appeared at the right time to be the one we just cast: from a few
	 * ticks before the expected tick up to a couple of ticks after the latest cast signal.
	 */
	private boolean isFromThisCast(NPC npc)
	{
		int spawned = spawnTick(npc);
		return spawned >= expectedSpawnTick - SPAWN_TOLERANCE_TICKS
			&& spawned <= Math.max(expectedSpawnTick, castTick) + LATE_SPAWN_TICKS
			&& typeMatches(npc);
	}

	/**
	 * The thrall that best fits our cast: appeared closest to the expected tick,
	 * then closest to us when it appeared, then closest to us now.
	 */
	private NPC bestFromThisCast(Player local)
	{
		WorldPoint from = local.getWorldLocation();
		if (from == null)
		{
			return null;
		}

		NPC best = null;
		long bestScore = Long.MAX_VALUE;
		for (Map.Entry<NPC, Seen> entry : sceneThralls.entrySet())
		{
			NPC npc = entry.getKey();
			Seen seen = entry.getValue();
			WorldPoint at = npc.getWorldLocation();
			if (npc == ownedThrall || at == null || !isFromThisCast(npc))
			{
				continue;
			}

			int distance = at.distanceTo(from);
			if (distance > CAST_MAX_DISTANCE)
			{
				continue;
			}

			int spawnDistance = seen.distance < 0 ? CAST_MAX_DISTANCE : Math.min(seen.distance, CAST_MAX_DISTANCE);
			long score = (long) Math.abs(seen.tick - expectedSpawnTick) * 10_000 + spawnDistance * 100L + distance;
			if (score < bestScore)
			{
				best = npc;
				bestScore = score;
			}
		}
		return best;
	}

	private boolean typeMatches(NPC npc)
	{
		return castType == null || castType.equals(Thralls.TYPES.get(npc.getId()));
	}

	private int spawnTick(NPC npc)
	{
		Seen seen = sceneThralls.get(npc);
		return seen == null ? Integer.MIN_VALUE / 2 : seen.tick;
	}

	private int distanceToLocalPlayer(NPC npc)
	{
		Player local = client.getLocalPlayer();
		WorldPoint from = local == null ? null : local.getWorldLocation();
		WorldPoint at = npc.getWorldLocation();
		return from == null || at == null ? -1 : at.distanceTo(from);
	}

	/**
	 * Turns the cast message's wording into the {@link Thralls#TYPES} form, e.g.
	 * "greater" + "zombified" becomes "greater zombie".
	 */
	static String typeOf(String tier, String kind)
	{
		switch (kind)
		{
			case "ghostly":
				kind = "ghost";
				break;
			case "skeletal":
				kind = "skeleton";
				break;
			case "zombified":
				kind = "zombie";
				break;
			default:
				break;
		}
		return tier + " " + kind;
	}
}
