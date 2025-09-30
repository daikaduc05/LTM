package Buoi7;

import java.awt.Graphics;
import java.awt.Image;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.Socket;
import javax.imageio.ImageIO;
import javax.swing.JFrame;

/**
 * Client tương thích với cả server cũ và mới
 * Tự động phát hiện protocol dựa trên dữ liệu nhận được
 */
public class CompatibleScreenClient extends JFrame {
    Socket soc;
    private long lastReceivedSeq = -1;
    private int frameCount = 0;
    private boolean isNewProtocol = false;
    private boolean protocolDetected = false;

    public static void main(String[] args) {
        new CompatibleScreenClient();
    }

    int off = 50;

    public CompatibleScreenClient() {
        this.setTitle("Share Screen - Compatible Client");
        this.setSize(500, 400);
        this.setDefaultCloseOperation(3);
        try {
            soc = new Socket("172.16.1.239", 2345);
            soc.setTcpNoDelay(true);
            System.out.println("Connected to server: " + soc.getRemoteSocketAddress());
        } catch (Exception e) {
            System.err.println("Failed to connect to server: " + e.getMessage());
            System.exit(1);
        }

        this.setVisible(true);
    }

    public void paint(Graphics g) {
        try {
            DataInputStream bis = new DataInputStream(soc.getInputStream());

            long seq = 0;
            long tsMillis = 0;
            int n;
            byte[] tmp;

            if (!protocolDetected) {
                // Thử đọc theo protocol mới trước
                try {
                    seq = bis.readLong();
                    tsMillis = bis.readLong();
                    n = bis.readInt();
                    tmp = bis.readNBytes(n);
                    isNewProtocol = true;
                    protocolDetected = true;
                    System.out.println("Detected NEW protocol (with seq/timestamp)");
                } catch (Exception e) {
                    // Nếu thất bại, thử protocol cũ
                    try {
                        bis = new DataInputStream(soc.getInputStream());
                        n = bis.readInt();
                        tmp = bis.readNBytes(n);
                        isNewProtocol = false;
                        protocolDetected = true;
                        System.out.println("Detected OLD protocol (length only)");
                    } catch (Exception e2) {
                        throw e2; // Re-throw nếu cả hai đều thất bại
                    }
                }
            } else {
                // Đã phát hiện protocol, đọc theo protocol đó
                if (isNewProtocol) {
                    seq = bis.readLong();
                    tsMillis = bis.readLong();
                    n = bis.readInt();
                    tmp = bis.readNBytes(n);
                } else {
                    n = bis.readInt();
                    tmp = bis.readNBytes(n);
                }
            }

            // Xử lý frame
            if (isNewProtocol && seq != lastReceivedSeq) {
                lastReceivedSeq = seq;
                frameCount++;

                this.setTitle("Share Screen - Frame #" + frameCount + " (Seq: " + seq + ")");

                if (frameCount % 30 == 0) {
                    long currentTime = System.currentTimeMillis();
                    long latency = currentTime - tsMillis;
                    System.out.println("Frame #" + frameCount + " - Seq: " + seq +
                            " - Size: " + n + " bytes - Latency: " + latency + "ms");
                }
            } else if (!isNewProtocol) {
                frameCount++;
                this.setTitle("Share Screen - Frame #" + frameCount + " (Legacy)");

                if (frameCount % 30 == 0) {
                    System.out.println("Frame #" + frameCount + " - Size: " + n + " bytes (Legacy protocol)");
                }
            }

            // Hiển thị hình ảnh
            ByteArrayInputStream bis1 = new ByteArrayInputStream(tmp);
            var img1 = ImageIO.read(bis1);
            int w = this.getWidth() - 2 * off;
            int h = this.getHeight() - 2 * off;
            Image img2 = img1.getScaledInstance(w, h, Image.SCALE_SMOOTH);

            g.drawImage(img2, off, off, this.getWidth() - off, this.getHeight() - off,
                    0, 0, w, h, null);

            this.repaint();
        } catch (Exception e) {
            System.err.println("Error receiving frame: " + e.getMessage());
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
