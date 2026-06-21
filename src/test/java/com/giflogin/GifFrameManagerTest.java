package com.giflogin;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import net.runelite.api.GameState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class GifFrameManagerTest
{
    private static final String GIF_METADATA = "javax_imageio_gif_image_1.0";

    @Test
    public void streamsSixMinuteGifWithBoundedBuffer() throws Exception
    {
        byte[] gif = createGif(360, 100);
        GifFrameManager manager = new GifFrameManager(
            new GifLoginConfig() { },
            () -> ImageIO.createImageInputStream(new ByteArrayInputStream(gif)),
            16,
            9
        );

        long totalDuration = 0L;
        int frames = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20L);
        manager.start();
        try
        {
            while (frames < 360 && System.nanoTime() < deadline)
            {
                assertTrue(manager.getBufferedFrameCount() <= GifFrameManager.BUFFER_CAPACITY);
                GifFrameManager.DecodedFrame frame = manager.pollFrame();
                if (frame == null)
                {
                    Thread.sleep(1L);
                    continue;
                }

                totalDuration += frame.getDurationMillis();
                frames++;
                frame.release();
            }
        }
        finally
        {
            manager.stop();
        }

        assertEquals(360, frames);
        assertEquals(TimeUnit.MINUTES.toMillis(6L), totalDuration);
    }

    @Test
    public void decoderStartupDoesNotWaitForInput() throws Exception
    {
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        GifFrameManager manager = new GifFrameManager(
            new GifLoginConfig() { },
            () ->
            {
                providerEntered.countDown();
                try
                {
                    releaseProvider.await();
                }
                catch (InterruptedException ex)
                {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted", ex);
                }
                return ImageIO.createImageInputStream(new ByteArrayInputStream(createGif(1, 1)));
            },
            16,
            9
        );

        long startedAt = System.nanoTime();
        manager.start();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        try
        {
            assertTrue("Decoder start blocked for " + elapsedMillis + "ms", elapsedMillis < 250L);
            assertTrue(providerEntered.await(2L, TimeUnit.SECONDS));
        }
        finally
        {
            releaseProvider.countDown();
            manager.stop();
        }
    }

    @Test
    public void stretchAndCoverUseDifferentScalingModes()
    {
        BufferedImage source = new BufferedImage(100, 50, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < source.getHeight(); y++)
        {
            for (int x = 0; x < source.getWidth(); x++)
            {
                source.setRGB(x, y, x < 5 ? Color.RED.getRGB() : Color.BLUE.getRGB());
            }
        }

        BufferedImage stretched = GifFrameManager.toLoginSize(source, true);
        BufferedImage covered = GifFrameManager.toLoginSize(source, false);
        try
        {
            assertEquals(Color.RED.getRGB(), stretched.getRGB(0, GifFrameManager.LOGIN_HEIGHT / 2));
            assertEquals(Color.BLUE.getRGB(), covered.getRGB(0, GifFrameManager.LOGIN_HEIGHT / 2));
        }
        finally
        {
            stretched.flush();
            covered.flush();
            source.flush();
        }
    }

    @Test
    public void preservesGifForEntireLoginFlowAndRestoresAfterward()
    {
        assertTrue(GifLoginPlugin.isLoginFlowState(GameState.LOGIN_SCREEN));
        assertTrue(GifLoginPlugin.isLoginFlowState(GameState.LOGIN_SCREEN_AUTHENTICATOR));
        assertTrue(GifLoginPlugin.isLoginFlowState(GameState.LOGGING_IN));
        assertTrue(GifLoginPlugin.isLoginFlowState(GameState.LOADING));
        assertFalse(GifLoginPlugin.isRestoreState(GameState.LOGGING_IN));
        assertFalse(GifLoginPlugin.isRestoreState(GameState.LOADING));
        assertTrue(GifLoginPlugin.isRestoreState(GameState.LOGGED_IN));
        assertTrue(GifLoginPlugin.isRestoreState(GameState.HOPPING));
    }

    private static byte[] createGif(int frameCount, int delayHundredths) throws IOException
    {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("gif");
        assertTrue(writers.hasNext());
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes))
        {
            writer.setOutput(output);
            writer.prepareWriteSequence(null);
            for (int i = 0; i < frameCount; i++)
            {
                BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
                image.setRGB(0, 0, (i & 1) == 0 ? Color.RED.getRGB() : Color.BLUE.getRGB());
                IIOMetadata metadata = writer.getDefaultImageMetadata(new ImageTypeSpecifier(image), null);
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(GIF_METADATA);
                IIOMetadataNode control = findNode(root, "GraphicControlExtension");
                assertNotNull(control);
                control.setAttribute("disposalMethod", "none");
                control.setAttribute("userInputFlag", "FALSE");
                control.setAttribute("transparentColorFlag", "FALSE");
                control.setAttribute("delayTime", Integer.toString(delayHundredths));
                control.setAttribute("transparentColorIndex", "0");
                metadata.setFromTree(GIF_METADATA, root);
                writer.writeToSequence(new IIOImage(image, null, metadata), null);
                image.flush();
            }
            writer.endWriteSequence();
        }
        finally
        {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static IIOMetadataNode findNode(IIOMetadataNode root, String name)
    {
        for (int i = 0; i < root.getLength(); i++)
        {
            if (name.equals(root.item(i).getNodeName()))
            {
                return (IIOMetadataNode) root.item(i);
            }
        }
        return null;
    }
}
