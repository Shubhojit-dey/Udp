### RTO sensitivity (loss = 5%, other parameters at baseline)

| RTO | Stop-and-Wait goodput (Mbps) | retx/1k | Go-Back-N goodput (Mbps) | retx/1k | Selective Repeat goodput (Mbps) | retx/1k |
|---|---|---|---|---|---|---|
| adaptive | 0.25 ± 0.01 | 99 | 1.02 ± 0.45 | 712 | 2.66 ± 1.00 | 98 |
| fixed:20 | 0.28 ± 0.00 | 1094 | 3.40 ± 0.58 | 1450 | 3.71 ± 0.35 | 1053 |
| fixed:50 | 0.26 ± 0.00 | 99 | 1.73 ± 0.27 | 712 | 2.82 ± 0.45 | 55 |
| fixed:100 | 0.23 ± 0.01 | 99 | 1.26 ± 0.23 | 712 | 2.08 ± 0.42 | 52 |
| fixed:250 | 0.17 ± 0.01 | 99 | 0.69 ± 0.14 | 712 | 1.15 ± 0.29 | 52 |
| fixed:500 | 0.12 ± 0.01 | 99 | 0.39 ± 0.09 | 712 | 0.66 ± 0.19 | 52 |

### Claim check: Selective Repeat goodput relative to the others

| loss (%) | SR Mbps | GBN Mbps | SW Mbps | SR/GBN | SR/SW |
|---|---|---|---|---|---|
| 0 | 4.74 | 4.72 | 0.30 | 1.00x | 15.57x |
| 1 | 4.18 | 3.80 | 0.30 | 1.10x | 14.15x |
| 2 | 3.76 | 3.11 | 0.29 | 1.21x | 13.06x |
| 5 | 2.16 | 1.02 | 0.25 | 2.12x | 8.61x |
| 10 | 1.74 | 0.19 | 0.19 | 9.03x | 9.14x |
| 15 | 1.02 | 0.07 | 0.12 | 15.09x | 8.19x |
| 20 | 0.64 | 0.04 | 0.07 | 16.47x | 8.76x |

| reorder (%) | SR Mbps | GBN Mbps | SW Mbps | SR/GBN | SR/SW |
|---|---|---|---|---|---|
| 0 | 4.19 | 3.81 | 0.30 | 1.10x | 14.17x |
| 2.5 | 4.03 | 1.35 | 0.29 | 2.99x | 13.79x |
| 5 | 3.78 | 0.41 | 0.28 | 9.27x | 13.36x |
| 10 | 3.48 | 0.12 | 0.27 | 29.97x | 13.04x |
| 15 | 3.29 | 0.07 | 0.25 | 47.96x | 13.04x |


rows used: 207 / 207 (rows failing integrity or completion are excluded and listed below)

