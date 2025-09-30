package Buoi7;

/**
 * Frame bất biến – publish một lần, mọi client dùng chung cùng byte[]
 */
final class Frame {
    final long seq;
    final long tsMillis;
    final byte[] jpeg;

    Frame(long seq, long tsMillis, byte[] jpeg) {
        this.seq = seq;
        this.tsMillis = tsMillis;
        this.jpeg = jpeg;
    }
}
