package rdt;

import java.net.DatagramSocket;
import java.net.SocketAddress;

/** One segment in flight. Retransmit on timeout with exponential backoff. */
public final class StopAndWaitSender extends ArqSender {
    public StopAndWaitSender(DatagramSocket s, SocketAddress peer, byte[] data, byte[] sha,
                             int segSize, int window, RtoEstimator rto) {
        super(Protocol.SW, s, peer, data, sha, segSize, 1, rto);
    }

    @Override
    protected void transfer() throws Exception {
        for (int seq = 1; seq <= total; seq++) {
            boolean retx = false;
            while (true) {
                long sent = System.nanoTime();
                transmit(seq, retx);
                long deadline = sent + rto.rtoNanos();
                boolean acked = false;
                while (true) {
                    long rem = deadline - System.nanoTime();
                    if (rem <= 0) break;
                    Packet p = recv(rem);
                    if (p != null && p.type == Packet.ACK && p.ack > seq) {
                        if (!retx) rto.sample((System.nanoTime() - sent) / 1e6); // Karn
                        acked = true;
                        break;
                    }
                }
                if (acked) break;
                timeouts++;
                rto.backoff();
                retx = true;
            }
        }
    }
}
