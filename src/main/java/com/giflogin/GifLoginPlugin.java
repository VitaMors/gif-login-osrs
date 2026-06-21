package com.giflogin;

import com.google.inject.Provides;
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
    private static final long UPDATE_INTERVAL_MILLIS = 16L;

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private GifFrameManager frameManager;

    private ScheduledExecutorService animationExecutor;
    private ScheduledFuture<?> animationTask;
    private SpritePixels currentSprite;
    private long nextFrameAtNanos;
    private boolean decoderRunning;
    private boolean loginScreenApplied;

    @Override
    protected void startUp()
    {
        resetPlayback();
        startDecoder();
        animationExecutor = Executors.newSingleThreadScheduledExecutor(runnable ->
        {
            Thread thread = new Thread(runnable, "gif-login-screen-animator");
            thread.setDaemon(true);
            return thread;
        });
        animationTask = animationExecutor.scheduleAtFixedRate(
            () -> clientThread.invoke(this::updateLoginScreenFrame),
            0L,
            UPDATE_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS
        );
    }

    @Override
    protected void shutDown()
    {
        if (animationTask != null)
        {
            animationTask.cancel(false);
            animationTask = null;
        }
        if (animationExecutor != null)
        {
            animationExecutor.shutdownNow();
            animationExecutor = null;
        }
        frameManager.stop();
        decoderRunning = false;
        resetPlayback();

        clientThread.invoke(() ->
        {
            client.setLoginScreen(null);
            client.setShouldRenderLoginScreenFire(true);
        });
    }

    private void updateLoginScreenFrame()
    {
        GameState gameState = client.getGameState();
        if (isRestoreState(gameState))
        {
            stopAfterLogin();
            return;
        }

        if (!isLoginFlowState(gameState))
        {
            return;
        }

        startDecoder();
        long now = System.nanoTime();
        if (currentSprite != null && now < nextFrameAtNanos)
        {
            return;
        }

        GifFrameManager.DecodedFrame frame = frameManager.pollFrame();
        if (frame == null)
        {
            return;
        }

        try
        {
            currentSprite = ImageUtil.getImageSpritePixels(frame.getImage(), client);
            client.setLoginScreen(currentSprite);
            client.setShouldRenderLoginScreenFire(false);
            loginScreenApplied = true;
            nextFrameAtNanos = now + TimeUnit.MILLISECONDS.toNanos(frame.getDurationMillis());
        }
        finally
        {
            frame.release();
        }
    }

    private void startDecoder()
    {
        if (!decoderRunning)
        {
            frameManager.start();
            decoderRunning = true;
        }
    }

    private void stopAfterLogin()
    {
        if (decoderRunning)
        {
            frameManager.stop();
            decoderRunning = false;
        }
        if (loginScreenApplied)
        {
            client.setLoginScreen(null);
            client.setShouldRenderLoginScreenFire(true);
        }
        resetPlayback();
    }

    private void resetPlayback()
    {
        currentSprite = null;
        nextFrameAtNanos = 0L;
        loginScreenApplied = false;
    }

    static boolean isLoginFlowState(GameState gameState)
    {
        return gameState == GameState.LOGIN_SCREEN
            || gameState == GameState.LOGIN_SCREEN_AUTHENTICATOR
            || gameState == GameState.LOGGING_IN
            || gameState == GameState.LOADING;
    }

    static boolean isRestoreState(GameState gameState)
    {
        return gameState == GameState.LOGGED_IN || gameState == GameState.HOPPING;
    }

    @Provides
    GifLoginConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(GifLoginConfig.class);
    }
}
