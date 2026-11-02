#!/usr/bin/env python3
"""Builds app/src/main/assets/switch-titles.tsv from blawar/titledb (MIT).

Download the region files first, then run:

    curl -LO https://raw.githubusercontent.com/blawar/titledb/master/US.en.json
    curl -LO https://raw.githubusercontent.com/blawar/titledb/master/HK.zh.json
    pip install opencc-python-reimplemented==0.1.7
    python3 scripts/build_switch_titles.py US.en.json HK.zh.json

Only base applications (TitleIDs ending in 000) are kept: a game's save data
is filed under that ID. Each line is "TitleID<TAB>name", the Chinese name
when the Hong Kong eShop has one (converted to Simplified Chinese), else the
English one.
"""
import json
import os
import re
import sys

from opencc import OpenCC

TITLE_ID = re.compile(r"^[0-9A-F]{13}000$")
OUTPUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "switch-titles.tsv")


def names(path):
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    result = {}
    for entry in data.values():
        title_id = (entry.get("id") or "").upper()
        name = " ".join((entry.get("name") or "").replace("™", "").replace("®", "").split())
        if TITLE_ID.match(title_id) and name and not entry.get("isDemo"):
            result.setdefault(title_id, name)
    return result


def main(english, chinese):
    merged = names(english)
    simplified = OpenCC("t2s")
    merged.update({title_id: simplified.convert(name) for title_id, name in names(chinese).items()})
    os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
    with open(OUTPUT, "w", encoding="utf-8", newline="\n") as out:
        for title_id in sorted(merged):
            out.write(f"{title_id}\t{merged[title_id]}\n")
    print(f"{len(merged)} titles -> {os.path.normpath(OUTPUT)} ({os.path.getsize(OUTPUT)} bytes)")


if __name__ == "__main__":
    main(*sys.argv[1:3])
