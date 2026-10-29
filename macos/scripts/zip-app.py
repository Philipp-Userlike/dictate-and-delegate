#!/usr/bin/env python3
"""Zips a .app like `ditto -c -k --keepParent`: symlinks stay symlinks (Electron's frameworks
need them, and the signature covers them), file modes are kept. Usage: zip-app.py <App.app> <out.zip>"""
import os, stat, sys, time, zipfile


def stamp(path):
    return time.localtime(max(os.lstat(path).st_mtime, 315532800))[:6]  # Zip can't go before 1980.

app, out = sys.argv[1], sys.argv[2]
parent = os.path.dirname(os.path.abspath(app))
tmp = out + '.tmp'
with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for root, dirs, files in os.walk(app, followlinks=False):
        dirs.sort()
        entries = sorted(files) + [d for d in dirs if os.path.islink(os.path.join(root, d))]
        rel_root = os.path.relpath(root, parent)
        info = zipfile.ZipInfo(rel_root + '/', stamp(root))
        info.external_attr = (stat.S_IFDIR | 0o755) << 16 | 0x10
        info.create_system = 3
        z.writestr(info, b'')
        for name in entries:
            full = os.path.join(root, name)
            rel = os.path.relpath(full, parent)
            st = os.lstat(full)
            info = zipfile.ZipInfo(rel, stamp(full))
            info.create_system = 3  # Unix, so macOS reads the modes.
            if stat.S_ISLNK(st.st_mode):
                info.external_attr = (stat.S_IFLNK | 0o755) << 16
                z.writestr(info, os.readlink(full), compress_type=zipfile.ZIP_STORED)
            else:
                info.external_attr = (stat.S_IFREG | stat.S_IMODE(st.st_mode)) << 16
                info.compress_type = zipfile.ZIP_DEFLATED
                with open(full, 'rb') as f:
                    z.writestr(info, f.read())
        dirs[:] = [d for d in dirs if not os.path.islink(os.path.join(root, d))]
os.replace(tmp, out)
