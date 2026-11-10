#!/usr/bin/env python3
"""List the licenses of the Android app's runtime dependencies.

Usage (from the repository root):

    cd android && ./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath > /tmp/deps.txt
    python3 docs/play-research/gen_licenses.py /tmp/deps.txt > docs/play-research/licenses.md

Licenses come from each artifact's POM in the local Gradle cache, following
<parent> POMs when the artifact's own POM does not declare any. Nothing is
guessed: an artifact whose POM chain names no license is reported as unknown.
"""

import collections
import datetime
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

CACHE = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")
LINE = re.compile(r"^[\s|+\\-]*([\w.\-]+):([\w.\-]+)(?::([\w.\-]+))?(?: -> ([\w.\-]+))?(?: \((\*|c|n)\))?\s*$")


def parse_tree(path):
    """Resolved group:artifact -> (version, direct?) from a `gradle dependencies` dump."""
    deps = {}
    for raw in open(path, encoding="utf-8"):
        if not raw.startswith(("+", "\\", "|", " ")):
            continue
        body = raw.rstrip("\n")
        stripped = body.lstrip(" |")
        depth = (len(body) - len(stripped)) // 5
        m = LINE.match(body)
        if not m:
            continue
        group, artifact, version, resolved, marker = m.groups()
        if marker in ("c", "n"):  # constraints and unresolved entries are not shipped
            continue
        version = resolved or version
        if not version:
            continue
        key = f"{group}:{artifact}"
        direct = depth == 0
        old = deps.get(key)
        deps[key] = (version, direct or (old[1] if old else False))
    return deps


def find_pom(group, artifact, version):
    hits = glob.glob(os.path.join(CACHE, group, artifact, version, "*", f"{artifact}-{version}.pom"))
    return hits[0] if hits else None


def strip_ns(tree):
    for el in tree.iter():
        if "}" in el.tag:
            el.tag = el.tag.split("}", 1)[1]
    return tree


def licenses_of(group, artifact, version, depth=0):
    """[(name, url)] declared by the POM or its nearest ancestor that declares any."""
    pom = find_pom(group, artifact, version)
    if not pom or depth > 6:
        return [], "POM 不在缓存" if not pom else "父 POM 层级过深"
    try:
        root = strip_ns(ET.parse(pom)).getroot()
    except ET.ParseError:
        return [], "POM 无法解析"
    found = [
        ((lic.findtext("name") or "").strip(), (lic.findtext("url") or "").strip())
        for lic in root.findall("./licenses/license")
    ]
    if found:
        return found, "自身 POM" if depth == 0 else f"父 POM（{depth} 级）"
    parent = root.find("./parent")
    if parent is None:
        return [], "POM 未声明许可"
    return licenses_of(
        (parent.findtext("groupId") or "").strip(),
        (parent.findtext("artifactId") or "").strip(),
        (parent.findtext("version") or "").strip(),
        depth + 1,
    )


def family(name, url):
    text = f"{name} {url}".lower()
    if "apache" in text:
        return "Apache-2.0"
    if "mit" in text.split() or "opensource.org/licenses/mit" in text or name.strip().upper() == "MIT":
        return "MIT"
    if "bsd" in text:
        return "BSD"
    if "lgpl" in text or "lesser general" in text:
        return "LGPL"
    if "gpl" in text or "general public" in text:
        return "GPL"
    if "eclipse" in text or "epl" in text:
        return "EPL"
    if "mozilla" in text or "mpl" in text:
        return "MPL"
    if "android software development kit" in text or "developer.android.com/studio/terms" in text:
        return "Android SDK License"
    return name or "未知"


def main():
    deps = parse_tree(sys.argv[1])
    rows = []
    for key in sorted(deps):
        version, direct = deps[key]
        group, artifact = key.split(":", 1)
        lics, source = licenses_of(group, artifact, version)
        fam = " / ".join(sorted({family(n, u) for n, u in lics})) if lics else "未知"
        rows.append((group, artifact, version, direct, lics, fam, source))

    counts = collections.Counter(r[5] for r in rows)
    print("# Android 运行时依赖许可清单（自动生成）")
    print()
    print(f"- 生成日期：{datetime.date.today().isoformat()}")
    print("- 生成方式：`cd android && ./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath`，"
          "再用 `docs/play-research/gen_licenses.py` 读取 Gradle 缓存里每个依赖的 POM（沿 `<parent>` 查找）。")
    print(f"- 依赖树去重后的依赖数：{len(deps)}；表格行数：{len(rows)}。")
    print()
    print("## 按许可汇总")
    print()
    print("| 许可 | 数量 |")
    print("|---|---|")
    for fam, n in counts.most_common():
        print(f"| {fam} | {n} |")
    print()
    print("## 完整清单")
    print()
    print("| group | artifact | 版本 | 直接依赖 | 许可 | 来源 |")
    print("|---|---|---|---|---|---|")
    for group, artifact, version, direct, lics, fam, source in rows:
        names = "; ".join(f"[{n or u}]({u})" if u else n for n, u in lics) or "未知"
        print(f"| {group} | {artifact} | {version} | {'是' if direct else ''} | {names} | {source} |")


if __name__ == "__main__":
    main()
