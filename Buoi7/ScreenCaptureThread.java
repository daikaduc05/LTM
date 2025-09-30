package Buoi7;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/**
 * Thread duy nhất để capture màn hình liên tục
 * Lưu frame mới nhất vào volatile byte[] để các Worker thread có thể đọc
 */
public class ScreenCaptureThread extends Thread {
    private volatile byte[] latestFrame;
    private volatile int frameCount = 0;
    private volatile boolean running = true;

    public ScreenCaptureThread() {
        super("ScreenCaptureThread");
        setDaemon(true);
    }

    @Override
    public void run() {
        Robot robot = null;
        Rectangle screenSize = null;

        try {
            robot = new Robot();
            screenSize = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            System.out.println("ScreenCaptureThread started - Screen size: " + screenSize);
        } catch (Exception e) {
            System.err.println("Failed to initialize Robot: " + e.getMessage());
            return;
        }

        while (running) {
            try {
                BufferedImage image = robot.createScreenCapture(screenSize);

                try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
                    ImageIO.write(image, "jpg", baos);
                    baos.flush();
                    latestFrame = baos.toByteArray();
                    frameCount++;

                    if (frameCount % 100 == 0) {
                        System.out
                                .println("Captured frame #" + frameCount + " - Size: " + latestFrame.length + " bytes");
                    }
                }

                // Capture với tần suất ~30 FPS
                Thread.sleep(33);

            } catch (Exception e) {
                System.err.println("Error capturing screen: " + e.getMessage());
                try {
                    Thread.sleep(100); // Retry sau 100ms nếu có lỗi
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }

        System.out.println("ScreenCaptureThread stopped");
    }

    public byte[] getLatestFrame() {
        return latestFrame;
    }

    public int getFrameCount() {
        return frameCount;
    }

    public void stopCapture() {
        running = false;
        interrupt();
    }
}
