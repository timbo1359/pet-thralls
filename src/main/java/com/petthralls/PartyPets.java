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
import java.util.Iterator;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shares the local player's pet with their RuneLite party, and draws party members'
 * thralls as the pets they've chosen.
 * <p>
 * Each player's own plugin works out which thrall is theirs and tells the party its NPC
 * index, which is the same for everyone on that world, so other players' thralls are
 * never guessed at. Only party members who also use the plugin, with party pets turned
 * on, take part. Must only be used from the client thread.
 */
class PartyPets
{
	private static final Logger log = LoggerFactory.getLogger(PartyPets.class);

	// What is shared when there is nothing to share: no pet, no thrall
	private static final PetThrallsPartyUpdate NOTHING = new PetThrallsPartyUpdate(ThrallPet.NONE, 0, -1, -1);

	private static final class Member
	{
		final PetFollower follower;
		PetThrallsPartyUpdate update;

		Member(PetFollower follower)
		{
			this.follower = follower;
		}
	}

	private final Client client;
	private final Consumer<PetThrallsPartyUpdate> sender;
	private final BooleanSupplier inParty;

	private boolean enabled = true;
	// What the party was last told about us
	private PetThrallsPartyUpdate lastSent = NOTHING;

	// Every thrall in the scene, by NPC index
	private final Map<Integer, NPC> sceneThralls = new HashMap<>();
	// Party members who have told us their pet, by member id
	private final Map<Long, Member> members = new HashMap<>();
	// Party members' thralls being drawn as pets this frame
	private final Map<NPC, PetFollower> shown = new HashMap<>();

	/**
	 * @param sender  sends a message to the party
	 * @param inParty whether the player is in a party
	 */
	PartyPets(Client client, Consumer<PetThrallsPartyUpdate> sender, BooleanSupplier inParty)
	{
		this.client = client;
		this.sender = sender;
		this.inParty = inParty;
	}

	/**
	 * Turns party pets on or off. Turning them off stops drawing party members' pets;
	 * the next {@link #share} tells the party to stop drawing ours.
	 */
	void setEnabled(boolean on)
	{
		if (on == enabled)
		{
			return;
		}

		enabled = on;
		if (!on)
		{
			removeMembers();
		}
	}

	boolean isEnabled()
	{
		return enabled;
	}

	/**
	 * Picks up thralls already in the scene, e.g. when the plugin is turned on.
	 */
	void start()
	{
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			onNpcSpawned(npc);
		}
	}

	void onNpcSpawned(NPC npc)
	{
		if (npc != null && Thralls.isThrall(npc.getId()))
		{
			sceneThralls.put(npc.getIndex(), npc);
		}
	}

	void onNpcDespawned(NPC npc)
	{
		if (npc != null)
		{
			sceneThralls.remove(npc.getIndex(), npc);
			shown.remove(npc);
		}
	}

	/**
	 * Tells the party which pet we use and which thrall is ours, if that changed since
	 * they were last told.
	 *
	 * @param pet        the pet our thrall is drawn as, {@link ThrallPet#NONE} if none
	 * @param ownThrall  our thrall, or null if it isn't out or isn't known
	 * @param force      send even if nothing changed, e.g. when a new member asks for everyone's state
	 */
	void share(ThrallPet pet, NPC ownThrall, boolean force)
	{
		if (!inParty.getAsBoolean())
		{
			lastSent = NOTHING;
			return;
		}

		PetThrallsPartyUpdate state = NOTHING;
		if (enabled && pet != null && pet != ThrallPet.NONE)
		{
			state = ownThrall == null
				? new PetThrallsPartyUpdate(pet, 0, -1, -1)
				: new PetThrallsPartyUpdate(pet, client.getWorld(), ownThrall.getIndex(), ownThrall.getId());
		}

		if (force ? state.sameAs(NOTHING) : state.sameAs(lastSent))
		{
			// Nobody draws a pet for us unless told to, so there's no need to say "nothing"
			// to a member who just joined
			return;
		}

		lastSent = state;
		log.debug("Sharing with the party: {}", state);
		sender.accept(state);
	}

	/**
	 * Another party member told us their pet and thrall. Our own messages, which the
	 * party echoes back, must not be passed in.
	 */
	void receive(PetThrallsPartyUpdate update)
	{
		if (!enabled || update == null)
		{
			return;
		}

		long memberId = update.getMemberId();
		ThrallPet pet = update.getPet();
		log.debug("Party member {} uses {} for thrall {} ({}) on world {}", memberId, pet.name(),
			update.getThrallIndex(), update.getThrallId(), update.getWorld());

		if (pet == ThrallPet.NONE)
		{
			memberLeft(memberId);
			return;
		}

		Member member = members.computeIfAbsent(memberId, id -> new Member(new PetFollower(client)));
		member.update = update;
		member.follower.setPet(pet);
	}

	void memberLeft(long memberId)
	{
		Member member = members.remove(memberId);
		if (member != null)
		{
			shown.values().remove(member.follower);
			member.follower.remove();
		}
	}

	/**
	 * Left or changed party: forgets every member and what we told the old party.
	 */
	void clear()
	{
		removeMembers();
		lastSent = NOTHING;
	}

	/**
	 * Logging out or hopping: the scene is gone, but members' pets are kept for when
	 * their thralls are seen again.
	 */
	void removeObjects()
	{
		sceneThralls.clear();
		shown.clear();
		for (Member member : members.values())
		{
			member.follower.remove();
		}
	}

	/**
	 * Draws each party member's thrall that is in the scene as their pet. Called every frame.
	 *
	 * @param ownThrall     our own thrall, or null
	 * @param ownPetShowing whether our own pet is being drawn on it, which takes priority
	 */
	void update(NPC ownThrall, boolean ownPetShowing, boolean showSymbol, boolean showOutline)
	{
		shown.clear();
		if (!enabled || members.isEmpty())
		{
			return;
		}

		int world = client.getWorld();
		for (Member member : members.values())
		{
			NPC thrall = thrallOf(member.update, world);
			if (thrall == null || shown.containsKey(thrall) || (ownPetShowing && thrall == ownThrall))
			{
				member.follower.hide();
				continue;
			}

			member.follower.setMarkings(showSymbol, showOutline);
			if (member.follower.update(thrall))
			{
				shown.put(thrall, member.follower);
			}
		}
	}

	/**
	 * @return true if this is a party member's thrall that is drawn as their pet this frame
	 */
	boolean hides(Object renderable)
	{
		return shown.containsKey(renderable);
	}

	/**
	 * @return the pet drawn on this party member's thrall, or null if it isn't one
	 */
	PetFollower followerFor(Object actor)
	{
		return shown.get(actor);
	}

	private NPC thrallOf(PetThrallsPartyUpdate update, int world)
	{
		if (update == null || update.getThrallIndex() < 0 || update.getWorld() != world)
		{
			return null;
		}

		NPC npc = sceneThralls.get(update.getThrallIndex());
		// Make sure the index still belongs to the same thrall
		return npc != null && npc.getId() == update.getThrallId() ? npc : null;
	}

	private void removeMembers()
	{
		shown.clear();
		for (Iterator<Member> it = members.values().iterator(); it.hasNext(); )
		{
			it.next().follower.remove();
			it.remove();
		}
	}
}
