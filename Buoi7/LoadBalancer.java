package Buoi7;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LoadBalancer phân phối client cho các Worker thread
 * Sử dụng round-robin để cân bằng tải
 */
public class LoadBalancer {
    private final List<Worker> workers;
    private final AtomicInteger currentWorkerIndex = new AtomicInteger(0);

    public LoadBalancer(List<Worker> workers) {
        this.workers = new CopyOnWriteArrayList<>(workers);
    }

    /**
     * Chọn worker có ít client nhất để xử lý client mới
     */
    public Worker selectWorker() {
        if (workers.isEmpty()) {
            throw new IllegalStateException("No workers available");
        }

        // Tìm worker có ít client nhất
        Worker selectedWorker = workers.get(0);
        int minClientCount = selectedWorker.getClientCount();

        for (Worker worker : workers) {
            int clientCount = worker.getClientCount();
            if (clientCount < minClientCount) {
                minClientCount = clientCount;
                selectedWorker = worker;
            }
        }

        return selectedWorker;
    }

    /**
     * Round-robin selection (backup method)
     */
    public Worker selectWorkerRoundRobin() {
        if (workers.isEmpty()) {
            throw new IllegalStateException("No workers available");
        }

        int index = currentWorkerIndex.getAndIncrement() % workers.size();
        return workers.get(index);
    }

    /**
     * Thêm client vào worker được chọn
     */
    public void addClient(SocketChannel clientChannel) throws IOException {
        Worker selectedWorker = selectWorker();
        selectedWorker.addClient(clientChannel);

        System.out.println("Client " + clientChannel.getRemoteAddress() +
                " assigned to " + selectedWorker.getName() +
                " (clients: " + selectedWorker.getClientCount() + ")");
    }

    /**
     * Lấy thống kê tải của tất cả workers
     */
    public String getLoadStats() {
        StringBuilder stats = new StringBuilder("Worker Load Stats:\n");
        for (Worker worker : workers) {
            stats.append("  ").append(worker.getName())
                    .append(": ").append(worker.getClientCount()).append(" clients\n");
        }
        return stats.toString();
    }

    /**
     * Dừng tất cả workers
     */
    public void stopAllWorkers() {
        System.out.println("Stopping all workers...");
        for (Worker worker : workers) {
            worker.stopWorker();
        }
    }
}
