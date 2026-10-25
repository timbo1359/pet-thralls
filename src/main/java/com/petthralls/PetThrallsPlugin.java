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

import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Renderable;
import net.runelite.api.ScriptID;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallback;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.PartyChanged;
import net.runelite.client.events.RuneScapeProfileChanged;
import net.runelite.client.party.PartyMember;
import net.runelite.client.party.PartyService;
import net.runelite.client.party.WSClient;
import net.runelite.client.party.events.UserJoin;
import net.runelite.client.party.events.UserPart;
import net.runelite.client.party.messages.UserSync;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Pet Thralls",
	description = "Draws your own thrall as a pet you have unlocked in your collection log, with an option to share it with your RuneLite party",
	tags = {"thrall", "pet", "arceuus", "cosmetic", "collection log", "model", "party"}
)
public class PetThrallsPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(PetThrallsPlugin.class);

	static final String UNLOCKED_PETS_KEY = "unlockedPets";
	// The core Chat Commands plugin also records the All Pets page; used until this plugin has its own copy
	private static final String CHAT_COMMANDS_GROUP = "chatcommands";
	private static final String CHAT_COMMANDS_PETS_KEY = "pets2";

	private static final Pattern COLLECTION_LOG_ITEM_PATTERN =
		Pattern.compile("New item added to your collection log: (.*)");
	private static final Pattern ADVENTURE_LOG_TITLE_PATTERN = Pattern.compile("The Exploits of (.+)");
	private static final String ALL_PETS_TITLE = "All Pets";
	private static final int COLLECTION_LOG_HEADER_TITLE_INDEX = 0;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private PetThrallsConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private Gson gson;

	@Inject
	private PartyService partyService;

	@Inject
	private WSClient wsClient;

	private final RenderCallback hideThralls = new RenderCallback()
	{
		@Override
		public boolean addEntity(Renderable renderable, boolean ui)
		{
			// Only hide the 3D thrall, and only while a pet is actually being drawn in its place
			if (ui)
			{
				return true;
			}
			if (tracker != null && renderable == tracker.getOwnedThrall() && follower != null && follower.isShowing())
			{
				return false;
			}
			return partyPets == null || !partyPets.hides(renderable);
		}
	};

	private ThrallTracker tracker;
	private PetFollower follower;
	private PartyPets partyPets;
	private boolean showSymbol = true;
	private boolean showOutline = true;

	// Item ids of the pets the local player has unlocked, from the collection log
	private final Set<Integer> unlockedPets = new HashSet<>();
	private boolean unlockedPetsKnown;

	// Collection log can be viewed for other players through a POH adventure log
	private String pohOwner;
	private boolean adventureLogLoaded;

	private boolean checkSelectionNextTick;

	@Provides
	PetThrallsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PetThrallsConfig.class);
	}

	@Override
	protected void startUp()
	{
		tracker = new ThrallTracker(client);
		follower = new PetFollower(client);
		partyPets = new PartyPets(client, this::sendToParty, partyService::isInParty);
		renderCallbackManager.register(hideThralls);
		wsClient.registerMessage(PetThrallsPartyUpdate.class);

		clientThread.invoke(() ->
		{
			partyPets.setEnabled(config.partyPets());
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				loadUnlockedPets();
				tracker.start();
				partyPets.start();
			}
			refreshPet();
			shareWithParty(true);
			requestPartySync();
		});
	}

	@Override
	protected void shutDown()
	{
		renderCallbackManager.unregister(hideThralls);
		wsClient.unregisterMessage(PetThrallsPartyUpdate.class);

		// Taken now so a quick restart can't have its new one cleared below
		PartyPets oldPartyPets = partyPets;
		partyPets = null;

		clientThread.invoke(() ->
		{
			if (oldPartyPets != null)
			{
				// Tell the party to stop drawing our pet
				oldPartyPets.setEnabled(false);
				oldPartyPets.share(ThrallPet.NONE, null, false);
				oldPartyPets.clear();
			}
			if (follower != null)
			{
				follower.remove();
				follower = null;
			}
			tracker = null;

			unlockedPets.clear();
			unlockedPetsKnown = false;
			ThrallPet.setUnlockedItems(null);
			pohOwner = null;
			adventureLogLoaded = false;
			checkSelectionNextTick = false;
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!PetThrallsConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		if ("pet".equals(event.getKey()))
		{
			clientThread.invoke(() ->
			{
				refreshPet();
				shareWithParty(false);
				if (client.getGameState() == GameState.LOGGED_IN)
				{
					warnIfSelectionLocked();
				}
			});
		}
		else if ("showSymbol".equals(event.getKey()) || "showOutline".equals(event.getKey()))
		{
			clientThread.invoke(this::refreshPet);
		}
		else if ("partyPets".equals(event.getKey()))
		{
			clientThread.invoke(() ->
			{
				if (partyPets == null)
				{
					return;
				}

				boolean on = config.partyPets();
				partyPets.setEnabled(on);
				// Off: tells the party to stop drawing our pet. On: shares it and asks for theirs
				shareWithParty(on);
				if (on)
				{
					requestPartySync();
				}
			});
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		clientThread.invoke(() ->
		{
			loadUnlockedPets();
			refreshPet();
			checkSelectionNextTick = true;
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			case LOADING:
				pohOwner = null;
				break;
			case LOGIN_SCREEN:
			case HOPPING:
				if (event.getGameState() == GameState.LOGIN_SCREEN)
				{
					// The next account may have different pets
					ThrallPet.setUnlockedItems(null);
				}
				pohOwner = null;
				if (tracker != null)
				{
					tracker.reset();
				}
				if (follower != null)
				{
					follower.remove();
				}
				if (partyPets != null)
				{
					partyPets.removeObjects();
				}
				// Our thrall is gone
				shareWithParty(false);
				break;
			default:
				break;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (tracker != null)
		{
			tracker.onGameTick();
		}
		// Tells the party when our thrall changes; does nothing if it hasn't
		shareWithParty(false);

		if (adventureLogLoaded)
		{
			adventureLogLoaded = false;
			String owner = readAdventureLogOwner();
			if (owner != null)
			{
				pohOwner = owner;
			}
		}

		if (checkSelectionNextTick)
		{
			checkSelectionNextTick = false;
			warnIfSelectionLocked();
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (tracker == null)
		{
			return;
		}

		Player local = client.getLocalPlayer();
		if (local != null && event.getActor() == local && local.getAnimation() == Thralls.CAST_ANIMATION)
		{
			tracker.onCastAnimation();
			return;
		}

		if (!(event.getActor() instanceof NPC))
		{
			return;
		}

		NPC npc = (NPC) event.getActor();
		if (!Thralls.ATTACK_ANIMATIONS.contains(npc.getAnimation()))
		{
			return;
		}

		PetFollower attacker = npc == tracker.getOwnedThrall() ? follower : null;
		if (attacker == null && partyPets != null)
		{
			// A party member's thrall
			attacker = partyPets.followerFor(npc);
		}

		if (attacker != null)
		{
			attacker.playAttack(Thralls.styleOf(npc.getId()));
		}
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		if (follower == null || tracker == null)
		{
			return;
		}

		NPC thrall = tracker.getOwnedThrall();
		if (thrall != null)
		{
			follower.update(thrall);
		}
		else
		{
			follower.hide();
		}

		if (partyPets != null)
		{
			partyPets.update(thrall, follower.isShowing(), showSymbol, showOutline);
		}
	}

	// ------------------------------------------------------------------
	// Party pets (see PartyPets)
	// ------------------------------------------------------------------

	// Party events arrive on the party connection's thread, so each is handled on the client thread.
	// Party members are looked up here, on the thread that changes them

	@Subscribe
	public void onPetThrallsPartyUpdate(PetThrallsPartyUpdate update)
	{
		if (update.getMemberId() == localMemberId())
		{
			// Our own message, echoed back
			return;
		}

		clientThread.invoke(() ->
		{
			if (partyPets != null)
			{
				partyPets.receive(update);
			}
		});
	}

	@Subscribe
	public void onUserSync(UserSync event)
	{
		// Someone joined and asked for everyone's state
		clientThread.invoke(() -> shareWithParty(true));
	}

	@Subscribe
	public void onUserJoin(UserJoin event)
	{
		if (event.getMemberId() == localMemberId())
		{
			// We've just joined: tell the party our pet
			clientThread.invoke(() -> shareWithParty(true));
		}
	}

	@Subscribe
	public void onUserPart(UserPart event)
	{
		clientThread.invoke(() ->
		{
			if (partyPets != null)
			{
				partyPets.memberLeft(event.getMemberId());
			}
		});
	}

	@Subscribe
	public void onPartyChanged(PartyChanged event)
	{
		clientThread.invoke(() ->
		{
			if (partyPets != null)
			{
				partyPets.clear();
			}
		});
	}

	private void shareWithParty(boolean force)
	{
		if (partyPets != null && follower != null && tracker != null)
		{
			partyPets.share(follower.getPet(), tracker.getOwnedThrall(), force);
		}
	}

	private void sendToParty(PetThrallsPartyUpdate update)
	{
		if (partyService.isInParty())
		{
			partyService.send(update);
		}
	}

	/**
	 * Asks every party member to send their state, so we see pets that were shared
	 * before we were listening.
	 */
	private void requestPartySync()
	{
		if (partyPets != null && partyPets.isEnabled() && partyService.isInParty())
		{
			partyService.send(new UserSync());
		}
	}

	private long localMemberId()
	{
		PartyMember local = partyService.getLocalMember();
		return local == null ? -1 : local.getMemberId();
	}

	// ------------------------------------------------------------------
	// Working out which thrall is ours (see ThrallTracker)
	// ------------------------------------------------------------------

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());
		if (tracker != null)
		{
			tracker.onGameMessage(message);
		}

		Matcher newItem = COLLECTION_LOG_ITEM_PATTERN.matcher(message);
		if (newItem.find())
		{
			ThrallPet pet = ThrallPet.fromItemName(newItem.group(1).trim());
			if (pet != null && unlockedPets.add(pet.getItemId()))
			{
				log.debug("New pet unlocked: {}", pet.name());
				unlockedPetsKnown = true;
				publishUnlockedPets();
				saveUnlockedPets();
				refreshPet();
			}
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (event.getVarbitId() == VarbitID.ARCEUUS_RESURRECTION_ACTIVE && tracker != null)
		{
			tracker.onResurrectionVarbit(event.getValue());
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		if (tracker != null)
		{
			tracker.onNpcSpawned(event.getNpc());
		}
		if (partyPets != null)
		{
			partyPets.onNpcSpawned(event.getNpc());
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		if (tracker != null)
		{
			tracker.onNpcDespawned(event.getNpc());
		}
		if (partyPets != null)
		{
			partyPets.onNpcDespawned(event.getNpc());
		}
	}

	// ------------------------------------------------------------------
	// Collection log pet validation
	// ------------------------------------------------------------------

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != ScriptID.COLLECTION_DRAW_LIST)
		{
			return;
		}

		Player local = client.getLocalPlayer();
		if (local == null || (pohOwner != null && !pohOwner.equals(local.getName())))
		{
			// Looking at somebody else's collection log
			return;
		}

		Widget header = client.getWidget(InterfaceID.Collection.HEADER_TEXT);
		if (header == null || header.getChildren() == null || header.getChildren().length <= COLLECTION_LOG_HEADER_TITLE_INDEX)
		{
			return;
		}

		Widget title = header.getChild(COLLECTION_LOG_HEADER_TITLE_INDEX);
		if (title == null || !ALL_PETS_TITLE.equals(title.getText()))
		{
			return;
		}

		Widget items = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
		if (items == null || items.getChildren() == null)
		{
			return;
		}

		Set<Integer> found = new HashSet<>();
		for (Widget child : items.getChildren())
		{
			// Unobtained entries are drawn faded out
			if (child != null && child.getOpacity() == 0 && child.getItemId() > 0)
			{
				found.add(child.getItemId());
			}
		}

		boolean changed = !unlockedPetsKnown || !found.equals(unlockedPets);
		unlockedPets.clear();
		unlockedPets.addAll(found);
		unlockedPetsKnown = true;
		publishUnlockedPets();

		if (changed)
		{
			log.debug("Loaded {} pets from the collection log", found.size());
			saveUnlockedPets();
			refreshPet();
			sendMessage("Synced " + found.size() + " unlocked pet" + (found.size() == 1 ? "" : "s") + " from your collection log.");
			warnIfSelectionLocked();
		}
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() == InterfaceID.MENU || event.getGroupId() == InterfaceID.MENU_NEW)
		{
			adventureLogLoaded = true;
		}
	}

	private String readAdventureLogOwner()
	{
		String owner = readAdventureLogOwner(InterfaceID.MenuNew.TITLE);
		return owner != null ? owner : readAdventureLogOwner(InterfaceID.Menu.LJ_LAYER2);
	}

	private String readAdventureLogOwner(int componentId)
	{
		Widget container = client.getWidget(componentId);
		if (container == null)
		{
			return null;
		}

		String owner = matchAdventureLogOwner(container.getText());
		if (owner != null || container.getChildren() == null)
		{
			return owner;
		}

		for (Widget child : container.getChildren())
		{
			if (child != null)
			{
				owner = matchAdventureLogOwner(child.getText());
				if (owner != null)
				{
					return owner;
				}
			}
		}
		return null;
	}

	private static String matchAdventureLogOwner(String text)
	{
		if (text == null)
		{
			return null;
		}

		Matcher matcher = ADVENTURE_LOG_TITLE_PATTERN.matcher(Text.removeTags(text));
		return matcher.find() ? matcher.group(1) : null;
	}

	private void loadUnlockedPets()
	{
		unlockedPets.clear();
		unlockedPetsKnown = false;

		List<Integer> stored = readPetList(PetThrallsConfig.GROUP, UNLOCKED_PETS_KEY);
		if (stored == null)
		{
			stored = readPetList(CHAT_COMMANDS_GROUP, CHAT_COMMANDS_PETS_KEY);
		}

		if (stored != null)
		{
			unlockedPets.addAll(stored);
			unlockedPetsKnown = true;
		}
		publishUnlockedPets();
		log.debug("Loaded {} unlocked pets for this account", unlockedPets.size());
	}

	/**
	 * Shares the unlocked pets with the settings dropdown, which colours them.
	 */
	private void publishUnlockedPets()
	{
		ThrallPet.setUnlockedItems(unlockedPetsKnown ? ImmutableSet.copyOf(unlockedPets) : null);
	}

	private List<Integer> readPetList(String group, String key)
	{
		String json = configManager.getRSProfileConfiguration(group, key);
		if (json == null || json.isEmpty())
		{
			return null;
		}

		try
		{
			List<Integer> list = gson.fromJson(json, new TypeToken<List<Integer>>()
			{
			}.getType());
			if (list == null)
			{
				return null;
			}
			list.removeIf(id -> id == null);
			return list;
		}
		catch (JsonSyntaxException e)
		{
			log.debug("Unable to read pet list {}.{}", group, key, e);
			return null;
		}
	}

	private void saveUnlockedPets()
	{
		List<Integer> list = new ArrayList<>(unlockedPets);
		Collections.sort(list);
		configManager.setRSProfileConfiguration(PetThrallsConfig.GROUP, UNLOCKED_PETS_KEY, gson.toJson(list));
	}

	// ------------------------------------------------------------------
	// Selected pet
	// ------------------------------------------------------------------

	/**
	 * Points the follower at the selected pet if it is unlocked, otherwise at nothing
	 * so the thrall is left as it is.
	 */
	private void refreshPet()
	{
		if (follower == null)
		{
			return;
		}

		ThrallPet selected = config.pet();
		boolean usable = selected != ThrallPet.NONE && unlockedPets.contains(selected.getItemId());
		follower.setPet(usable ? selected : ThrallPet.NONE);
		// Also used for party members' pets
		showSymbol = config.showSymbol();
		showOutline = config.showOutline();
		follower.setMarkings(showSymbol, showOutline);
	}

	private void warnIfSelectionLocked()
	{
		ThrallPet selected = config.pet();
		if (selected == ThrallPet.NONE || unlockedPets.contains(selected.getItemId()))
		{
			return;
		}

		if (!unlockedPetsKnown)
		{
			sendMessage("Open Collection log > Other > All Pets so your unlocked pets can be checked. "
				+ "Your thrall will look normal until then.");
		}
		else
		{
			sendMessage(selected.getPetName() + " isn't unlocked in your collection log, so your thrall will look normal. "
				+ "If you've just got it, open Collection log > Other > All Pets to sync.");
		}
	}

	private void sendMessage(String text)
	{
		String message = new ChatMessageBuilder()
			.append(ChatColorType.HIGHLIGHT)
			.append("Pet Thralls: ")
			.append(ChatColorType.NORMAL)
			.append(text)
			.build();

		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(message)
			.build());
	}
}
