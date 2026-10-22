#!/usr/bin/env python3
"""Fail if a translation has drifted from the default language.

A missing key does not crash anything — it silently shows the English string in
the middle of a Hindi screen, which is the kind of thing nobody notices until a
user does. So it fails the build instead.

Run from the repository root:  python3 tools/check_translations.py
"""
import pathlib
import re
import sys

RES = pathlib.Path("app/src/main/res")
DEFAULT = RES / "values" / "strings.xml"

NAME = re.compile(r'<string name="([A-Za-z0-9_]+)"')


def keys(path: pathlib.Path) -> list[str]:
    """A list, not a set — a duplicated key is a resource-merge failure, and a
    set would hide exactly that."""
    return NAME.findall(path.read_text(encoding="utf-8"))


def duplicates(names: list[str]) -> list[str]:
    return sorted({n for n in names if names.count(n) > 1})


def main() -> int:
    if not DEFAULT.exists():
        print(f"FAIL: {DEFAULT} is missing")
        return 1

    expected_list = keys(DEFAULT)
    problems = 0

    dupes = duplicates(expected_list)
    if dupes:
        print(f"FAIL default: duplicated key(s): {dupes}")
        problems += 1

    expected = set(expected_list)

    folders = sorted(p for p in RES.glob("values-*") if p.is_dir())
    if not folders:
        print("note: no translations to check")
    for folder in folders:
        path = folder / "strings.xml"
        if not path.exists():
            print(f"FAIL {folder.name}: no strings.xml")
            problems += 1
            continue

        found_list = keys(path)
        found = set(found_list)

        dupes = duplicates(found_list)
        if dupes:
            print(f"FAIL {folder.name}: duplicated key(s): {dupes}")
            problems += 1

        missing = sorted(expected - found)
        extra = sorted(found - expected)
        if missing:
            print(f"FAIL {folder.name}: missing {len(missing)} key(s): {missing[:6]}")
            problems += 1
        if extra:
            print(f"FAIL {folder.name}: {len(extra)} key(s) not in the default: {extra[:6]}")
            problems += 1
        if not missing and not extra and not dupes:
            print(f"OK   {folder.name}: {len(found)} keys match the default")

    print(f"{len(expected)} keys in the default language")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
