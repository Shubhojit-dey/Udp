# P3 · Reliable Data Transfer over UDP

CS-30003 · Coding Assignment 1 · Path A (catalogue project) · Team 4
Shubhojit Dey · Shwe Yee Mon · Souvick Biswas · Snigdhanil Sabud

A file-transfer tool on raw UDP sockets with three hand-written ARQ protocols
(Stop-and-Wait, Go-Back-N, Selective Repeat), a seed-controlled network emulator, and an
experiment pipeline that tests the claim:

> As loss and reordering increase, Selective Repeat holds a materially higher goodput than
> Go-Back-N and Stop-and-Wait, because it never retransmits segments the receiver already has.

Only the JDK standard library is used (`DatagramSocket`, `java.security.MessageDigest`).
Python (pandas, matplotlib) is used only for orchestration and plots.
The full write-up is in `report/P3_Project_Report_Final.pdf`; AI usage is declared in `AI-USE.md`.

## Quick start

**Linux / macOS**
```bash
./build.sh                                   # compiles to ./out
java -cp out rdt.SelfTest                    # unit + integration tests (all 3 protocols, hostile channel)
./run_demo.sh sr 10                          # one transfer: Selective Repeat, 10 % loss, hash verified
```

**Windows (Command Prompt, inside the project folder)**
```
dir /s /b src\main\java\*.java > sources.txt
javac -d out @sources.txt
java -Xmx128m -cp out rdt.SelfTest
```
`-Xmx128m` limits Java to 128 MB of memory. Use it on machines with little free memory, otherwise
Java may crash with "paging file is too small". `build.sh` and `run_demo.sh` need bash.

**Experiments**
```
pip install -r requirements.txt
python experiments/run_experiments.py --profile smoke        # minutes, sanity check
python experiments/run_experiments.py --profile standard --size-mb 0.5 --sw-size-mb 0.5 --run-timeout 600 --out results/final_small.csv
python experiments/plot.py results/final_small.csv           # -> results/plots/
```
Profiles: `smoke` 0.25 MB, 1 repeat; `standard` 2 MB, 3 repeats (the team's final run overrides the size to
0.5 MB); `full` 100 MB (`--sw-size-mb 5` for Stop-and-Wait). The runner is resumable: finished runs in the
CSV are skipped. It retries automatically when Windows refuses to start a Java process.

Manual use (three terminals):
```bash
java -cp out rdt.Main receive --port 9000 --out recv.bin
java -cp out rdt.Main emulate --listen 9001 --target 127.0.0.1:9000 --loss 10 --reorder 5 --seed 7
java -cp out rdt.Main send --to 127.0.0.1:9001 --file big.bin --proto sr --window 32
```
Probabilities are percentages. `--rto fixed:100` switches off adaptive RTO.

## Layout

```
src/main/java/rdt/
  Packet.java            22-byte header, encode/decode, checksum verification
  Checksum.java          hand-built RFC 1071 ones'-complement checksum
  RtoEstimator.java      Jacobson/Karels + backoff (Karn enforced by callers), or fixed RTO
  ArqSender.java         handshake, transmit/recv helpers, stats; protocol factory
  StopAndWaitSender.java | GoBackNSender.java | SelectiveRepeatSender.java
  ArqReceiver.java       in-order (SW/GBN) or reorder-buffer + SACK (SR); SHA-256 verification
  ChannelEmulator.java   loss / duplication / reordering / corruption / jitter proxy
  Sha256Util.java, Json.java, Protocol.java, Main.java (CLI), SelfTest.java
experiments/run_experiments.py   sweeps + resumable CSV logging
experiments/plot.py              plots, RTO table, claim check
results/                         final_small.csv and results/plots/ (graphs, tables, summary.md)
report/                          project report (Word and PDF)
docs/VIVA_NOTES.md               questions each owner should be able to answer
AI-USE.md                        declaration of AI use
```

## Work distribution

Every member owns one component end to end and shares one task, so scope is equal.

| Member | Roll no. | Owns end to end | Shares with a partner | Main files |
|---|---|---|---|---|
| Shubhojit Dey | 25057064 | Stop-and-Wait (framing, sequencing, checksum) | Channel emulator, with Shwe Yee Mon | `StopAndWaitSender`, `Packet`, `Checksum`, `ChannelEmulator` |
| Shwe Yee Mon | 24052735 | Go-Back-N (cumulative ACKs, sliding window) | Channel emulator, with Shubhojit Dey | `GoBackNSender`, in-order part of `ArqReceiver`, `ChannelEmulator` |
| Souvick Biswas | 25057075 | Selective Repeat (selective ACKs, reorder buffer) | Experiment harness and hash checks, with Snigdhanil Sabud | `SelectiveRepeatSender`, SACK part of `ArqReceiver`, `run_experiments.py`, `plot.py`, `Sha256Util` |
| Snigdhanil Sabud | 25057071 | Adaptive RTO (Jacobson/Karels, Karn) | Experiment harness and hash checks, with Souvick Biswas | `RtoEstimator`, `run_experiments.py`, `plot.py`, `Sha256Util` |

| Week | Target | Owner |
|---|---|---|
| 1 | Repo live, socket scaffolding, shared header format, proposal finalised | All |
| 2 | Stop-and-Wait complete on a lossless channel | Shubhojit Dey |
| 3 | Go-Back-N complete | Shwe Yee Mon |
| 4 | Selective Repeat complete | Souvick Biswas |
| 5 | Adaptive RTO wired into all three protocols | Snigdhanil Sabud |
| 6 | Channel emulator built and integrated | Shubhojit Dey and Shwe Yee Mon |
| 7 | Full experiment sweep, plots, hash-integrity validation | Souvick Biswas and Snigdhanil Sabud |
| 8 | Report, `AI-USE.md`, code clean-up, viva rehearsal | All |

Each member must be able to explain and defend their own component in the viva (see `docs/VIVA_NOTES.md`).

## Design

**Wire format** (big endian): `type(1) flags(1) seq(4) ack(4) sack(8) len(2) checksum(2) | payload`.
`ack` is cumulative (next expected segment). Bit *i* of `sack` means segment `ack+1+i` is
buffered at the receiver, so Selective Repeat supports windows up to 64. A SYN carries file size,
segment size, protocol, window and the source SHA-256; the receiver verifies the hash itself and
reports `integrity_ok`.

**Reliability.**
- *Stop-and-Wait*: one segment in flight, retransmit on timeout.
- *Go-Back-N*: window W, one timer on the oldest unacked segment, cumulative ACKs, timeout resends the whole window; receiver discards out-of-order segments.
- *Selective Repeat*: per-segment deadlines, receiver buffers out-of-order segments inside the window and returns cumulative ACK + SACK bitmap; only missing segments are resent.

**Timing.** RTO = SRTT + 4·RTTVAR (α=1/8, β=1/4), clamped to [20 ms, 1 s] by default, doubled on
every timeout. Karn: a segment that was ever retransmitted never yields an RTT sample, and the
backed-off RTO is kept until a clean sample arrives.

**Concurrency (deviation from the proposal's thread-per-timer sketch).** The proposal's first
risk is timer/ACK races, with a centralised timer as fallback. We adopted the fallback from the
start: each sender is a single-threaded event loop that sends, then blocks on the socket until the
earliest retransmission deadline. Timer expiry and ACK processing can never interleave, so there
is no timer race. The emulator and receiver use threads where they need them.

**Emulator.** `sender → front socket … back socket → receiver`. Each fault decision is a SplitMix64
hash of `(seed, direction, type, seq-or-ack, occurrence number)` rather than a shared RNG stream,
so "transmission #3 of segment 812 is dropped" is reproduced exactly regardless of protocol or
thread timing. (ACK fates are reproducible only statistically, because the number of ACKs a
receiver emits depends on the protocol.) Jitter is modelled as queueing delay on a FIFO link and
never reorders by itself; reordering is an explicit impairment (a selected packet is held back by
`--reorder-gap` ms, default 15, so later packets overtake it). This keeps the experiments
one-variable-at-a-time.

## Experiments

Baseline: loss 1 %, window 16, reorder 0 %, 1200 B segments, 5 ms one-way delay ± 1 ms jitter,
adaptive RTO. Sweeps change exactly one of: loss {0,1,2,5,10,15,20} %, window {4,8,16,32,64},
reorder {0,2.5,5,10,15} %, plus an RTO sweep (adaptive vs fixed 20–500 ms at 5 % loss).
Seeds are 1000, 1001, 1002, so each repeat sees the same network decisions for all three protocols.
Every CSV row stores its full configuration and seed.

**Final run (team's computers):** 207 runs, 0.5 MB file, 3 repeats, all 207 passed the SHA-256 check.

| Result (mean of 3 runs) | Selective Repeat vs Go-Back-N |
|---|---|
| 0 % loss | equal (4.74 vs 4.72 Mbps) |
| 10 % loss | 9.0 times faster (1.74 vs 0.19 Mbps) |
| 20 % loss | 16.5 times faster (0.64 vs 0.04 Mbps) |
| 15 % reordering | 48 times faster (3.29 vs 0.07 Mbps) |

Selective Repeat is also at least 8 times faster than Stop-and-Wait across the whole loss sweep.

**Known limitations (also stated in the report)**
- The proposal specifies a 100 MB file; the final data uses 0.5 MB because larger runs take hours.
  Stop-and-Wait measured about 0.3 Mbps on the team's machines, so one 100 MB run would take
  roughly 45 minutes or more even without loss.
- In the window sweep at 1 % loss, Selective Repeat and Go-Back-N are close and Selective Repeat's
  advantage is not established (its standard deviation is large at windows 16, 32 and 64).
- Selective Repeat varies more from run to run than the other two protocols, and it makes some
  unnecessary retransmissions even at 0 % loss (about 64 per transfer). A likely cause is the sender
  checking timers before reading ACKs that are already waiting; this was not changed after the
  data was collected.
- Sender, emulator and receiver run on one machine, so absolute Mbps depend on the CPU; the
  relative ordering and trends are what the claim concerns.

## Integrity
Receiver recomputes SHA-256 of the written file and compares with the hash from the SYN; the
harness also records `integrity_ok` per run and `plot.py` excludes (and lists) any run that failed.
