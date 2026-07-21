#!/usr/bin/env python3
import sys
import re

def parse_time(time_str):
    time_str = time_str.strip()
    match = re.match(r'([\d.]+)\s*(ns|us|ms|s)', time_str)
    if not match:
        return None, None
    value = float(match.group(1))
    unit = match.group(2)
    if unit == 'ns':
        ns = value
    elif unit == 'us':
        ns = value * 1000
    elif unit == 'ms':
        ns = value * 1_000_000
    elif unit == 's':
        ns = value * 1_000_000_000
    return ns, format_time(ns)

def format_time(ns):
    if ns == 0:
        return "<1 ns"
    elif ns < 1000:
        return f"{ns:.0f} ns"
    elif ns < 1_000_000:
        return f"{ns/1000:.1f} μs"
    elif ns < 1_000_000_000:
        return f"{ns/1_000_000:.2f} ms"
    else:
        return f"{ns/1_000_000_000:.2f} s"

def main():
    results = {}
    
    for line in sys.stdin:
        line = line.strip()
        if not line or line.startswith('name') or line.startswith('-'):
            continue
        if line.startswith('==='):
            continue
            
        parts = re.split(r'\s{2,}', line)
        if len(parts) < 2:
            continue
            
        name = parts[0]
        time_str = parts[1]
        
        ns, _ = parse_time(time_str)
        if ns is None:
            continue
            
        if name.startswith('boltffi_'):
            bench = name[8:]
            if bench not in results:
                results[bench] = {}
            results[bench]['boltffi'] = ns
        elif name.startswith('uniffi_'):
            bench = name[7:]
            if bench not in results:
                results[bench] = {}
            results[bench]['uniffi'] = ns

    print()
    print(f"{'Benchmark':<30} {'BoltFFI':>12} {'UniFFI':>12} {'Speedup':>12}")
    print("-" * 68)
    
    for bench in results:
        data = results[bench]
        bolt = data.get('boltffi')
        uni = data.get('uniffi')
        
        bolt_str = format_time(bolt) if bolt is not None else "N/A"
        uni_str = format_time(uni) if uni is not None else "N/A"
        
        if bolt is not None and uni is not None:
            if bolt == 0 and uni > 0:
                speedup = "∞"
            elif bolt > 0:
                ratio = uni / bolt
                if ratio >= 1:
                    speedup = f"{ratio:.0f}x"
                else:
                    speedup = f"{1/ratio:.1f}x slower"
            else:
                speedup = ""
        else:
            speedup = ""
        
        print(f"{bench:<30} {bolt_str:>12} {uni_str:>12} {speedup:>12}")

if __name__ == '__main__':
    main()
