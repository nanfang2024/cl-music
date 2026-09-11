#!/usr/bin/env python3
"""审计验证4：HEAD+okhttp UA 探测 + 歌词接口 base64 参数编码"""
import requests, base64, zlib
from kuwo_audit import encryptquery

hdr_mobi = {"User-Agent": "okhttp/3.10.0"}

# 拿官方链接
query = "user=0&corp=kuwo&source=kwplayer_ar_5.1.0.0_B_jiakong_vh.apk&p2p=1&type=convert_url2&sig=0&format=mp3&rid=228908"
q = encryptquery(query)
r = requests.get(f"https://mobi.kuwo.cn/mobi.s?f=kuwo&q={q}", headers=hdr_mobi, timeout=15)
url = [l.split("=", 1)[1] for l in r.text.splitlines() if l.startswith("url=")][0]

print("=== HEAD 探测（模拟 v1.5.1 probeUrl，OkHttp 默认 okhttp/4.x UA）===")
for ua in ["okhttp/4.12.0", "okhttp/3.10.0"]:
    try:
        h = requests.head(url, headers={"User-Agent": ua}, timeout=8)
        print(f"[HEAD {ua}] HTTP {h.status_code} Content-Type={h.headers.get('Content-Type')} Length={h.headers.get('Content-Length')}")
    except Exception as e:
        print(f"[HEAD {ua}] 异常: {str(e)[:80]}")

print()
print("=== 歌词接口（newlyric.kuwo.cn）XOR base64 参数 ===")
KEY = b"yeelion"
params = b"user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_228908&lrcx=1"
xored = bytes(params[i] ^ KEY[i % len(KEY)] for i in range(len(params)))
p64 = base64.b64encode(xored).decode()
print(f"歌词参数 base64: {p64}")
print(f"含'+'字符: {'+' in p64}  含'/': {'/' in p64}")

def decode_lyric(buf):
    if buf[:10] != b"tp=content": return None, "前缀不是 tp=content"
    idx = buf.index(b"\r\n\r\n") + 4
    try:
        lrc = zlib.decompress(buf[idx:])
    except Exception as e:
        return None, f"zlib失败: {e}"
    b64part = lrc.decode("utf-8")
    dec = base64.b64decode(b64part)
    out = bytes(dec[i] ^ KEY[i % len(KEY)] for i in range(len(dec)))
    return out.decode("gb18030", errors="ignore"), None

for name, u in [
    ("RAW(未编码,v1.5.1的写法)", f"https://newlyric.kuwo.cn/newlyric.lrc?{p64}"),
    ("ENC(已编码)", "https://newlyric.kuwo.cn/newlyric.lrc?" + requests.utils.quote(p64, safe="")),
]:
    try:
        r = requests.get(u, timeout=10)
        lrc, err = decode_lyric(r.content)
        if lrc is None:
            print(f"[{name}] HTTP {r.status_code} 解码失败: {err} (body前60: {r.content[:60]})")
        else:
            lines = [l for l in lrc.splitlines() if l.strip()][:4]
            print(f"[{name}] HTTP {r.status_code} 成功！歌词前4行:")
            for l in lines: print(f"    {l[:80]}")
    except Exception as e:
        print(f"[{name}] 异常: {str(e)[:100]}")
