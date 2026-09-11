#!/usr/bin/env python3
"""审计验证2：官方链接 HEAD 探测 + 第三方兜底源可用性"""
import requests, base64, json
from kuwo_audit import encryptquery  # 复用 DES

hdr_mobi = {"User-Agent": "okhttp/3.10.0"}

# 1. 官方解析拿到播放链接
rid = "228908"
query = f"user=0&corp=kuwo&source=kwplayer_ar_5.1.0.0_B_jiakong_vh.apk&p2p=1&type=convert_url2&sig=0&format=mp3&rid={rid}"
q = encryptquery(query)
r = requests.get(f"https://mobi.kuwo.cn/mobi.s?f=kuwo&q={q}", headers=hdr_mobi, timeout=15)
url = [l.split("=",1)[1] for l in r.text.splitlines() if l.startswith("url=")][0]
print(f"官方播放链接: {url[:100]}...")

# 2. HEAD 探测（v1.5.1 probeUrl 的做法）
for method, kw in [("HEAD", {}), ("GET", {"headers": {"Range": "bytes=0-0"}})]:
    try:
        resp = requests.request(method, url, timeout=8, **kw)
        ct = resp.headers.get("Content-Type", "")
        cl = resp.headers.get("Content-Length", "")
        cr = resp.headers.get("Content-Range", "")
        print(f"[{method}] HTTP {resp.status_code} Content-Type={ct} Length={cl} {cr}")
    except Exception as e:
        print(f"[{method}] 异常: {type(e).__name__}: {str(e)[:120]}")

# 3. 第三方兜底源可用性（v1.5.1 的 fallback 链）
print()
print("=== 第三方兜底源测试（搜索周杰伦第一首的 rid=442418 只是示例，实际按接口要求）===")
# nxinxz
try:
    r = requests.get("https://api.xingxue.cn/api/kuwo?rid=228908&br=128kmp3", timeout=10)
    print(f"[nxinxz] HTTP {r.status_code}: {r.text[:150]}")
except Exception as e:
    print(f"[nxinxz] 异常: {str(e)[:120]}")
# haitangw
try:
    r = requests.get("https://music-api.haitangw.cn/api/kuwo?rid=228908&br=128kmp3", timeout=10)
    print(f"[haitangw] HTTP {r.status_code}: {r.text[:150]}")
except Exception as e:
    print(f"[haitangw] 异常: {str(e)[:120]}")
# xcloudv
try:
    r = requests.post("https://music.xcloudv.top/api/kuwo", json={"action": "url", "songid": "228908", "yz": 5}, timeout=10)
    print(f"[xcloudv] HTTP {r.status_code}: {r.text[:150]}")
except Exception as e:
    print(f"[xcloudv] 异常: {str(e)[:120]}")
