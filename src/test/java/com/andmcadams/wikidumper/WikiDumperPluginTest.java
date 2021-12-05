package com.andmcadams.wikidumper;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class WikiDumperPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(WikiDumperPlugin.class);
		RuneLite.main(args);
	}
}