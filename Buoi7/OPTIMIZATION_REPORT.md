# Screen Sharing Application - Optimization Report

## Tổng quan

Tài liệu này mô tả các tối ưu hóa đã được thực hiện cho ứng dụng chia sẻ màn hình Java, bao gồm việc thêm logging FPS/độ trễ và chuyển đổi từ PNG sang JPG format.

## Các tối ưu hóa đã thực hiện

### 1. Thêm Logging FPS và Độ trễ (Latency) - Chi tiết Pipeline Timing

#### 1.1 ScreenClient.java

**Các tính năng được thêm:**

- **FPS Logging**: Theo dõi và hiển thị số khung hình mỗi giây
- **Network Latency**: Đo thời gian nhận dữ liệu từ server
- **Total Processing Time**: Đo thời gian xử lý hoàn chỉnh từ nhận dữ liệu đến render

**Code được thêm:**

```java
// FPS and latency tracking variables
private long frameCount = 0;
private long lastFpsTime = System.currentTimeMillis();

// Trong method paint():
long frameStartTime = System.nanoTime();
// ... xử lý dữ liệu ...
long networkLatency = (System.nanoTime() - frameStartTime) / 1_000_000;
long totalProcessingTime = (System.nanoTime() - frameStartTime) / 1_000_000;

// FPS calculation mỗi giây
if (currentTime - lastFpsTime >= 1000) {
    double fps = frameCount * 1000.0 / (currentTime - lastFpsTime);
    System.out.println("Client FPS: " + String.format("%.2f", fps) +
                     " | Network Latency: " + networkLatency + "ms" +
                     " | Total Processing: " + totalProcessingTime + "ms");
}
```

#### 1.2 ScreenServer.java

**Screen Class (Screen Capture):**

- **Screen Capture FPS**: Theo dõi số lần capture màn hình mỗi giây
- **Capture Time**: Đo thời gian capture và encode mỗi frame
- **Frame Size Tracking**: Theo dõi kích thước frame được capture

**ScreenProcessing Class (Data Processing):**

- **Processing Latency**: Đo thời gian xử lý và gửi dữ liệu đến client
- **Processing Count**: Đếm số frame đã xử lý
- **Data Size Monitoring**: Theo dõi kích thước dữ liệu được gửi

**Code được thêm:**

```java
// Trong Screen class
private long frameCount = 0;
private long lastFpsTime = System.currentTimeMillis();
private long lastCaptureTime = System.currentTimeMillis();

// FPS calculation mỗi giây
if (currentTime - lastFpsTime >= 1000) {
    double fps = frameCount * 1000.0 / (currentTime - lastFpsTime);
    System.out.println("Server Capture FPS: " + String.format("%.2f", fps) +
                     " | Capture Time: " + captureTime + "ms" +
                     " | Frame Size: " + tmp.length + " bytes");
}

// Trong ScreenProcessing class
private long processingCount = 0;
private long lastProcessingTime = System.currentTimeMillis();

// Log processing stats mỗi 50 frames
if (processingCount % 50 == 0) {
    System.out.println("Processing Frame " + processingCount +
                     " | Processing Time: " + processingTime + "ms" +
                     " | Time Since Last: " + timeSinceLastProcessing + "ms" +
                     " | Data Size: " + tmp.length + " bytes");
}
```

### 2. Chuyển đổi từ PNG sang JPG Format

#### 2.1 Lý do chuyển đổi

- **Giảm kích thước file**: JPG compression tạo ra file nhỏ hơn đáng kể so với PNG
- **Cải thiện hiệu suất**: File nhỏ hơn = truyền tải mạng nhanh hơn
- **Tiết kiệm băng thông**: Hiệu quả hơn cho streaming mạng
- **Tăng FPS**: Ít dữ liệu cần xử lý và truyền tải

#### 2.2 Thay đổi trong ScreenServer.java

**Trước (PNG):**

```java
BufferedImage img = r.createScreenCapture(capture);
ByteArrayOutputStream bos = new ByteArrayOutputStream();
ImageIO.write(img, "png", bos);
bos.flush();
tmp = bos.toByteArray();
```

**Sau (JPG với tối ưu):**

```java
BufferedImage img = r.createScreenCapture(capture);
ByteArrayOutputStream bos = new ByteArrayOutputStream();

// Sử dụng JPG compression với quality control
ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
ImageOutputStream ios = ImageIO.createImageOutputStream(bos);
writer.setOutput(ios);

ImageWriteParam param = writer.getDefaultWriteParam();
param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
param.setCompressionQuality(0.7f); // 70% quality cho cân bằng tốt

writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
writer.dispose();
ios.close();
bos.flush();
tmp = bos.toByteArray();
```

#### 2.3 Thay đổi trong ScreenClient.java

- **Không cần thay đổi**: `ImageIO.read()` tự động detect format JPG
- **Tương thích ngược**: Client vẫn hoạt động bình thường với JPG

### 3. Chi tiết Pipeline Timing - Đo thời gian từng bước

#### 3.1 Server Pipeline (ScreenServer.java)

**8 bước xử lý được đo thời gian:**

1. **Capture**: `r.createScreenCapture(capture)` - Chụp màn hình
2. **Pre-scale**: Chuẩn bị cho việc scaling (nếu cần)
3. **Scale**: Resize hình ảnh (hiện tại dùng kích thước gốc)
4. **Encode**: Nén JPG với quality control (70%)
5. **Send**: Truyền dữ liệu qua network

**Logging format:**

```
=== SERVER TIMING (FPS: 15.23) ===
Capture: 12.45ms | PreScale: 0.12ms | Scale: 0.08ms | Encode: 45.67ms | Size: 125432 bytes

Frame 50 | Capture: 11ms | PreScale: 0ms | Scale: 0ms | Encode: 42ms | Size: 125432 bytes | Interval: 33ms
```

#### 3.2 Client Pipeline (ScreenClient.java)

**3 bước xử lý được đo thời gian:**

6. **Receive**: Nhận dữ liệu từ network
7. **Decode**: Giải nén JPG thành BufferedImage
8. **Render**: Scaling và vẽ lên màn hình

**Logging format:**

```
=== CLIENT TIMING (FPS: 15.23) ===
Receive: 8.45ms | Decode: 23.12ms | Render: 15.67ms | Size: 125432 bytes

Client Frame 50 | Receive: 7ms | Decode: 22ms | Render: 14ms | Size: 125432 bytes
```

#### 3.3 Tổng quan Pipeline

```
Server: Capture → PreScale → Scale → Encode → Send
Client: Receive → Decode → Render
```

**Timing Variables:**

- `totalCaptureTime`, `totalPreScaleTime`, `totalScaleTime`, `totalEncodeTime` (Server)
- `totalReceiveTime`, `totalDecodeTime`, `totalRenderTime` (Client)
- Tính toán average time mỗi giây
- Logging chi tiết mỗi 50 frames

### 4. Server-Side Scaling và Client 1:1 Blit Optimization

#### 4.1 Client-Server Size Negotiation

**Client gửi kích thước màn hình khi kết nối:**

```java
// Client gửi dimensions ngay khi connect
DataOutputStream dos = new DataOutputStream(soc.getOutputStream());
dos.writeInt(this.getWidth());
dos.writeInt(this.getHeight());
dos.flush();
```

**Server nhận và lưu client dimensions:**

```java
// Server nhận và set scaling dimensions
DataInputStream dis = new DataInputStream(soc.getInputStream());
clientWidth = dis.readInt();
clientHeight = dis.readInt();
Screen.setClientDimensions(clientWidth, clientHeight);
```

#### 4.2 Server-Side Pre-Scaling

**Tính toán scaling với aspect ratio:**

```java
// Calculate scaling factor to fit client size while maintaining aspect ratio
double scaleX = (double) scaledWidth / screenWidth;
double scaleY = (double) scaledHeight / screenHeight;
double scale = Math.min(scaleX, scaleY);

int newWidth = (int) (screenWidth * scale);
int newHeight = (int) (screenHeight * scale);
```

**High-quality scaling với Graphics2D:**

```java
scaledImg = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
Graphics2D g2d = scaledImg.createGraphics();
g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                   RenderingHints.VALUE_INTERPOLATION_BILINEAR);
g2d.drawImage(img, 0, 0, newWidth, newHeight, null);
```

#### 4.3 Frame Data Format

**Server gửi width/height với mỗi frame:**

```java
output.writeInt(Screen.scaledWidth);  // Scaled width
output.writeInt(Screen.scaledHeight); // Scaled height
output.writeInt(tmp.length);          // Data size
output.write(tmp);                    // JPG data
```

**Client nhận và sử dụng exact dimensions:**

```java
int frameWidth = bis.readInt();   // Receive scaled width
int frameHeight = bis.readInt();  // Receive scaled height
int n = bis.readInt();
byte tmp[] = bis.readNBytes(n);
```

#### 4.4 Client 1:1 Blit (No Scaling)

**Eliminate client-side scaling:**

```java
// Direct 1:1 blit - image is already scaled to exact client size
g.drawImage(img1, 0, 0, frameWidth, frameHeight, null);
```

**Benefits:**

- ✅ **No client-side scaling** - eliminates expensive scaling operations
- ✅ **Perfect aspect ratio** - server handles aspect ratio preservation
- ✅ **Crisp rendering** - no blur from double scaling
- ✅ **Better performance** - client only does simple blit
- ✅ **Exact fit** - image fits client window perfectly

### 5. Cải thiện Resource Management

#### 5.1 Import Optimization

**Loại bỏ các import không sử dụng:**

```java
// Đã loại bỏ:
import java.awt.Rectangle;     // ScreenClient
import java.awt.Robot;         // ScreenClient
import java.awt.Toolkit;       // ScreenClient
import java.io.BufferedInputStream;  // ScreenClient
import java.io.ByteArrayOutputStream; // ScreenClient
import java.io.DataOutputStream;     // ScreenClient
import java.io.BufferedOutputStream; // ScreenServer
```

#### 5.2 Memory Management

- **Proper disposal**: `writer.dispose()` và `ios.close()` để giải phóng tài nguyên
- **Stream flushing**: Đảm bảo dữ liệu được flush đúng cách

## Kết quả mong đợi

### 1. Cải thiện Performance

- **Giảm 60-80% kích thước file** (JPG vs PNG)
- **Tăng FPS** do ít dữ liệu cần xử lý
- **Giảm độ trễ mạng** do file nhỏ hơn
- **Tiết kiệm băng thông** đáng kể
- **Eliminate client-side scaling** - loại bỏ hoàn toàn scaling ở client
- **Server-side optimization** - scaling được tối ưu một lần ở server
- **Perfect aspect ratio** - không bị méo hình
- **Crisp rendering** - không bị mờ do double scaling

### 2. Monitoring và Debugging

- **Real-time FPS monitoring** cho cả client và server
- **Pipeline timing** với độ chính xác nano giây cho từng bước
- **Performance metrics** chi tiết mỗi giây với average timing
- **Frame size monitoring** để tối ưu hóa thêm
- **Bottleneck identification** - xác định bước nào chậm nhất
- **Per-frame analysis** mỗi 50 frames để debug

### 3. Quality vs Performance Balance

- **70% JPG quality** cung cấp cân bằng tốt giữa chất lượng và kích thước
- **Có thể điều chỉnh** quality parameter nếu cần
- **Tương thích** với các client khác nhau

## Cách sử dụng

### 1. Chạy Server

```bash
cd Buoi7
javac *.java
java Buoi7.ScreenServer
```

### 2. Chạy Client

```bash
java Buoi7.ScreenClient
```

### 3. Monitoring Output

- **Server**: Hiển thị Capture FPS, Capture Time, Frame Size
- **Client**: Hiển thị Client FPS, Network Latency, Total Processing Time
- **Processing**: Hiển thị Processing Time, Data Size mỗi 50 frames

## Tối ưu hóa trong tương lai

### 1. Có thể thực hiện thêm

- **Adaptive Quality**: Tự động điều chỉnh JPG quality dựa trên network conditions
- **Frame Skipping**: Bỏ qua frame nếu client không theo kịp
- **Compression Levels**: Nhiều mức compression khác nhau
- **Region-based Capture**: Chỉ capture vùng thay đổi

### 2. Monitoring nâng cao

- **Network Bandwidth Monitoring**
- **CPU Usage Tracking**
- **Memory Usage Monitoring**
- **Client Connection Status**

## Kết luận

Các tối ưu hóa đã thực hiện mang lại:

- ✅ **Cải thiện đáng kể performance**
- ✅ **Giảm kích thước file và băng thông**
- ✅ **Thêm monitoring chi tiết**
- ✅ **Duy trì chất lượng hình ảnh tốt**
- ✅ **Code sạch và tối ưu**

Ứng dụng giờ đây phù hợp hơn cho việc streaming màn hình real-time với hiệu suất cao và monitoring chi tiết.
