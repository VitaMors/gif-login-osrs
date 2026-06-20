package com.giflogin;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;
import net.runelite.client.plugins.Plugin;

public class GifLoginPluginTest
{
    public static void main(String[] args) throws Exception
    {
        Class<? extends Plugin> pluginClass = Class
            .forName("com.giflogin.GifLoginPlugin")
            .asSubclass(Plugin.class);

        ExternalPluginManager.loadBuiltin(pluginClass);
        RuneLite.main(args);
    }
}
