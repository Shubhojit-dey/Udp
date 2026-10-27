package rdt;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UDP fault-injecting proxy:  sender --> front[listen]  ...  back --> receiver.
 *
 * Determinism: every fault decision is a pure function of
 *     (seed, direction, packet type, seq-or-ack, occurrence number of that exact id)
 * Jitter is queueing delay on a FIFO link (never reorders by itself); reordering is an explicit,
 * separately controlled impairment, so the experiments can vary one cause at a time.
 * computed with a SplitMix64 hash, NOT of arrival order or a shared RNG stream. So "the 3rd
 * transmission of segment 812 is dropped" holds on every run with the same seed, whichever
 * protocol is sending and however the OS schedules threads. (ACK fates depend on how many ACKs
 * the receiver emits, which is protocol/timing dependent, so they match only statistically.)
 */
public final class ChannelEmulator {
    public static final class Config {
        public double loss, dup, reorder, corrupt;       // probabilities 0..1
        public double delayMs = 5, jitterMs = 1;         // one-way base delay and +/- jitter
        public double reorderGapMs = 15;                 // extra hold applied to a "reordered" packet
        public long seed = 1;
        public boolean forwardOnly = false;              // impair only sender->receiver direction
    }

    private final Config cfg;
    private final DatagramSocket front, back;
    private final InetSocketAddress target;
    private volatile SocketAddress senderAddr;
    /** Packet waiting for its release time; ordered by (due, arrival sequence) so FIFO order is exact. */
    private static final class Item implements Delayed {
        final int dir; final byte[] data; final long due; final long order;
        Item(int dir, byte[] data, long due, long order) { this.dir = dir; this.data = data; this.due = due; this.order = order; }
        @Override public long getDelay(TimeUnit u) { return u.convert(due - System.nanoTime(), TimeUnit.NANOSECONDS); }
        @Override public int compareTo(Delayed o) {
            Item x = (Item) o;
            return due != x.due ? Long.compare(due, x.due) : Long.compare(order, x.order);
        }
    }

    private final DelayQueue<Item> queue = new DelayQueue<>();
    private final AtomicLong orderSeq = new AtomicLong();
    private Thread releaser;
    private final AtomicLong[][] counters = new AtomicLong[2][5]; // seen, dropped, duplicated, corrupted, reordered
    private final HashMap<Long, Integer>[] occurrence;
    private final long[] lastDue = new long[2]; // each element touched by one pump thread only
    private volatile long lastActivity = System.nanoTime();

    @SuppressWarnings({"unchecked", "rawtypes"})
    public ChannelEmulator(Config cfg, int listenPort, InetSocketAddress target) throws Exception {
        this.cfg = cfg;
        this.target = target;
        this.front = new DatagramSocket(listenPort, InetAddress.getByName("127.0.0.1"));
        this.back = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
        front.setReceiveBufferSize(8 << 20);
        back.setReceiveBufferSize(8 << 20);
        for (AtomicLong[] row : counters) for (int i = 0; i < row.length; i++) row[i] = new AtomicLong();
        occurrence = new HashMap[] { new HashMap<Long, Integer>(), new HashMap<Long, Integer>() };
    }

    public int port() { return front.getLocalPort(); }

    public void start() {
        Thread f = new Thread(() -> pump(front, 0), "emu-forward");
        Thread b = new Thread(() -> pump(back, 1), "emu-reverse");
        f.setDaemon(true);
        b.setDaemon(true);
        f.start();
        b.start();
        releaser = new Thread(this::release, "emu-release");
        releaser.setDaemon(true);
        releaser.start();
    }

    private void release() {
        try {
            while (true) {
                Item it = queue.take();
                try {
                    if (it.dir == 0) back.send(new DatagramPacket(it.data, it.data.length, target));
                    else if (senderAddr != null) front.send(new DatagramPacket(it.data, it.data.length, senderAddr));
                } catch (Exception ignored) { /* socket closed during shutdown */ }
            }
        } catch (InterruptedException ignored) { /* stop() */ }
    }

    public void stop() {
        if (releaser != null) releaser.interrupt();
        front.close();
        back.close();
    }

    public long idleMillis() { return (System.nanoTime() - lastActivity) / 1_000_000L; }

    private void pump(DatagramSocket in, int dir) {
        byte[] buf = new byte[70000];
        try {
            while (!in.isClosed()) {
                DatagramPacket dp = new DatagramPacket(buf, buf.length);
                in.receive(dp);
                lastActivity = System.nanoTime();
                if (dir == 0) senderAddr = dp.getSocketAddress();
                handle(dir, Arrays.copyOf(buf, dp.getLength()));
            }
        } catch (Exception e) {
            if (!in.isClosed()) e.printStackTrace();
        }
    }

    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static double unit(long h) { return (h >>> 11) * (1.0 / (1L << 53)); }

    private void handle(int dir, byte[] d) {
        counters[dir][0].incrementAndGet();
        if (d.length < Packet.HEADER) return;
        int type = d[0];
        int seq = ((d[2] & 0xff) << 24) | ((d[3] & 0xff) << 16) | ((d[4] & 0xff) << 8) | (d[5] & 0xff);
        int ack = ((d[6] & 0xff) << 24) | ((d[7] & 0xff) << 16) | ((d[8] & 0xff) << 8) | (d[9] & 0xff);
        long id = ((long) type << 40) ^ (type == Packet.ACK ? ack : seq);
        int occ = occurrence[dir].merge(id, 1, Integer::sum);
        long h = mix(cfg.seed ^ mix(((long) dir << 62) ^ mix(id) ^ ((long) occ << 20)));
        double[] u = new double[7];
        for (int i = 0; i < u.length; i++) { h = mix(h); u[i] = unit(h); }

        boolean impaired = dir == 0 || !cfg.forwardOnly;
        double delay = cfg.delayMs;
        boolean drop = false, dup = false, corrupt = false, held = false;
        if (impaired) {
            drop = u[0] < cfg.loss;
            dup = u[1] < cfg.dup;
            corrupt = u[2] < cfg.corrupt;
            delay = Math.max(0, cfg.delayMs + (u[4] * 2 - 1) * cfg.jitterMs);
            if (u[3] < cfg.reorder) { delay += cfg.reorderGapMs; held = true; counters[dir][4].incrementAndGet(); }
        }
        if (drop) { counters[dir][1].incrementAndGet(); return; }
        byte[] out = d;
        if (corrupt) {
            out = d.clone();
            int bit = (int) (u[5] * out.length * 8);
            out[bit / 8] ^= (byte) (1 << (bit % 8));
            counters[dir][3].incrementAndGet();
        }
        // Jitter models queueing delay on a FIFO link: it never reorders by itself. Only packets
        // explicitly selected for reordering ("held") may be overtaken by later ones.
        long now = System.nanoTime();
        long due = now + (long) (delay * 1_000_000);
        if (!held) {
            due = Math.max(due, lastDue[dir]);
            lastDue[dir] = due;
        }
        deliver(dir, out, due);
        if (dup) {
            counters[dir][2].incrementAndGet();
            deliver(dir, out, due + (long) ((0.2 + u[6] * 2.0) * 1_000_000));
        }
    }

    private void deliver(int dir, byte[] out, long dueNanos) {
        queue.add(new Item(dir, out, dueNanos, orderSeq.getAndIncrement()));
    }

    public Json stats() {
        Json j = new Json().put("role", "emulator").put("seed", cfg.seed).put("loss", cfg.loss)
            .put("dup", cfg.dup).put("reorder", cfg.reorder).put("corrupt", cfg.corrupt)
            .put("delay_ms", cfg.delayMs).put("jitter_ms", cfg.jitterMs);
        String[] names = { "seen", "dropped", "duplicated", "corrupted", "reordered" };
        String[] dirs = { "fwd", "rev" };
        for (int d = 0; d < 2; d++)
            for (int i = 0; i < 5; i++) j.put(dirs[d] + "_" + names[i], counters[d][i].get());
        return j;
    }
}
