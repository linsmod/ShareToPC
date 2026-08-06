#!/usr/bin/env python3
import json
import os
import sys

import requests

BASE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_SRC = os.path.join(BASE, "icons_download.json")
OUT = os.path.join(BASE, "download_icons")


def download(src):
    print("== " + os.path.basename(src))
    with open(src, encoding="utf-8") as f:
        data = json.load(f)

    os.makedirs(OUT, exist_ok=True)
    headers = {"User-Agent": "Mozilla/5.0"}
    ok = fail = 0
    for i in range(0, len(data), 2):
        url = data[i].strip() if isinstance(data[i], str) else ""
        if not url:
            continue
        name = os.path.basename(url.split("?")[0])
        path = os.path.join(OUT, name)
        try:
            r = requests.get(url, headers=headers, timeout=30)
            r.raise_for_status()
            with open(path, "wb") as fp:
                fp.write(r.content)
            print("OK ", name)
            ok += 1
        except Exception as e:
            print("FAIL", url, "->", e)
            fail += 1
    print(f"Done: {ok} ok, {fail} failed\n")
    return ok, fail


def main():
    srcs = sys.argv[1:] if len(sys.argv) > 1 else [DEFAULT_SRC]
    total_ok = total_fail = 0
    for s in srcs:
        ok, fail = download(s)
        total_ok += ok
        total_fail += fail
    print(f"All done: {total_ok} ok, {total_fail} failed -> {OUT}")


if __name__ == "__main__":
    main()
