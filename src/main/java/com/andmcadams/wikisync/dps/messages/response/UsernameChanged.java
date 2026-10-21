package com.andmcadams.wikisync.dps.messages.response;

import com.andmcadams.wikisync.dps.messages.RequestType;
import java.util.Arrays;
import java.util.List;
import lombok.Value;

@Value
public class UsernameChanged
{

	RequestType _wsType = RequestType.UsernameChanged;
	String username;
	List<RequestType> capabilities = Arrays.asList(RequestType.GetPlayer,
		RequestType.GetOwnedItems, RequestType.GetOwnedItemsStatus);

}
