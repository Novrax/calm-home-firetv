"""Builds aerial-gallery.html from Apple's tvOS Aerial manifest + the scenes already in AerialView.java.

Usage: python -I gen_gallery.py <manifest_dir> <AerialView.java> <out.html> <template.html>
"""
import json, os, plistlib, re, sys

mdir, java_path, out_path, tpl_path = sys.argv[1:5]
d = json.load(open(os.path.join(mdir, 'entries.json'), encoding='utf-8'))
strings = plistlib.load(open(os.path.join(mdir, 'TVIdleScreenStrings.bundle/en.lproj/Localizable.nocache.strings'), 'rb'))
cats = {c['id']: strings.get(c['localizedNameKey'], '?') for c in d['categories']}

java = open(java_path, encoding='utf-8').read()
tv = []
for m in re.finditer(r'new Scene\("([^"]+)",\s*"([^"]+)",\s*"([^"]+)",\s*"[^"]*",\s*"([^"]+)",\s*(null|"[^"]+")', java):
    sid, title, sub, desc, url = m.groups()
    tv.append(dict(id=sid, title=title, sub=sub, desc=desc, url=None if url == 'null' else url.strip('"')))

def fname(u):
    return u.rsplit('/', 1)[-1] if u else None

tv_by_file = {fname(s['url']): s for s in tv if s['url']}
scenes, matched = [], set()
for a in d['assets']:
    sdr = a['url-1080-SDR']
    poi = a.get('pointsOfInterest', {})
    first = poi.get('0') or (list(poi.values())[0] if poi else None)
    caption = strings.get(first, '').replace('\xa0', ' ').replace('\n', ' ') if first else ''
    on = tv_by_file.get(fname(sdr))
    if on: matched.add(on['id'])
    scenes.append(dict(
        label=a['accessibilityLabel'],
        cat=cats.get(a['categories'][0], 'Other'),
        caption=caption,
        shot=a['shotID'],
        thumb='http://sylvan.apple.com/Aerials/jjGqDwCNrOsVJ4of6uSVr/snapshots/%s.jpg' % a['shotID'],
        preview=a['url-1080-H264'].replace('https://', 'http://'),
        tv=sdr.replace('https://', 'http://'),
        onTv=on['title'] if on else None,
    ))

# TV scenes that aren't in this manifest (older Apple catalogue / Glow).
for s in tv:
    if s['id'] in matched: continue
    scenes.append(dict(label=s['title'].split(' — ')[0], cat='Landscape' if s['url'] else 'Ambient',
                          caption=s['desc'], shot=s['id'], thumb=None,
                          preview=s['url'],
                          tv=s['url'], onTv=s['title']))

# On-TV scenes first in the TV's own order, then the rest grouped by category.
tv_order = {s['title']: i for i, s in enumerate(tv)}
cat_order = ['Underwater', 'Landscape', 'Cityscape', 'Earth', 'Ambient', 'Other']
scenes.sort(key=lambda s: (0, tv_order[s['onTv']]) if s['onTv'] else (1, cat_order.index(s['cat'])))

html = open(tpl_path, encoding='utf-8').read().replace('/*DATA*/[]', json.dumps(scenes, ensure_ascii=False))
open(out_path, 'w', encoding='utf-8').write(html)
print(len(scenes), 'scenes;', sum(1 for s in scenes if s['onTv']), 'on TV;', 'unmatched TV:', [s['id'] for s in tv if s['id'] not in matched])
