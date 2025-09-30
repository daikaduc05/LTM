package Buoi7;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/**
 * Encoder JPEG tái sử dụng (giảm alloc + setup)
 */
final class JpegEncoder implements AutoCloseable {
    private final ImageWriter writer;
    private final ImageWriteParam param;
    private final ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20); // ~1MB

    JpegEncoder(float quality) {
        ImageIO.setUseCache(false); // tránh I/O đĩa tạm
        writer = ImageIO.getImageWritersByFormatName("jpg").next();
        param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
    }

    synchronized void setQuality(float q) {
        param.setCompressionQuality(q);
    }

    synchronized byte[] encode(BufferedImage img) throws Exception {
        bos.reset();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
        }
        return bos.toByteArray();
    }

    @Override
    public void close() {
        writer.dispose();
    }
}
