package rdt;

import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;


public final class Main {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); return; }
        Map<String, String> o = parse(args);
        switch (args[0]) {
            case "send":    send(o); break;
            case "receive": receive(o); break;
            case "emulate": emulate(o); break;
            case "hash":    System.out.println(Sha256Util.hex(Sha256Util.digest(Paths.get(args[1])))); break;
            case "genfile": genfile(o); break;
            default:        usage();
        }
    }

    private static void usage() {
        System.err.println(String.join("\n",
            "usage:",
            "  receive --port P --out FILE [--linger MS] [--stats FILE]",
            "  send    --to HOST:PORT --file FILE --proto sw|gbn|sr [--window W] [--seg BYTES]",
            "          [--rto adaptive|fixed:MS] [--rto-init MS] [--rto-min MS] [--rto-max MS] [--stats FILE]",
            "  emulate --listen P --target HOST:PORT [--loss %] [--dup %] [--reorder %] [--corrupt %]",
            "          [--delay MS] [--jitter MS] [--reorder-gap MS] [--seed N] [--forward-only]",
            "          [--idle-exit SEC] [--stats FILE]",
            "  hash FILE",
            "  genfile --out FILE --bytes N [--seed N]"));
    }

    private static Map<String, String> parse(String[] a) {
        Map<String, String> m = new HashMap<>();
        for (int i = 1; i < a.length; i++) {
            if (!a[i].startsWith("--")) continue;
            String k = a[i].substring(2);
            if (i + 1 < a.length && !a[i + 1].startsWith("--")) m.put(k, a[++i]);
            else m.put(k, "true");
        }
        return m;
    }

    private static InetSocketAddress addr(String hp) {
        int c = hp.lastIndexOf(':');
        return new InetSocketAddress(hp.substring(0, c), Integer.parseInt(hp.substring(c + 1)));
    }

    private static double d(Map<String, String> o, String k, double def) {
        return o.containsKey(k) ? Double.parseDouble(o.get(k)) : def;
    }

    private static void writeStats(Map<String, String> o, Json j) throws Exception {
        if (o.containsKey("stats")) Files.write(Paths.get(o.get("stats")), (j + "\n").getBytes());
    }

    private static void send(Map<String, String> o) throws Exception {
        Path file = Paths.get(o.get("file"));
        byte[] data = Files.readAllBytes(file);
        byte[] sha = Sha256Util.digest(file);
        Protocol p = Protocol.parse(o.getOrDefault("proto", "sr"));
        int seg = (int) d(o, "seg", 1200), win = (int) d(o, "window", 16);
        String mode = o.getOrDefault("rto", "adaptive");
        boolean adaptive = mode.equals("adaptive");
        double init = adaptive ? d(o, "rto-init", 200) : Double.parseDouble(mode.substring(mode.indexOf(':') + 1));
        RtoEstimator rto = new RtoEstimator(adaptive, init, d(o, "rto-min", 20), d(o, "rto-max", 1000));
        try (DatagramSocket s = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            s.setReceiveBufferSize(4 << 20);
            ArqSender snd = ArqSender.create(p, s, addr(o.get("to")), data, sha, seg, win, rto);
            snd.run();
            Json j = snd.stats(data.length).put("sha256", Sha256Util.hex(sha));
            writeStats(o, j);
            System.out.println("RESULT " + j);
        }
    }

    private static void receive(Map<String, String> o) throws Exception {
        try (DatagramSocket s = new DatagramSocket(Integer.parseInt(o.getOrDefault("port", "0")),
                                                   InetAddress.getByName("127.0.0.1"))) {
            s.setReceiveBufferSize(4 << 20);
            System.out.println("READY " + s.getLocalPort());
            System.out.flush();
            ArqReceiver r = new ArqReceiver(s, Paths.get(o.get("out")), (int) d(o, "linger", 1500));
            Exception err = null;
            try { r.run((int) d(o, "start-timeout", 60000)); } catch (Exception e) { err = e; }
            Json j = r.stats("-");
            writeStats(o, j);
            System.out.println("RESULT " + j);
            if (err != null) { System.err.println(err); System.exit(2); }
            if (!r.integrityOk) System.exit(3);
        }
    }

    private static void emulate(Map<String, String> o) throws Exception {
        ChannelEmulator.Config c = new ChannelEmulator.Config();
        c.loss = d(o, "loss", 0) / 100; c.dup = d(o, "dup", 0) / 100;
        c.reorder = d(o, "reorder", 0) / 100; c.corrupt = d(o, "corrupt", 0) / 100;
        c.delayMs = d(o, "delay", 5); c.jitterMs = d(o, "jitter", 1);
        c.reorderGapMs = d(o, "reorder-gap", 15); c.seed = (long) d(o, "seed", 1);
        c.forwardOnly = o.containsKey("forward-only");
        ChannelEmulator e = new ChannelEmulator(c, (int) d(o, "listen", 0), addr(o.get("target")));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { writeStats(o, e.stats()); } catch (Exception ignored) {}
        }));
        e.start();
        System.out.println("READY " + e.port());
        System.out.flush();
        long idleExit = (long) (d(o, "idle-exit", 0) * 1000);
        while (true) {
            Thread.sleep(200);
            if (idleExit > 0 && e.idleMillis() > idleExit) { e.stop(); System.exit(0); }
        }
    }

    private static void genfile(Map<String, String> o) throws Exception {
        long n = (long) d(o, "bytes", 1 << 20);
        Random r = new Random((long) d(o, "seed", 42));
        byte[] buf = new byte[1 << 16];
        try (OutputStream os = Files.newOutputStream(Paths.get(o.get("out")))) {
            while (n > 0) {
                r.nextBytes(buf);
                int k = (int) Math.min(n, buf.length);
                os.write(buf, 0, k);
                n -= k;
            }
        }
    }
}
