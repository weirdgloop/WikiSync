package com.andmcadams.wikisync.dps;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

final class OwnedItemsSnapshot
{
	private String sessionId = UUID.randomUUID().toString();
	private long revision;
	private long bankScannedAt;
	private Map<Integer, Integer> bank = Collections.emptyMap();
	private Map<Integer, Integer> inventory = Collections.emptyMap();
	private Map<Integer, Integer> equipped = Collections.emptyMap();
	private Map<String, Integer> levels = Collections.emptyMap();
	private Map<Integer, String> names = Collections.emptyMap();

	void reset()
	{
		sessionId = UUID.randomUUID().toString();
		revision++;
		bankScannedAt = 0;
		bank = inventory = equipped = Collections.emptyMap();
		levels = Collections.emptyMap();
		names = Collections.emptyMap();
	}

	Map<Integer, Integer> bank()
	{
		return Collections.unmodifiableMap(bank);
	}

	void update(Map<Integer, Integer> newBank, Map<Integer, Integer> newInventory,
		Map<Integer, Integer> newEquipped, Map<String, Integer> newLevels,
		Map<Integer, String> newNames, long scannedAt)
	{
		boolean freshBank = newBank != null && (bankScannedAt == 0 || !bank.equals(newBank));
		boolean changed = freshBank || !inventory.equals(newInventory) || !equipped.equals(newEquipped)
			|| !levels.equals(newLevels) || !names.equals(newNames);
		if (!changed)
		{
			return;
		}
		if (freshBank)
		{
			bank = new TreeMap<>(newBank);
			bankScannedAt = scannedAt;
		}
		inventory = new TreeMap<>(newInventory);
		equipped = new TreeMap<>(newEquipped);
		levels = new TreeMap<>(newLevels);
		names = new TreeMap<>(newNames);
		revision++;
	}

	JsonObject payload(boolean includeItems, boolean enabled, boolean loggedIn)
	{
		boolean ready = enabled && loggedIn && bankScannedAt > 0;
		if (includeItems && !ready)
		{
			throw new IllegalStateException(!enabled ? "Enable local owned-item sharing in WikiSync settings."
				: !loggedIn ? "Log in to RuneLite before requesting owned items."
				: "Open your bank after enabling sharing, logging in or hopping, then try again.");
		}
		JsonObject result = new JsonObject();
		result.addProperty("schemaVersion", 1);
		result.addProperty("sessionId", sessionId);
		result.addProperty("revision", revision);
		result.addProperty("enabled", enabled);
		result.addProperty("loggedIn", loggedIn);
		result.addProperty("ready", ready);
		result.addProperty("bankScannedAt", ready ? bankScannedAt : 0);
		if (includeItems)
		{
			result.add("bank", integers(bank));
			result.add("inventory", integers(inventory));
			result.add("equipped", integers(equipped));
			result.add("levels", integers(levels));
			JsonObject itemNames = new JsonObject();
			names.forEach((id, name) -> itemNames.addProperty(id.toString(), name));
			result.add("names", itemNames);
		}
		return result;
	}

	private static JsonObject integers(Map<?, Integer> values)
	{
		JsonObject result = new JsonObject();
		values.forEach((key, value) -> result.addProperty(key.toString(), value));
		return result;
	}

	static void addItem(Map<Integer, Integer> items, int id, int quantity,
		boolean placeholder, boolean noted, int unnotedId)
	{
		if (id <= 0 || quantity <= 0 || placeholder)
		{
			return;
		}
		int normalized = noted ? unnotedId : id;
		if (normalized > 0)
		{
			items.merge(normalized, quantity, (a, b) -> (int) Math.min(Integer.MAX_VALUE, (long) a + b));
		}
	}
}
