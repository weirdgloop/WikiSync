package com.andmcadams.wikisync;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlayerData
{
    Map<Integer, Integer> varb = new HashMap<>();
    Map<Integer, Integer> varp = new HashMap<>();
    Map<String, Integer> level = new HashMap<>();
    Integer collectionLogItemCount = null;
    String collectionLogSlots = "";

    public boolean isEmpty()
    {
        return varb.isEmpty() && varp.isEmpty() && level.isEmpty() && collectionLogSlots.isEmpty() && collectionLogItemCount == null;
    }

    public void clearCollectionLog()
    {
        collectionLogSlots = "";
        collectionLogItemCount = null;
    }
}