package rdt;

import java.nio.ByteBuffer;

/**
 * Wire format (big endian), 22-byte header + payload:
 * <pre>
 *  0      type      (1)  SYN=1 DATA=2 ACK=3 FIN=4
 *  1      flags     (1)  reserved
 *  2..5   seq       (4)  segment number (SYN=0, data = 1..N)
 *  6..9   ack       (4)  cumulative: next segment expected (everything below received)
 * 10..17  sack      (8)  bit i set => segment (ack + 1 + i) is buffered at receiver
 * 18..19  length    (2)  payload bytes
 * 20..21  checksum  (2)  Internet checksum over whole datagram (field zeroed)
 * </pre>
 */
public final class Packet {
    public static final int HEADER = 22;
    public static final byte SYN = 1, DATA = 2, ACK = 3, FIN = 4;

    public byte type;
    public int seq;
    public int ack;
    public long sack;
    public byte[] payload = new byte[0];

    public Packet(byte type, int seq, int ack, long sack, byte[] payload) {
        this.type = type;
        this.seq = seq;
        this.ack = ack;
        this.sack = sack;
        if (payload != null) this.payload = payload;
    }

    public byte[] encode() {
        int plen = payload.length;
        byte[] b = new byte[HEADER + plen];
        ByteBuffer bb = ByteBuffer.wrap(b);
        bb.put(type).put((byte) 0).putInt(seq).putInt(ack).putLong(sack)
          .putShort((short) plen).putShort((short) 0);
        bb.put(payload);
        int c = Checksum.compute(b, 0, b.length);
        b[20] = (byte) (c >> 8);
        b[21] = (byte) c;
        return b;
    }

    /** Returns null for truncated or corrupted datagrams (checksum mismatch). */
    public static Packet decode(byte[] buf, int n) {
        if (n < HEADER) return null;
        ByteBuffer bb = ByteBuffer.wrap(buf, 0, n);
        byte type = bb.get();
        bb.get();
        int seq = bb.getInt();
        int ack = bb.getInt();
        long sack = bb.getLong();
        int plen = bb.getShort() & 0xffff;
        int stored = bb.getShort() & 0xffff;
        if (n != HEADER + plen) return null;
        if (type < SYN || type > FIN) return null;
        byte[] copy = new byte[n];
        System.arraycopy(buf, 0, copy, 0, n);
        copy[20] = 0;
        copy[21] = 0;
        if (Checksum.compute(copy, 0, n) != stored) return null;
        byte[] payload = new byte[plen];
        System.arraycopy(buf, HEADER, payload, 0, plen);
        return new Packet(type, seq, ack, sack, payload);
    }
}
