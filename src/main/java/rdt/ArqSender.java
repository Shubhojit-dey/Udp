package rdt;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Common sender skeleton. Each protocol subclass implements transfer() as a SINGLE-THREADED
 * event loop: send what the window allows, then block on the socket until the earliest
 * retransmission deadline. Because timers and ACK processing share one thread there is no
 * timer/ACK race (this is the "centralised timer" fallback named in the proposal's risk table).
 */
public abstract class ArqSender {
    public static final int SYN_PAYLOAD = 8 + 4 + 1 + 4 + 32;

    protected final DatagramSocket sock;
    protected final SocketAddress peer;
    protected final byte[] data;
    protected final int segSize, window, total;
    protected final RtoEstimator rto;
    protected final Protocol proto;
    protected final byte[] sha;
    private final byte[] rbuf = new byte[4096];

    public long transmissions, retransmissions, timeouts, badPackets;
    public double elapsedSec;

    protected ArqSender(Protocol proto, DatagramSocket sock, SocketAddress peer, byte[] data,
                        byte[] sha, int segSize, int window, RtoEstimator rto) {
        this.proto = proto;
        this.sock = sock;
        this.peer = peer;
        this.data = data;
        this.sha = sha;
        this.segSize = segSize;
        this.window = Math.max(1, window);
        this.rto = rto;
        this.total = (data.length + segSize - 1) / segSize;
    }

    public static ArqSender create(Protocol p, DatagramSocket s, SocketAddress peer, byte[] data, byte[] sha,
                                   int segSize, int window, RtoEstimator rto) {
        switch (p) {
            case SW:  return new StopAndWaitSender(s, peer, data, sha, segSize, 1, rto);
            case GBN: return new GoBackNSender(s, peer, data, sha, segSize, window, rto);
            default:  return new SelectiveRepeatSender(s, peer, data, sha, segSize, Math.min(window, 64), rto);
        }
    }

    /** Protocol-specific transfer of segments 1..total; returns when all are acknowledged. */
    protected abstract void transfer() throws Exception;

    public final void run() throws Exception {
        handshake();
        long t0 = System.nanoTime();
        transfer();
        elapsedSec = (System.nanoTime() - t0) / 1e9;
        byte[] fin = new Packet(Packet.FIN, total + 1, 0, 0, null).encode();
        for (int i = 0; i < 3; i++) sock.send(new DatagramPacket(fin, fin.length, peer)); // best effort
    }

    private void handshake() throws Exception {
        ByteBuffer bb = ByteBuffer.allocate(SYN_PAYLOAD);
        bb.putLong(data.length).putInt(segSize).put((byte) proto.id).putInt(window).put(sha);
        byte[] syn = new Packet(Packet.SYN, 0, 0, 0, bb.array()).encode();
        for (int attempt = 0; attempt < 30; attempt++) {
            sock.send(new DatagramPacket(syn, syn.length, peer));
            long deadline = System.nanoTime() + 300_000_000L;
            while (true) {
                long rem = deadline - System.nanoTime();
                if (rem <= 0) break;
                Packet p = recv(rem);
                if (p != null && p.type == Packet.ACK && p.ack >= 1) return;
            }
        }
        throw new IllegalStateException("handshake failed: receiver not reachable");
    }

    protected final void transmit(int seq, boolean retx) throws Exception {
        int from = (seq - 1) * segSize;
        int to = Math.min(data.length, from + segSize);
        byte[] wire = new Packet(Packet.DATA, seq, 0, 0, Arrays.copyOfRange(data, from, to)).encode();
        sock.send(new DatagramPacket(wire, wire.length, peer));
        transmissions++;
        if (retx) retransmissions++;
    }

    /** One receive attempt. Returns null on timeout or when a corrupt datagram was dropped. */
    protected final Packet recv(long timeoutNanos) throws Exception {
        int ms = (int) Math.max(1, (timeoutNanos + 999_999L) / 1_000_000L);
        sock.setSoTimeout(ms);
        DatagramPacket dp = new DatagramPacket(rbuf, rbuf.length);
        try {
            sock.receive(dp);
        } catch (SocketTimeoutException e) {
            return null;
        }
        Packet p = Packet.decode(rbuf, dp.getLength());
        if (p == null) badPackets++;
        return p;
    }

    public Json stats(long fileBytes) {
        double goodput = elapsedSec > 0 ? fileBytes * 8.0 / elapsedSec / 1e6 : 0;
        return new Json()
            .put("role", "sender").put("protocol", proto.name()).put("window", window)
            .put("seg_size", segSize).put("file_bytes", fileBytes).put("segments", total)
            .put("transmissions", transmissions).put("retransmissions", retransmissions)
            .put("timeouts", timeouts).put("bad_acks", badPackets)
            .put("elapsed_s", round(elapsedSec, 4)).put("goodput_mbps", round(goodput, 4))
            .put("rto_mode", rto.adaptive() ? "adaptive" : "fixed")
            .put("rto_final_ms", round(rto.rtoMs(), 3)).put("srtt_ms", round(rto.srttMs(), 3))
            .put("rtt_samples", rto.samples());
    }

    private static double round(double v, int d) {
        double s = Math.pow(10, d);
        return Math.round(v * s) / s;
    }
}
