package com.giflogin;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.SpritePixels;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.ImageUtil;

@PluginDescriptor(
    name = "GIF Login Screen",
    description = "Plays an animated GIF behind the OSRS login UI",
    tags = {"login", "gif", "animated", "background"}
)
public class GifLoginPlugin extends Plugin
{
    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private GifFrameManager frameManager;

    @Inject
    private GifLoginConfig config;

    private final ScheduledExecutorService animationExecutor = Executors.newScheduledThreadPool(2, runnable ->
    {
        Thread thread = new Thread(runnable, "gif-login-screen-animator");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Integer, SpritePixels> spriteCache = new HashMap<>();
    private ScheduledFuture<?> animationTask;
    private boolean loginScreenApplied;
    private int lastFrameIndex = -1;

    @Override
    protected void startUp()
    {
        frameManager.load();
        loginScreenApplied = false;
        lastFrameIndex = -1;
        startAnimation();
    }

    @Override
    protected void shutDown()
    {
        stopAnimation();
        frameManager.clear();
        spriteCache.clear();
        loginScreenApplied = false;
        lastFrameIndex = -1;

        clientThread.invoke(() ->
        {
            client.setLoginScreen(null);
            client.setShouldRenderLoginScreenFire(true);
        });
    }

    private void startAnimation()
    {
        long frameDuration = Math.max(16L, 1000L / Math.max(1, config.fps()));
        animationTask = animationExecutor.scheduleAtFixedRate(
            () -> clientThread.invoke(this::updateLoginScreenFrame),
            0L,
            frameDuration,
            TimeUnit.MILLISECONDS
        );
    }

    private void stopAnimation()
    {
        if (animationTask != null)
        {
            animationTask.cancel(false);
            animationTask = null;
        }

    }

    private void updateLoginScreenFrame()
    {
        GameState gameState = client.getGameState();
        if (gameState != GameState.LOGIN_SCREEN && gameState != GameState.LOGIN_SCREEN_AUTHENTICATOR)
        {
            if (loginScreenApplied)
            {
                client.setLoginScreen(null);
                client.setShouldRenderLoginScreenFire(true);
                loginScreenApplied = false;
            }
            return;
        }

        int frameIndex = frameManager.getCurrentFrameIndex();
        if (frameIndex == -1 || frameIndex == lastFrameIndex)
        {
            return;
        }

        SpritePixels sprite = spriteCache.get(frameIndex);
        if (sprite == null)
        {
            BufferedImage frame = frameManager.getFrame(frameIndex);
            if (frame == null)
            {
                return;
            }

            sprite = ImageUtil.getImageSpritePixels(frame, client);
            spriteCache.put(frameIndex, sprite);
        }

        client.setLoginScreen(sprite);
        client.setShouldRenderLoginScreenFire(false);
        loginScreenApplied = true;
        lastFrameIndex = frameIndex;
    }

    @Provides
    GifLoginConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(GifLoginConfig.class);
    }
}
