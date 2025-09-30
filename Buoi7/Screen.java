package Buoi7;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Producer theo pipeline: capture → scale → encode → publish Screen.latest
 * Optimized version with pipeline processing and shared frame data
 */
public class Screen extends Thread {
    static volatile Frame latest;               // frame mới nhất dùng chung
    static final AtomicLong seq = new AtomicLong(0);

    static volatile double scale = 1.0;         // tỉ lệ scale server-side
    static volatile float jpegQuality = 0.45f;  // chất lượng JPEG (0.2–0.8)

    private final long targetMs = 1000 / 30;    // ~30 FPS mục tiêu
    private volatile boolean running = true;

    // Hàng đợi nhỏ kiểu "giữ cái mới nhất"
    private final BlockingQueue<BufferedImage> qRaw    = new ArrayBlockingQueue<>(2);
    private final BlockingQueue<BufferedImage> qScaled = new ArrayBlockingQueue<>(2);

    private static <T> void offerLatest(BlockingQueue<T> q, T v) {
        if (!q.offer(v)) { q.poll(); q.offer(v); } // drop cũ, giữ mới nhất
    }

    public Screen() {
        super("ScreenCapturePipeline");
        setDaemon(true);
    }

    @Override 
    public void run() {
        try {
            // --- bước chuẩn bị ---
            javax.imageio.ImageIO.setUseCache(false);
            Robot r = new Robot();
            Rectangle cap = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            
            System.out.println("Screen capture pipeline started - Screen size: " + cap);

            // luồng Capture (giữ nhịp ~30Hz)
            Thread tCap = new Thread(() -> {
                try {
                    while (running) {
                        long t0 = System.currentTimeMillis();
                        BufferedImage img = r.createScreenCapture(cap);
                        offerLatest(qRaw, img);

                        long dt = System.currentTimeMillis() - t0;
                        if (dt < targetMs) Thread.sleep(targetMs - dt); // nhịp chụp
                    }
                } catch (Exception e) { 
                    if (running) e.printStackTrace(); 
                }
            }, "capture");

            // luồng Scale
            Thread tScale = new Thread(() -> {
                try {
                    while (running) {
                        BufferedImage raw = qRaw.take();
                        // đọc scale 1 lần (volatile)
                        double sc = scale;
                        int newW = Math.max(1, (int)(raw.getWidth()  * sc));
                        int newH = Math.max(1, (int)(raw.getHeight() * sc));
                        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g2d = scaled.createGraphics();

                        // Tốc độ cao → dùng NEAREST_NEIGHBOR (nếu cần đẹp hơn thì BILINEAR)
                        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

                        g2d.drawImage(raw, 0, 0, newW, newH, null);
                        g2d.dispose();

                        offerLatest(qScaled, scaled);
                    }
                } catch (Exception e) { 
                    if (running) e.printStackTrace(); 
                }
            }, "scale");

            // luồng Encode + Publish
            Thread tEnc = new Thread(() -> {
                try (JpegEncoder enc = new JpegEncoder(jpegQuality)) {
                    long lastQCheck = System.currentTimeMillis();
                    while (running) {
                        BufferedImage scImg = qScaled.take();

                        // nếu quality có thể thay đổi (adaptive), sync vào encoder mỗi 200ms
                        long now = System.currentTimeMillis();
                        if (now - lastQCheck >= 200) {
                            enc.setQuality(jpegQuality);
                            lastQCheck = now;
                        }

                        byte[] arr = enc.encode(scImg);
                        long s = seq.incrementAndGet();
                        latest = new Frame(s, System.currentTimeMillis(), arr);
                    }
                } catch (Exception e) { 
                    if (running) e.printStackTrace(); 
                }
            }, "encode");

            // start pipeline
            tCap.setDaemon(true);   tCap.start();
            tScale.setDaemon(true); tScale.start();
            tEnc.setDaemon(true);   tEnc.start();

            // Giữ thread "Screen" sống
            while (running) Thread.sleep(1_000);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Backward compatibility methods for existing Worker code
    public byte[] getLatestFrame() {
        Frame f = latest;
        return f != null ? f.jpeg : null;
    }

    public int getFrameCount() {
        return (int) seq.get();
    }

    public void stopCapture() {
        running = false;
        interrupt();
    }
}
