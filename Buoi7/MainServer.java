package Buoi7;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main server sử dụng NIO để accept client và phân phối cho Worker threads
 */
public class MainServer {
    private static final int SERVER_PORT = 2345;
    private static final int WORKER_COUNT = Runtime.getRuntime().availableProcessors();

    private ServerSocketChannel serverChannel;
    private Selector selector;
    private Screen screenCaptureThread;
    private List<Worker> workers;
    private LoadBalancer loadBalancer;
    private ScheduledExecutorService statsScheduler;
    private volatile boolean running = true;

    public static void main(String[] args) {
        new MainServer().start();
    }

    public void start() {
        try {
            initializeServer();
            startWorkers();
            startStatsReporting();

            System.out.println("=== NIO Screen Sharing Server Started ===");
            System.out.println("Port: " + SERVER_PORT);
            System.out.println("Workers: " + WORKER_COUNT);
            System.out.println("Screen capture thread: " + screenCaptureThread.getName());
            System.out.println("==========================================");

            runServerLoop();

        } catch (Exception e) {
            System.err.println("Server startup failed: " + e.getMessage());
            e.printStackTrace();
        } finally {
            shutdown();
        }
    }

    private void initializeServer() throws IOException {
        // Khởi tạo Screen pipeline
        screenCaptureThread = new Screen();
        screenCaptureThread.start();

        // Khởi tạo ServerSocketChannel với NIO
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.bind(new InetSocketAddress(SERVER_PORT));

        // Khởi tạo Selector
        selector = Selector.open();
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        System.out.println("Server initialized on port " + SERVER_PORT);
    }

    private void startWorkers() throws IOException {
        workers = new ArrayList<>();

        // Tạo các Worker threads
        for (int i = 0; i < WORKER_COUNT; i++) {
            Worker worker = new Worker();
            workers.add(worker);
            worker.start();
        }

        // Khởi tạo LoadBalancer
        loadBalancer = new LoadBalancer(workers);

        System.out.println("Started " + WORKER_COUNT + " worker threads");
    }

    private void startStatsReporting() {
        statsScheduler = Executors.newScheduledThreadPool(1);
        statsScheduler.scheduleAtFixedRate(() -> {
            System.out.println("\n" + loadBalancer.getLoadStats());
            System.out.println("Screen frames captured: " + Screen.seq.get());
        }, 30, 30, TimeUnit.SECONDS);
    }

    private void runServerLoop() {
        System.out.println("Server loop started - waiting for clients...");

        while (running) {
            try {
                // Non-blocking select với timeout
                int readyChannels = selector.select(1000);

                if (readyChannels == 0) {
                    continue; // Timeout
                }

                var selectedKeys = selector.selectedKeys();
                var keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    if (key.isValid() && key.isAcceptable()) {
                        handleAccept();
                    }
                }

            } catch (IOException e) {
                System.err.println("Server loop error: " + e.getMessage());
                break;
            }
        }
    }

    private void handleAccept() {
        try {
            SocketChannel clientChannel = serverChannel.accept();
            if (clientChannel != null) {
                clientChannel.configureBlocking(false);

                // Phân phối client cho worker
                loadBalancer.addClient(clientChannel);
            }
        } catch (IOException e) {
            System.err.println("Error accepting client: " + e.getMessage());
        }
    }

    private void shutdown() {
        System.out.println("\nShutting down server...");

        running = false;

        // Dừng stats reporting
        if (statsScheduler != null) {
            statsScheduler.shutdown();
        }

        // Dừng screen capture
        if (screenCaptureThread != null) {
            screenCaptureThread.stopCapture();
        }

        // Dừng tất cả workers
        if (loadBalancer != null) {
            loadBalancer.stopAllWorkers();
        }

        // Đóng server channel
        try {
            if (serverChannel != null) {
                serverChannel.close();
            }
            if (selector != null) {
                selector.close();
            }
        } catch (IOException e) {
            System.err.println("Error closing server resources: " + e.getMessage());
        }

        System.out.println("Server shutdown complete");
    }
}
