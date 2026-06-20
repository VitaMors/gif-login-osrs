package com.giflogin;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.inject.Inject;
import net.runelite.client.RuneLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

class GifFrameManager
{
    private static final Logger log = LoggerFactory.getLogger(GifFrameManager.class);
    private static final int LOGIN_WIDTH = 1536;
    private static final int LOGIN_HEIGHT = 864;
    private static final String GIF_STREAM_METADATA = "javax_imageio_gif_stream_1.0";
    private static final String GIF_IMAGE_METADATA = "javax_imageio_gif_image_1.0";
    private static final File GIF_DIRECTORY = new File(RuneLite.RUNELITE_DIR, "gif-login");
    private static final File GIF_FILE = new File(GIF_DIRECTORY, "login.gif");

    private final GifLoginConfig config;
    private final List<BufferedImage> frames = new ArrayList<>();
    private long[] frameStartTimes = new long[0];
    private long totalDuration;

    @Inject
    GifFrameManager(GifLoginConfig config)
    {
        this.config = config;
    }

    void load()
    {
        clear();

        if (!GIF_DIRECTORY.exists() && !GIF_DIRECTORY.mkdirs())
        {
            log.warn("Unable to create GIF Login Screen directory: {}", GIF_DIRECTORY);
            return;
        }

        File file = GIF_FILE;
        if (!file.isFile())
        {
            log.warn("GIF login background not found: {}", file);
            return;
        }

        try (ImageInputStream stream = ImageIO.createImageInputStream(file))
        {
            if (stream == null)
            {
                log.warn("Unable to open GIF login background: {}", file);
                return;
            }

            ImageReader reader = getGifReader();
            if (reader == null)
            {
                log.warn("No GIF ImageIO reader is available");
                return;
            }

            try
            {
                reader.setInput(stream, false, false);
                int imageCount = reader.getNumImages(true);
                List<Long> durations = new ArrayList<>(imageCount);
                int[] logicalScreen = getLogicalScreenSize(reader);
                BufferedImage canvas = new BufferedImage(logicalScreen[0], logicalScreen[1], BufferedImage.TYPE_INT_ARGB);
                Graphics2D canvasGraphics = canvas.createGraphics();
                canvasGraphics.setComposite(AlphaComposite.Src);
                canvasGraphics.setColor(Color.BLACK);
                canvasGraphics.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

                for (int i = 0; i < imageCount; i++)
                {
                    IIOImage image = reader.readAll(i, null);
                    BufferedImage rawFrame = (BufferedImage) image.getRenderedImage();
                    FrameMetadata metadata = getFrameMetadata(image.getMetadata());
                    BufferedImage beforeFrame = copy(canvas, BufferedImage.TYPE_INT_ARGB);

                    canvasGraphics.setComposite(AlphaComposite.SrcOver);
                    canvasGraphics.drawImage(rawFrame, metadata.left, metadata.top, null);
                    frames.add(toLoginSize(copy(canvas, BufferedImage.TYPE_INT_RGB)));
                    durations.add(metadata.duration);

                    applyDisposal(canvasGraphics, canvas, beforeFrame, metadata);
                }

                canvasGraphics.dispose();
                buildTimeline(durations);

                log.info(
                    "Loaded {} GIF login background frames from {} as {}x{} over {}ms",
                    frames.size(),
                    file,
                    LOGIN_WIDTH,
                    LOGIN_HEIGHT,
                    totalDuration
                );
            }
            finally
            {
                reader.dispose();
            }
        }
        catch (IOException ex)
        {
            log.warn("Failed to load GIF login background: {}", file, ex);
        }
    }

    BufferedImage getFrame(int index)
    {
        if (index < 0 || index >= frames.size())
        {
            return null;
        }

        return frames.get(index);
    }

    int getCurrentFrameIndex()
    {
        if (frames.isEmpty())
        {
            return -1;
        }

        if (totalDuration <= 0L || frameStartTimes.length != frames.size())
        {
            int fps = Math.max(1, config.fps());
            long frameDuration = 1000L / fps;
            return (int) ((System.currentTimeMillis() / frameDuration) % frames.size());
        }

        long playbackTime = System.currentTimeMillis() % totalDuration;
        int index = Arrays.binarySearch(frameStartTimes, playbackTime);
        if (index >= 0)
        {
            return index;
        }

        return Math.max(0, -index - 2);
    }

    void clear()
    {
        frames.clear();
        frameStartTimes = new long[0];
        totalDuration = 0L;
    }

    List<BufferedImage> getFrames()
    {
        return Collections.unmodifiableList(frames);
    }

    private static ImageReader getGifReader()
    {
        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
        return readers.hasNext() ? readers.next() : null;
    }

    private static int[] getLogicalScreenSize(ImageReader reader) throws IOException
    {
        IIOMetadata streamMetadata = reader.getStreamMetadata();
        if (streamMetadata != null)
        {
            Node root = streamMetadata.getAsTree(GIF_STREAM_METADATA);
            Node descriptor = findChild(root, "LogicalScreenDescriptor");
            if (descriptor != null)
            {
                int width = getIntAttribute(descriptor, "logicalScreenWidth", LOGIN_WIDTH);
                int height = getIntAttribute(descriptor, "logicalScreenHeight", LOGIN_HEIGHT);
                return new int[] {Math.max(1, width), Math.max(1, height)};
            }
        }

        BufferedImage firstFrame = reader.read(0);
        return new int[] {firstFrame.getWidth(), firstFrame.getHeight()};
    }

    private static FrameMetadata getFrameMetadata(IIOMetadata metadata)
    {
        Node root = metadata.getAsTree(GIF_IMAGE_METADATA);
        Node descriptor = findChild(root, "ImageDescriptor");
        Node control = findChild(root, "GraphicControlExtension");

        int left = descriptor == null ? 0 : getIntAttribute(descriptor, "imageLeftPosition", 0);
        int top = descriptor == null ? 0 : getIntAttribute(descriptor, "imageTopPosition", 0);
        String disposalMethod = control == null ? "none" : getStringAttribute(control, "disposalMethod", "none");
        int delayHundredths = control == null ? 0 : getIntAttribute(control, "delayTime", 0);
        long duration = delayHundredths > 0 ? delayHundredths * 10L : 1000L / 20L;

        return new FrameMetadata(left, top, disposalMethod, Math.max(20L, duration));
    }

    private static void applyDisposal(
        Graphics2D graphics,
        BufferedImage canvas,
        BufferedImage beforeFrame,
        FrameMetadata metadata
    )
    {
        if ("restoreToBackgroundColor".equals(metadata.disposalMethod))
        {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
        }
        else if ("restoreToPrevious".equals(metadata.disposalMethod))
        {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(beforeFrame, 0, 0, null);
        }
    }

    private BufferedImage toLoginSize(BufferedImage source)
    {
        BufferedImage resized = new BufferedImage(LOGIN_WIDTH, LOGIN_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, LOGIN_WIDTH, LOGIN_HEIGHT);
        drawCover(graphics, source);
        graphics.dispose();
        return resized;
    }

    private static void drawCover(Graphics2D graphics, BufferedImage source)
    {
        double scale = Math.max(
            (double) LOGIN_WIDTH / source.getWidth(),
            (double) LOGIN_HEIGHT / source.getHeight()
        );
        int width = (int) Math.round(source.getWidth() * scale);
        int height = (int) Math.round(source.getHeight() * scale);
        int x = (LOGIN_WIDTH - width) / 2;
        int y = (LOGIN_HEIGHT - height) / 2;

        graphics.drawImage(source, x, y, width, height, null);
    }

    private static BufferedImage copy(BufferedImage source, int imageType)
    {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), imageType);
        Graphics2D graphics = copy.createGraphics();
        graphics.setComposite(AlphaComposite.Src);
        graphics.drawImage(source, 0, 0, null);
        graphics.dispose();
        return copy;
    }

    private static Node findChild(Node node, String name)
    {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling())
        {
            if (name.equals(child.getNodeName()))
            {
                return child;
            }
        }

        return null;
    }

    private static int getIntAttribute(Node node, String name, int fallback)
    {
        String value = getStringAttribute(node, name, null);
        if (value == null)
        {
            return fallback;
        }

        try
        {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException ex)
        {
            return fallback;
        }
    }

    private static String getStringAttribute(Node node, String name, String fallback)
    {
        NamedNodeMap attributes = node.getAttributes();
        if (attributes == null)
        {
            return fallback;
        }

        Node attribute = attributes.getNamedItem(name);
        return attribute == null ? fallback : attribute.getNodeValue();
    }

    private void buildTimeline(List<Long> durations)
    {
        frameStartTimes = new long[durations.size()];
        totalDuration = 0L;

        for (int i = 0; i < durations.size(); i++)
        {
            frameStartTimes[i] = totalDuration;
            totalDuration += Math.max(20L, durations.get(i));
        }
    }

    private static class FrameMetadata
    {
        private final int left;
        private final int top;
        private final String disposalMethod;
        private final long duration;

        private FrameMetadata(int left, int top, String disposalMethod, long duration)
        {
            this.left = left;
            this.top = top;
            this.disposalMethod = disposalMethod;
            this.duration = duration;
        }
    }
}
