package com.andmcadams.wikisync.dps;

import com.andmcadams.wikisync.dps.ws.WSHandler;
import com.andmcadams.wikisync.dps.ws.WSWebsocketServer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.client.callback.ClientThread;
import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.Test;
import static org.junit.Assert.*;

public class OwnedItemsWebSocketTest
{
	@Test
	public void exchangesOwnedItemsAndErrorsWithoutChangingPlayerImport() throws Exception
	{
		OwnedItemsSnapshot snapshot = new OwnedItemsSnapshot();
		snapshot.update(Map.of(4151, 1), Collections.emptyMap(), Collections.emptyMap(),
			Map.of("attack", 75), Map.of(4151, "Abyssal whip"), 123456);
		AtomicBoolean enabled = new AtomicBoolean(true);
		OwnedItemsDataFetcher owned = new OwnedItemsDataFetcher(null, null, null, null)
		{
			@Override public JsonObject getPayload(boolean includeItems)
			{
				return snapshot.payload(includeItems, enabled.get(), true);
			}
		};
		DpsDataFetcher player = new DpsDataFetcher(null, null)
		{
			@Override public String getUsername() { return "Protocol test"; }
			@Override public JsonObject buildShortlinkData()
			{
				JsonObject data = new JsonObject();
				data.addProperty("playerFixture", true);
				return data;
			}
		};
		ClientThread clientThread = new ClientThread()
		{
			@Override public void invokeLater(Runnable task) { task.run(); }
		};
		WebSocketManager manager = new WebSocketManager(new Gson(), player, owned, clientThread);
		CountDownLatch started = new CountDownLatch(1);
		WSWebsocketServer server = new WSWebsocketServer(0, new WSHandler()
		{
			@Override public void onStart() { started.countDown(); }
			@Override public void onOpen(WebSocket connection, ClientHandshake handshake) { manager.onOpen(connection, handshake); }
			@Override public void onMessage(WebSocket connection, String message) { manager.onMessage(connection, message); }
		});
		BlockingQueue<String> messages = new LinkedBlockingQueue<>();
		WebSocketClient browser = null;
		try
		{
			server.start();
			assertTrue(started.await(3, TimeUnit.SECONDS));
			browser = new WebSocketClient(new URI("ws://127.0.0.1:" + server.getPort()),
				Map.of("Origin", "http://localhost:3000"))
			{
				@Override public void onOpen(ServerHandshake handshake) { }
				@Override public void onMessage(String message) { messages.add(message); }
				@Override public void onClose(int code, String reason, boolean remote) { }
				@Override public void onError(Exception error) { }
			};
			assertTrue(browser.connectBlocking(3, TimeUnit.SECONDS));
			JsonObject greeting = next(messages);
			assertEquals("Protocol test", greeting.get("username").getAsString());
			assertTrue(greeting.getAsJsonArray("capabilities").toString().contains("GetOwnedItems"));
			browser.send("{\"_wsType\":\"GetPlayer\",\"sequenceId\":1}");
			JsonObject old = next(messages);
			assertEquals("GetPlayer", old.get("_wsType").getAsString());
			assertTrue(old.getAsJsonObject("payload").get("playerFixture").getAsBoolean());
			browser.send("{\"_wsType\":\"GetOwnedItems\",\"sequenceId\":2}");
			JsonObject items = next(messages);
			assertEquals(2, items.get("sequenceId").getAsInt());
			assertEquals(1, items.getAsJsonObject("payload").getAsJsonObject("bank").get("4151").getAsInt());
			enabled.set(false);
			browser.send("{\"_wsType\":\"GetOwnedItems\",\"sequenceId\":3}");
			JsonObject denied = next(messages);
			assertEquals(3, denied.get("sequenceId").getAsInt());
			assertTrue(denied.get("error").getAsString().contains("Enable"));
			assertFalse(denied.has("payload"));
			browser.send("{\"_wsType\":\"GetOwnedItemsStatus\",\"sequenceId\":4}");
			JsonObject status = next(messages).getAsJsonObject("payload");
			assertFalse(status.get("enabled").getAsBoolean());
			assertFalse(status.has("bank"));
		}
		finally
		{
			if (browser != null) { browser.closeBlocking(); }
			server.stop(1000);
		}
	}

	private JsonObject next(BlockingQueue<String> messages) throws InterruptedException
	{
		String message = messages.poll(3, TimeUnit.SECONDS);
		assertNotNull("Expected WebSocket response", message);
		return new JsonParser().parse(message).getAsJsonObject();
	}
}
