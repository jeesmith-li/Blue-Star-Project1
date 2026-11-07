"""Build a fresh-install ZIP from an assembled release, without machine state."""
import argparse
import json
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('release_dir', type=Path)
args = parser.parse_args()
root = args.release_dir.resolve(strict=True)
output = root.parent / (root.name + '.zip')
required = ['app.jar', 'start.bat', 'stop.bat', 'jre', 'python', 'python-runtime']
for name in required:
    if not (root / name).exists():
        raise SystemExit(f'Missing required release component: {name}')
# Explicit allowlist: never copy data, backups, logs, or browser state.
runtime_dirs = ['data/database', 'data/debug/reply_rpa', 'data/edge_profile',
                'data/temp', 'data/screenshots', 'data/uploads', 'backup/database', 'logs']
files = []
for name in required:
    path = root / name
    files.extend([path] if path.is_file() else sorted(p for p in path.rglob('*') if p.is_file()))
files.extend(sorted(root.glob('*.md')))
files.extend(sorted(root.glob('*.html')))
files = [p for p in files if p.relative_to(root).parts[0] != 'python' or (p.suffix != '.pyc' and not p.name.endswith('_test.py'))]
selected = set(files)
excluded = [p for p in root.rglob('*') if p.is_file() and p not in selected]
summary = {}
for p in excluded:
    rel = p.relative_to(root)
    group = '/'.join(rel.parts[:2]) if rel.parts[0] == 'data' else rel.parts[0]
    item = summary.setdefault(group, {'files': 0, 'bytes': 0})
    item['files'] += 1
    item['bytes'] += p.stat().st_size
pending = output.with_suffix('.zip.tmp')
try:
    with zipfile.ZipFile(pending, 'w', zipfile.ZIP_DEFLATED) as z:
        for directory in runtime_dirs:
            z.writestr(f'{root.name}/{directory}/', b'')
        for p in files:
            z.write(p, f'{root.name}/{p.relative_to(root).as_posix()}')
    with zipfile.ZipFile(pending) as z:
        bad_entry = z.testzip()
        if bad_entry is not None:
            raise RuntimeError(f'Corrupt ZIP entry: {bad_entry}')
        for directory in runtime_dirs:
            prefix = f'{root.name}/{directory}/'
            names = z.namelist()
            if prefix not in names or any(n.startswith(prefix) and n != prefix for n in names):
                raise RuntimeError(f'Runtime directory is missing or populated: {directory}')
    pending.replace(output)
finally:
    pending.unlink(missing_ok=True)
report = {'zip': str(output), 'zip_bytes': output.stat().st_size,
          'included_files': len(files), 'empty_directories': runtime_dirs, 'excluded': summary}
output.with_suffix('.manifest.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))

