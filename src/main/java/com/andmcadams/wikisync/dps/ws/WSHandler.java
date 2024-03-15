package com.andmcadams.wikisync.dps.ws;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

public interface WSHandler
{

	default void onOpen(WebSocket conn, ClientHandshake handshake) {}
	default void onClose(WebSocket conn, int code, String reason, boolean remote) {}
	default void onMessage(WebSocket conn, String message) {}
	default void onError(WebSocket conn, Exception ex) {}
	default void onStart() {}

}
