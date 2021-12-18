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

import com.google.gson.*;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.RuneScapeProfileType;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;

@Slf4j
@Singleton
public class DataManager
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private Gson gson;

	@Inject
	private WikiSyncPlugin plugin;

	private final HashMap<Integer, Integer> varbData = new HashMap<>();
	private final HashMap<Integer, Integer> varpData = new HashMap<>();
	private final HashMap<String, Integer> levelData = new HashMap<>();

	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final String MANIFEST_ENDPOINT = "https://sync.runescape.wiki/runelite/manifest";
	private static final String POST_ENDPOINT = "https://sync.runescape.wiki/runelite/submit";


	public void storeVarbitChanged(int varbIndex, int varbValue)
	{
		log.info("Stored varb with index " + varbIndex + " and value " + varbValue);
		synchronized (this)
		{
			varbData.put(varbIndex, varbValue);
		}
	}

	public void storeVarpChanged(int varpIndex, int varpValue)
	{
		log.info("Stored varp with index " + varpIndex + " and value " + varpValue);
		synchronized (this)
		{
			varpData.put(varpIndex, varpValue);
		}
	}

	public void storeSkillChanged(String skill, int skillLevel)
	{
		log.info("Stored skill " + skill + " with level " + skillLevel);
		synchronized (this)
		{
			levelData.put(skill, skillLevel);
		}
	}

	private <K, V> HashMap<K, V> clearChanges(HashMap<K, V> h)
	{
		HashMap<K, V> temp;
		synchronized (this)
		{
			if (h.isEmpty())
			{
				return new HashMap<>();
			}
			temp = new HashMap<>(h);
			h.clear();
		}
		return temp;
	}

	private boolean hasDataToPush()
	{
		return !(varbData.isEmpty() && varpData.isEmpty());
	}

	private String convertToJson()
	{
		HashMap<Integer, Integer> tempVarbData = clearChanges(varbData);
		HashMap<Integer, Integer> tempVarpData = clearChanges(varpData);
		HashMap<String, Integer> tempLevelData = clearChanges(levelData);

		JsonObject j = new JsonObject();
		j.add("varb", gson.toJsonTree(tempVarbData));
		j.add("varp", gson.toJsonTree(tempVarpData));
		j.add("level", gson.toJsonTree(tempLevelData));

		JsonObject parent = new JsonObject();
		parent.addProperty("username", client.getLocalPlayer().getName());
		parent.addProperty("profile", RuneScapeProfileType.getCurrent(client).name());
		parent.add("data", j);
		log.info(parent.toString());

		return parent.toString();
	}

	protected void submitToAPI()
	{
		if (!hasDataToPush() || client.getLocalPlayer() == null || client.getLocalPlayer().getName() == null)
			return;

		if (RuneScapeProfileType.getCurrent(client) == RuneScapeProfileType.BETA)
			return;

		log.info("Submitting changed data to endpoint");
		Request r = new Request.Builder()
			.url(POST_ENDPOINT)
			.post(RequestBody.create(JSON, convertToJson()))
			.build();

		okHttpClient.newCall(r).enqueue(new Callback()
		{
			@Override
			public void onFailure(@NonNull Call call, @NonNull IOException e)
			{
				log.debug("Error sending changed data", e);
			}

			@Override
			public void onResponse(@NonNull Call call, @NonNull Response response)
			{
				log.debug("Successfully sent changed data");
				response.close();
			}
		});
	}

	private HashSet<Integer> parseSet(JsonArray j)
	{
		HashSet<Integer> h = new HashSet<>();
		for (JsonElement jObj : j)
		{
			h.add(jObj.getAsInt());
		}
		return h;
	}

	protected void getManifest()
	{
		try
		{
			Request r = new Request.Builder()
				.url(MANIFEST_ENDPOINT)
				.build();
			okHttpClient.newCall(r).enqueue(new Callback()
			{
				@Override
				public void onFailure(@NonNull Call call, @NonNull IOException e)
				{
					log.debug("Error retrieving manifest", e);
				}

				@Override
				public void onResponse(@NonNull Call call, @NonNull Response response)
				{
					if (response.isSuccessful())
					{
						try
						{
							// We want to be able to change the varbs and varps we get on the fly. To do so, we tell
							// the client what to send the server on startup via the manifest.

							if (response.body() == null)
							{
								log.error("Manifest request succeeded but returned empty body");
								response.close();
								return;
							}
							JsonObject j = new Gson().fromJson(response.body().string(), JsonObject.class);
							try
							{
								plugin.setVarbitsToCheck(parseSet(j.getAsJsonArray("varbits")));
								plugin.setVarpsToCheck(parseSet(j.getAsJsonArray("varps")));
								plugin.setManifestSuccess(true);
								// This will not actually push anything if the player is not logged in.
								// If the player is logged in, this ensures that we run after grabbing the manifest.
								clientThread.invoke(() -> {
									if (client != null && client.getGameState() != null)
										plugin.handleInitialDump(client.getGameState());
									return true;
								});
							}
							catch (NullPointerException e) {
								// This is probably an issue with the server. "varbits" or "varps" might be missing.
								log.error("Manifest possibly missing varbits or varps entry from /manifest call");
								log.error(e.getLocalizedMessage());
							}
							catch (ClassCastException e) {
								// This is probably an issue with the server. "varbits" or "varps" might be not be a list.
								log.error("Manifest from /manifest call might have varbits or varps as not a list");
								log.error(e.getLocalizedMessage());
							}
						}
						catch (IOException | JsonSyntaxException e)
						{
							log.error(e.getLocalizedMessage());
						}
					}
					else
					{
						log.error("Manifest request returned with status " + response.code());
						if (response.body() == null)
						{
							log.error("Manifest request returned empty body");
						}
						else
						{
							log.error(response.body().toString());
						}
					}
					response.close();
				}
			});
		}
		catch (IllegalArgumentException e)
		{
			log.error("Bad URL given: " + e.getLocalizedMessage());
		}
	}
}
