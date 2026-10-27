package rdt;

public enum Protocol {
    SW(1, "Stop-and-Wait"), GBN(2, "Go-Back-N"), SR(3, "Selective Repeat");

    public final int id;
    public final String label;

    Protocol(int id, String label) { this.id = id; this.label = label; }

    public static Protocol fromId(int id) {
        for (Protocol p : values()) if (p.id == id) return p;
        throw new IllegalArgumentException("bad protocol id " + id);
    }

    public static Protocol parse(String s) {
        switch (s.toLowerCase()) {
            case "sw": case "saw": case "stop-and-wait": return SW;
            case "gbn": case "go-back-n": return GBN;
            case "sr": case "selective-repeat": return SR;
            default: throw new IllegalArgumentException("unknown protocol: " + s + " (use sw|gbn|sr)");
        }
    }
}
