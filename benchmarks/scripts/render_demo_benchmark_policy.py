from __future__ import annotations

import sys
from collections import defaultdict

from demo_benchmark_policy import BENCHMARK_FAMILIES


def main() -> int:
    families_by_category: dict[str, list] = defaultdict(list)
    for family in BENCHMARK_FAMILIES:
        families_by_category[family.category].append(family)

    total_direct = 0
    total_represented = 0
    total_skipped = 0

    for category in sorted(families_by_category):
        print(f"[{category}] {len(families_by_category[category])} families")
        for family in families_by_category[category]:
            total_direct += len(family.direct_exports)
            total_represented += len(family.represented_exports)
            total_skipped += len(family.skipped_exports)

            print(f"  {family.family_id}")
            print(f"    title: {family.title}")
            print(f"    variants: {', '.join(family.variant_axes)}")
            print(f"    direct: {len(family.direct_exports)}")
            if family.represented_exports:
                print(f"    represented: {len(family.represented_exports)}")
            if family.skipped_exports:
                print(f"    skipped: {len(family.skipped_exports)}")
        print()

    print("totals")
    print(f"  families: {len(BENCHMARK_FAMILIES)}")
    print(f"  direct exports: {total_direct}")
    print(f"  represented exports: {total_represented}")
    print(f"  skipped exports: {total_skipped}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
