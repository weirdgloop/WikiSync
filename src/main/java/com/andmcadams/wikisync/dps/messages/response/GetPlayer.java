package com.andmcadams.wikisync.dps.messages.response;

import com.andmcadams.wikisync.dps.messages.RequestType;
import com.google.gson.JsonObject;
import lombok.Value;

@Value
public class GetPlayer
{
	RequestType _wsType = RequestType.GetPlayer;
	int sequenceId;
	JsonObject payload;
}
