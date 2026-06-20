package com.giflogin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("giflogin")
public interface GifLoginConfig extends Config
{
    @ConfigItem(
        keyName = "gifPath",
        name = "GIF path",
        description = "Path to the GIF used as the login screen background"
    )
    default String gifPath()
    {
        return System.getProperty("user.home") + "/.runelite/login.gif";
    }

    @Range(min = 1, max = 60)
    @ConfigItem(
        keyName = "fps",
        name = "Fallback FPS",
        description = "Playback speed used only when a GIF frame has no timing metadata"
    )
    default int fps()
    {
        return 20;
    }

    @ConfigItem(
        keyName = "stretch",
        name = "Stretch",
        description = "Scale the GIF to fill the RuneLite login background"
    )
    default boolean stretch()
    {
        return true;
    }
}
