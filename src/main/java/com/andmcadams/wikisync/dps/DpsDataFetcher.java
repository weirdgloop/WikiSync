package com.andmcadams.wikisync.dps;

import com.andmcadams.wikisync.dps.messages.response.UsernameChanged;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Objects;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemID;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.Varbits;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DpsDataFetcher
{

	private final Client client;
	private final EventBus eventBus;

	@Getter
	private String username;

	@Subscribe
	public void onGameTick(GameTick e)
	{
		checkUsername();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		checkUsername();
	}

	private void checkUsername()
	{
		String currentName = null;
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			Player p = client.getLocalPlayer();
			if (p != null)
			{
				currentName = p.getName();
			}
		}

		if (!Objects.equals(this.username, currentName))
		{
			log.debug("WS player name changed prev=[{}] next=[{}]", this.username, currentName);
			this.username = currentName;
			eventBus.post(new UsernameChanged(this.username));
		}
	}

	// TODO: Delete this once the Wiki plugin service exists. See https://github.com/runelite/runelite/pull/17524
	// This is directly copied from https://github.com/runelite/runelite/pull/17524/files#diff-141a15aba5d017de9818b5d39722f85f95b330ef96f8eb06103a947c1094b905
	@Nullable
	private JsonObject createEquipmentObject(ItemContainer itemContainer, EquipmentInventorySlot slot)
	{
		if (itemContainer == null)
		{
			return null;
		}

		if (slot == EquipmentInventorySlot.BOOTS && itemContainer.count() == 1 && itemContainer.contains(ItemID.CHEFS_HAT))
		{
			JsonObject o = new JsonObject();
			o.addProperty("id", ItemID.SNAIL_SHELL);
			return o;
		}

		Item item = itemContainer.getItem(slot.getSlotIdx());
		if (item != null)
		{
			JsonObject o = new JsonObject();
			o.addProperty("id", item.getId());
			return o;
		}
		return null;
	}

	// TODO: Delete this once the Wiki plugin service exists. See https://github.com/runelite/runelite/pull/17524
	// This is directly copied from https://github.com/runelite/runelite/pull/17524/files#diff-141a15aba5d017de9818b5d39722f85f95b330ef96f8eb06103a947c1094b905
	public JsonObject buildShortlinkData()
	{
		JsonObject j = new JsonObject();

		// Build the player's loadout data
		JsonArray loadouts = new JsonArray();
		ItemContainer eqContainer = client.getItemContainer(InventoryID.EQUIPMENT);

		JsonObject l = new JsonObject();
		JsonObject eq = new JsonObject();

		eq.add("ammo", createEquipmentObject(eqContainer, EquipmentInventorySlot.AMMO));
		eq.add("body", createEquipmentObject(eqContainer, EquipmentInventorySlot.BODY));
		eq.add("cape", createEquipmentObject(eqContainer, EquipmentInventorySlot.CAPE));
		eq.add("feet", createEquipmentObject(eqContainer, EquipmentInventorySlot.BOOTS));
		eq.add("hands", createEquipmentObject(eqContainer, EquipmentInventorySlot.GLOVES));
		eq.add("head", createEquipmentObject(eqContainer, EquipmentInventorySlot.HEAD));
		eq.add("legs", createEquipmentObject(eqContainer, EquipmentInventorySlot.LEGS));
		eq.add("neck", createEquipmentObject(eqContainer, EquipmentInventorySlot.AMULET));
		eq.add("ring", createEquipmentObject(eqContainer, EquipmentInventorySlot.RING));
		eq.add("shield", createEquipmentObject(eqContainer, EquipmentInventorySlot.SHIELD));
		eq.add("weapon", createEquipmentObject(eqContainer, EquipmentInventorySlot.WEAPON));
		l.add("equipment", eq);

		JsonObject skills = new JsonObject();
		skills.addProperty("atk", client.getRealSkillLevel(Skill.ATTACK));
		skills.addProperty("def", client.getRealSkillLevel(Skill.DEFENCE));
		skills.addProperty("hp", client.getRealSkillLevel(Skill.HITPOINTS));
		skills.addProperty("magic", client.getRealSkillLevel(Skill.MAGIC));
		skills.addProperty("mining", client.getRealSkillLevel(Skill.MINING));
		skills.addProperty("prayer", client.getRealSkillLevel(Skill.PRAYER));
		skills.addProperty("ranged", client.getRealSkillLevel(Skill.RANGED));
		skills.addProperty("str", client.getRealSkillLevel(Skill.STRENGTH));
		l.add("skills", skills);

		JsonObject buffs = new JsonObject();
		buffs.addProperty("inWilderness", client.getVarbitValue(Varbits.IN_WILDERNESS) == 1);
		buffs.addProperty("kandarinDiary", client.getVarbitValue(Varbits.DIARY_KANDARIN_HARD) == 1);
		buffs.addProperty("onSlayerTask", client.getVarpValue(VarPlayer.SLAYER_TASK_SIZE) > 0);
		buffs.addProperty("chargeSpell", client.getVarpValue(VarPlayer.CHARGE_GOD_SPELL) > 0);
		l.add("buffs", buffs);

		l.addProperty("name", client.getLocalPlayer().getName());

		loadouts.add(l);
		j.add("loadouts", loadouts);

		return j;
	}

}
