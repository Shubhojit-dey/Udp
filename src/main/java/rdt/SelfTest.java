package rdt;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
 import java.util.Random;

/** In-process regression test: every protocol must deliver a byte-identical file through a hostile channel. */
public final class SelfTest {
    static int failures = 0;

    static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) failures++;
    }

    public static void main(String[] a) throws Exception {
        // --- unit: checksum / packet
        Packet p = new Packet(Packet.DATA, 7, 3, 0xF0F0L, new byte[] { 1, 2, 3, 4, 5 });
        byte[] w = p.encode();
        Packet q = Packet.decode(w, w.length);
        check(q != null && q.seq == 7 && q.ack == 3 && q.sack == 0xF0F0L && q.payload.length == 5, "packet round-trip");
        boolean allDetected = true;
        for (int bit = 0; bit < w.length * 8; bit++) {
            byte[] c = w.clone();
            c[bit / 8] ^= (byte) (1 << (bit % 8));
            if (Packet.decode(c, c.length) != null) allDetected = false;
        }
        check(allDetected, "every single-bit corruption is detected by the checksum");

        // --- unit: RTO
        RtoEstimator r = new RtoEstimator(true, 200, 1, 10000);
        r.sample(100);
        check(Math.abs(r.rtoMs() - 300) < 1e-9, "Jacobson/Karels first sample: RTO = R + 4*R/2 = 3R");
        double before = r.rtoMs();
        r.backoff();
        check(Math.abs(r.rtoMs() - 2 * before) < 1e-9, "RTO doubles on timeout");

        // --- integration through the emulator
        Random rnd = new Random(7);
        byte[] file = new byte[300_000];
        rnd.nextBytes(file);
        Path in = Files.createTempFile("rdt-in", ".bin");
        Files.write(in, file);
        byte[] sha = Sha256Util.digest(in);

        double[][] profiles = { { 0, 0, 0, 0 }, { 10, 0, 0, 0 }, { 5, 5, 10, 2 } }; // loss, dup, reorder, corrupt (%)
        for (Protocol proto : Protocol.values()) {
            for (double[] pf : profiles) {
                Path out = Files.createTempFile("rdt-out", ".bin");
                try (DatagramSocket rs = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
                     DatagramSocket ss = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
                    ChannelEmulator.Config c = new ChannelEmulator.Config();
                    c.loss = pf[0] / 100; c.dup = pf[1] / 100; c.reorder = pf[2] / 100; c.corrupt = pf[3] / 100;
                    c.delayMs = 2; c.jitterMs = 1; c.reorderGapMs = 8; c.seed = 99;
                    ChannelEmulator emu = new ChannelEmulator(c, 0,
                        new InetSocketAddress("127.0.0.1", rs.getLocalPort()));
                    emu.start();
                    ArqReceiver rcv = new ArqReceiver(rs, out, 800);
                    Thread t = new Thread(() -> { try { rcv.run(20000); } catch (Exception e) { e.printStackTrace(); } });
                    t.start();
                    RtoEstimator rto = new RtoEstimator(true, 100, 10, 500);
                    ArqSender snd = ArqSender.create(proto, ss, new InetSocketAddress("127.0.0.1", emu.port()),
                        file, sha, 1000, 16, rto);
                    snd.run();
                    t.join();
                    emu.stop();
                    check(rcv.integrityOk, String.format("%-4s loss=%2.0f%% dup=%2.0f%% reorder=%2.0f%% corrupt=%1.0f%%  retx=%d",
                        proto, pf[0], pf[1], pf[2], pf[3], snd.retransmissions));
                }
                Files.deleteIfExists(out);
            }
        }
        Files.deleteIfExists(in);
        System.out.println(failures == 0 ? "ALL TESTS PASSED" : failures + " FAILURE(S)");
        System.exit(failures == 0 ? 0 : 1);
    }
}
