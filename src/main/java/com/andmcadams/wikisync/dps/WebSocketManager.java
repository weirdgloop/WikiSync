package com.andmcadams.wikisync.dps;

import com.andmcadams.wikisync.dps.messages.response.GetPlayer;
import com.andmcadams.wikisync.dps.messages.Request;
import com.andmcadams.wikisync.dps.messages.response.UsernameChanged;
import com.andmcadams.wikisync.dps.ws.WSHandler;
import com.andmcadams.wikisync.dps.ws.WSWebsocketServer;
import com.google.common.collect.ImmutableSet;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class WebSocketManager implements WSHandler
{

	private final static int PORT_MIN = 37767;
	private final static int PORT_MAX = 37776;

	private final static Set<String> ALLOWED_ORIGIN_HOSTS = ImmutableSet.of("localhost", "dps.osrs.wiki", "tools.runescape.wiki");

	private final AtomicBoolean serverActive = new AtomicBoolean(false);

	private final Gson gson;
	private final DpsDataFetcher dpsDataFetcher;

	private int nextPort;

	private WSWebsocketServer server;

	@Inject
	private ClientThread clientThread;

	private static final ExecutorService executorService = Executors.newSingleThreadExecutor();

	public void startUp()
	{
		this.nextPort = PORT_MIN;
		// Just in case we are in a bad state, let's try to stop any active server.
		stopServer();
		ensureActive();
	}

	public void shutDown()
	{
		log.debug("Shutting down WikiSync Websocket Manager. Server active = {}", serverActive.getPlain());
		stopServer();
	}

	/**
	 * If a server is not currently running or starting, then try to start a new server. If a server is currently
	 * running or starting, then do nothing. Then method is meant to be called regularly, so it will happily do nothing
	 * on any specific run.
	 */
	public void ensureActive()
	{
		if (!serverActive.compareAndExchange(false, true))
		{
			this.server = new WSWebsocketServer(this.nextPort++, this);
			this.server.start();
			log.debug("WSWSS attempting to start at: {}", this.server.getAddress());
			if (this.nextPort > PORT_MAX) {
				this.nextPort = PORT_MIN;
			}
		}
	}

	@Subscribe
	public void onUsernameChanged(UsernameChanged e)
	{
		if (serverActive.get())
		{
			executorService.submit(()->{
				this.server.broadcast(gson.toJson(e));
			});
		}
	}

	@Override
	public void onOpen(WebSocket conn, ClientHandshake handshake)
	{
		// Validate that only trusted sources are allowed to connect. This is not foolproof, but it should catch
		// unauthorized access from any major browser.
		String requestPath = conn.getResourceDescriptor();
		String origin = handshake.getFieldValue("origin");
		log.debug("Received new WebSocket request. requestPath: {}, origin: {}", requestPath, origin);
		if (!Objects.equals(requestPath, "/")) {
			log.error("Unknown requestPath: {}", requestPath);
			conn.close();
			return;
		}
		try
		{
			URI originUri = new URI(origin);
			String host = originUri.getHost();
			if (!ALLOWED_ORIGIN_HOSTS.contains(host)) {
				log.error("Unauthorized origin: {}", host);
				conn.close();
				return;
			}
		}
		catch (URISyntaxException e)
		{
			log.error("Could not parse origin: {}", (Object) e);
			conn.close();
			return;
		}

		// This connection appears to be valid!
		conn.send(gson.toJson(new UsernameChanged(dpsDataFetcher.getUsername())));
	}

	@Override
	public void onMessage(WebSocket conn, String message)
	{
		Request request = gson.fromJson(message, Request.class);
		switch (request.get_wsType()) {
			case GetPlayer:
				clientThread.invokeLater(() -> {
					JsonObject payload = dpsDataFetcher.buildShortlinkData();
					executorService.submit(()->{
						conn.send(gson.toJson(new GetPlayer(request.getSequenceId(), payload)));
					});
				});
				break;
			default:
				log.debug("Got request with no handler.");
				break;
		}
	}


	@Override
	public void onError(WebSocket conn, Exception ex)
	{
		log.debug("ws error conn=[{}]", conn == null ? null : conn.getLocalSocketAddress(), ex);
		// `conn == null` signals the error is related to the whole server, not just a specific connection.
		if (conn == null)
		{
			log.debug("failed to bind to port, trying next");
			stopServer();
			// Immediately trying a new port is okay to do once for each port, but we do not want to continuously try to
			// spawn servers in a tight loop if something goes wrong. If the attempted ports have wrapped around back to
			// `PORT_MIN`, then we can stop attempting to start servers and wait for the next scheduled call to
			// `ensureActive`.
			if (this.nextPort != PORT_MIN)
			{
				ensureActive();
			}
		}
	}

	@Override
	public void onStart()
	{
		log.debug("Started! Port: {}", server.getPort());
	}

	private void stopServer()
	{
		try
		{
			if (this.server != null)
			{
				try
				{
					this.server.stop();
				}
				catch (InterruptedException e)
				{
					// ignored
				}
				finally
				{
					this.server = null;
				}
			}
		} finally
		{
			this.serverActive.set(false);
		}
	}
}
