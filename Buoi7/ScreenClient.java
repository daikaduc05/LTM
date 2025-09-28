package Buoi7;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;

import javax.imageio.ImageIO;
import javax.swing.JFrame;

public class ScreenClient extends JFrame {
	Socket soc;

	public static void main(String[] args) {
		new ScreenClient();
	}

	int off = 50;

	// FPS and latency tracking variables
	private long frameCount = 0;
	private long lastFpsTime = System.currentTimeMillis();

	// Detailed timing variables
	private long totalReceiveTime = 0;
	private long totalDecodeTime = 0;
	private long totalRenderTime = 0;

	public ScreenClient() {
		this.setTitle("Share Screen");
		this.setSize(500, 400);
		this.setDefaultCloseOperation(3);
		try {
			soc = new Socket("localhost", 2345);

			// Send client screen size to server
			DataOutputStream dos = new DataOutputStream(soc.getOutputStream());
			dos.writeInt(this.getWidth());
			dos.writeInt(this.getHeight());
			dos.flush();
			System.out.println("Sent client size: " + this.getWidth() + "x" + this.getHeight());
		} catch (Exception e) {
			System.exit(1);
		}

		this.setVisible(true);
	}

	public void paint(Graphics g) {
		try {
			long currentTime = System.currentTimeMillis();
			long stepStartTime, stepEndTime;

			// Step 6: Receive (network data reception)
			stepStartTime = System.nanoTime();
			DataInputStream bis = new DataInputStream(soc.getInputStream());
			int frameWidth = bis.readInt(); // Receive scaled width
			int frameHeight = bis.readInt(); // Receive scaled height
			int n = bis.readInt();
			byte tmp[] = bis.readNBytes(n);
			stepEndTime = System.nanoTime();
			long receiveTime = (stepEndTime - stepStartTime) / 1_000_000;
			totalReceiveTime += receiveTime;

			// Step 7: Decode (JPG decompression)
			stepStartTime = System.nanoTime();
			ByteArrayInputStream bis1 = new ByteArrayInputStream(tmp);
			BufferedImage img1 = ImageIO.read(bis1);
			stepEndTime = System.nanoTime();
			long decodeTime = (stepEndTime - stepStartTime) / 1_000_000;
			totalDecodeTime += decodeTime;

			// Step 8: Render (1:1 blit - no scaling needed)
			stepStartTime = System.nanoTime();
			// Direct 1:1 blit - image is already scaled to exact client size
			g.drawImage(img1, 0, 0, frameWidth, frameHeight, null);
			stepEndTime = System.nanoTime();
			long renderTime = (stepEndTime - stepStartTime) / 1_000_000;
			totalRenderTime += renderTime;

			// FPS calculation with detailed timing
			frameCount++;
			if (currentTime - lastFpsTime >= 1000) { // Update FPS every second
				double fps = frameCount * 1000.0 / (currentTime - lastFpsTime);
				double avgReceiveTime = (double) totalReceiveTime / frameCount;
				double avgDecodeTime = (double) totalDecodeTime / frameCount;
				double avgRenderTime = (double) totalRenderTime / frameCount;

				System.out.println("=== CLIENT TIMING (FPS: " + String.format("%.2f", fps) + ") ===");
				System.out.println("Receive: " + String.format("%.2f", avgReceiveTime) + "ms | " +
						"Decode: " + String.format("%.2f", avgDecodeTime) + "ms | " +
						"Render: " + String.format("%.2f", avgRenderTime) + "ms | " +
						"Size: " + tmp.length + " bytes");

				// Reset counters
				frameCount = 0;
				lastFpsTime = currentTime;
				totalReceiveTime = 0;
				totalDecodeTime = 0;
				totalRenderTime = 0;
			}

			// Log every 50 frames with detailed per-frame timing
			if (frameCount % 50 == 0) {
				System.out.println("Client Frame " + frameCount + " | " +
						"Receive: " + receiveTime + "ms | " +
						"Decode: " + decodeTime + "ms | " +
						"Render: " + renderTime + "ms | " +
						"Size: " + tmp.length + " bytes");
			}

			this.repaint();
		} catch (Exception e) {
		}
	}

}
