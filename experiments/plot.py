#!/usr/bin/env python3
"""Reads results/results.csv, writes plots + tables to results/plots/ (pandas + matplotlib)."""
import sys
from pathlib import Path
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent.parent
CSV = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "results" / "results.csv"
OUT = ROOT / "results" / "plots"
OUT.mkdir(parents=True, exist_ok=True)
LABEL = {"sw": "Stop-and-Wait", "gbn": "Go-Back-N", "sr": "Selective Repeat"}
COLOR = {"sw": "#7f7f7f", "gbn": "#d62728", "sr": "#1f77b4"}
MARK = {"sw": "s", "gbn": "^", "sr": "o"}

df = pd.read_csv(CSV)
total_rows = len(df)
bad = df[~(df["integrity_ok"].astype(str) == "True")]
df = df[df["integrity_ok"].astype(str) == "True"].copy()
df["retx_per_1k"] = df["retransmissions"] / df["segments"] * 1000


def agg(sweep, xcol):
    d = df[df["sweep"] == sweep]
    g = d.groupby(["protocol", xcol]).agg(
        goodput=("goodput_mbps", "mean"), goodput_sd=("goodput_mbps", "std"),
        retx=("retransmissions", "mean"), retx_sd=("retransmissions", "std"),
        retx1k=("retx_per_1k", "mean"), retx1k_sd=("retx_per_1k", "std"),
        time=("elapsed_s", "mean"), n=("goodput_mbps", "count")).reset_index()
    g[["goodput_sd", "retx_sd", "retx1k_sd"]] = g[["goodput_sd", "retx_sd", "retx1k_sd"]].fillna(0)
    return g


def line_plot(sweep, xcol, xlabel, fname, title, logy=False):
    g = agg(sweep, xcol)
    if g.empty:
        return None
    fig, ax = plt.subplots(figsize=(7, 4.5))
    for p in ["sw", "gbn", "sr"]:
        s = g[g["protocol"] == p].sort_values(xcol)
        if s.empty:
            continue
        ax.errorbar(s[xcol], s["goodput"], yerr=s["goodput_sd"], label=LABEL[p], color=COLOR[p],
                    marker=MARK[p], capsize=3, lw=1.8)
    ax.set_xlabel(xlabel); ax.set_ylabel("Goodput (Mbps)"); ax.set_title(title)
    if logy: ax.set_yscale("log")
    ax.grid(alpha=.3); ax.legend()
    fig.tight_layout(); fig.savefig(OUT / fname, dpi=160); plt.close(fig)
    g.to_csv(OUT / fname.replace(".png", ".csv"), index=False)
    return g


gl = line_plot("loss", "loss", "Packet loss rate (%)", "goodput_vs_loss.png", "Goodput vs loss (mean ± std, 3 repeats)")
gw = line_plot("window", "window", "Window size (segments)", "goodput_vs_window.png", "Goodput vs window size")
gr = line_plot("reorder", "reorder", "Reordering probability (%)", "goodput_vs_reorder.png", "Goodput vs reordering")

# retransmissions vs reordering: grouped bars
g = agg("reorder", "reorder")
if not g.empty:
    xs = sorted(g["reorder"].unique())
    fig, ax = plt.subplots(figsize=(7.5, 4.5))
    w = 0.26
    for i, p in enumerate(["sw", "gbn", "sr"]):
        s = g[g["protocol"] == p].set_index("reorder").reindex(xs)
        ax.bar([k + (i - 1) * w for k in range(len(xs))], s["retx1k"], w, yerr=s["retx1k_sd"],
               label=LABEL[p], color=COLOR[p], capsize=3)
    ax.set_xticks(range(len(xs))); ax.set_xticklabels([f"{x:g}%" for x in xs])
    ax.set_xlabel("Reordering probability"); ax.set_ylabel("Retransmissions per 1000 segments")
    ax.set_title("Retransmissions vs reordering"); ax.legend(); ax.grid(axis="y", alpha=.3)
    fig.tight_layout(); fig.savefig(OUT / "retx_vs_reorder.png", dpi=160); plt.close(fig)

# RTO sensitivity table
d = df[df["sweep"] == "rto"]
lines = []
if not d.empty:
    t = d.groupby(["rto", "protocol"]).agg(gp=("goodput_mbps", "mean"), gps=("goodput_mbps", "std"),
                                           rx=("retx_per_1k", "mean")).reset_index().fillna(0)
    order = ["adaptive"] + sorted([r for r in t["rto"].unique() if r != "adaptive"], key=lambda s: float(s.split(":")[1]))
    hdr = "| RTO | " + " | ".join(f"{LABEL[p]} goodput (Mbps) | retx/1k" for p in ["sw", "gbn", "sr"]) + " |"
    lines += ["### RTO sensitivity (loss = 5%, other parameters at baseline)", "", hdr,
              "|" + "---|" * (1 + 2 * 3)]
    for r in order:
        cells = []
        for p in ["sw", "gbn", "sr"]:
            x = t[(t["rto"] == r) & (t["protocol"] == p)]
            cells += [f"{x.iloc[0]['gp']:.2f} ± {x.iloc[0]['gps']:.2f}", f"{x.iloc[0]['rx']:.0f}"] if len(x) else ["-", "-"]
        lines.append(f"| {r} | " + " | ".join(cells) + " |")
    t.to_csv(OUT / "rto_sensitivity.csv", index=False)

# claim check
claim = ["", "### Claim check: Selective Repeat goodput relative to the others", ""]
for name, gdf, xcol in (("loss (%)", gl, "loss"), ("reorder (%)", gr, "reorder")):
    if gdf is None or gdf.empty:
        continue
    claim += [f"| {name} | SR Mbps | GBN Mbps | SW Mbps | SR/GBN | SR/SW |", "|---|---|---|---|---|---|"]
    for x in sorted(gdf[xcol].unique()):
        v = {p: gdf[(gdf["protocol"] == p) & (gdf[xcol] == x)]["goodput"].mean() for p in ["sr", "gbn", "sw"]}
        claim.append(f"| {x:g} | {v['sr']:.2f} | {v['gbn']:.2f} | {v['sw']:.2f} | {v['sr']/v['gbn']:.2f}x | {v['sr']/v['sw']:.2f}x |")
    claim.append("")
(OUT / "summary.md").write_text("\n".join(lines + claim) + f"\n\nrows used: {len(df)} / {total_rows} (rows failing integrity or completion are excluded and listed below)\n"
                                + ("\n" + bad.to_string() if len(bad) else "") + "\n")
print((OUT / "summary.md").read_text())
print("plots written to", OUT)
