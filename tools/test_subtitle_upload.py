"""Run with: PYTHONDONTWRITEBYTECODE=1 python3 tools/test_subtitle_upload.py"""
import contextlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
import zipfile

import subtitle_upload as tool


class UploadChecks(unittest.TestCase):
    def test_http_preview_commit_check_and_extra_episode(self):
        calls = []
        files = [{'id': 'f0', 'original_path': '00.ass', 'language': 'chs', 'status': 'UNMATCHED',
                  'video_name': None, 'final_name': None, 'warnings': ['独立前作，不自动匹配']},
                 {'id': 'f1', 'original_path': '01.srt', 'language': 'chs', 'status': 'READY',
                  'video_name': '银河 S01E01.mkv', 'final_name': '银河 S01E01.zh-CN.srt', 'warnings': []}]

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def send(self, data):
                encoded = json.dumps({'code': 200, 'data': data}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

            def do_GET(self):
                calls.append(('GET', self.path, self.headers.get('Authorization')))
                if self.path.startswith('/api/v1/subtitles/locations?'):
                    self.send({'items': [{'target_id': 'account-1', 'target_name': '库'}]})
                elif self.path == '/api/v1/subtitles/uploads/upload-1':
                    self.send({'id': 'upload-1', 'status': 'WAITING_FOR_AS', 'stage': 'waiting_for_as'})
                elif self.path == '/api/v1/subtitles/uploads/upload-1/check':
                    self.send({'status': 'COMPLETE',
                               'source': {'status': 'COMPLETE', 'files': [], 'message': '源目录已齐'},
                               'output': {'status': 'UNAVAILABLE', 'files': [], 'message': '输出目录未挂载'}})
                else:
                    self.send_error(404)

            def do_POST(self):
                body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
                calls.append(('POST', self.path, self.headers.get('Authorization')))
                if self.path == '/api/v1/subtitles/preparations':
                    self.server.test.assertIn(b'filename="input.zip"', body)
                    self.server.test.assertIn('银河'.encode(), body)
                    self.server.test.assertIn(b'name="target_id"\r\n\r\naccount-1', body)
                    self.send({'id': 'preview-1', 'expires_at': '2099-01-01T00:00:00Z',
                               'target_path': '/Media/银河/Season 01', 'files': files,
                               'warnings': [], 'skipped_entries': []})
                elif self.path == '/api/v1/subtitles/preparations/preview-1/commit':
                    self.server.test.assertEqual(json.loads(body), {'files': [{'id': 'f1'}], 'overwrite': False})
                    self.send({'id': 'upload-1', 'status': 'PROCESSING'})
                else:
                    self.send_error(404)

        with tempfile.TemporaryDirectory() as temporary:
            archive = Path(temporary) / 'input.zip'
            with zipfile.ZipFile(archive, 'w') as package:
                package.writestr('01.srt', '1\n00:00:01,000 --> 00:00:02,000\n你好')
            server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
            server.test = self
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with patch.dict(os.environ, {'MEDIANEXUS_TOKEN': 'test-token'}), contextlib.redirect_stdout(io.StringIO()):
                    code = tool.main(['upload', '--archive', str(archive), '--media-type', 'series',
                                      '--title', '银河', '--season', '1', '--language', 'chs', '--yes',
                                      '--backend', 'http://127.0.0.1:' + str(server.server_port)])
                self.assertEqual(code, 0)
                self.assertEqual([item[:2] for item in calls][1:3],
                                 [('POST', '/api/v1/subtitles/preparations'),
                                  ('POST', '/api/v1/subtitles/preparations/preview-1/commit')])
                self.assertTrue(all(item[2] == 'Bearer test-token' for item in calls))
            finally:
                server.shutdown()
                server.server_close()
                thread.join()

    def test_explicit_selection_never_commits_conflict(self):
        preparation = {'files': [{'id': 'f1', 'status': 'CONFLICT', 'language': 'chs',
                                 'original_path': '01.srt', 'warnings': []}]}
        with contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(ValueError):
                tool.preview_selections(preparation, 'f1', 'all')
            with self.assertRaises(ValueError):
                tool.preview_selections(preparation, 'missing-id', 'all')
            with self.assertRaises(ValueError):
                tool.preview_selections(preparation, 'f1,f1', 'all')
            ambiguous = {'files': [{'id': 'f2', 'status': 'AMBIGUOUS', 'language': 'chs',
                                    'original_path': 'movie.srt', 'warnings': []}]}
            self.assertEqual(tool.preview_selections(ambiguous, 'f2', 'chs', 'movie.mkv'),
                             [{'id': 'f2', 'video_name': 'movie.mkv'}])
            unmatched = {'files': [{'id': 'zero', 'status': 'UNMATCHED', 'language': 'chs',
                                    'original_path': '00.srt', 'warnings': []}]}
            self.assertEqual(tool.preview_selections(unmatched, 'zero', 'chs', 'special.mkv'),
                             [{'id': 'zero', 'video_name': 'special.mkv'}])
            duplicate = {'files': [{'id': name, 'status': 'CONFLICT', 'conflict_reason': 'DUPLICATE_CANDIDATE',
                                   'language': 'chs', 'extension': 'srt', 'video_name': 'movie.mkv',
                                   'original_path': name + '.srt', 'warnings': []} for name in ['a', 'b']]}
            self.assertEqual(tool.preview_selections(duplicate, 'a', 'chs'), [{'id': 'a'}])
            with self.assertRaises(ValueError):
                tool.preview_selections(duplicate, 'a,b', 'chs')
        with self.assertRaises(ValueError):
            tool.MediaNexus('https://user:pass@example.com')
        with self.assertRaises(ValueError):
            tool.MediaNexus('http://example.com')

    def test_original_archive_format_is_preserved(self):
        with tempfile.TemporaryDirectory() as temporary:
            file = Path(temporary) / 'raw.bin'
            for head, suffix in [(b'Rar!\x1a\x07\x01\x00', '.rar'), (b'7z\xbc\xaf\x27\x1c', '.7z'), (b'PK\x03\x04', '.zip')]:
                file.write_bytes(head)
                self.assertEqual(tool.archive_suffix(file), suffix)


if __name__ == '__main__':
    unittest.main()
