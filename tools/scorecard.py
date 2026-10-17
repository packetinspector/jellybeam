"""Size, coordination and new-mechanism counts between two source snapshots (tools/scorecard.sh)."""
import os
import re
import sys
from collections import Counter


def strip_comments(lines):
    out, block = [], False
    for line in lines:
        s = line.strip()
        if block:
            if '*/' in s:
                block = False
            continue
        if s.startswith('/*'):
            block = '*/' not in s
            continue
        if not s or s.startswith('//') or s.startswith('*'):
            continue
        out.append(line)
    return out


def split_rust(lines):
    """Production lines vs inline #[cfg(test)] module lines (brace-matched)."""
    prod, test, i = [], [], 0
    while i < len(lines):
        if (lines[i].strip().startswith('#[cfg(test)]') and i + 1 < len(lines)
                and re.match(r'\s*(pub(\(crate\))?\s+)?mod\s+\w+\s*\{', lines[i + 1])):
            depth, j = 0, i + 1
            while j < len(lines):
                depth += lines[j].count('{') - lines[j].count('}')
                test.append(lines[j])
                j += 1
                if depth <= 0:
                    break
            i = j
            continue
        prod.append(lines[i])
        i += 1
    return prod, test


COORD = {
    'kt': {
        'state_holders': r'MutableStateFlow\(|mutableStateOf\(|MutableSharedFlow\(|AtomicBoolean|AtomicInteger|AtomicLong|AtomicReference|@Volatile|\bMutex\(\)',
        'launch_sites': r'\.launch\b|\blaunch\s*\{|\basync\s*\{|LaunchedEffect\(|DisposableEffect\(',
        'gen_epoch_token': r'\b(generation|epoch|token|revision|seq)\w*\b',
        'owner_types': r'\b(class|object|interface)\s+\w*(Owner|Gate|Token|Seat|Funnel|Coordinator|Scheduler|Guard|Registry|Scope)\b',
    },
    'rs': {
        'state_holders': r'\bMutex<|\bRwLock<|\bAtomic\w+\b|watch::channel|broadcast::channel|mpsc::|Notify\b',
        'launch_sites': r'tokio::spawn|\.spawn\(|spawn_blocking|thread::spawn',
        'gen_epoch_token': r'\b(generation|epoch|token|revision|seq)\w*\b',
        'owner_types': r'\b(struct|enum|trait)\s+\w*(Owner|Gate|Token|Seat|Funnel|Coordinator|Scheduler|Guard|Registry|Scope|Claim|Slot)\b',
        'ffi_exports': r'#\[uniffi::export',
    },
}
TYPE = {'kt': r'^\s*(?:[a-z]+\s+)*(?:class|object|interface)\s+([A-Z]\w*)',
        'rs': r'^\s*(?:pub(?:\([\w:]+\))?\s+)?(?:struct|enum|trait)\s+([A-Z]\w*)'}


def ffi_items(lines):
    """Names under #[uniffi::export]: free fns, and `Type::method` for exported impls and traits."""
    names, i = set(), 0
    while i < len(lines):
        if lines[i].strip().startswith('#[uniffi::export'):
            j = i + 1
            while j < len(lines) and lines[j].strip().startswith('#['):
                j += 1
            if j >= len(lines):
                break
            m = (re.match(r'\s*impl(?:<[^>]*>)?\s+(?:\w+\s+for\s+)?(\w+)', lines[j])
                 or re.match(r'\s*(?:pub(?:\([\w:]+\))?\s+)?trait\s+(\w+)', lines[j]))
            if m:
                depth, k = 0, j
                while k < len(lines):
                    f = re.match(r'\s*(?:pub\s+)?(?:async\s+)?fn\s+(\w+)', lines[k])
                    if f and depth == 1:
                        names.add(f'{m.group(1)}::{f.group(1)}')
                    depth += lines[k].count('{') - lines[k].count('}')
                    k += 1
                    if depth <= 0 and k > j + 1:
                        break
            else:
                f = re.search(r'\b(?:fn|trait)\s+(\w+)', lines[j])
                if f:
                    names.add(f.group(1))
        i += 1
    return names


def settings_fields(lines, lang):
    """`Owner.field` for every struct/data class whose name ends in Settings."""
    out, owner, depth = set(), None, 0
    for line in lines:
        m = re.search(r'(?:struct|data class)\s+(\w*Settings)\b', line)
        if m and owner is None:
            owner, depth = m.group(1), 0
        if owner:
            pat = r'^\s*(?:pub(?:\([\w:]+\))?\s+)?(\w+)\s*:' if lang == 'rs' else r'\b(?:val|var)\s+(\w+)\s*:'
            if lang == 'kt' or depth == 1:
                f = re.search(pat, line)
                if f:
                    out.add(f'{owner}.{f.group(1)}')
            depth += line.count('{') - line.count('}') + (line.count('(') - line.count(')') if lang == 'kt' else 0)
            if depth <= 0 and ('}' in line or ')' in line):
                owner = None
    return out


def scan(root):
    size = {'kt_prod': 0, 'kt_test': 0, 'rs_prod': 0, 'rs_test': 0}
    coord = {f'{l}.{k}': 0 for l in COORD for k in COORD[l]}
    types, ffi, settings, files = Counter(), set(), set(), {}
    for dp, _, fns in os.walk(root):
        if '/target' in dp or '/build/' in dp or '/generated' in dp:
            continue
        for fn in fns:
            lang = 'kt' if fn.endswith('.kt') else 'rs' if fn.endswith('.rs') else None
            if not lang:
                continue
            p = os.path.join(dp, fn)
            rel = os.path.relpath(p, root)
            with open(p, errors='ignore') as fh:
                lines = strip_comments(fh.read().split('\n'))
            if lang == 'kt':
                if '/src/test/' in rel or '/src/androidTest/' in rel:
                    size['kt_test'] += len(lines)
                    continue
                prod = lines
            else:
                if '/tests/' in rel or '/benches/' in rel or fn.endswith('_tests.rs') or fn == 'tests.rs':
                    size['rs_test'] += len(lines)
                    continue
                prod, test = split_rust(lines)
                size['rs_test'] += len(test)
            size[f'{lang}_prod'] += len(prod)
            text = '\n'.join(prod)
            for k, v in COORD[lang].items():
                coord[f'{lang}.{k}'] += len(re.findall(v, text))
            # Counted per crate (Rust) or module (Kotlin): a moved type is not new, a reused name is.
            module = '/'.join(rel.split('/')[:2])
            types.update(f'{m} ({module})' for m in re.findall(TYPE[lang], text, re.M))
            if lang == 'rs':
                ffi |= ffi_items(prod)
            settings |= settings_fields(prod, lang)
            files[rel] = len(prod)
    return size, coord, types, ffi, settings, files


def main():
    base_dir, head_dir, base, head, md = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5] == 'md'
    a, b = scan(base_dir), scan(head_dir)
    row = (lambda *c: '| ' + ' | '.join(str(x) for x in c) + ' |') if md else (lambda *c: '  '.join(f'{x!s:<28}' for x in c))
    title = (lambda t: f'\n### {t}\n') if md else (lambda t: f'\n== {t}')
    print(title(f'Lines and coordination {base} -> {head}'))
    print(row('metric', base, head, 'Δ'))
    if md:
        print('|---|---|---|---|')
    for d in (0, 1):
        for k in a[d]:
            print(row(k, a[d][k], b[d][k], f'{b[d][k] - a[d][k]:+d}'))
    print(title('New mechanisms (each needs a design row or a reason)'))
    for label, d in (('types', 2), ('FFI exports', 3), ('settings fields', 4)):
        new = sorted((b[d] - a[d]).elements()) if d == 2 else sorted(b[d] - a[d])
        print(f'- new {label} ({len(new)}): ' + (', '.join(new) if new else 'none'))
    new_files = sorted(set(b[5]) - set(a[5]))
    gone = sorted(set(a[5]) - set(b[5]))
    print(f'- new files ({len(new_files)}): ' + (', '.join(new_files) if new_files else 'none'))
    print(f'- deleted files ({len(gone)}): ' + (', '.join(gone) if gone else 'none'))
    removed = sorted((a[2] - b[2]).elements())
    print(f'- removed types ({len(removed)}): ' + (', '.join(removed) if removed else 'none'))
    print(title('Biggest production growth'))
    grow = sorted(((b[5].get(f, 0) - a[5].get(f, 0), f) for f in set(a[5]) | set(b[5])), reverse=True)
    for delta, f in [g for g in grow if g[0] > 0][:10]:
        print(f'- {delta:+d}  {a[5].get(f, 0)} -> {b[5].get(f, 0)}  `{f}`' if md else f'  {delta:+5d}  {f}')


main()
