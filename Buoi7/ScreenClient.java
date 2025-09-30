package Buoi7;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

class ViewerPanel extends JPanel {
	volatile BufferedImage lastRendered;

	ViewerPanel() {
		setBackground(Color.BLACK);
		setDoubleBuffered(true);
	}

	@Override
	protected void paintComponent(Graphics g) {
		super.paintComponent(g);
		BufferedImage img = lastRendered;
		if (img == null)
			return;
		// vẽ full panel, server đã scale sẵn nên thao tác này nhẹ
		g.drawImage(img, 0, 0, getWidth(), getHeight(), null);
		Toolkit.getDefaultToolkit().sync(); // giảm tearing (nhất là trên X11)
	}
}

public class ScreenClient extends JFrame {
	private Socket soc;
	private DataInputStream in;
	private final ViewerPanel panel = new ViewerPanel();
	private final AtomicBoolean repaintScheduled = new AtomicBoolean(false);

	// thống kê
	private long recvFrames = 0, recvBytes = 0, recvLastMs = System.currentTimeMillis();

	public static void main(String[] args) {
		SwingUtilities.invokeLater(ScreenClient::new);
	}

	public ScreenClient() {
		setTitle("Share Screen - Low Latency Optimized");
		setDefaultCloseOperation(EXIT_ON_CLOSE);
		setContentPane(panel);
		setSize(1000, 650);
		setLocationRelativeTo(null);

		try {
			ImageIO.setUseCache(false);
			soc = new Socket("localhost", 2345);
			soc.setTcpNoDelay(true);
			in = new DataInputStream(soc.getInputStream());
			System.out.println("Connected to server: " + soc.getRemoteSocketAddress());
		} catch (Exception e) {
			System.err.println("Failed to connect to server: " + e.getMessage());
			e.printStackTrace();
			System.exit(1);
			return;
		}

		Thread reader = new Thread(this::readerLoop, "reader");
		reader.setDaemon(true);
		reader.start();

		setVisible(true);
	}

	private void readerLoop() {
		try {
			while (true) {
				long seq = in.readLong();
				long ts = in.readLong();
				int n = in.readInt();
				byte[] buf = in.readNBytes(n);

				recvFrames++;
				recvBytes += n;

				BufferedImage img = ImageIO.read(new ByteArrayInputStream(buf));
				panel.lastRendered = img;

				// latency thực tế
				long latency = System.currentTimeMillis() - ts;

				long now = System.currentTimeMillis();
				if (now - recvLastMs >= 1000) {
					double fps = recvFrames * 1000.0 / (now - recvLastMs);
					double mbps = (recvBytes * 8.0) / 1_000_000.0;
					System.out.printf("[CLIENT] recvFPS=%.1f, recvMbps=%.2f, latency~%d ms, lastSeq=%d%n",
							fps, mbps, latency, seq);
					recvFrames = 0;
					recvBytes = 0;
					recvLastMs = now;
				}

				// "đến là vẽ" nhưng không spam EDT
				if (repaintScheduled.compareAndSet(false, true)) {
					SwingUtilities.invokeLater(() -> {
						panel.repaint();
						repaintScheduled.set(false);
					});
				}
			}
		} catch (Exception e) {
			System.out.println("[CLIENT] Disconnected: " + e.getMessage());
		}
	}
}