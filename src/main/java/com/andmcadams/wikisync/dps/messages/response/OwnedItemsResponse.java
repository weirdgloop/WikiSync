package com.andmcadams.wikisync.dps.messages.response;

import com.andmcadams.wikisync.dps.messages.RequestType;
import com.google.gson.JsonObject;
import lombok.Value;

@Value
public class OwnedItemsResponse
{
	RequestType _wsType;
	int sequenceId;
	JsonObject payload;
	String error;
}
