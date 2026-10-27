package rdt;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

public final class Sha256Util {
    private Sha256Util() {}

    public static byte[] digest(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return md.digest();
    }

    public static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
