package Buoi7;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Worker thread quản lý nhiều client bằng NIO Selector
 * Mỗi worker có một event loop để xử lý non-blocking I/O
 */
public class Worker extends Thread {
    private final Selector selector;
    private final AtomicInteger clientCount = new AtomicInteger(0);
    private final ConcurrentHashMap<SocketChannel, ClientSession> clients = new ConcurrentHashMap<>();
    private final ScreenCaptureThread screenCaptureThread;
    private volatile boolean running = true;
    private int lastSentFrameCount = 0;

    public Worker(ScreenCaptureThread screenCaptureThread) throws IOException {
        super("Worker-" + System.currentTimeMillis());
        this.selector = Selector.open();
        this.screenCaptureThread = screenCaptureThread;
        setDaemon(true);
    }

    @Override
    public void run() {
        System.out.println(getName() + " started");

        while (running) {
            try {
                // Non-blocking select với timeout 10ms
                int readyChannels = selector.select(10);

                if (readyChannels == 0) {
                    // Timeout - kiểm tra frame mới và gửi cho client có thể write
                    sendLatestFrameToReadyClients();
                    continue;
                }

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();

                    if (key.isValid()) {
                        if (key.isWritable()) {
                            handleWrite(key);
                        }
                        if (key.isReadable()) {
                            handleRead(key);
                        }
                    }
                }

            } catch (IOException e) {
                System.err.println(getName() + " selector error: " + e.getMessage());
            }
        }

        System.out.println(getName() + " stopped");
    }

    private void sendLatestFrameToReadyClients() {
        byte[] latestFrame = screenCaptureThread.getLatestFrame();
        int currentFrameCount = screenCaptureThread.getFrameCount();

        // Chỉ gửi nếu có frame mới
        if (latestFrame != null && currentFrameCount != lastSentFrameCount) {
            lastSentFrameCount = currentFrameCount;

            for (ClientSession session : clients.values()) {
                if (session.canWrite()) {
                    session.prepareFrame(latestFrame);
                }
            }
        }
    }

    private void handleWrite(SelectionKey key) {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientSession session = clients.get(channel);

        if (session != null && session.hasFrameToSend()) {
            try {
                ByteBuffer buffer = session.getWriteBuffer();
                int bytesWritten = channel.write(buffer);

                if (bytesWritten > 0) {
                    session.markFrameSent();
                }

                // Nếu buffer còn data, giữ OP_WRITE
                if (buffer.hasRemaining()) {
                    key.interestOps(SelectionKey.OP_WRITE);
                } else {
                    // Frame đã gửi xong, chỉ quan sát OP_READ
                    key.interestOps(SelectionKey.OP_READ);
                }

            } catch (IOException e) {
                try {
                    System.err.println("Write error for client " + channel.getRemoteAddress() + ": " + e.getMessage());
                } catch (IOException ie) {
                    System.err.println("Write error for client (unknown address): " + e.getMessage());
                }
                removeClient(channel);
            }
        }
    }

    private void handleRead(SelectionKey key) {
        SocketChannel channel = (SocketChannel) key.channel();

        try {
            ByteBuffer buffer = ByteBuffer.allocate(1024);
            int bytesRead = channel.read(buffer);

            if (bytesRead == -1) {
                // Client đóng connection
                removeClient(channel);
            } else if (bytesRead > 0) {
                // Client gửi data (có thể là ping/pong)
                // Ở đây chỉ đơn giản là echo hoặc ignore
            }

        } catch (IOException e) {
            try {
                System.err.println("Read error for client " + channel.getRemoteAddress() + ": " + e.getMessage());
            } catch (IOException ie) {
                System.err.println("Read error for client (unknown address): " + e.getMessage());
            }
            removeClient(channel);
        }
    }

    public void addClient(SocketChannel channel) throws IOException {
        try {
            channel.configureBlocking(false);
            SelectionKey key = channel.register(selector, SelectionKey.OP_READ);

            ClientSession session = new ClientSession(channel, key);
            clients.put(channel, session);
            clientCount.incrementAndGet();

            System.out.println(getName() + " added client " + channel.getRemoteAddress() +
                    " (total: " + clientCount.get() + ")");
        } catch (IOException e) {
            System.err.println("Error adding client: " + e.getMessage());
            throw e;
        }
    }

    private void removeClient(SocketChannel channel) {
        ClientSession session = clients.remove(channel);
        if (session != null) {
            try {
                session.close();
                clientCount.decrementAndGet();
                System.out.println(getName() + " removed client " + channel.getRemoteAddress() +
                        " (total: " + clientCount.get() + ")");
            } catch (Exception e) {
                System.err.println("Error removing client: " + e.getMessage());
            }
        }
    }

    public int getClientCount() {
        return clientCount.get();
    }

    public void stopWorker() {
        running = false;
        selector.wakeup();

        // Đóng tất cả client connections
        for (SocketChannel channel : clients.keySet()) {
            try {
                channel.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }

    /**
     * Session quản lý state của mỗi client
     */
    private static class ClientSession {
        private final SocketChannel channel;
        private final SelectionKey key;
        private ByteBuffer writeBuffer;
        private boolean hasFrameToSend = false;

        public ClientSession(SocketChannel channel, SelectionKey key) {
            this.channel = channel;
            this.key = key;
        }

        public void prepareFrame(byte[] frameData) {
            if (!hasFrameToSend) { // Drop frame cũ nếu chưa gửi xong
                writeBuffer = ByteBuffer.allocate(4 + frameData.length);
                writeBuffer.putInt(frameData.length);
                writeBuffer.put(frameData);
                writeBuffer.flip();
                hasFrameToSend = true;

                // Đăng ký OP_WRITE để có thể gửi
                key.interestOps(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
            }
        }

        public boolean canWrite() {
            return !hasFrameToSend;
        }

        public boolean hasFrameToSend() {
            return hasFrameToSend;
        }

        public ByteBuffer getWriteBuffer() {
            return writeBuffer;
        }

        public void markFrameSent() {
            if (!writeBuffer.hasRemaining()) {
                hasFrameToSend = false;
            }
        }

        public void close() throws IOException {
            channel.close();
        }
    }
}
