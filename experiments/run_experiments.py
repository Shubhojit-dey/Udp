#!/usr/bin/env python3
"""
Experiment orchestrator for the claim:
  "as loss and reordering increase, Selective Repeat holds a materially higher goodput than
   Go-Back-N and Stop-and-Wait."

One parameter is varied at a time (loss, window, reorder, plus an RTO-sensitivity sweep); every
other parameter stays at the BASELINE. Each data point is repeated with seeds base_seed+repeat,
and every row in results.csv records the full config + seed so any point can be replayed.

Usage:
  python3 experiments/run_experiments.py --profile smoke      # ~1-2 min sanity run
  python3 experiments/run_experiments.py --profile standard   # 2 MB file, 3 repeats
  python3 experiments/run_experiments.py --profile full       # 100 MB (see --sw-size-mb note)
The script is resumable: finished (config, seed) rows already in the CSV are skipped.
"""
import argparse, csv, hashlib, itertools, json, os, random, subprocess, sys, tempfile, time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CLASSES = ROOT / "out"

BASELINE = dict(loss=1.0, dup=0.0, reorder=0.0, corrupt=0.0, window=16, rto="adaptive")
SWEEPS = {
    "loss":    ("loss",    [0, 1, 2, 5, 10, 15, 20]),
    "window":  ("window",  [4, 8, 16, 32, 64]),
    "reorder": ("reorder", [0, 2.5, 5, 10, 15]),
    "rto":     ("rto",     ["adaptive", "fixed:20", "fixed:50", "fixed:100", "fixed:250", "fixed:500"]),
}
PROTOCOLS = ["sw", "gbn", "sr"]
FIELDS = ["sweep", "protocol", "loss", "dup", "reorder", "corrupt", "window", "rto", "delay_ms",
          "jitter_ms", "seg", "seed", "repeat", "size_bytes", "segments", "goodput_mbps",
          "retransmissions", "transmissions", "timeouts", "elapsed_s", "rto_final_ms",
          "integrity_ok", "completed", "emu_fwd_dropped", "emu_fwd_reordered"]


def java(*args, **kw):
    # Windows antivirus can briefly refuse to start a process (WinError 5); retry a few times.
    for attempt in range(8):
        try:
            return subprocess.Popen(["java", "-Xmx1g", "-cp", str(CLASSES), "rdt.Main", *map(str, args)],
                                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, **kw)
        except OSError:
            if attempt == 7:
                raise
            time.sleep(2 + attempt)


def wait_ready(proc, what):
    line = proc.stdout.readline().strip()
    if not line.startswith("READY"):
        raise RuntimeError(f"{what} did not start: {line!r} {proc.stderr.read()}")
    return int(line.split()[1])


def make_input(path: Path, size_bytes: int):
    if path.exists() and path.stat().st_size == size_bytes:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    rng = random.Random(2024)
    with open(path, "wb") as f:
        left = size_bytes
        while left > 0:
            n = min(left, 1 << 20)
            f.write(rng.randbytes(n))
            left -= n


def run_one(proto, cfg, seed, infile, args):
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        rcv = java("receive", "--port", 0, "--out", td / "out.bin", "--stats", td / "r.json", "--linger", 1500)
        emu = None
        try:
            rport = wait_ready(rcv, "receiver")
            emu = java("emulate", "--listen", 0, "--target", f"127.0.0.1:{rport}",
                       "--loss", cfg["loss"], "--dup", cfg["dup"], "--reorder", cfg["reorder"],
                       "--corrupt", cfg["corrupt"], "--delay", args.delay, "--jitter", args.jitter,
                       "--reorder-gap", args.reorder_gap, "--seed", seed, "--stats", td / "e.json")
            eport = wait_ready(emu, "emulator")
            snd = java("send", "--to", f"127.0.0.1:{eport}", "--file", infile, "--proto", proto,
                       "--window", cfg["window"], "--seg", args.seg, "--rto", cfg["rto"],
                       "--rto-min", args.rto_min, "--stats", td / "s.json")
            try:
                snd.wait(timeout=args.run_timeout)
            except subprocess.TimeoutExpired:
                snd.kill()
                return None
            try:
                rcv.wait(timeout=15)
            except subprocess.TimeoutExpired:
                rcv.kill()
            s = json.loads((td / "s.json").read_text()) if (td / "s.json").exists() else None
            r = json.loads((td / "r.json").read_text()) if (td / "r.json").exists() else {}
            if s is None:
                return None
            e = {}
            emu.terminate()
            try:
                emu.wait(timeout=3)
            except subprocess.TimeoutExpired:
                emu.kill()
            if (td / "e.json").exists():
                e = json.loads((td / "e.json").read_text())
            return s, r, e
        finally:
            for p in (rcv, emu):
                if p and p.poll() is None:
                    p.kill()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--profile", choices=["smoke", "standard", "full"], default="standard")
    ap.add_argument("--size-mb", type=float)
    ap.add_argument("--sw-size-mb", type=float, help="smaller file for Stop-and-Wait (goodput is size-independent)")
    ap.add_argument("--repeats", type=int)
    ap.add_argument("--sweeps", default="loss,window,reorder,rto")
    ap.add_argument("--protocols", default="sw,gbn,sr")
    ap.add_argument("--base-seed", type=int, default=1000)
    ap.add_argument("--delay", type=float, default=5.0, help="one-way base delay ms (RTT ~ 2x)")
    ap.add_argument("--jitter", type=float, default=1.0)
    ap.add_argument("--reorder-gap", type=float, default=15.0)
    ap.add_argument("--seg", type=int, default=1200)
    ap.add_argument("--rto-min", type=float, default=20.0)
    ap.add_argument("--run-timeout", type=float, default=900)
    ap.add_argument("--out", default=str(ROOT / "results" / "results.csv"))
    args = ap.parse_args()

    prof = {"smoke": (0.25, 0.25, 1), "standard": (2, 1, 3), "full": (100, 5, 3)}[args.profile]
    size_mb = args.size_mb or prof[0]
    sw_mb = args.sw_size_mb or prof[1]
    repeats = args.repeats or prof[2]
    sweeps = args.sweeps.split(",")
    if args.profile == "smoke":
        SWEEPS["loss"] = ("loss", [0, 10, 20]); SWEEPS["window"] = ("window", [4, 64])
        SWEEPS["reorder"] = ("reorder", [0, 15]); SWEEPS["rto"] = ("rto", ["adaptive", "fixed:100"])

    if not (CLASSES / "rdt" / "Main.class").exists():
        subprocess.check_call([str(ROOT / "build.sh")])

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    done = set()
    if out.exists():
        with open(out) as f:
            for row in csv.DictReader(f):
                done.add((row["sweep"], row["protocol"], row["loss"], row["reorder"], row["window"], row["rto"], row["seed"], row["size_bytes"]))
    new_file = not out.exists()
    fh = open(out, "a", newline="")
    w = csv.DictWriter(fh, fieldnames=FIELDS)
    if new_file:
        w.writeheader()

    jobs = []
    for sw in sweeps:
        key, values = SWEEPS[sw]
        for v in values:
            cfg = dict(BASELINE)
            cfg[key] = v
            if sw == "rto":
                cfg["loss"] = 5.0  # make timeouts matter
            for proto in args.protocols.split(","):
                for rep in range(repeats):
                    jobs.append((sw, proto, cfg, rep))
    print(f"{len(jobs)} runs planned ({args.profile}: {size_mb} MB, S&W {sw_mb} MB, {repeats} repeats)")

    t_start = time.time()
    for n, (sw, proto, cfg, rep) in enumerate(jobs, 1):
        seed = args.base_seed + rep
        mb = sw_mb if proto == "sw" else size_mb
        size = int(mb * 1024 * 1024)
        key = (sw, proto, str(float(cfg["loss"])) if False else str(cfg["loss"]), str(cfg["reorder"]),
               str(cfg["window"]), cfg["rto"], str(seed), str(size))
        if key in done:
            continue
        infile = ROOT / "results" / "data" / f"input_{size}.bin"
        make_input(infile, size)
        res = None
        for attempt in range(3):
            try:
                res = run_one(proto, cfg, seed, infile, args)
                break
            except (OSError, RuntimeError) as ex:
                print(f"  start-up problem ({ex.__class__.__name__}), retrying...", flush=True)
                time.sleep(3)
        if res is None:
            row = dict(sweep=sw, protocol=proto, **{k: cfg[k] for k in ("loss", "dup", "reorder", "corrupt", "window", "rto")},
                       delay_ms=args.delay, jitter_ms=args.jitter, seg=args.seg, seed=seed, repeat=rep,
                       size_bytes=size, completed=False, integrity_ok=False)
        else:
            s, r, e = res
            row = dict(sweep=sw, protocol=proto, **{k: cfg[k] for k in ("loss", "dup", "reorder", "corrupt", "window", "rto")},
                       delay_ms=args.delay, jitter_ms=args.jitter, seg=args.seg, seed=seed, repeat=rep,
                       size_bytes=size, segments=s["segments"], goodput_mbps=s["goodput_mbps"],
                       retransmissions=s["retransmissions"], transmissions=s["transmissions"],
                       timeouts=s["timeouts"], elapsed_s=s["elapsed_s"], rto_final_ms=s["rto_final_ms"],
                       integrity_ok=r.get("integrity_ok", False), completed=r.get("complete", False),
                       emu_fwd_dropped=e.get("fwd_dropped", ""), emu_fwd_reordered=e.get("fwd_reordered", ""))
        w.writerow(row)
        fh.flush()
        eta = (time.time() - t_start) / n * (len(jobs) - n)
        print(f"[{n}/{len(jobs)}] {sw:7s} {proto:3s} loss={cfg['loss']} reorder={cfg['reorder']} win={cfg['window']} "
              f"rto={cfg['rto']} seed={seed} -> "
              + (f"{row['goodput_mbps']} Mbps retx={row['retransmissions']} ok={row['integrity_ok']}" if res else "FAILED/TIMEOUT")
              + f"  (eta {eta/60:.1f} min)", flush=True)
    fh.close()
    bad = 0
    with open(out) as f:
        for row in csv.DictReader(f):
            if row["integrity_ok"] != "True":
                bad += 1
    print(f"done. rows with failed integrity/completion: {bad}  ->  {out}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
