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

import com.andmcadams.wikisync.dps.DpsDataFetcher;
import com.andmcadams.wikisync.dps.WebSocketManager;
import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneScapeProfileType;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;
import okhttp3.*;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
	private EventBus eventBus;

	@Inject
	private ConfigManager configManager;

	@Inject
	private WebSocketManager webSocketManager;

	@Inject
	private DpsDataFetcher dpsDataFetcher;

	@Inject
	private WikiSyncConfig config;

	@Inject
	private Gson gson;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private SyncButtonManager syncButtonManager;

	@Inject
	private ScheduledExecutorService scheduledExecutorService;

	private static final int SECONDS_BETWEEN_UPLOADS = 10;
	private static final int SECONDS_BETWEEN_MANIFEST_CHECKS = 1200;

//	private static final String MANIFEST_URL = "https://sync.runescape.wiki/runelite/manifest";
//	private static final String SUBMIT_URL = "https://sync.runescape.wiki/runelite/submit";
	private static final String MANIFEST_URL = "http://localhost:3000/runelite/manifest";
	private static final String SUBMIT_URL = "http://localhost:3000/runelite/submit";
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	private static final int VARBITS_ARCHIVE_ID = 14;
	private Map<Integer, VarbitComposition> varbitCompositions = new HashMap<>();

	public static final String CONFIG_GROUP_KEY = "WikiSync";
	// THIS VERSION SHOULD BE INCREMENTED EVERY RELEASE WHERE WE ADD A NEW TOGGLE
	public static final int VERSION = 1;

	private Manifest manifest;
	private Map<PlayerProfile, PlayerData> playerDataMap = new HashMap<>();
	private boolean webSocketStarted;
	private int cyclesSinceSuccessfulCall = 0;

	// Keeps track of what collection log slots the user has set.
	private static final BitSet clogItemsBitSet = new BitSet();
	// Map item ids to bit index in the bitset
	private static final HashMap<Integer, Integer> collectionLogItemIdToBitsetIndex = new HashMap<>();
	private boolean collectionLogScriptFired = false;
	private HashSet<Integer> collectionLogItemIdsFromCache;

	@Provides
	WikiSyncConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WikiSyncConfig.class);
	}

	@Override
	public void startUp()
	{
		clogItemsBitSet.clear();
		clientThread.invoke(() -> {
			collectionLogItemIdsFromCache = parseCacheForClog();
			if (client.getIndexConfig() == null || client.getGameState().ordinal() < GameState.LOGIN_SCREEN.ordinal())
			{
				log.debug("Failed to get varbitComposition, state = {}", client.getGameState());
				return false;
			}
			final int[] varbitIds = client.getIndexConfig().getFileIds(VARBITS_ARCHIVE_ID);
			for (int id : varbitIds)
			{
				varbitCompositions.put(id, client.getVarbit(id));
			}
			return true;
		});

		checkManifest();
		if (config.enableLocalWebSocketServer()) {
			startUpWebSocketManager();
		}
		syncButtonManager.startUp();
	}

	private void startUpWebSocketManager()
	{
		webSocketManager.startUp();
		eventBus.register(webSocketManager);
		eventBus.register(dpsDataFetcher);
		webSocketStarted = true;
	}

	@Override
	protected void shutDown()
	{
		log.debug("WikiSync stopped!");
		clogItemsBitSet.clear();
		shutDownWebSocketManager();
		syncButtonManager.shutDown();
	}

	private void shutDownWebSocketManager()
	{
		webSocketManager.shutDown();
		eventBus.unregister(webSocketManager);
		eventBus.unregister(dpsDataFetcher);
		webSocketStarted = false;
	}

	/**
	 * Finds the index this itemId is assigned to in the collections mapping.
	 * @param itemId: The itemId to look up
	 * @return The index of the bit that represents the given itemId, if it is in the map. -1 otherwise.
	 */
	private int lookupCollectionLogItemIndex(int itemId) {
		// The map has not loaded yet, or failed to load.
		if (collectionLogItemIdToBitsetIndex.isEmpty()) {
			return -1;
		}
		Integer result = collectionLogItemIdToBitsetIndex.get(itemId);
		if (result == null) {
			log.error("Item id {} not found in the mapping of items", itemId);
			return -1;
		}
		return result;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		switch (event.getGameState())
		{
			// When hopping, we need to clear any state related to the player
			case HOPPING:
			case LOGGING_IN:
			case CONNECTION_LOST:
				clogItemsBitSet.clear();
				break;
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired preFired) {
		if (preFired.getScriptId() == 4100) {
			if (collectionLogItemIdToBitsetIndex.isEmpty())
			{
				log.error("Manifest has no collections data");
				return;
			}
			collectionLogScriptFired = true;

			Object[] args = preFired.getScriptEvent().getArguments();
			int itemId = (int) args[1];
			int idx = lookupCollectionLogItemIndex(itemId);
			// We should never return -1 under normal circumstances
			if (idx != -1)
				clogItemsBitSet.set(idx);
		}
	}

	@Subscribe
	public void onGameTick(GameTick gameTick) {
		// Fire a submit attempt after loading the collection log
		if (collectionLogScriptFired) {
			scheduledExecutorService.execute(this::submitTask);
			collectionLogScriptFired = false;
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e) {
		if (e.getGroup().equals(CONFIG_GROUP_KEY)){
			if (config.enableLocalWebSocketServer() != webSocketStarted) {
				if (config.enableLocalWebSocketServer()) {
					startUpWebSocketManager();
				} else {
					shutDownWebSocketManager();
				}
			}
		}
	}

	@Schedule(
		period = SECONDS_BETWEEN_UPLOADS,
		unit = ChronoUnit.SECONDS,
		asynchronous = true
	)
	public void queueSubmitTask() {
		scheduledExecutorService.execute(this::submitTask);
	}

	synchronized public void submitTask()
	{
		// TODO: do we want other GameStates?
		if (client.getGameState() != GameState.LOGGED_IN || varbitCompositions.isEmpty())
		{
			return;
		}

		if (manifest == null || client.getLocalPlayer() == null)
		{
			log.debug("Skipped due to bad manifest: {}", manifest);
			return;
		}

		String username = client.getLocalPlayer().getName();
		RuneScapeProfileType profileType = RuneScapeProfileType.getCurrent(client);
		PlayerProfile profileKey = new PlayerProfile(username, profileType);

		PlayerData newPlayerData = getPlayerData();
		PlayerData oldPlayerData = playerDataMap.computeIfAbsent(profileKey, k -> new PlayerData());

		// Subtraction is done in place so newPlayerData becomes a map of only changed fields
		subtract(newPlayerData, oldPlayerData);
		if (newPlayerData.isEmpty())
		{
			return;
		}
		submitPlayerData(profileKey, newPlayerData, oldPlayerData);
	}

	@Schedule(
			period = SECONDS_BETWEEN_MANIFEST_CHECKS,
			unit = ChronoUnit.SECONDS,
			asynchronous = true
	)
	public void manifestTask()
	{
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			checkManifest();
		}
	}


	private int getVarbitValue(int varbitId)
	{
		VarbitComposition v = varbitCompositions.get(varbitId);
		if (v == null)
		{
			return -1;
		}

		int value = client.getVarpValue(v.getIndex());
		int lsb = v.getLeastSignificantBit();
		int msb = v.getMostSignificantBit();
		int mask = (1 << ((msb - lsb) + 1)) - 1;
		return (value >> lsb) & mask;
	}

	private PlayerData getPlayerData()
	{
		PlayerData out = new PlayerData();
		for (int varbitId : manifest.varbits)
		{
			out.varb.put(varbitId, getVarbitValue(varbitId));
		}
		for (int varpId : manifest.varps)
		{
			out.varp.put(varpId, client.getVarpValue(varpId));
		}
		for(Skill s : Skill.values())
		{
			out.level.put(s.getName(), client.getRealSkillLevel(s));
		}
		out.collectionLog = Base64.getEncoder().encodeToString(clogItemsBitSet.toByteArray());
		return out;
	}

	private void subtract(PlayerData newPlayerData, PlayerData oldPlayerData)
	{
		oldPlayerData.varb.forEach(newPlayerData.varb::remove);
		oldPlayerData.varp.forEach(newPlayerData.varp::remove);
		oldPlayerData.level.forEach(newPlayerData.level::remove);
		if (newPlayerData.collectionLog.equals(oldPlayerData.collectionLog))
			newPlayerData.clearCollectionLog();
	}

	private void merge(PlayerData oldPlayerData, PlayerData delta)
	{
		oldPlayerData.varb.putAll(delta.varb);
		oldPlayerData.varp.putAll(delta.varp);
		oldPlayerData.level.putAll(delta.level);
		oldPlayerData.collectionLog = delta.collectionLog;
	}

	private void submitPlayerData(PlayerProfile profileKey, PlayerData delta, PlayerData old)
	{
		// If cyclesSinceSuccessfulCall is not a perfect square, we should not try to submit.
		// This gives us quadratic backoff.
		cyclesSinceSuccessfulCall += 1;
		if (Math.pow((int) Math.sqrt(cyclesSinceSuccessfulCall), 2) != cyclesSinceSuccessfulCall)
		{
			return;
		}

		PlayerDataSubmission submission = new PlayerDataSubmission(
				profileKey.getUsername(),
				profileKey.getProfileType().name(),
				delta
		);

		Request request = new Request.Builder()
				.url(SUBMIT_URL)
				.post(RequestBody.create(JSON, gson.toJson(submission)))
				.build();

		Call call = okHttpClient.newCall(request);
		call.timeout().timeout(3, TimeUnit.SECONDS);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to submit: ", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					if (!response.isSuccessful()) {
						log.debug("Failed to submit: {}", response.code());
						return;
					}
					merge(old, delta);
					cyclesSinceSuccessfulCall = 0;
				}
				finally
				{
					response.close();
				}
			}
		});
	}

	private void checkManifest()
	{
		Request request = new Request.Builder()
				.url(MANIFEST_URL)
				.build();
		okHttpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Failed to get manifest: ", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try
				{
					if (!response.isSuccessful())
					{
						log.debug("Failed to get manifest: {}", response.code());
						return;
					}
					InputStream in = response.body().byteStream();
					manifest = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Manifest.class);

					clientThread.invoke(() -> {
						// Add missing keys in order to the map. Order is extremely important here so
						// we get a stable map given the same cache data.
						List<Integer> itemIdsMissingFromManifest = collectionLogItemIdsFromCache
                                .stream()
                                .filter((t) -> !manifest.collections.contains(t))
								.sorted()
								.collect(Collectors.toList());

                        int currentIndex = 0;
						collectionLogItemIdToBitsetIndex.clear();
						for (Integer itemId : manifest.collections)
							collectionLogItemIdToBitsetIndex.put(itemId, currentIndex++);
						for (Integer missingItemId : itemIdsMissingFromManifest) {
							collectionLogItemIdToBitsetIndex.put(missingItemId, currentIndex++);
						}
					});
				}
				catch (JsonParseException e)
				{
					log.debug("Failed to parse manifest: ", e);
				}
				finally
				{
					response.close();
				}
			}
		});
	}

	@Schedule(
		period = 30,
		unit = ChronoUnit.SECONDS,
		asynchronous = true
	)
	public void scheduledEnsureDpsWsActive()
	{
		log.debug("ensuring active!!");
		if (webSocketStarted)
		{
			webSocketManager.ensureActive();
		}
	}

	/**
	 * Parse the enums and structs in the cache to figure out which item ids
	 * exist in the collection log. This can be diffed with the manifest to
	 * determine the item ids that need to be appended to the end of the
	 * bitset we send to the WikiSync server.
	 */
	private HashSet<Integer> parseCacheForClog()
	{
		HashSet<Integer> itemIds = new HashSet<>();
		// 2102 - Struct that contains the highest level tabs in the collection log (Bosses, Raids, etc)
		// https://chisel.weirdgloop.org/structs/index.html?type=enums&id=2102
		int[] topLevelTabStructIds = client.getEnum(2102).getIntVals();
		for (int topLevelTabStructIndex : topLevelTabStructIds)
		{
			// The collection log top level tab structs contain a param that points to the enum
			// that contains the pointers to sub tabs.
			// ex: https://chisel.weirdgloop.org/structs/index.html?type=structs&id=471
			StructComposition topLevelTabStruct = client.getStructComposition(topLevelTabStructIndex);

			// Param 683 contains the pointer to the enum that contains the subtabs ids
			// ex: https://chisel.weirdgloop.org/structs/index.html?type=enums&id=2103
			int[] subtabStructIndices = client.getEnum(topLevelTabStruct.getIntValue(683)).getIntVals();
			for (int subtabStructIndex : subtabStructIndices) {

				// The subtab structs are for subtabs in the collection log (Commander Zilyana, Chambers of Xeric, etc.)
				// and contain a pointer to the enum that contains all the item ids for that tab.
				// ex subtab struct: https://chisel.weirdgloop.org/structs/index.html?type=structs&id=476
				// ex subtab enum: https://chisel.weirdgloop.org/structs/index.html?type=enums&id=2109
				StructComposition subtabStruct = client.getStructComposition(subtabStructIndex);
				int[] clogItems = client.getEnum(subtabStruct.getIntValue(690)).getIntVals();
				for (int clogItemId : clogItems) itemIds.add(clogItemId);
			}
		}

		// Some items with data saved on them have replacements to fix a duping issue (satchels, flamtaer bag)
		// Enum 3721 contains a mapping of the item ids to replace -> ids to replace them with
		EnumComposition replacements = client.getEnum(3721);
		for (int badItemId : replacements.getKeys())
			itemIds.remove(badItemId);
		for (int goodItemId : replacements.getIntVals())
			itemIds.add(goodItemId);

		return itemIds;
	}

}
