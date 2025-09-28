package Buoi7;

import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

public class ScreenClient extends JFrame {
	private Socket soc;
	private RenderPanel renderPanel;
	private FrameReceiver frameReceiver;

	public static void main(String[] args) {
		new ScreenClient();
	}

	public ScreenClient() {
		this.setTitle("Share Screen - 15 FPS Locked, Blit 1:1");
		this.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		renderPanel = new RenderPanel();
		this.add(renderPanel);

		// Set initial size
		this.setSize(800, 600);

		try {
			soc = new Socket("localhost", 2345);
			frameReceiver = new FrameReceiver(soc, renderPanel);
			frameReceiver.start();
		} catch (Exception e) {
			System.err.println("Failed to connect to server: " + e.getMessage());
			System.exit(1);
		}

		this.setVisible(true);
	}

	// Hàm chuẩn hóa BufferedImage về TYPE_INT_RGB
	public static BufferedImage toIntRGB(BufferedImage src) {
		if (src.getType() == BufferedImage.TYPE_INT_RGB) {
			return src;
		}

		BufferedImage result = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g2 = result.createGraphics();
		g2.drawImage(src, 0, 0, null);
		g2.dispose();
		return result;
	}
}

class RenderPanel extends JPanel {
	private volatile BufferedImage currentImage;
	private final AtomicBoolean isRendering = new AtomicBoolean(false);
	private final AtomicLong lastRepaintTime = new AtomicLong(0);

	// Frame dimensions
	private volatile int frameWidth = 0;
	private volatile int frameHeight = 0;

	// Metrics
	private long lastTime = System.currentTimeMillis();
	private int receivedFrames = 0;
	private long receivedDataSize = 0;
	private long totalDecodeTime = 0;
	private long totalRenderTime = 0;

	public void setCurrentImage(BufferedImage image, int width, int height) {
		// Throttle: chỉ cập nhật nếu không đang render và đã qua 33ms (30 FPS max)
		long now = System.currentTimeMillis();
		if (isRendering.get() || (now - lastRepaintTime.get()) < 33) {
			return; // Drop frame nếu quá nhanh
		}

		this.currentImage = image;
		this.frameWidth = width;
		this.frameHeight = height;

		// Set preferred size để panel khớp với frame size
		SwingUtilities.invokeLater(() -> {
			if (getPreferredSize().width != width || getPreferredSize().height != height) {
				setPreferredSize(new java.awt.Dimension(width, height));
				getParent().revalidate();
			}
			repaint();
		});

		lastRepaintTime.set(now);
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);

		if (currentImage == null || frameWidth == 0 || frameHeight == 0)
			return;

		long renderStart = System.currentTimeMillis();
		isRendering.set(true);

		try {
			Graphics2D g2 = (Graphics2D) g;

			// Tối ưu rendering hints cho blit 1:1
			g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);

			// Blit 1:1 - vẽ ảnh đúng kích thước frame, không scale
			int panelW = getWidth();
			int panelH = getHeight();

			// Nếu panel khớp với frame size, vẽ trực tiếp
			if (panelW == frameWidth && panelH == frameHeight) {
				g2.drawImage(currentImage, 0, 0, null); // Perfect 1:1 blit
			} else {
				// Tính vị trí center nếu panel khác kích thước
				int x = Math.max(0, (panelW - frameWidth) / 2);
				int y = Math.max(0, (panelH - frameHeight) / 2);

				// Fill background
				g2.setColor(java.awt.Color.BLACK);
				g2.fillRect(0, 0, panelW, panelH);

				// Vẽ ảnh 1:1 (không scale)
				g2.drawImage(currentImage, x, y, null);
			}

		} finally {
			isRendering.set(false);
			long renderEnd = System.currentTimeMillis();
			totalRenderTime += (renderEnd - renderStart);
		}
	}

	public void updateMetrics(int frameSize, long decodeTime) {
		receivedFrames++;
		receivedDataSize += frameSize;
		totalDecodeTime += decodeTime;

		long currentTime = System.currentTimeMillis();
		if (currentTime - lastTime >= 1000) {
			double intervalSeconds = (currentTime - lastTime) / 1000.0;
			double fps = (double) receivedFrames / intervalSeconds;

			// Công thức bandwidth đúng
			double Mbps = (receivedDataSize * 8.0) / intervalSeconds / 1_000_000.0;
			double KiBps = receivedDataSize / 1024.0 / intervalSeconds;
			double avgFrameKiB = (double) receivedDataSize / receivedFrames / 1024.0;

			double avgDecodeMs = (double) totalDecodeTime / receivedFrames;
			double avgRenderMs = (double) totalRenderTime / receivedFrames;

			System.out.println(String.format(
					"[CLIENT] FPS: %.2f, Throughput: %.2f Mb/s (%.0f KiB/s), AvgFrame: %.0f KiB, Decode: %.1f ms, Render: %.1f ms, WxH=%dx%d, Blit=1:1",
					fps, Mbps, KiBps, avgFrameKiB, avgDecodeMs, avgRenderMs, frameWidth, frameHeight));

			// Reset counters
			receivedFrames = 0;
			receivedDataSize = 0;
			totalDecodeTime = 0;
			totalRenderTime = 0;
			lastTime = currentTime;
		}
	}
}

class FrameReceiver extends Thread {
	private final RenderPanel renderPanel;
	private final DataInputStream inputStream;

	public FrameReceiver(Socket socket, RenderPanel renderPanel) throws Exception {
		this.renderPanel = renderPanel;
		this.inputStream = new DataInputStream(socket.getInputStream());
	}

	@Override
	public void run() {
		try {
			while (!Thread.currentThread().isInterrupted()) {

				// Đọc header mới: magic(4) + ver(1) + flags(1) + frameId(4) + width(2) +
				// height(2) + payloadLen(4) + payload
				int magic = inputStream.readInt();
				if (magic != 0x12345678) {
					System.err.println("Invalid magic number: " + Integer.toHexString(magic));
					continue;
				}

				inputStream.readByte(); // version (unused)
				inputStream.readByte(); // flags (unused)
				inputStream.readInt(); // frameId (unused)
				int frameWidth = inputStream.readShort() & 0xFFFF; // Unsigned short
				int frameHeight = inputStream.readShort() & 0xFFFF; // Unsigned short
				int payloadLength = inputStream.readInt();

				byte[] frameData = inputStream.readNBytes(payloadLength);

				long receiveTime = System.currentTimeMillis();

				// Decode ảnh trong worker thread
				ByteArrayInputStream bis = new ByteArrayInputStream(frameData);
				BufferedImage originalImage = ImageIO.read(bis);

				// Chuẩn hóa về TYPE_INT_RGB
				BufferedImage normalizedImage = ScreenClient.toIntRGB(originalImage);

				long decodeTime = System.currentTimeMillis() - receiveTime;

				// Cập nhật ảnh và metrics với kích thước frame
				renderPanel.setCurrentImage(normalizedImage, frameWidth, frameHeight);
				renderPanel.updateMetrics(payloadLength, decodeTime);

			}
		} catch (Exception e) {
			System.err.println("Frame receiver error: " + e.getMessage());
		}
	}
}
