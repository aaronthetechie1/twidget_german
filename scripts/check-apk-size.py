#!/usr/bin/env python3
"""Gate fresh universal release APKs; this is not the Play device download size."""
import argparse
from pathlib import Path
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('apks', nargs='+', type=Path)
parser.add_argument('--max-bytes', type=int, default=19_000_000)
args = parser.parse_args()
failed = False
for path in args.apks:
    size = path.stat().st_size
    print(f'{path}: {size:,} bytes (budget {args.max_bytes:,})')
    failed |= size > args.max_bytes
raise SystemExit(1 if failed else 0)
