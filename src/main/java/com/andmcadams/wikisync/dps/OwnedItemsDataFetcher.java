package com.andmcadams.wikisync.dps;

import com.andmcadams.wikisync.WikiSyncConfig;
import com.andmcadams.wikisync.WikiSyncPlugin;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.RuneScapeProfileChanged;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OwnedItemsDataFetcher
{
	private final Client client;
	private final ClientThread clientThread;
	private final ConfigManager configManager;
	private final WikiSyncConfig config;
	private final OwnedItemsSnapshot snapshot = new OwnedItemsSnapshot();
	private final Map<Integer, String> itemNames = new TreeMap<>();
	private String profile;
	private Item[] pendingBank;
	private boolean pending = true;

	public void reset()
	{
		snapshot.reset();
		itemNames.clear();
		profile = null;
		pendingBank = null;
		pending = true;
	}

	private void checkProfile()
	{
		String current = configManager.getRSProfileKey();
		if (!Objects.equals(profile, current))
		{
			reset();
			profile = current;
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (!config.enableLocalOwnedItems() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		checkProfile();
		if (event.getContainerId() == InventoryID.BANK)
		{
			pendingBank = event.getItemContainer().getItems().clone();
			pending = true;
		}
		if (event.getContainerId() == InventoryID.INV || event.getContainerId() == InventoryID.WORN)
		{
			pending = true;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		pending = true;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING
			|| event.getGameState() == GameState.LOGGING_IN)
		{
			reset();
		}
	}

	@Subscribe
	public void onRuneScapeProfileChanged(RuneScapeProfileChanged event)
	{
		clientThread.invokeLater(this::reset);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (event.getGroup().equals(WikiSyncPlugin.CONFIG_GROUP_KEY)
			&& event.getKey().equals(WikiSyncConfig.ENABLE_LOCAL_OWNED_ITEMS_KEYNAME))
		{
			clientThread.invokeLater(this::reset);
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		refresh();
	}

	private void refresh()
	{
		if (!config.enableLocalOwnedItems() || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		checkProfile();
		if (!pending)
		{
			return;
		}
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		ItemContainer equipped = client.getItemContainer(InventoryID.WORN);
		if (inventory == null || equipped == null)
		{
			return;
		}
		Map<Integer, Integer> bank = pendingBank == null ? null : normalize(pendingBank);
		Map<Integer, Integer> inv = normalize(inventory.getItems());
		Map<Integer, Integer> worn = normalize(equipped.getItems());
		Map<String, Integer> levels = new TreeMap<>();
		for (Skill skill : Skill.values())
		{
			if (skill != Skill.OVERALL)
			{
				levels.put(skill.name().toLowerCase(Locale.ROOT), client.getRealSkillLevel(skill));
			}
		}
		if (client.getLocalPlayer() != null)
		{
			levels.put("combat", client.getLocalPlayer().getCombatLevel());
		}
		Map<Integer, String> names = new TreeMap<>();
		for (Map<Integer, Integer> container : java.util.Arrays.asList(bank == null ? snapshot.bank() : bank, inv, worn))
		{
			for (int id : container.keySet())
			{
				names.put(id, itemNames.computeIfAbsent(id, key -> client.getItemDefinition(key).getName()));
			}
		}
		snapshot.update(bank, inv, worn, levels, names, System.currentTimeMillis());
		pendingBank = null;
		pending = false;
	}

	private Map<Integer, Integer> normalize(Item[] items)
	{
		Map<Integer, Integer> normalized = new TreeMap<>();
		for (Item item : items)
		{
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			ItemComposition definition = client.getItemDefinition(item.getId());
			OwnedItemsSnapshot.addItem(normalized, item.getId(), item.getQuantity(),
				definition.getPlaceholderTemplateId() != -1, definition.getNote() != -1,
				definition.getLinkedNoteId());
		}
		return normalized;
	}

	public JsonObject getPayload(boolean includeItems)
	{
		refresh();
		return snapshot.payload(includeItems, config.enableLocalOwnedItems(),
			client.getGameState() == GameState.LOGGED_IN);
	}
}
