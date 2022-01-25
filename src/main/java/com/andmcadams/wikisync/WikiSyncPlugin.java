/*
 * Copyright (c) 2021, andmcadams
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
package com.andmcadams.wikisync;

import com.google.common.collect.HashMultimap;
import com.google.inject.Provides;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IndexDataBase;
import net.runelite.api.Skill;
import net.runelite.api.VarbitComposition;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;

import javax.inject.Inject;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;

@Slf4j
@PluginDescriptor(
	name = "WikiSync"
)
public class WikiSyncPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ConfigManager configManager;

	@Inject
	private DataManager dataManager;

	@Inject
	private WikiSyncConfig config;

	@Getter
	@Setter
	private int lastManifestVersion = -1;

	private int[] oldVarps;
	private RuneScapeProfileType lastProfile;
	private final AtomicReference<HashSet<Integer>> varbitsToCheck = new AtomicReference<>();
	private final AtomicReference<HashSet<Integer>> varpsToCheck = new AtomicReference<>();

	private final HashMultimap<Integer, Integer> varpToVarbitMapping = HashMultimap.create();
	private final HashMap<String, Integer> skillLevelCache = new HashMap<>();
	private final int SECONDS_BETWEEN_UPLOADS = 10;
	private final int SECONDS_BETWEEN_MANIFEST_CHECKS = 20*60;
	private final int VARBITS_ARCHIVE_ID = 14;

	public static final String CONFIG_GROUP_KEY = "WikiSync";
	// THIS VERSION SHOULD BE INCREMENTED EVERY RELEASE WHERE WE ADD A NEW TOGGLE
	public static final int VERSION = 1;

	@Provides
	WikiSyncConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WikiSyncConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		log.info("WikiSync started!");
		setTogglesBasedOnVersion();
		lastProfile = null;
		skillLevelCache.clear();
		dataManager.getManifest();
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("WikiSync stopped!");
		varbitsToCheck.set(null);
		varpsToCheck.set(null);
		dataManager.clearData();
	}

	@Schedule(
		period = SECONDS_BETWEEN_UPLOADS,
		unit = ChronoUnit.SECONDS,
		asynchronous = true
	)
	public void submitToAPI()
	{
		if (client != null && client.getGameState() != GameState.HOPPING)
			dataManager.submitToAPI();
	}

	@Schedule(
		period = SECONDS_BETWEEN_MANIFEST_CHECKS,
		unit = ChronoUnit.SECONDS,
		asynchronous = true
	)
	public void resyncManifest()
	{
		log.debug("Attempting to resync manifest");
		if (dataManager.getVersion() != lastManifestVersion)
		{
			dataManager.getManifest();
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick)
	{
		// Call a helper function since it needs to be called from DataManager as well
		checkProfileChange();
	}

	public void checkProfileChange()
	{
		RuneScapeProfileType r = RuneScapeProfileType.getCurrent(client);
		HashSet<Integer> vvarbitsToCheck = varbitsToCheck.get();
		HashSet<Integer> vvarpsToCheck = varpsToCheck.get();
		if (r != lastProfile && client != null && vvarbitsToCheck != null && vvarpsToCheck != null)
		{
			// profile change, we should clear the datamanager and do a new initial dump
			log.debug("Profile seemed to change... Reloading all data and updating profile");
			lastProfile = r;
			dataManager.clearData();
			loadInitialData(vvarbitsToCheck, vvarpsToCheck);
		}
	}

	// Need to keep track of old varps and what varps each varb is in.
	// On change
	// Get varp, if varp in hashset, queue it.
	// Get each varb index in varp. If varb changed and varb in hashset, queue it.
	// Checking if varb has changed requires us to keep track of old varps
	private void setupVarpTracking()
	{
		// Init stuff to keep track of varb changes
		varpToVarbitMapping.clear();

		if (oldVarps == null)
		{
			oldVarps = new int[client.getVarps().length];
		}

		// Set oldVarps to be the current varps
		System.arraycopy(client.getVarps(), 0, oldVarps, 0, oldVarps.length);

		// For all varbits, add their ids to the multimap with the varp index as their key
		clientThread.invoke(() -> {
			if (client.getIndexConfig() == null)
			{
				return false;
			}
			IndexDataBase indexVarbits = client.getIndexConfig();
			final int[] varbitIds = indexVarbits.getFileIds(VARBITS_ARCHIVE_ID);
			for (int id : varbitIds)
			{
				VarbitComposition varbit = client.getVarbit(id);
				if (varbit != null)
				{
					varpToVarbitMapping.put(varbit.getIndex(), id);
				}
			}
			return true;
		});
	}

	public void loadInitialData(HashSet<Integer> vvarbitsToCheck, HashSet<Integer> vvarpsToCheck)
	{
		for(int varbIndex : vvarbitsToCheck)
		{
			dataManager.storeVarbitChanged(varbIndex, client.getVarbitValue(varbIndex));
		}

		for(int varpIndex : vvarpsToCheck)
		{
			dataManager.storeVarpChanged(varpIndex, client.getVarpValue(varpIndex));
		}
		for(Skill s : Skill.values())
		{
			if (s != Skill.OVERALL)
				dataManager.storeSkillChanged(s.getName(), client.getRealSkillLevel(s));
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged varbitChanged)
	{
		HashSet<Integer> vvarbitsToCheck = varbitsToCheck.get();
		HashSet<Integer> vvarpsToCheck = varpsToCheck.get();
		if (client == null || vvarbitsToCheck == null || vvarpsToCheck == null)
			return;
		if (oldVarps == null)
			setupVarpTracking();

		int varpIndexChanged = varbitChanged.getIndex();
		if (vvarpsToCheck.contains(varpIndexChanged))
		{
			dataManager.storeVarpChanged(varpIndexChanged, client.getVarpValue(varpIndexChanged));
		}
		for (Integer i : varpToVarbitMapping.get(varpIndexChanged))
		{
			if (!vvarbitsToCheck.contains(i))
				continue;
			// For each varbit index, see if it changed.
			int oldValue = client.getVarbitValue(oldVarps, i);
			int newValue = client.getVarbitValue(i);
			if (oldValue != newValue)
				dataManager.storeVarbitChanged(i, newValue);
		}
		oldVarps[varpIndexChanged] = client.getVarpValue(varpIndexChanged);
	}

	@Subscribe
	public void onStatChanged(StatChanged statChanged)
	{
		if (statChanged.getSkill() == null || statChanged.getSkill() == Skill.OVERALL)
			return;
	    Integer cachedLevel = skillLevelCache.get(statChanged.getSkill().getName());
		if (cachedLevel == null || cachedLevel != statChanged.getLevel())
		{
			skillLevelCache.put(statChanged.getSkill().getName(), statChanged.getLevel());
			dataManager.storeSkillChanged(statChanged.getSkill().getName(), statChanged.getLevel());
		}
	}

	private void setTogglesBasedOnVersion()
	{
		// Conditionally turn off certain features by default
		Integer version = configManager.getConfiguration(CONFIG_GROUP_KEY, WikiSyncConfig.WIKISYNC_VERSION_KEYNAME, Integer.class);
		if (version == null)
			return;
		int maxVersion = version;
		/* EXAMPLE TOGGLE SETTING CLAUSE */
		/* if (version < 2)
		{
			// Location tracking was added in deploy 2
			configManager.setConfiguration(CONFIG_GROUP_KEY, WikiSyncConfig.WIKISYNC_TOGGLE_KEYNAME, false);
			maxVersion = 2;
		}
		*/

		// This is done here and not in each block because we don't want to rely on the order of the if clauses being correct.
		configManager.setConfiguration(CONFIG_GROUP_KEY, WikiSyncConfig.WIKISYNC_VERSION_KEYNAME, maxVersion);
		log.debug("WikiSync version set to deployment number " + version);
	}

	public void setVarbitsToCheck(HashSet<Integer> varbitsToCheck)
	{
		this.varbitsToCheck.set(varbitsToCheck);
	}

	public void setVarpsToCheck(HashSet<Integer> varpsToCheck)
	{
		this.varpsToCheck.set(varpsToCheck);
	}
}
