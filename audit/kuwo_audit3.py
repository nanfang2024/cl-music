#!/usr/bin/env python3
"""审计验证3：官方链接完整 GET 是否可下载（不同 UA/头组合）"""
import requests
from kuwo_audit import encryptquery

hdr_mobi = {"User-Agent": "okhttp/3.10.0"}

def resolve(rid, fmt):
    query = f"user=0&corp=kuwo&source=kwplayer_ar_5.1.0.0_B_jiakong_vh.apk&p2p=1&type=convert_url2&sig=0&format={fmt}&rid={rid}"
    q = encryptquery(query)
    r = requests.get(f"https://mobi.kuwo.cn/mobi.s?f=kuwo&q={q}", headers=hdr_mobi, timeout=15)
    d = {}
    for line in r.text.splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            if k not in d: d[k] = v
    return d

d = resolve("228908", "mp3")
url = d.get("url", "")
print("=== 完整响应字段 ===")
for k in ["format", "bitrate", "type", "rid", "sig"]:
    if k in d: print(f"  {k} = {d[k]}")
print(f"  url = {url}")

print()
print("=== 完整 GET 下载测试（Range: bytes=0-99999 取前100KB）===")
for name, headers in [
    ("默认requests UA", {}),
    ("okhttp/3.10.0", {"User-Agent": "okhttp/3.10.0"}),
    ("空UA", {"User-Agent": ""}),
]:
    try:
        r = requests.get(url, headers={**headers, "Range": "bytes=0-99999"}, timeout=15)
        data_len = len(r.content)
        print(f"[{name}] HTTP {r.status_code} 拿到 {data_len} bytes, Content-Type={r.headers.get('Content-Type')}")
        if r.status_code == 200 or r.status_code == 206:
            print(f"    前16字节: {r.content[:16].hex()}")
    except Exception as e:
        print(f"[{name}] 异常: {str(e)[:100]}")

print()
print("=== 320kmp3 / flac 档位 ===")
for fmt in ["mp3&br=320kmp3", "flac"]:
    try:
        d2 = resolve("228908", fmt)
        u2 = d2.get("url", "")
        r2 = requests.get(u2, headers={"User-Agent": "okhttp/3.10.0", "Range": "bytes=0-99999"}, timeout=15)
        print(f"[{fmt}] HTTP {r2.status_code} {len(r2.content)} bytes, bitrate={d2.get('bitrate')}, format={d2.get('format')}")
        if r2.status_code in (200, 206):
            print(f"    链接前缀: {u2[:90]}")
    except Exception as e:
        print(f"[{fmt}] 异常: {str(e)[:100]}")
