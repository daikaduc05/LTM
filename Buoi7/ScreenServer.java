package Buoi7;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import javax.imageio.stream.ImageOutputStream;

public class ScreenServer {
    // FPS Lock Configuration
    private static final int TARGET_FPS = 15;
    public static final long FRAME_PERIOD_MS = 1000 / TARGET_FPS;

    // Scale Configuration
    private static final double SCALE = 0.66;
    private static float JPG_QUALITY = 0.62f;

    // ThreadLocal buffer để tái sử dụng, giảm GC
    private static final ThreadLocal<ByteArrayOutputStream> TL_BAOS = ThreadLocal
            .withInitial(() -> new ByteArrayOutputStream(1 << 20)); // ~1MB

    // Pipeline queues
    private static final BlockingQueue<BufferedImage> captureQueue = new LinkedBlockingQueue<>(2);
    private static final BlockingQueue<FrameData> encodeQueue = new LinkedBlockingQueue<>(3);

    // Executors
    private static ScheduledExecutorService captureExec;
    private static ScheduledExecutorService encodeExec;

    // Frame counter
    private static int frameId = 0;

    // Global frame data
    static volatile byte[] currentPayload = null;
    static volatile int currentFrameWidth = 0;
    static volatile int currentFrameHeight = 0;
    static volatile int currentFrameId = 0;

    public static void main(String[] args) {
        new ScreenServer();
    }

    // Frame data class
    static class FrameData {
        final BufferedImage image;
        final int width;
        final int height;
        final int frameId;
        final byte[] payload;

        FrameData(BufferedImage image, int width, int height, int frameId, byte[] payload) {
            this.image = image;
            this.width = width;
            this.height = height;
            this.frameId = frameId;
            this.payload = payload;
        }
    }

    // Helper function: Chuẩn hóa BufferedImage về TYPE_INT_RGB
    public static BufferedImage toIntRGB(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    // Helper function: Scale ảnh với rendering hints tối ưu
    public static BufferedImage scaleRGB(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    // Helper function: Unsharp 3x3 filter để tăng nét chữ
    public static BufferedImage unsharp3x3(BufferedImage src) {
        float[] kernel = { 0, -1, 0, -1, 5, -1, 0, -1, 0 };
        ConvolveOp op = new ConvolveOp(new Kernel(3, 3, kernel), ConvolveOp.EDGE_NO_OP, null);
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        op.filter(src, dst);
        return dst;
    }

    // Helper function: Encode JPG với quality và tối ưu Huffman
    public static byte[] encodeJpg(BufferedImage rgb, float quality) throws IOException {
        ByteArrayOutputStream baos = TL_BAOS.get();
        baos.reset();

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageOutputStream ios = ImageIO.createImageOutputStream(baos);
        writer.setOutput(ios);

        ImageWriteParam p = writer.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality);
        p.setProgressiveMode(ImageWriteParam.MODE_DISABLED);

        if (p instanceof JPEGImageWriteParam jp) {
            jp.setOptimizeHuffmanTables(true); // Optimize Huffman for JPG
            jp.setProgressiveMode(ImageWriteParam.MODE_DISABLED);
        }

        writer.write(null, new IIOImage(rgb, null, null), p);
        writer.dispose();
        ios.close();
        return baos.toByteArray();
    }

    public ScreenServer() {
        try {
            // Initialize executors
            captureExec = Executors.newSingleThreadScheduledExecutor();
            encodeExec = Executors.newSingleThreadScheduledExecutor();

            // Start capture task
            startCaptureTask();

            // Start encode task
            startEncodeTask();

            // Start server
            ServerSocket server = new ServerSocket(2345);
            System.out.println("ScreenServer started on port 2345, FPS=" + TARGET_FPS + ", Scale=" + SCALE
                    + ", JPG Quality=" + JPG_QUALITY);

            while (true) {
                Socket soc = server.accept();
                ScreenProcessing sp = new ScreenProcessing(soc);
                sp.start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startCaptureTask() {
        try {
            Robot robot = new Robot();
            Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());

            captureExec.scheduleAtFixedRate(() -> {
                try {
                    BufferedImage shot = robot.createScreenCapture(screenRect);
                    // Drop oldest if queue is full
                    if (!captureQueue.offer(shot)) {
                        captureQueue.poll(); // Remove oldest
                        captureQueue.offer(shot); // Add newest
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }, 0, FRAME_PERIOD_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void startEncodeTask() {
        encodeExec.execute(() -> {
            // Metrics
            long lastTime = System.currentTimeMillis();
            int frameCount = 0;
            long totalDataSize = 0;
            long totalPreScaleTime = 0;
            long totalEncodeTime = 0;
            List<Long> encodeTimes = new ArrayList<>();

            while (!Thread.currentThread().isInterrupted()) {
                try {
                    BufferedImage captured = captureQueue.take();

                    // Pre-scale pipeline
                    BufferedImage rgb = toIntRGB(captured);
                    int targetW = (int) Math.round(rgb.getWidth() * SCALE);
                    int targetH = (int) Math.round(rgb.getHeight() * SCALE);

                    long t0 = System.nanoTime();
                    BufferedImage scaled = scaleRGB(rgb, targetW, targetH);
                    // Apply unsharp filter for better text clarity
                    scaled = unsharp3x3(scaled);
                    long t1 = System.nanoTime();

                    // Encode JPG
                    long e0 = System.nanoTime();
                    byte[] payload = encodeJpg(scaled, JPG_QUALITY);
                    long e1 = System.nanoTime();

                    // Create frame data
                    FrameData frameData = new FrameData(scaled, targetW, targetH, frameId++, payload);

                    // Update global frame data
                    currentPayload = payload;
                    currentFrameWidth = targetW;
                    currentFrameHeight = targetH;
                    currentFrameId = frameData.frameId;

                    // Update metrics
                    frameCount++;
                    totalDataSize += payload.length;
                    totalPreScaleTime += (t1 - t0) / 1_000_000; // Convert to ms
                    totalEncodeTime += (e1 - e0) / 1_000_000; // Convert to ms
                    encodeTimes.add((e1 - e0) / 1_000_000);
                    if (encodeTimes.size() > 100) {
                        encodeTimes.remove(0);
                    }

                    // Log metrics every second
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastTime >= 1000) {
                        double intervalSeconds = (currentTime - lastTime) / 1000.0;
                        double fps = (double) frameCount / intervalSeconds;

                        double Mbps = (totalDataSize * 8.0) / intervalSeconds / 1_000_000.0;
                        double KiBps = totalDataSize / 1024.0 / intervalSeconds;
                        double avgFrameKiB = (double) totalDataSize / frameCount / 1024.0;

                        double avgPreScaleMs = (double) totalPreScaleTime / frameCount;
                        double avgEncodeMs = (double) totalEncodeTime / frameCount;

                        // Calculate p95
                        double encodeMsP95 = 0.0;
                        if (!encodeTimes.isEmpty()) {
                            List<Long> sorted = new ArrayList<>(encodeTimes);
                            sorted.sort(Long::compareTo);
                            int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
                            encodeMsP95 = sorted.get(Math.max(0, index));
                        }

                        // Adaptive quality control
                        if (encodeMsP95 > 45 || avgFrameKiB > 70) {
                            JPG_QUALITY = Math.max(0.35f, JPG_QUALITY - 0.05f);
                        } else if (encodeMsP95 < 30 && avgFrameKiB < 45) {
                            JPG_QUALITY = Math.min(0.75f, JPG_QUALITY + 0.02f);
                        }

                        System.out.println(String.format(
                                "[SERVER] FPS: %.2f, Throughput: %.2f Mb/s (%.0f KiB/s), AvgFrame: %.0f KiB, PreScale: %.1f ms, Encode: %.1f ms, JPG Q=%.2f, Scale=%.2f, WxH=%dx%d",
                                fps, Mbps, KiBps, avgFrameKiB, avgPreScaleMs, avgEncodeMs, JPG_QUALITY, SCALE, targetW,
                                targetH));

                        // Reset counters
                        frameCount = 0;
                        totalDataSize = 0;
                        totalPreScaleTime = 0;
                        totalEncodeTime = 0;
                        lastTime = currentTime;
                    }

                    // Drop oldest if queue is full
                    if (!encodeQueue.offer(frameData)) {
                        encodeQueue.poll(); // Remove oldest
                        encodeQueue.offer(frameData); // Add newest
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }
}

class ScreenProcessing extends Thread {
    private final Socket socket;
    private final DataOutputStream output;

    public ScreenProcessing(Socket socket) throws IOException {
        this.socket = socket;
        this.output = new DataOutputStream(socket.getOutputStream());
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                // Wait for new frame data
                if (ScreenServer.currentPayload != null) {
                    sendFrame();
                }
                Thread.sleep(ScreenServer.FRAME_PERIOD_MS);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void sendFrame() throws IOException {
        // Header format: magic(4) + ver(1) + flags(1) + frameId(4) + width(2) +
        // height(2) + payloadLen(4) + payload
        output.writeInt(0x12345678); // Magic
        output.writeByte(1); // Version
        output.writeByte(0); // Flags
        output.writeInt(ScreenServer.currentFrameId);
        output.writeShort((short) ScreenServer.currentFrameWidth);
        output.writeShort((short) ScreenServer.currentFrameHeight);
        output.writeInt(ScreenServer.currentPayload.length);
        output.write(ScreenServer.currentPayload);
        output.flush();
    }
}