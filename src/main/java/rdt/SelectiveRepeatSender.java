package rdt;

import java.net.DatagramSocket;
import java.net.SocketAddress;

public final class SelectiveRepeatSender extends ArqSender {
    public SelectiveRepeatSender(DatagramSocket s, SocketAddress peer, byte[] data, byte[] sha,
                                 int segSize, int window, RtoEstimator rto) {
        super(Protocol.SR, s, peer, data, sha, segSize, Math.min(window, 64), rto);
    }

    @Override
    protected void transfer() throws Exception {
        boolean[] acked = new boolean[total + 2];
        boolean[] retx = new boolean[total + 2];
        long[] sentAt = new long[total + 2];
        long[] due = new long[total + 2];
        int base = 1, next = 1;

        while (base <= total) {
            while (next < base + window && next <= total) {
                sentAt[next] = System.nanoTime();
                due[next] = sentAt[next] + rto.rtoNanos();
                transmit(next, false);
                next++;
            }
            long now = System.nanoTime();
            long earliest = Long.MAX_VALUE;
            for (int s = base; s < next; s++) if (!acked[s] && due[s] < earliest) earliest = due[s];
            long rem = earliest - now;
            if (rem <= 0) { // retransmit every expired, still-missing segment
                boolean any = false;
                for (int s = base; s < next; s++) {
                    if (!acked[s] && due[s] <= now) {
                        if (!any) { timeouts++; rto.backoff(); any = true; }
                        retx[s] = true;
                        transmit(s, true);
                        due[s] = System.nanoTime() + rto.rtoNanos();
                    }
                }
                continue;
            }
            Packet p = recv(rem);
            if (p == null || p.type != Packet.ACK) continue;
            long t = System.nanoTime();
            for (int s = base; s < p.ack && s < next; s++) mark(s, acked, retx, sentAt, t);
            for (int i = 0; i < 64; i++) {
                if ((p.sack >>> i & 1L) != 0) {
                    int s = p.ack + 1 + i;
                    if (s >= base && s < next) mark(s, acked, retx, sentAt, t);
                }
            }
            while (base < next && acked[base]) base++;
        }
    }

    private void mark(int s, boolean[] acked, boolean[] retx, long[] sentAt, long now) {
        if (acked[s]) return;
        acked[s] = true;
        if (!retx[s]) rto.sample((now - sentAt[s]) / 1e6);
    }
}
