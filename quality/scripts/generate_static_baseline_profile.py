#!/usr/bin/env python3
import argparse
import fnmatch
import struct
import zipfile
from collections import Counter
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class BaselineRule:
    display_name: str
    flags: str
    pattern: str
    package_prefix: str

    @property
    def class_flags(self) -> str:
        return "L" if "L" in self.flags else ""

    @property
    def method_flags(self) -> str:
        return self.flags

    def matches(self, internal_class_name: str) -> bool:
        return fnmatch.fnmatchcase(internal_class_name, self.pattern)


@dataclass(frozen=True)
class MethodEntry:
    name: str
    descriptor: str


@dataclass(frozen=True)
class ClassEntry:
    internal_name: str
    methods: tuple[MethodEntry, ...]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate Lomo static baseline profile from Kotlin Toolchain jars.")
    parser.add_argument("--build-dir", required=True, type=Path, help="Kotlin Toolchain build directory to scan.")
    parser.add_argument("--rules-file", default=Path("app/baseline-rules.txt"), type=Path)
    parser.add_argument("--output", default=Path("app/src/main/baselineProfiles/generated.txt"), type=Path)
    parser.add_argument(
        "--report",
        default=Path("build/reports/ai/static-baseline-profile/static-baseline-profile-report.txt"),
        type=Path,
    )
    parser.add_argument("--max-total-entries", type=int)
    return parser.parse_args()


def parse_rules(path: Path) -> list[BaselineRule]:
    rules: list[BaselineRule] = []
    for line_number, raw_line in enumerate(path.read_text().splitlines(), start=1):
        line = raw_line.split("#", maxsplit=1)[0].strip()
        if not line:
            continue
        parts = line.split(maxsplit=1)
        if len(parts) != 2:
            raise ValueError(f"Invalid baseline rule at {path}:{line_number}: {raw_line}")
        flags, pattern = parts
        if any(flag not in "HSPL" for flag in flags):
            raise ValueError(f"Invalid baseline flags at {path}:{line_number}: {flags}")
        rules.append(
            BaselineRule(
                display_name=line,
                flags=flags,
                pattern=pattern,
                package_prefix=package_prefix_for(pattern),
            ),
        )
    if not rules:
        raise ValueError(f"Baseline rules file is empty: {path}")
    return rules


def package_prefix_for(pattern: str) -> str:
    wildcard_positions = [index for index in (pattern.find("*"), pattern.find("?")) if index >= 0]
    if wildcard_positions:
        static_prefix = pattern[: min(wildcard_positions)].rstrip("/")
    else:
        static_prefix = pattern.rsplit("/", maxsplit=1)[0] if "/" in pattern else ""
    return static_prefix or "(default)"


def candidate_jars(build_dir: Path) -> list[Path]:
    if not build_dir.exists():
        raise FileNotFoundError(f"Toolchain build directory does not exist: {build_dir}")
    jars = sorted(path for path in build_dir.rglob("*.jar") if path.is_file())
    if not jars:
        raise FileNotFoundError(f"No jar files found under Toolchain build directory: {build_dir}")
    return jars


def read_u1(data: bytes, offset: int) -> tuple[int, int]:
    return data[offset], offset + 1


def read_u2(data: bytes, offset: int) -> tuple[int, int]:
    return struct.unpack_from(">H", data, offset)[0], offset + 2


def read_u4(data: bytes, offset: int) -> tuple[int, int]:
    return struct.unpack_from(">I", data, offset)[0], offset + 4


def skip_attributes(data: bytes, offset: int) -> int:
    attribute_count, offset = read_u2(data, offset)
    for _ in range(attribute_count):
        _, offset = read_u2(data, offset)
        attribute_length, offset = read_u4(data, offset)
        offset += attribute_length
    return offset


def decode_modified_utf8(data: bytes) -> str:
    code_units: list[int] = []
    offset = 0
    while offset < len(data):
        byte = data[offset]
        if byte <= 0x7F:
            code_units.append(byte)
            offset += 1
        elif (byte & 0xE0) == 0xC0:
            if offset + 1 >= len(data):
                raise UnicodeDecodeError("modified-utf8", data, offset, offset + 1, "truncated two-byte sequence")
            second = data[offset + 1]
            code_units.append(((byte & 0x1F) << 6) | (second & 0x3F))
            offset += 2
        elif (byte & 0xF0) == 0xE0:
            if offset + 2 >= len(data):
                raise UnicodeDecodeError("modified-utf8", data, offset, offset + 1, "truncated three-byte sequence")
            second = data[offset + 1]
            third = data[offset + 2]
            code_units.append(((byte & 0x0F) << 12) | ((second & 0x3F) << 6) | (third & 0x3F))
            offset += 3
        else:
            raise UnicodeDecodeError("modified-utf8", data, offset, offset + 1, "unsupported byte")

    chars: list[str] = []
    index = 0
    while index < len(code_units):
        unit = code_units[index]
        if 0xD800 <= unit <= 0xDBFF and index + 1 < len(code_units):
            next_unit = code_units[index + 1]
            if 0xDC00 <= next_unit <= 0xDFFF:
                chars.append(chr(0x10000 + ((unit - 0xD800) << 10) + (next_unit - 0xDC00)))
                index += 2
                continue
        chars.append(chr(unit))
        index += 1
    return "".join(chars)


def parse_class(data: bytes) -> ClassEntry:
    magic, offset = read_u4(data, 0)
    if magic != 0xCAFEBABE:
        raise ValueError("Not a Java class file")
    offset += 4
    constant_pool_count, offset = read_u2(data, offset)
    utf8: dict[int, str] = {}
    class_name_index: dict[int, int] = {}
    index = 1
    while index < constant_pool_count:
        tag, offset = read_u1(data, offset)
        if tag == 1:
            length, offset = read_u2(data, offset)
            utf8[index] = decode_modified_utf8(data[offset : offset + length])
            offset += length
        elif tag in (3, 4):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        elif tag == 7:
            name_index, offset = read_u2(data, offset)
            class_name_index[index] = name_index
        elif tag == 8:
            offset += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            offset += 4
        elif tag == 15:
            offset += 3
        elif tag == 16:
            offset += 2
        elif tag in (19, 20):
            offset += 2
        else:
            raise ValueError(f"Unsupported constant pool tag: {tag}")
        index += 1

    _, offset = read_u2(data, offset)
    this_class, offset = read_u2(data, offset)
    _, offset = read_u2(data, offset)
    internal_name = utf8[class_name_index[this_class]]

    interfaces_count, offset = read_u2(data, offset)
    offset += interfaces_count * 2

    fields_count, offset = read_u2(data, offset)
    for _ in range(fields_count):
        offset += 6
        offset = skip_attributes(data, offset)

    methods: list[MethodEntry] = []
    methods_count, offset = read_u2(data, offset)
    for _ in range(methods_count):
        offset += 2
        name_index, offset = read_u2(data, offset)
        descriptor_index, offset = read_u2(data, offset)
        methods.append(MethodEntry(name=utf8[name_index], descriptor=utf8[descriptor_index]))
        offset = skip_attributes(data, offset)

    return ClassEntry(internal_name=internal_name, methods=tuple(methods))


def iter_classes(jars: list[Path]) -> tuple[list[ClassEntry], Counter[str]]:
    classes: list[ClassEntry] = []
    jar_class_counts: Counter[str] = Counter()
    for jar in jars:
        try:
            with zipfile.ZipFile(jar) as archive:
                class_names = [name for name in archive.namelist() if name.endswith(".class") and not name.endswith("module-info.class")]
                for class_name in class_names:
                    classes.append(parse_class(archive.read(class_name)))
                if class_names:
                    jar_class_counts[str(jar)] = len(class_names)
        except zipfile.BadZipFile as error:
            raise ValueError(f"Invalid jar file: {jar}") from error
    return classes, jar_class_counts


def generate_entries(
    classes: list[ClassEntry],
    rules: list[BaselineRule],
) -> tuple[dict[str, BaselineRule], set[str]]:
    entries: dict[str, BaselineRule] = {}
    matched_rules: set[str] = set()
    for class_entry in classes:
        rule = next((candidate for candidate in rules if candidate.matches(class_entry.internal_name)), None)
        if rule is None:
            continue
        matched_rules.add(rule.display_name)
        if rule.class_flags:
            entries.setdefault(f"{rule.class_flags}{class_entry.internal_name};", rule)
        for method in class_entry.methods:
            entries.setdefault(f"{rule.method_flags}{class_entry.internal_name};->{method.name}{method.descriptor}", rule)
    if not entries:
        raise ValueError("Baseline profile generation matched no classes. Check --build-dir and app/baseline-rules.txt.")
    return dict(sorted(entries.items())), matched_rules


def write_profile(path: Path, entries: dict[str, BaselineRule]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("# Generated by static baseline profile task.\n" + "\n".join(entries.keys()) + "\n")


def write_report(
    path: Path,
    entries: dict[str, BaselineRule],
    rules: list[BaselineRule],
    matched_rules: set[str],
    jar_class_counts: Counter[str],
    max_total_entries: int | None,
) -> None:
    package_counts = Counter(rule.package_prefix for rule in entries.values())
    rule_counts = Counter(rule.display_name for rule in entries.values())
    budget_status = "PASS" if max_total_entries is None or len(entries) <= max_total_entries else "FAIL"

    lines = [
        "# Generated by static baseline profile task.",
        f"totalEntries={len(entries)}",
        f"configuredRuleGlobs={len(rules)}",
        f"matchedRuleGlobs={len(matched_rules)}",
        f"maxTotalEntries={max_total_entries if max_total_entries is not None else 'unbounded'}",
        f"budgetStatus={budget_status}",
        "",
        "[entriesByPackagePrefix]",
    ]
    lines.extend(f"{name}={count}" for name, count in sorted(package_counts.items()))
    lines.extend(["", "[entriesByBaselineRuleGlob]"])
    lines.extend(f"{name}={count}" for name, count in sorted(rule_counts.items()))
    lines.extend(["", "[scannedJarsWithClasses]"])
    lines.extend(f"{name}={count}" for name, count in sorted(jar_class_counts.items()))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n")

    if budget_status != "PASS":
        raise ValueError(f"Static baseline profile entry budget exceeded: actual={len(entries)}, max={max_total_entries}")


def main() -> None:
    args = parse_args()
    if args.max_total_entries is not None and args.max_total_entries < 0:
        raise ValueError("--max-total-entries must be non-negative")
    rules = parse_rules(args.rules_file)
    jars = candidate_jars(args.build_dir)
    classes, jar_class_counts = iter_classes(jars)
    entries, matched_rules = generate_entries(classes, rules)
    write_profile(args.output, entries)
    write_report(args.report, entries, rules, matched_rules, jar_class_counts, args.max_total_entries)
    print(f"generated {len(entries)} baseline profile entries")
    print(f"profile: {args.output}")
    print(f"report: {args.report}")


if __name__ == "__main__":
    main()
