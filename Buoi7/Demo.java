package Buoi7;

/**
 * Demo class để test hệ thống NIO Screen Sharing
 * Chạy server và client để kiểm tra hoạt động
 */
public class Demo {
    public static void main(String[] args) {
        System.out.println("=== NIO Screen Sharing Demo ===");
        System.out.println("1. Start server: java Buoi7.MainServer");
        System.out.println("2. Start client: java Buoi7.ScreenClient");
        System.out.println("3. Server sẽ log worker stats mỗi 30 giây");
        System.out.println("4. Client sẽ hiển thị screen sharing window");
        System.out.println("================================");

        // Có thể start server ngay tại đây nếu muốn
        if (args.length > 0 && "server".equals(args[0])) {
            System.out.println("Starting server...");
            MainServer.main(new String[0]);
        } else {
            System.out.println("To start server: java Buoi7.Demo server");
        }
    }
}
