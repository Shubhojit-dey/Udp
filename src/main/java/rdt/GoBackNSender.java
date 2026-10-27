package rdt;

import java.net.DatagramSocket;
import java.net.SocketAddress;

/**
 * Sliding window with cumulative ACKs and ONE timer for the oldest unacknowledged segment.
 * On timeout the whole outstanding window [base, next) is retransmitted.
 */
public final class GoBackNSender extends ArqSender {
    public GoBackNSender(DatagramSocket s, SocketAddress peer, byte[] data, byte[] sha,
                         int segSize, int window, RtoEstimator rto) {
        super(Protocol.GBN, s, peer, data, sha, segSize, window, rto);
    }

    @Override
    protected void transfer() throws Exception {
        long[] sentAt = new long[total + 2];
        boolean[] retx = new boolean[total + 2];
        int base = 1, next = 1;
        long deadline = 0; // 0 = timer off

        while (base <= total) {
            while (next < base + window && next <= total) {
                sentAt[next] = System.nanoTime();
                transmit(next, false);
                if (deadline == 0) deadline = sentAt[next] + rto.rtoNanos();
                next++;
            }
            long now = System.nanoTime();
            long rem = deadline - now;
            if (rem <= 0) { // timeout: go back N
                timeouts++;
                rto.backoff();
                for (int s = base; s < next; s++) {
                    retx[s] = true;
                    transmit(s, true);
                }
                deadline = System.nanoTime() + rto.rtoNanos();
                continue;
            }
            Packet p = recv(rem);
            if (p != null && p.type == Packet.ACK && p.ack > base) {
                int newest = Math.min(p.ack - 1, next - 1);
                if (!retx[newest]) rto.sample((System.nanoTime() - sentAt[newest]) / 1e6); // Karn
                base = Math.min(p.ack, next);
                deadline = base < next ? System.nanoTime() + rto.rtoNanos() : 0;
            }
        }
    }
}
