#!/usr/bin/env python3
"""验证 Kotlin DES 与 Python DES 输出一致性"""
import base64, sys, subprocess, os

# Python 版加密
sys.path.insert(0, "/workspace/audit")
from kuwo_audit import encryptquery

query = "user=0&corp=kuwo&source=kwplayer_ar_5.1.0.0_B_jiakong_vh.apk&p2p=1&type=convert_url2&sig=0&format=mp3&rid=228908"
py_result = encryptquery(query)
print(f"Python base64 ({len(py_result)} chars):")
print(f"  {py_result}")
print(f"  含+{'+' in py_result} 含/{'/' in py_result} 含={'=' in py_result}")
