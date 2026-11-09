#!/usr/bin/env python3
"""
Builds custom.channels.xml for github.com/iptv-org/epg, covering every channel
in the app's UK, USA and Sports playlists that iptv-org has a guide source for.

Run it from inside your clone of iptv-org/epg:
    python3 /path/to/TVPlayer/tools/make_epg_channels.py
"""
import json
import re
import urllib.request
from xml.sax.saxutils import escape, quoteattr

IPTV = "https://iptv-org.github.io/iptv"
PLAYLISTS = [
    f"{IPTV}/countries/uk.m3u",
    f"{IPTV}/countries/us.m3u",
    f"{IPTV}/categories/sports.m3u",
]
GUIDES = "https://iptv-org.github.io/api/guides.json"
OUTPUT = "custom.channels.xml"


def fetch(url: str) -> str:
    with urllib.request.urlopen(url, timeout=120) as r:
        return r.read().decode("utf-8")


def main() -> None:
    ids = set()
    for url in PLAYLISTS:
        print(f"Reading {url}")
        ids.update(m.group(1).split("@")[0] for m in re.finditer(r'tvg-id="([^"]+)"', fetch(url)))
    print(f"{len(ids)} channels with an ID in the playlists")

    print("Reading guide sources")
    chosen = {}
    for g in json.loads(fetch(GUIDES)):
        ch = g.get("channel")
        if ch not in ids or not g.get("site") or not g.get("site_id"):
            continue
        rank = 0 if g.get("lang") == "en" else 1   # prefer English listings
        if ch not in chosen or rank < chosen[ch][0]:
            chosen[ch] = (rank, g)

    lines = ['<?xml version="1.0" encoding="UTF-8"?>', "<channels>"]
    for ch, (_, g) in sorted(chosen.items()):
        lines.append(
            f"  <channel site={quoteattr(g['site'])} lang={quoteattr(g.get('lang') or 'en')} "
            f"xmltv_id={quoteattr(ch)} site_id={quoteattr(str(g['site_id']))}>"
            f"{escape(g.get('site_name') or ch)}</channel>"
        )
    lines.append("</channels>")

    with open(OUTPUT, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"Wrote {OUTPUT}: {len(chosen)} channels have a guide source, {len(ids) - len(chosen)} don't")


if __name__ == "__main__":
    main()
