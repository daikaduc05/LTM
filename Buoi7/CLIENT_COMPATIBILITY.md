# Client Compatibility Guide

## Vấn đề tương thích

Server đã được tối ưu hóa với protocol mới:

- **Protocol cũ**: `length(4) + jpeg_data`
- **Protocol mới**: `seq(8) + tsMillis(8) + length(4) + jpeg_data`

## Các Client có sẵn

### 1. `ScreenClient.java` - Client cho Server mới

- **Sử dụng**: Chỉ tương thích với server đã tối ưu hóa
- **Tính năng**:
  - Hiển thị sequence number và frame count
  - Tính toán latency từ timestamp
  - TCP_NODELAY để giảm độ trễ
  - Error handling tốt hơn

### 2. `CompatibleScreenClient.java` - Client tương thích ngược

- **Sử dụng**: Hoạt động với cả server cũ và mới
- **Tính năng**:
  - Tự động phát hiện protocol
  - Fallback về protocol cũ nếu cần
  - Hiển thị thông tin phù hợp với từng protocol

## Cách sử dụng

### Để test với server mới:

```bash
javac Buoi7/ScreenClient.java
java Buoi7.ScreenClient
```

### Để test với cả server cũ và mới:

```bash
javac Buoi7/CompatibleScreenClient.java
java Buoi7.CompatibleScreenClient
```

## Thông tin hiển thị

### Server mới + ScreenClient:

- Title: "Share Screen - Frame #X (Seq: Y)"
- Console: Frame info với latency

### Server cũ + CompatibleScreenClient:

- Title: "Share Screen - Frame #X (Legacy)"
- Console: Frame info đơn giản

### Server mới + CompatibleScreenClient:

- Title: "Share Screen - Frame #X (Seq: Y)"
- Console: Frame info với latency

## Troubleshooting

1. **Client không nhận được frame**: Kiểm tra IP address trong code
2. **Protocol error**: Sử dụng CompatibleScreenClient
3. **Performance issues**: Đảm bảo sử dụng server đã tối ưu hóa
