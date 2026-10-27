package rdt;

/** Hand-built 16-bit ones'-complement Internet checksum (RFC 1071 style). */
public final class Checksum {
    private Checksum() {}

    public static int compute(byte[] b, int off, int len) {
        long sum = 0;
        int i = off, end = off + len;
        while (i + 1 < end) {
            sum += ((b[i] & 0xff) << 8) | (b[i + 1] & 0xff);
            i += 2;
        }
        if (i < end) sum += (b[i] & 0xff) << 8; // pad odd trailing byte with zero
        while ((sum >> 16) != 0) sum = (sum & 0xffff) + (sum >> 16);
        return (int) (~sum) & 0xffff;
    }
}
