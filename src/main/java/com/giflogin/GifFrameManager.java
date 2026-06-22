package com.giflogin;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
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
    static final int BUFFER_CAPACITY = 2;
    static final int LOGIN_WIDTH = 1536;
    static final int LOGIN_HEIGHT = 864;
    private static final long MAX_SOURCE_BYTES = 32L * 1024L * 1024L;
    private static final String GIF_STREAM_METADATA = "javax_imageio_gif_stream_1.0";
    private static final String GIF_IMAGE_METADATA = "javax_imageio_gif_image_1.0";
    private static final File GIF_DIRECTORY = new File(RuneLite.RUNELITE_DIR, "gif-login");
    private static final File GIF_FILE = new File(GIF_DIRECTORY, "login.gif");

    private final GifLoginConfig config;
    private final StreamProvider streamProvider;
    private final int outputWidth;
    private final int outputHeight;
    private final BlockingQueue<DecodedFrame> frameBuffer = new ArrayBlockingQueue<>(BUFFER_CAPACITY);
    private final AtomicLong generation = new AtomicLong();
    private ExecutorService decoderExecutor;
    private Future<?> decoderTask;

    @Inject
    GifFrameManager(GifLoginConfig config)
    {
        this(config, GifFrameManager::openPluginGif, LOGIN_WIDTH, LOGIN_HEIGHT);
    }

    GifFrameManager(GifLoginConfig config, StreamProvider streamProvider, int outputWidth, int outputHeight)
    {
        this.config = config;
        this.streamProvider = streamProvider;
        this.outputWidth = outputWidth;
        this.outputHeight = outputHeight;
    }

    synchronized void start()
    {
        stop();
        long activeGeneration = generation.incrementAndGet();
        decoderExecutor = Executors.newSingleThreadExecutor(runnable ->
        {
            Thread thread = new Thread(runnable, "gif-login-screen-decoder");
            thread.setDaemon(true);
            return thread;
        });
        decoderTask = decoderExecutor.submit(() -> decodeLoop(activeGeneration));
    }

    synchronized void stop()
    {
        generation.incrementAndGet();
        if (decoderTask != null)
        {
            decoderTask.cancel(false);
            decoderTask = null;
        }
        if (decoderExecutor != null)
        {
            decoderExecutor.shutdown();
            decoderExecutor = null;
        }
        clearBuffer();
    }

    DecodedFrame pollFrame()
    {
        return frameBuffer.poll();
    }

    int getBufferedFrameCount()
    {
        return frameBuffer.size();
    }

    private void decodeLoop(long activeGeneration)
    {
        int completedLoops = 0;
        while (isActive(activeGeneration))
        {
            try (ImageInputStream stream = streamProvider.open())
            {
                if (stream == null)
                {
                    log.warn("Unable to open GIF login background: {}", GIF_FILE);
                    return;
                }

                int decodedFrames = decodeStream(stream, activeGeneration);
                if (decodedFrames == 0)
                {
                    log.warn("GIF login background contains no readable frames: {}", GIF_FILE);
                    return;
                }

                if (completedLoops++ == 0)
                {
                    log.info("Streaming GIF login background from {} with at most {} prefetched frames", GIF_FILE, BUFFER_CAPACITY);
                }
            }
            catch (IOException | RuntimeException ex)
            {
                if (isActive(activeGeneration))
                {
                    log.warn("Failed to decode GIF login background: {}", GIF_FILE, ex);
                }
                return;
            }
        }
    }

    private int decodeStream(ImageInputStream stream, long activeGeneration) throws IOException
    {
        ImageReader reader = getGifReader();
        if (reader == null)
        {
            log.warn("No GIF ImageIO reader is available");
            return 0;
        }

        try
        {
            reader.setInput(stream, false, false);
            int[] logicalScreen = getLogicalScreenSize(reader);
            validateImageSize(logicalScreen[0], logicalScreen[1], "logical screen");

            BufferedImage canvas = new BufferedImage(logicalScreen[0], logicalScreen[1], BufferedImage.TYPE_INT_ARGB);
            Graphics2D canvasGraphics = canvas.createGraphics();
            try
            {
                canvasGraphics.setComposite(AlphaComposite.Src);
                canvasGraphics.setColor(Color.BLACK);
                canvasGraphics.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

                int frameIndex = 0;
                while (isActive(activeGeneration))
                {
                    IIOImage image;
                    try
                    {
                        validateImageSize(reader.getWidth(frameIndex), reader.getHeight(frameIndex), "frame " + frameIndex);
                        image = reader.readAll(frameIndex, null);
                    }
                    catch (IndexOutOfBoundsException ex)
                    {
                        break;
                    }

                    BufferedImage rawFrame = (BufferedImage) image.getRenderedImage();
                    FrameMetadata metadata = getFrameMetadata(image.getMetadata());
                    BufferedImage beforeFrame = "restoreToPrevious".equals(metadata.disposalMethod)
                        ? copy(canvas, BufferedImage.TYPE_INT_ARGB)
                        : null;

                    canvasGraphics.setComposite(AlphaComposite.SrcOver);
                    canvasGraphics.drawImage(rawFrame, metadata.left, metadata.top, null);
                    rawFrame.flush();

                    BufferedImage output = resize(canvas, config.stretch(), outputWidth, outputHeight);
                    if (!offerFrame(new DecodedFrame(output, metadata.duration), activeGeneration))
                    {
                        output.flush();
                        if (beforeFrame != null)
                        {
                            beforeFrame.flush();
                        }
                        break;
                    }

                    applyDisposal(canvasGraphics, beforeFrame, metadata);
                    if (beforeFrame != null)
                    {
                        beforeFrame.flush();
                    }
                    frameIndex++;
                }
                return frameIndex;
            }
            finally
            {
                canvasGraphics.dispose();
                canvas.flush();
            }
        }
        finally
        {
            reader.dispose();
        }
    }

    private boolean offerFrame(DecodedFrame frame, long activeGeneration)
    {
        while (isActive(activeGeneration))
        {
            if (frameBuffer.offer(frame))
            {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10L));
        }
        return false;
    }

    private boolean isActive(long activeGeneration)
    {
        return generation.get() == activeGeneration;
    }

    private void clearBuffer()
    {
        DecodedFrame frame;
        while ((frame = frameBuffer.poll()) != null)
        {
            frame.release();
        }
    }

    private static ImageInputStream openPluginGif() throws IOException
    {
        if (!GIF_DIRECTORY.exists() && !GIF_DIRECTORY.mkdirs())
        {
            throw new IOException("Unable to create GIF Login Screen directory: " + GIF_DIRECTORY);
        }
        if (!GIF_DIRECTORY.isDirectory())
        {
            throw new IOException("GIF Login Screen path is not a directory: " + GIF_DIRECTORY);
        }
        if (!GIF_FILE.isFile())
        {
            throw new IOException("GIF login background not found: " + GIF_FILE);
        }
        return ImageIO.createImageInputStream(GIF_FILE);
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
        return new int[] {reader.getWidth(0), reader.getHeight(0)};
    }

    private static void validateImageSize(int width, int height, String label) throws IOException
    {
        long estimatedBytes = (long) width * (long) height * Integer.BYTES;
        if (width <= 0 || height <= 0 || estimatedBytes > MAX_SOURCE_BYTES)
        {
            throw new IOException("GIF " + label + " exceeds the 32 MiB decoded-image safety limit: " + width + "x" + height);
        }
    }

    private long fallbackFrameDuration()
    {
        return Math.max(20L, 1000L / Math.max(1, config.fps()));
    }

    private FrameMetadata getFrameMetadata(IIOMetadata metadata)
    {
        Node root = metadata.getAsTree(GIF_IMAGE_METADATA);
        Node descriptor = findChild(root, "ImageDescriptor");
        Node control = findChild(root, "GraphicControlExtension");

        int left = descriptor == null ? 0 : getIntAttribute(descriptor, "imageLeftPosition", 0);
        int top = descriptor == null ? 0 : getIntAttribute(descriptor, "imageTopPosition", 0);
        int width = descriptor == null ? 0 : getIntAttribute(descriptor, "imageWidth", 0);
        int height = descriptor == null ? 0 : getIntAttribute(descriptor, "imageHeight", 0);
        String disposalMethod = control == null ? "none" : getStringAttribute(control, "disposalMethod", "none");
        int delayHundredths = control == null ? 0 : getIntAttribute(control, "delayTime", 0);
        long duration = delayHundredths > 0 ? delayHundredths * 10L : fallbackFrameDuration();

        return new FrameMetadata(left, top, width, height, disposalMethod, Math.max(20L, duration));
    }

    private static void applyDisposal(Graphics2D graphics, BufferedImage beforeFrame, FrameMetadata metadata)
    {
        if ("restoreToBackgroundColor".equals(metadata.disposalMethod))
        {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(metadata.left, metadata.top, metadata.width, metadata.height);
        }
        else if (beforeFrame != null)
        {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(beforeFrame, 0, 0, null);
        }
    }

    static BufferedImage toLoginSize(BufferedImage source, boolean stretch)
    {
        return resize(source, stretch, LOGIN_WIDTH, LOGIN_HEIGHT);
    }

    private static BufferedImage resize(BufferedImage source, boolean stretch, int outputWidth, int outputHeight)
    {
        BufferedImage resized = new BufferedImage(outputWidth, outputHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = resized.createGraphics();
        try
        {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.setColor(Color.BLACK);
            graphics.fillRect(0, 0, outputWidth, outputHeight);

            if (stretch)
            {
                graphics.drawImage(source, 0, 0, outputWidth, outputHeight, null);
            }
            else
            {
                drawCover(graphics, source, outputWidth, outputHeight);
            }
        }
        finally
        {
            graphics.dispose();
        }
        return resized;
    }

    private static void drawCover(Graphics2D graphics, BufferedImage source, int outputWidth, int outputHeight)
    {
        double scale = Math.max((double) outputWidth / source.getWidth(), (double) outputHeight / source.getHeight());
        int width = (int) Math.round(source.getWidth() * scale);
        int height = (int) Math.round(source.getHeight() * scale);
        int x = (outputWidth - width) / 2;
        int y = (outputHeight - height) / 2;
        graphics.drawImage(source, x, y, width, height, null);
    }

    private static BufferedImage copy(BufferedImage source, int imageType)
    {
        BufferedImage copy = new BufferedImage(source.getWidth(), source.getHeight(), imageType);
        Graphics2D graphics = copy.createGraphics();
        try
        {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(source, 0, 0, null);
        }
        finally
        {
            graphics.dispose();
        }
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

    static final class DecodedFrame
    {
        private final BufferedImage image;
        private final long durationMillis;

        private DecodedFrame(BufferedImage image, long durationMillis)
        {
            this.image = image;
            this.durationMillis = durationMillis;
        }

        BufferedImage getImage()
        {
            return image;
        }

        long getDurationMillis()
        {
            return durationMillis;
        }

        void release()
        {
            image.flush();
        }
    }

    @FunctionalInterface
    interface StreamProvider
    {
        ImageInputStream open() throws IOException;
    }

    private static final class FrameMetadata
    {
        private final int left;
        private final int top;
        private final int width;
        private final int height;
        private final String disposalMethod;
        private final long duration;

        private FrameMetadata(int left, int top, int width, int height, String disposalMethod, long duration)
        {
            this.left = left;
            this.top = top;
            this.width = width;
            this.height = height;
            this.disposalMethod = disposalMethod;
            this.duration = duration;
        }
    }
}
