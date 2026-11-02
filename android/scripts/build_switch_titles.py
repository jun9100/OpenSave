#!/usr/bin/env python3
"""Builds app/src/main/assets/switch-titles.tsv from blawar/titledb (MIT).

Download the region files first, then run:

    curl -LO https://raw.githubusercontent.com/blawar/titledb/master/US.en.json
    curl -LO https://raw.githubusercontent.com/blawar/titledb/master/HK.zh.json
    curl -LO https://raw.githubusercontent.com/blawar/titledb/master/JP.ja.json
    pip install opencc-python-reimplemented==0.1.7
    python3 scripts/build_switch_titles.py US.en.json HK.zh.json JP.ja.json

Only base applications (TitleIDs ending in 000) are kept: a game's save data
is filed under that ID. Each line is "TitleID<TAB>name<TAB>icon", the
Chinese name when the Hong Kong eShop has one (converted to Simplified
Chinese), else the English one, else the Japanese one for titles sold only
in Japan; icon is the hash of the eShop icon at
img-eshop.cdn.nintendo.net/i/<hash>.jpg, or empty.
"""
import json
import os
import re
import sys

from opencc import OpenCC

TITLE_ID = re.compile(r"^[0-9A-F]{13}000$")
ICON_URL = re.compile(r"^https://img-eshop\.cdn\.nintendo\.net/i/([0-9a-f]{64})\.jpg$")
OUTPUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "switch-titles.tsv")


def titles(path):
    """TitleID -> (name, icon hash or "")."""
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    result = {}
    for entry in data.values():
        title_id = (entry.get("id") or "").upper()
        name = " ".join((entry.get("name") or "").replace("™", "").replace("®", "").split())
        icon = ICON_URL.match(entry.get("iconUrl") or "")
        if TITLE_ID.match(title_id) and name and not entry.get("isDemo"):
            result.setdefault(title_id, (name, icon.group(1) if icon else ""))
    return result


def main(english, chinese, japanese):
    merged = {title_id: title for title_id, title in titles(japanese).items()}
    for title_id, (name, icon) in titles(english).items():
        merged[title_id] = (name, icon or merged.get(title_id, ("", ""))[1])
    simplified = OpenCC("t2s")
    for title_id, (name, icon) in titles(chinese).items():
        merged[title_id] = (simplified.convert(name), merged.get(title_id, ("", ""))[1] or icon)
    os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
    with open(OUTPUT, "w", encoding="utf-8", newline="\n") as out:
        for title_id in sorted(merged):
            name, icon = merged[title_id]
            out.write(f"{title_id}\t{name}\t{icon}\n")
    print(f"{len(merged)} titles -> {os.path.normpath(OUTPUT)} ({os.path.getsize(OUTPUT)} bytes)")


if __name__ == "__main__":
    main(*sys.argv[1:4])
