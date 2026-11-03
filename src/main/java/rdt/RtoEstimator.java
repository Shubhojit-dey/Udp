package rdt;

/**
 * Retransmission timeout estimator.
 * Adaptive mode = Jacobson/Karels (RFC 6298):
 *   first sample: SRTT=R, RTTVAR=R/2
 *   later:        RTTVAR = (1-b)*RTTVAR + b*|SRTT-R| ; SRTT = (1-a)*SRTT + a*R ; a=1/8, b=1/4
 *   RTO = SRTT + 4*RTTVAR, clamped to [min,max]; doubled on every timeout (backoff).
 * Karn's algorithm is enforced by the callers: sample() is never called with an RTT measured
 * from a retransmitted segment, and the backed-off RTO is kept until a clean sample arrives.
 * Fixed mode returns a constant RTO (used for the RTO-sensitivity experiment).
 */
public final class  RtoEstimator {
    private static final double ALPHA = 0.125, BETA = 0.25;

    private final boolean adaptive;
    private final double minMs, maxMs;
    private double srtt = -1, rttvar = 0, rto;
    private int samples = 0;

    public RtoEstimator(boolean adaptive, double initialMs, double minMs, double maxMs) {
        this.adaptive = adaptive;
        this.minMs = minMs;
        this.maxMs = maxMs;
        this.rto = adaptive ? clamp(initialMs) : initialMs;
    }

    private double clamp(double v) { return Math.max(minMs, Math.min(maxMs, v)); }

    public void sample(double rttMs) {
        if (!adaptive) return;
        samples++;
        if (srtt < 0) {
            srtt = rttMs;
            rttvar = rttMs / 2;
        } else {
            rttvar = (1 - BETA) * rttvar + BETA * Math.abs(srtt - rttMs);
            srtt = (1 - ALPHA) * srtt + ALPHA * rttMs;
        }
        rto = clamp(srtt + 4 * rttvar);
    }

    public void backoff() {
        if (adaptive) rto = clamp(rto * 2);
    }

    public double rtoMs() { return rto; }
    public long rtoNanos() { return (long) (rto * 1_000_000.0); }
    public double srttMs() { return srtt; }
    public int samples() { return samples; }
    public boolean adaptive() { return adaptive; }
}
