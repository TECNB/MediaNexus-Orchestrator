"""Offline checks: python3 tools/test_subhd_download.py"""
import json
from pathlib import Path
import stat
import subprocess
import sys
import tempfile
import unittest
import zipfile

import subhd_download as tool

ASS = '[Script Info]\n[Events]\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,你好\n'


class DownloadChecks(unittest.TestCase):
    def test_archive_cli_and_no_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive, output = root / 'input.zip', root / 'output'
            with zipfile.ZipFile(archive, 'w') as package:
                package.writestr('简中/作品 第01話.chs.ass', ASS)
                package.writestr('繁中/作品 第01話.cht.ass', ASS)
                package.writestr('fonts.txt', 'not a subtitle')
                package.writestr('video.torrent', 'not a subtitle')
            command = [sys.executable, str(Path(tool.__file__)), '--archive', str(archive),
                       '-o', str(output), '--language', 'chs']
            first = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            report = json.loads((output / 'manifest.json').read_text())
            self.assertEqual(len(report['files']), 1)
            self.assertEqual(report['files'][0]['episode'], 1)
            self.assertEqual(report['files'][0]['language'], 'chs')
            self.assertEqual(len(report['skipped']), 3)
            self.assertEqual(len(list((output / 'subtitles').rglob('*.ass'))), 1)
            self.assertEqual((output / 'source.zip').read_bytes(), archive.read_bytes())
            before = (output / 'manifest.json').read_bytes()
            second = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(second.returncode, 1)
            self.assertEqual((output / 'manifest.json').read_bytes(), before)

    def test_unsafe_archive_paths_and_symlinks(self):
        for name in ('../escape.ass', '/absolute.ass', 'C:\\escape.ass', 'a/../../x.ass'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                tool.zip_name(zipfile.ZipInfo(name))
        link = zipfile.ZipInfo('link.ass')
        link.external_attr = (stat.S_IFLNK | 0o777) << 16
        with self.assertRaises(ValueError):
            tool.zip_name(link)
        legacy = zipfile.ZipInfo('简中/第01話.chs.ass'.encode('gb18030').decode('cp437'))
        self.assertEqual(tool.zip_name(legacy), '简中/第01話.chs.ass')

    def test_invalid_files_are_not_published(self):
        for filename, content in [('bad.ass', '<html>login</html>'), ('empty.srt', ''),
                                  ('broken.ass', '[Events]\nDialogue: garbage\n'),
                                  ('backwards.ass', ASS.replace('0:00:02.00', '0:00:00.00'))]:
            with self.subTest(filename=filename), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                archive = root / 'bad.zip'
                with zipfile.ZipFile(archive, 'w') as package:
                    package.writestr(filename, content)
                result = subprocess.run([sys.executable, tool.__file__, '--archive', str(archive),
                                         '-o', str(root / 'output')], capture_output=True)
                self.assertEqual(result.returncode, 1)
                self.assertFalse((root / 'output').exists())
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            archive = root / 'bomb.zip'
            with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as package:
                package.writestr('oversized.ass', b'x' * (tool.MAX_FILE + 1))
            with self.assertRaises(ValueError):
                tool.organize(archive, root / 'stage', 'all')

    def test_url_and_subtitle_metadata(self):
        self.assertEqual(tool.source_url('gbjp7H'), 'https://subhd.tv/a/gbjp7H')
        self.assertEqual(tool.source_url('https://subhd.me/down/gbjp7H'), 'https://subhd.me/a/gbjp7H')
        for value in ('https://subhd.tv.evil/a/gbjp7H', 'file:///tmp/test',
                      'https://subhd.tv/a/gbjp7H?secret=1'):
            with self.subTest(value=value), self.assertRaises(ValueError):
                tool.source_url(value)
        entry = tool.inspect_subtitle('简体繁体合集/01.cht.ass', ASS.encode())
        self.assertEqual(entry['language'], 'cht')
        entry = tool.inspect_subtitle('简体繁体合集/01.ass', ASS.encode())
        self.assertEqual(entry['language'], 'unknown')
        entry = tool.inspect_subtitle('Series.S02E03.zh-TW.ass', ASS.encode('utf-16'))
        self.assertEqual((entry['season'], entry['episode'], entry['language']), (2, 3, 'cht'))
        entry = tool.inspect_subtitle('[12].chs.srt', '1\n00:00:01,000 --> 00:00:02,000\n你好\n'.encode())
        self.assertEqual((entry['episode'], entry['events']), (12, 1))


if __name__ == '__main__':
    unittest.main()
