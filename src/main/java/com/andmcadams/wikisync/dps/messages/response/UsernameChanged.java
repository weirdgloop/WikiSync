package com.andmcadams.wikisync.dps.messages.response;

import com.andmcadams.wikisync.dps.messages.RequestType;
import lombok.Value;

@Value
public class UsernameChanged
{

	RequestType _wsType = RequestType.UsernameChanged;
	String username;

}
