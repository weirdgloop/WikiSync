package com.andmcadams.wikisync.dps;

import com.andmcadams.wikisync.dps.messages.Request;
import com.andmcadams.wikisync.dps.messages.RequestType;
import com.andmcadams.wikisync.dps.messages.response.GetPlayer;
import com.andmcadams.wikisync.dps.messages.response.OwnedItemsResponse;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;
import static org.junit.Assert.*;

public class OwnedItemsSnapshotTest
{
	private OwnedItemsSnapshot scanned()
	{
		OwnedItemsSnapshot state = new OwnedItemsSnapshot();
		state.update(Map.of(4151, 2), Map.of(4151, 1), Map.of(6585, 1),
			Map.of("attack", 75), Map.of(4151, "Abyssal whip", 6585, "Amulet of fury"), 123456);
		return state;
	}

	@Test
	public void sharesSeparateContainersWithoutAddingOwnershipOrLeakingProfileIdentifiers()
	{
		JsonObject data = scanned().payload(true, true, true);
		assertEquals(1, data.get("schemaVersion").getAsInt());
		assertEquals(2, data.getAsJsonObject("bank").get("4151").getAsInt());
		assertEquals(1, data.getAsJsonObject("inventory").get("4151").getAsInt());
		assertEquals(1, data.getAsJsonObject("equipped").get("6585").getAsInt());
		assertEquals(75, data.getAsJsonObject("levels").get("attack").getAsInt());
		assertFalse(data.has("profile"));
		assertFalse(data.has("username"));
	}

	@Test
	public void metadataDoesNotContainItemsAndDisabledOrUnscannedRequestsFail()
	{
		OwnedItemsSnapshot state = scanned();
		JsonObject status = state.payload(false, true, true);
		assertTrue(status.get("ready").getAsBoolean());
		assertFalse(status.has("bank"));
		assertFalse(status.has("inventory"));
		assertFalse(status.has("names"));
		assertFalse(state.payload(false, false, true).get("ready").getAsBoolean());
		assertFalse(state.payload(false, true, false).get("ready").getAsBoolean());
		assertBlocked(state, false, true, "Enable");
		assertBlocked(state, true, false, "Log in");
		assertBlocked(new OwnedItemsSnapshot(), true, true, "Open your bank");
	}

	private void assertBlocked(OwnedItemsSnapshot state, boolean enabled, boolean loggedIn, String message)
	{
		try
		{
			state.payload(true, enabled, loggedIn);
			fail("Expected unavailable snapshot");
		}
		catch (IllegalStateException ex)
		{
			assertTrue(ex.getMessage().contains(message));
		}
	}

	@Test
	public void refreshesRevisionOnlyWhenInputsChangeAndCopiesInputs()
	{
		OwnedItemsSnapshot state = scanned();
		long revision = state.payload(false, true, true).get("revision").getAsLong();
		Map<Integer, Integer> inventory = new TreeMap<>(Map.of(4151, 1));
		state.update(null, inventory, Map.of(6585, 1), Map.of("attack", 75),
			Map.of(4151, "Abyssal whip", 6585, "Amulet of fury"), 999999);
		assertEquals(revision, state.payload(false, true, true).get("revision").getAsLong());
		inventory.put(4151, 4);
		state.update(null, inventory, Map.of(6585, 1), Map.of("attack", 75),
			Map.of(4151, "Abyssal whip", 6585, "Amulet of fury"), 999999);
		inventory.clear();
		JsonObject data = state.payload(true, true, true);
		assertEquals(revision + 1, data.get("revision").getAsLong());
		assertEquals(4, data.getAsJsonObject("inventory").get("4151").getAsInt());
		assertEquals(123456, data.get("bankScannedAt").getAsLong());
	}

	@Test
	public void resetInvalidatesSessionsAndClearsEveryContainer()
	{
		OwnedItemsSnapshot state = scanned();
		JsonObject old = state.payload(false, true, true);
		state.reset();
		JsonObject next = state.payload(false, true, true);
		assertNotEquals(old.get("sessionId"), next.get("sessionId"));
		assertTrue(next.get("revision").getAsLong() > old.get("revision").getAsLong());
		assertFalse(next.get("ready").getAsBoolean());
		assertEquals(0, next.get("bankScannedAt").getAsLong());
		assertTrue(state.bank().isEmpty());
		assertBlocked(state, true, true, "Open your bank");
		state.update(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(),
			Collections.emptyMap(), Collections.emptyMap(), 999);
		assertEquals(0, state.payload(true, true, true).getAsJsonObject("bank").size());
	}

	@Test
	public void normalizesNotesIgnoresPlaceholdersAndPreservesChargedVariants()
	{
		Map<Integer, Integer> items = new TreeMap<>();
		OwnedItemsSnapshot.addItem(items, 4152, 2, false, true, 4151);
		OwnedItemsSnapshot.addItem(items, 4151, 1, false, false, -1);
		OwnedItemsSnapshot.addItem(items, 14000, 10, true, false, -1);
		OwnedItemsSnapshot.addItem(items, 11908, 1, false, false, -1);
		OwnedItemsSnapshot.addItem(items, 11905, 1, false, false, -1);
		OwnedItemsSnapshot.addItem(items, -1, 1, false, false, -1);
		OwnedItemsSnapshot.addItem(items, 1127, 0, false, false, -1);
		assertEquals(Map.of(4151, 3, 11908, 1, 11905, 1), items);
		OwnedItemsSnapshot.addItem(items, 4151, Integer.MAX_VALUE, false, false, -1);
		assertEquals(Integer.valueOf(Integer.MAX_VALUE), items.get(4151));
	}

	@Test
	public void newResponseTypesPreserveSequenceIdsAndExistingGetPlayerWireFormat()
	{
		Gson gson = new Gson();
		Request request = gson.fromJson("{\"_wsType\":\"GetOwnedItems\",\"sequenceId\":7}", Request.class);
		assertEquals(RequestType.GetOwnedItems, request.get_wsType());
		OwnedItemsResponse response = new OwnedItemsResponse(request.get_wsType(), request.getSequenceId(), scanned().payload(true, true, true), null);
		JsonObject json = gson.toJsonTree(response).getAsJsonObject();
		assertEquals("GetOwnedItems", json.get("_wsType").getAsString());
		assertEquals(7, json.get("sequenceId").getAsInt());
		assertFalse(json.has("error"));
		JsonObject failed = gson.toJsonTree(new OwnedItemsResponse(RequestType.GetOwnedItems, 8, null, "Open your bank")).getAsJsonObject();
		assertEquals(8, failed.get("sequenceId").getAsInt());
		assertTrue(failed.has("error"));
		assertFalse(failed.has("payload"));
		assertEquals("{\"_wsType\":\"GetPlayer\",\"sequenceId\":9,\"payload\":{}}", gson.toJson(new GetPlayer(9, new JsonObject())));
	}
}
