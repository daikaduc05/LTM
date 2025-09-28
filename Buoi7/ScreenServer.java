package Buoi7;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

public class ScreenServer {
    public static void main(String[] args) {
        new ScreenServer();
    }

    public ScreenServer() {
        Screen s = new Screen();
        // s.setPriority(10);
        s.start();
        try {
            ServerSocket server = new ServerSocket(2345);
            while (true) {
                Socket soc = server.accept();
                ScreenProcessing sp = new ScreenProcessing(soc);
                // sp.setPriority(1);
                sp.start();
            }
        } catch (Exception e) {

        }
    }

}

class Screen extends Thread {
    static byte[] tmp;
    static int count = 0;
    static int scaledWidth = 0, scaledHeight = 0; // Store scaled dimensions

    // FPS and latency tracking variables
    private long frameCount = 0;
    private long lastFpsTime = System.currentTimeMillis();
    private long lastCaptureTime = System.currentTimeMillis();

    // Detailed timing variables
    private long totalCaptureTime = 0;
    private long totalPreScaleTime = 0;
    private long totalScaleTime = 0;
    private long totalEncodeTime = 0;

    // Method to set client dimensions for scaling
    public static void setClientDimensions(int width, int height) {
        scaledWidth = width;
        scaledHeight = height;
        System.out.println("Screen scaling set to: " + scaledWidth + "x" + scaledHeight);
    }

    public void run() {
        Robot r = null;
        Rectangle capture = null;
        try {
            r = new Robot();
            capture = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        } catch (Exception e) {

        }
        while (true) {
            try {
                long currentTime = System.currentTimeMillis();
                long stepStartTime, stepEndTime;

                // Step 1: Capture
                stepStartTime = System.nanoTime();
                BufferedImage img = r.createScreenCapture(capture);
                stepEndTime = System.nanoTime();
                long captureTime = (stepEndTime - stepStartTime) / 1_000_000;
                totalCaptureTime += captureTime;

                // Step 2: Pre-scale (prepare for scaling if needed)
                stepStartTime = System.nanoTime();
                // No pre-scaling needed for full screen capture
                stepEndTime = System.nanoTime();
                long preScaleTime = (stepEndTime - stepStartTime) / 1_000_000;
                totalPreScaleTime += preScaleTime;

                // Step 3: Scale (resize to client dimensions)
                stepStartTime = System.nanoTime();
                BufferedImage scaledImg;
                if (scaledWidth > 0 && scaledHeight > 0) {
                    // Scale to client dimensions maintaining aspect ratio
                    int screenWidth = img.getWidth();
                    int screenHeight = img.getHeight();

                    // Calculate scaling factor to fit client size while maintaining aspect ratio
                    double scaleX = (double) scaledWidth / screenWidth;
                    double scaleY = (double) scaledHeight / screenHeight;
                    double scale = Math.min(scaleX, scaleY);

                    int newWidth = (int) (screenWidth * scale);
                    int newHeight = (int) (screenHeight * scale);

                    scaledImg = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D g2d = scaledImg.createGraphics();
                    g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2d.drawImage(img, 0, 0, newWidth, newHeight, null);
                    g2d.dispose();

                    // Update static dimensions for sending
                    Screen.scaledWidth = newWidth;
                    Screen.scaledHeight = newHeight;
                } else {
                    scaledImg = img; // No scaling if client dimensions not set
                    Screen.scaledWidth = img.getWidth();
                    Screen.scaledHeight = img.getHeight();
                }
                stepEndTime = System.nanoTime();
                long scaleTime = (stepEndTime - stepStartTime) / 1_000_000;
                totalScaleTime += scaleTime;

                // Step 4: Encode (JPG compression)
                stepStartTime = System.nanoTime();
                ByteArrayOutputStream bos = new ByteArrayOutputStream();

                // Use optimized JPG compression with quality control
                ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
                ImageOutputStream ios = ImageIO.createImageOutputStream(bos);
                writer.setOutput(ios);

                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.7f); // 70% quality for good balance of size/quality

                writer.write(null, new javax.imageio.IIOImage(scaledImg, null, null), param);
                writer.dispose();
                ios.close();
                bos.flush();
                tmp = bos.toByteArray();
                stepEndTime = System.nanoTime();
                long encodeTime = (stepEndTime - stepStartTime) / 1_000_000;
                totalEncodeTime += encodeTime;

                count++;
                frameCount++;

                // Calculate FPS every second with detailed timing
                if (currentTime - lastFpsTime >= 1000) {
                    double fps = frameCount * 1000.0 / (currentTime - lastFpsTime);
                    double avgCaptureTime = (double) totalCaptureTime / frameCount;
                    double avgPreScaleTime = (double) totalPreScaleTime / frameCount;
                    double avgScaleTime = (double) totalScaleTime / frameCount;
                    double avgEncodeTime = (double) totalEncodeTime / frameCount;

                    System.out.println("=== SERVER TIMING (FPS: " + String.format("%.2f", fps) + ") ===");
                    System.out.println("Capture: " + String.format("%.2f", avgCaptureTime) + "ms | " +
                            "PreScale: " + String.format("%.2f", avgPreScaleTime) + "ms | " +
                            "Scale: " + String.format("%.2f", avgScaleTime) + "ms | " +
                            "Encode: " + String.format("%.2f", avgEncodeTime) + "ms | " +
                            "Size: " + tmp.length + " bytes");

                    // Reset counters
                    frameCount = 0;
                    lastFpsTime = currentTime;
                    totalCaptureTime = 0;
                    totalPreScaleTime = 0;
                    totalScaleTime = 0;
                    totalEncodeTime = 0;
                }

                // Log every 50 frames with detailed per-frame timing
                if (count % 50 == 0) {
                    long timeSinceLastCapture = currentTime - lastCaptureTime;
                    System.out.println("Frame " + count + " | " +
                            "Capture: " + captureTime + "ms | " +
                            "PreScale: " + preScaleTime + "ms | " +
                            "Scale: " + scaleTime + "ms | " +
                            "Encode: " + encodeTime + "ms | " +
                            "Size: " + tmp.length + " bytes | " +
                            "Interval: " + timeSinceLastCapture + "ms");
                    lastCaptureTime = currentTime;
                }
            } catch (Exception e) {

            }
        }
    }
}

class ScreenProcessing extends Thread {
    Socket soc;
    int clientWidth, clientHeight;

    public ScreenProcessing(Socket soc) {
        this.soc = soc;
        // Receive client screen dimensions on connection
        try {
            DataInputStream dis = new DataInputStream(soc.getInputStream());
            clientWidth = dis.readInt();
            clientHeight = dis.readInt();
            System.out.println("Received client size: " + clientWidth + "x" + clientHeight);

            // Set client dimensions for scaling
            Screen.setClientDimensions(clientWidth, clientHeight);
        } catch (Exception e) {
            clientWidth = 500; // Default fallback
            clientHeight = 400;
            System.out.println("Failed to receive client size, using default: " + clientWidth + "x" + clientHeight);
            Screen.setClientDimensions(clientWidth, clientHeight);
        }
    }

    int countNow;

    // Latency tracking variables
    private long processingCount = 0;
    private long lastProcessingTime = System.currentTimeMillis();

    // Send timing variables
    private long totalSendTime = 0;

    public void run() {
        while (true) {
            try {
                if (countNow == Screen.count) {
                    Thread.sleep(1);
                    continue;
                }

                long currentTime = System.currentTimeMillis();
                long stepStartTime, stepEndTime;

                // Step 5: Send (network transmission)
                stepStartTime = System.nanoTime();
                byte[] tmp = Screen.tmp.clone();
                countNow = Screen.count;

                DataOutputStream output = new DataOutputStream(soc.getOutputStream());
                output.writeInt(Screen.scaledWidth); // Send scaled width
                output.writeInt(Screen.scaledHeight); // Send scaled height
                output.writeInt(tmp.length);
                output.write(tmp);
                output.flush();
                stepEndTime = System.nanoTime();
                long sendTime = (stepEndTime - stepStartTime) / 1_000_000;
                totalSendTime += sendTime;

                processingCount++;

                // Log processing stats every 50 frames with send timing
                if (processingCount % 50 == 0) {
                    long timeSinceLastProcessing = currentTime - lastProcessingTime;
                    double avgSendTime = (double) totalSendTime / processingCount;
                    System.out.println("=== SEND TIMING (Frame " + processingCount + ") ===");
                    System.out.println("Send: " + String.format("%.2f", avgSendTime) + "ms | " +
                            "Current: " + sendTime + "ms | " +
                            "Size: " + tmp.length + " bytes | " +
                            "Interval: " + timeSinceLastProcessing + "ms");
                    lastProcessingTime = currentTime;
                }

                Thread.sleep(1);
            } catch (Exception e) {

            }
        }
    }
}
