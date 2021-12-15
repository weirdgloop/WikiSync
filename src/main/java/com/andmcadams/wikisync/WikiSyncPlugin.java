package com.andmcadams.wikisync;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.inject.Provides;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import javax.inject.Inject;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IndexDataBase;
import net.runelite.api.VarbitComposition;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.task.Schedule;

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
	private WikiSyncConfig config;

	@Inject
	private DataManager dataManager;

	@Setter
	private static HashSet<Integer> varbitsToCheck;

	@Setter
	private static HashSet<Integer> varpsToCheck;

	private static int[] oldVarps;
	private Multimap<Integer, Integer> varpToVarbitMapping;

	// TODO: Change this number since this is just for testing
	private static final int SECONDS_BETWEEN_UPLOADS = 5;

	private static final int VARBITS_ARCHIVE_ID = 14;

	@Override
	protected void startUp() throws Exception
	{
		log.info("WikiSync started!");
		dataManager.getManifest();
		allowDump = true;
		clientThread.invoke(() -> {
			if (client != null && client.getGameState() != null)
				handleInitialDump(client.getGameState());
			return true;
		});
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.info("WikiSync stopped!");
	}

	@Schedule(
		period = SECONDS_BETWEEN_UPLOADS,
		unit = ChronoUnit.SECONDS,
		asynchronous = true
	)
	public void submitToAPI()
	{
		dataManager.submitToAPI();
	}

	private static boolean allowDump = true;
	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		handleInitialDump(gameStateChanged.getGameState());
	}

	private void handleInitialDump(GameState gameState)
	{
		if (gameState == GameState.LOGGED_IN  && allowDump)
		{
			allowDump = false;
			loadInitialData();
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
		varpToVarbitMapping = HashMultimap.create();

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

	private void loadInitialData()
	{
		for(int varbIndex : varbitsToCheck)
		{
			dataManager.storeVarbitChanged(varbIndex, client.getVarbitValue(varbIndex));
		}

		for(int varpIndex : varpsToCheck)
		{
			dataManager.storeVarpChanged(varpIndex, client.getVarpValue(varpIndex));
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged varbitChanged)
	{
		if (client == null || varpsToCheck == null || varbitsToCheck == null)
			return;
		if (oldVarps == null)
			setupVarpTracking();

		int varpIndexChanged = varbitChanged.getIndex();
		if (varpsToCheck.contains(varpIndexChanged))
		{
			// Do some stuff here
			dataManager.storeVarpChanged(varpIndexChanged, client.getVarpValue(varpIndexChanged));
		}
		for (Integer i : varpToVarbitMapping.get(varpIndexChanged))
		{
			if (!varbitsToCheck.contains(i))
				continue;
			// For each varbit index, see if it changed.
			int oldValue = client.getVarbitValue(oldVarps, i);
			int newValue = client.getVarbitValue(i);
			if (oldValue != newValue)
				dataManager.storeVarbitChanged(i, newValue);
		}
		oldVarps[varpIndexChanged] = client.getVarpValue(varpIndexChanged);
	}

	@Provides
	WikiSyncConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(WikiSyncConfig.class);
	}
}
