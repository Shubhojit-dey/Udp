# AI-USE.md

CS-30003 · Coding Assignment 1 · Project P3 (Reliable Data Transfer over UDP) · Team 4

## 1. Tool used
Claude (Anthropic), through claude.ai chat. It was given the team's approved project proposal
(P3, Path A) and asked to build the full project. The team then ran, tested and measured the
result on its own Windows computers and sent the output files back for the report.

## 2. What the AI produced
- **Java transport core** (`src/main/java/rdt/`): packet format and checksum, the three ARQ senders
  (Stop-and-Wait, Go-Back-N, Selective Repeat), the receiver, the RTO estimator, SHA-256 helper,
  command-line interface and the self-test (`SelfTest.java`).
- **Channel emulator** (`ChannelEmulator.java`): loss, duplication, reordering, corruption and jitter,
  controlled by a seed.
- **Python scripts** (`experiments/`): the experiment runner and the plotting script.
- **Documents:** `README.md`, this file, `docs/VIVA_NOTES.md`, and the project report
  (Word and PDF), written from the result CSV files the team supplied.
- **Fixes made after testing:**
  1. Emulator jitter was reordering packets because delivery times were computed relative to
     scheduling latency; replaced the scheduler with an exact `DelayQueue`.
  2. Corrupt-datagram handling in the receiver was tidied up.
  3. The experiment runner now retries when Windows refuses to start a Java process
     ("Access is denied"), and the team was advised to run Java with `-Xmx128m` after
     out-of-memory crashes.
  4. The team was advised to use a 0.5 MB file after full-size runs proved too slow.

## 3. What the team did
- Supplied the approved proposal and the requirements.
- Compiled the code and ran `rdt.SelfTest` on their own computers (all tests passed).
- Ran the experiment sweep: 207 runs (loss, window, reordering and RTO sweeps, 3 repeats each,
  0.5 MB file). All 207 runs finished with a matching SHA-256 hash.
- Handled crashes by re-running failed runs and removing duplicate records.
- Generated the plots and supplied the CSV results used in the report.
- Chose the 0.5 MB file size instead of the 100 MB in the proposal.

## 4. Work distribution (from the approved proposal)
Each member owns one component end to end and shares one task with a partner, so scope and
accountability are equal. The AI wrote the first version of all of these files; every member is
responsible for understanding and being able to explain and defend the component below in the viva
(see `docs/VIVA_NOTES.md`).

| Member | Roll no. | Owns end to end | Shares with a partner | Main files |
|---|---|---|---|---|
| Shubhojit Dey | 25057064 | Stop-and-Wait: framing, sequencing, checksum logic | Channel emulator (with Shwe Yee Mon) | `StopAndWaitSender.java`, `Packet.java`, `Checksum.java`, `ChannelEmulator.java` |
| Shwe Yee Mon | 24052735 | Go-Back-N: cumulative ACKs, sliding window | Channel emulator (with Shubhojit Dey) | `GoBackNSender.java`, in-order branch of `ArqReceiver.java`, `ChannelEmulator.java` |
| Souvick Biswas | 25057075 | Selective Repeat: selective ACKs, reorder buffer | Experiment harness and hash-integrity checks (with Snigdhanil Sabud) | `SelectiveRepeatSender.java`, SACK branch of `ArqReceiver.java`, `run_experiments.py`, `plot.py`, `Sha256Util.java` |
| Snigdhanil Sabud | 25057071 | Adaptive RTO: Jacobson/Karels and Karn's algorithm | Experiment harness and hash-integrity checks (with Souvick Biswas) | `RtoEstimator.java` (used by all three senders), `run_experiments.py`, `plot.py`, `Sha256Util.java` |

Weekly plan from the proposal: Week 1 all (repository, socket scaffolding, header format);
Week 2 Shubhojit; Week 3 Shwe Yee Mon; Week 4 Souvick; Week 5 Snigdhanil;
Week 6 Shubhojit and Shwe Yee Mon (emulator); Week 7 Souvick and Snigdhanil (full sweep, plots,
integrity validation); Week 8 all (report, this file, clean-up, viva rehearsal).

## 5. Design decisions the AI made that differ from the proposal
- Each sender is a single-threaded event loop with a centralised timer, instead of per-segment OS
  timers. This is the fallback named in the proposal's risk table.
- Emulator fault decisions are hashed from (seed, direction, packet id, occurrence) instead of drawn
  from a shared random stream.
- Jitter never reorders packets by itself; reordering is a separate, explicit setting.
- Stop-and-Wait can run on a smaller file (`--sw-size-mb`) because of its low throughput.
- Final experiments used a 0.5 MB file, not the 100 MB in the proposal.

## 6. How the results were checked
- `java -Xmx128m -cp out rdt.SelfTest` checks the checksum, the RTO rules and that all three protocols
  deliver a byte-identical file through a hostile channel.
- Every experiment run compares the SHA-256 of the sent and received file.
- Every number in the report's tables was recomputed from the team's result CSV files.

## 7. What was not verified, and open points
- The AI could not run the experiments on the team's computers; all reported measurements come from
  the team's own runs.
- Two explanations in the report are the AI's best guesses and were not tested separately: (a) why
  Selective Repeat sends some unnecessary retransmissions even at 0% loss (likely timer wake-up
  timing on Windows), and (b) why the adaptive timer is slower than a fixed 20 ms timer at 5% loss.
- The 100 MB experiment from the proposal was not run.
- Selective Repeat results vary more from run to run than Go-Back-N and Stop-and-Wait results; the
  report says so.
