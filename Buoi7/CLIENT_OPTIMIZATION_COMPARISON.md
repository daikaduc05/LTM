# Client Optimization Comparison

## Cải tiến chính trong ScreenClient mới

### **1. Kiến trúc Thread Separation**

**Trước:**

- Tất cả logic trong `paint()` method
- Blocking I/O trên EDT (Event Dispatch Thread)
- Gây lag UI khi đọc dữ liệu

**Sau:**

- Separate reader thread cho I/O operations
- EDT chỉ xử lý rendering
- Non-blocking, responsive UI

### **2. Rendering Optimization**

**Trước:**

```java
public void paint(Graphics g) {
    // Đọc data + decode + render trong cùng method
    DataInputStream bis = new DataInputStream(soc.getInputStream());
    // ... blocking operations
    g.drawImage(img2, off, off, ...);
    this.repaint(); // Recursive call!
}
```

**Sau:**

```java
class ViewerPanel extends JPanel {
    volatile BufferedImage lastRendered;

    @Override
    protected void paintComponent(Graphics g) {
        // Chỉ render, không đọc data
        BufferedImage img = lastRendered;
        g.drawImage(img, 0, 0, getWidth(), getHeight(), null);
        Toolkit.getDefaultToolkit().sync(); // Giảm tearing
    }
}
```

### **3. Performance Monitoring**

**Trước:**

- Chỉ log mỗi 30 frames
- Không có thống kê bandwidth/FPS

**Sau:**

```java
// Thống kê real-time mỗi giây
double fps = recvFrames * 1000.0 / (now - recvLastMs);
double mbps = (recvBytes * 8.0) / 1_000_000.0;
System.out.printf("[CLIENT] recvFPS=%.1f, recvMbps=%.2f, latency~%d ms, lastSeq=%d%n",
        fps, mbps, latency, seq);
```

### **4. Memory Management**

**Trước:**

- Tạo DataInputStream mới mỗi frame
- Không tối ưu BufferedImage handling

**Sau:**

- Reuse DataInputStream
- Direct BufferedImage assignment
- `ImageIO.setUseCache(false)` để giảm I/O overhead

### **5. Swing EDT Handling**

**Trước:**

- `this.repaint()` trong paint() → recursive calls
- Không kiểm soát repaint frequency

**Sau:**

```java
// Atomic flag để tránh spam EDT
if (repaintScheduled.compareAndSet(false, true)) {
    SwingUtilities.invokeLater(() -> {
        panel.repaint();
        repaintScheduled.set(false);
    });
}
```

### **6. Visual Improvements**

**Trước:**

- Window size: 500x400
- Border offset: 50px
- Manual scaling

**Sau:**

- Window size: 1000x650 (full screen friendly)
- Full panel rendering
- No manual scaling (server handles it)

## Kết quả Performance

### **Latency Reduction:**

- Loại bỏ blocking I/O trên EDT
- Direct image assignment thay vì scaling
- Toolkit.sync() giảm tearing

### **CPU Usage:**

- Separation of concerns
- Reduced redundant operations
- Better thread utilization

### **Memory Efficiency:**

- Reuse objects
- No recursive repaint calls
- Optimized image handling

### **User Experience:**

- Responsive UI
- Real-time performance stats
- Better visual quality
- No UI freezing

## Usage

```bash
# Compile và chạy
javac Buoi7/ScreenClient.java
java Buoi7.ScreenClient
```

**Output mẫu:**

```
Connected to server: localhost/127.0.0.1:2345
[CLIENT] recvFPS=29.8, recvMbps=12.5, latency~45 ms, lastSeq=1234
[CLIENT] recvFPS=30.1, recvMbps=13.2, latency~42 ms, lastSeq=1264
```

Client mới này cung cấp trải nghiệm mượt mà và hiệu suất cao hơn đáng kể so với phiên bản trước!
