# Viva notes: questions each owner should be able to answer

**Stop-and-Wait (`StopAndWaitSender`, `Packet`, `Checksum`)**
- Why is S&W throughput ≈ segment size / RTT, and why is it independent of window size?
- Why does the checksum detect every single-bit error but not every multi-bit error? (SelfTest proves the former.)
- Why do we ignore ACKs with `ack <= seq`?

**Go-Back-N (`GoBackNSender`, in-order branch of `ArqReceiver`)**
- Why one timer? What happens to the timer when the base advances?
- Why does one lost segment cost up to a whole window of retransmissions, and why does reordering hurt GBN even with zero loss?
- Why does the receiver re-send the cumulative ACK for out-of-order segments?

**Selective Repeat (`SelectiveRepeatSender`, SACK/buffer branch of `ArqReceiver`)**
- Why must the window be <= 64 here? (SACK bitmap width; bit i ↔ segment ack+1+i.)
- What happens if a SACK is lost? (Next ACK carries the same information; cumulative ACK is the backstop.)
- Why is the reorder buffer bounded by the window??

**Adaptive RTO (`RtoEstimator`)**
- Write the Jacobson/Karels update from memory (α=1/8, β=1/4, RTO=SRTT+4·RTTVAR).
- Why does Karn's algorithm exclude retransmitted segments (retransmission ambiguity)? Where is it enforced in each sender?
- Why keep the backed-off RTO until a clean sample arrives?

**Channel emulator (`ChannelEmulator`)**
- Why hash per (packet id, occurrence) rather than draw from one RNG? What is still non-deterministic (ACK fates, OS timing)?
- Why does jitter not reorder, and how is reordering produced instead?

**Harness / integrity (`run_experiments.py`, `Sha256Util`, SYN hash)**
- Why vary one parameter at a time? Why are seeds shared across protocols within a repeat?
- What does "goodput" measure here, and when does the clock start/stop?
