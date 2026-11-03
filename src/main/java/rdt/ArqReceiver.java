package rdt;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.TreeMap;


public final class ArqReceiver {
    private final DatagramSocket sock;
    private final Path out;
    private final int lingerMs;
    private final byte[] rbuf = new byte[70000];

    public long dataReceived, duplicates, outOfOrder, corruptDropped, acksSent;
    public boolean complete, integrityOk;
    public String sha256 = "";
    public double elapsedSec;

    public ArqReceiver(DatagramSocket sock, Path out, int lingerMs) {
        this.sock = sock;
        this.out = out;
        this.lingerMs = lingerMs;
    }

    public void run(int startTimeoutMs) throws Exception {
        // ---- wait for SYN ----
        long fileSize = 0;
        int segSize = 0, window = 1, total = 0;
        Protocol proto = null;
        byte[] expectedSha = null;
        SocketAddress peer = null;
        long deadline = System.currentTimeMillis() + startTimeoutMs;
        while (proto == null) {
            Packet p = receive(Math.max(1, (int) (deadline - System.currentTimeMillis())));
            if (p == null) {
                if (System.currentTimeMillis() > deadline) throw new IllegalStateException("no SYN received");
                continue;
            }
            if (p.type != Packet.SYN || p.payload.length < ArqSender.SYN_PAYLOAD) continue;
            ByteBuffer bb = ByteBuffer.wrap(p.payload);
            fileSize = bb.getLong();
            segSize = bb.getInt();
            proto = Protocol.fromId(bb.get());
            window = bb.getInt();
            expectedSha = new byte[32];
            bb.get(expectedSha);
            peer = lastPeer;
            total = (int) ((fileSize + segSize - 1) / segSize);
            sendAck(peer, 1, 0);
        }

        long t0 = System.nanoTime();
        int expected = 1;
        TreeMap<Integer, byte[]> buf = new TreeMap<>();
        boolean selective = proto == Protocol.SR;
        boolean fin = false;

        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(out), 1 << 20)) {
            while (true) {
                if (expected > total) { complete = true; }
                Packet p = receive(complete ? lingerMs : 30_000);
                if (p == null) {
                    if (complete) break;          // linger expired
                    throw new IllegalStateException("receiver idle timeout");
                }
                peer = lastPeer;
                if (p.type == Packet.SYN) { sendAck(peer, expected, sackBits(buf, expected)); continue; }
                if (p.type == Packet.FIN) { if (complete) { fin = true; break; } continue; }
                if (p.type != Packet.DATA) continue;
                dataReceived++;
                int seq = p.seq;
                if (seq < expected || (selective && buf.containsKey(seq))) {
                    duplicates++;
                } else if (seq == expected) {
                    os.write(p.payload);
                    expected++;
                    if (selective) {
                        while (buf.containsKey(expected)) { os.write(buf.remove(expected)); expected++; }
                    }
                } else {
                    outOfOrder++;
                    if (selective && seq < expected + window && seq <= total) buf.put(seq, p.payload);
                }
                sendAck(peer, expected, selective ? sackBits(buf, expected) : 0);
                if (expected > total && !complete) {
                    complete = true;
                    elapsedSec = (System.nanoTime() - t0) / 1e9;
                }
            }
        }
        if (elapsedSec == 0) elapsedSec = (System.nanoTime() - t0) / 1e9;
        byte[] got = Sha256Util.digest(out);
        sha256 = Sha256Util.hex(got);
        integrityOk = complete && Arrays.equals(got, expectedSha) && Files.size(out) == fileSize;
    }

    private static long sackBits(TreeMap<Integer, byte[]> buf, int expected) {
        long bits = 0;
        for (int s : buf.keySet()) {
            int i = s - expected - 1;
            if (i >= 0 && i < 64) bits |= 1L << i;
        }
        return bits;
    }

    
    private static final Packet NOISE = new Packet((byte) 0, 0, 0, 0, null);

    private SocketAddress lastPeer;

    private Packet receive(int timeoutMs) throws Exception {
        sock.setSoTimeout(Math.max(1, timeoutMs));
        DatagramPacket dp = new DatagramPacket(rbuf, rbuf.length);
        try {
            sock.receive(dp);
        } catch (SocketTimeoutException e) {
            return null;
        }
        Packet p = Packet.decode(rbuf, dp.getLength());
        if (p == null) { corruptDropped++; return NOISE; }
        lastPeer = dp.getSocketAddress();
        return p;
    }

    private void sendAck(SocketAddress to, int ack, long sack) throws Exception {
        byte[] w = new Packet(Packet.ACK, 0, ack, sack, null).encode();
        sock.send(new DatagramPacket(w, w.length, to));
        acksSent++;
    }

    public Json stats(String protocolName) {
        return new Json().put("role", "receiver").put("protocol", protocolName)
            .put("complete", complete).put("integrity_ok", integrityOk).put("sha256", sha256)
            .put("data_received", dataReceived).put("duplicates", duplicates)
            .put("out_of_order", outOfOrder).put("corrupt_dropped", corruptDropped)
            .put("acks_sent", acksSent).put("elapsed_s", Math.round(elapsedSec * 10000) / 10000.0);
    }
}
