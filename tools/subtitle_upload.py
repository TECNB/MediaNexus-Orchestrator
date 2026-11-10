#!/usr/bin/env python3
"""Search SubHD and install subtitles through MediaNexus's authenticated API."""
import argparse
import getpass
import json
import mimetypes
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
from urllib import error, parse, request
import uuid

import subhd_download

MAX_UPLOAD = 50 * 1024 * 1024

class NoRedirect(request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('后端返回重定向；请使用最终服务地址，未转发认证信息。')



class MediaNexus:
    def __init__(self, base_url, token=''):
        url = parse.urlsplit(base_url)
        if (url.scheme not in ('http', 'https') or not url.netloc or url.username
                or url.password or url.query or url.fragment or url.path.rstrip('/') not in ('', '/java-api')):
            raise ValueError('--backend 应为服务根地址或 /java-api 前缀，例如 https://media.example.com/java-api')
        if url.scheme == 'http' and url.hostname not in ('localhost', '127.0.0.1', '::1'):
            raise ValueError('远程后端必须使用 HTTPS，避免泄露登录凭据。')
        self.base_url, self.token = base_url.rstrip('/'), token

    def call(self, method, path, payload=None, body=None, content_type=None):
        headers = {'Accept': 'application/json'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode('utf-8')
            content_type = 'application/json'
        if content_type:
            headers['Content-Type'] = content_type
        req = request.Request(self.base_url + path, data=body, headers=headers, method=method)
        try:
            with request.build_opener(NoRedirect()).open(req, timeout=180) as response:
                envelope = json.load(response)
        except error.HTTPError as exc:
            try:
                message = json.loads(exc.read(65536)).get('message', 'HTTP ' + str(exc.code))
            except (ValueError, UnicodeError):
                message = 'HTTP ' + str(exc.code)
            raise ValueError('后端请求失败：' + message) from None
        except error.URLError:
            raise ValueError('无法连接后端，请确认 --backend 和服务状态。') from None
        if envelope.get('code') != 200 or envelope.get('data') is None:
            raise ValueError('后端拒绝请求：' + str(envelope.get('message', '无有效响应')))
        return envelope['data']

    def prepare(self, archive, fields):
        if archive.stat().st_size > MAX_UPLOAD:
            raise ValueError('字幕原包超过 50 MiB；未提交到后端。')
        boundary = 'MediaNexusSubtitle' + uuid.uuid4().hex
        chunks = []
        for key, value in fields.items():
            if value is not None:
                chunks.append(('--' + boundary + '\r\nContent-Disposition: form-data; name="'
                               + key + '"\r\n\r\n' + str(value) + '\r\n').encode('utf-8'))
        suffix = archive_suffix(archive)
        filename = re.sub(r'["\r\n\x00-\x1f\x7f]', '_', archive.name)
        chunks.append(('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; '
                       'filename="' + filename + '"\r\nContent-Type: '
                       + (mimetypes.guess_type('subtitle' + suffix)[0] or 'application/octet-stream')
                       + '\r\n\r\n').encode('utf-8'))
        chunks.extend([archive.read_bytes(), ('\r\n--' + boundary + '--\r\n').encode('ascii')])
        return self.call('POST', '/api/v1/subtitles/preparations', body=b''.join(chunks),
                         content_type='multipart/form-data; boundary=' + boundary)


def archive_suffix(path):
    with path.open('rb') as stream:
        head = stream.read(16)
    if head.startswith(b'PK'):
        return '.zip'
    if head.startswith(b'Rar!\x1a\x07'):
        return '.rar'
    if head.startswith(b'7z\xbc\xaf\x27\x1c'):
        return '.7z'
    if path.suffix.lower() in ('.ass', '.ssa', '.srt', '.vtt', '.sup'):
        return path.suffix.lower()
    raise ValueError('未识别字幕文件格式。请提供原始字幕文件或 ZIP/RAR/7z。')


def search_subhd(query):
    if not query.strip():
        raise ValueError('片名不能为空。')
    if not shutil.which('ego-browser'):
        raise ValueError('片名搜索需要本机 ego-browser；也可传 --archive 使用本地字幕。')
    with tempfile.TemporaryDirectory(prefix='subhd-search-') as temporary:
        output = Path(temporary) / 'results.json'
        config = json.dumps({'url': 'https://subhd.tv/search/' + parse.quote(query, safe=''),
                             'output': str(output)})
        script = r'''
const cfg = CONFIG;
const task = await taskSpace("SubHD 片名搜索 " + Date.now());
const page = task.page("p1");
try {
  await page.goto(cfg.url);
  const results = await page.evaluate(() => {
    const seen = new Set();
    return Array.from(document.querySelectorAll('a[href]')).flatMap(link => {
      const url = new URL(link.href);
      if (!/^\/a\/[A-Za-z0-9]{6}$/.test(url.pathname) || seen.has(url.pathname)) return [];
      seen.add(url.pathname);
      let card = link;
      for (let i = 0; i < 5 && card.parentElement; i++) {
        const parent = card.parentElement;
        const ids = new Set(Array.from(parent.querySelectorAll('a[href]')).map(a => a.getAttribute('href')).filter(h => /^\/a\//.test(h || '')));
        if (ids.size > 1) break;
        card = parent;
      }
      return [{id: url.pathname.split('/').pop(), url: url.href,
               title: link.innerText.trim(), summary: card.innerText.trim().slice(0, 1800)}];
    });
  });
  const fs = await import('node:fs/promises');
  await fs.writeFile(cfg.output, JSON.stringify(results));
  await task.finish({keep: []});
} catch (error) {
  if (/control.*user|EGO_USER|hard stop|paused/i.test(String(error.message))) throw error;
  await task.handOff();
  throw new Error("搜索未完成，请在浏览器检查；任务 " + task.spaceId);
}
'''
        result = subprocess.run(['ego-browser', 'nodejs'], input=script.replace('CONFIG', config), text=True)
        if result.returncode or not output.is_file():
            raise ValueError('片名搜索失败，未将错误当作零结果。')
        candidates = json.loads(output.read_text())
    print('SubHD 搜索：' + query)
    for index, candidate in enumerate(candidates, 1):
        print('\n[{}] {}\n{}\n{}'.format(index, candidate['title'], candidate['url'], candidate['summary']))
    if not candidates:
        print('当前搜索页没有字幕候选。可换中文别名、原名再查。')
    else:
        print('\n仅展示当前搜索页；结果有歧义时请换更精确的片名，不自动翻页批量抓取。')
    return candidates


def choose(items, prompt, label):
    if not items:
        raise ValueError('没有可选择的候选。')
    for index, item in enumerate(items, 1):
        print('[{}] {}'.format(index, label(item)))
    answer = input(prompt + '（输入序号，留空取消）：').strip()
    if not answer:
        raise ValueError('已取消。')
    if not answer.isdigit() or not 1 <= int(answer) <= len(items):
        raise ValueError('序号无效；未自动选择候选。')
    return items[int(answer) - 1]


def preview_selections(preparation, selected_ids, language_filter, video_name=None):
    files = preparation['files']
    parts = [part.strip() for part in selected_ids.split(',')] if selected_ids else None
    ids = set(parts) if parts is not None else None
    if parts is not None and len(ids) != len(parts):
        raise ValueError('--select 不能包含重复文件 ID。')
    if ids is not None and ('' in ids or ids - {str(row['id']) for row in files}):
        raise ValueError('--select 包含不存在的文件 ID。')
    selected = []
    print('\n目标：{}\n预览有效期：{}'.format(preparation.get('target_path'), preparation.get('expires_at')))
    for row in files:
        ready = row['status'] == 'READY'
        explicit_video = bool(video_name and ids is not None and row['status'] in ('AMBIGUOUS', 'UNMATCHED'))
        duplicate_candidate = row['status'] == 'CONFLICT' and row.get('conflict_reason') == 'DUPLICATE_CANDIDATE'
        wanted = str(row['id']) in ids if ids is not None else ready
        wanted = wanted and (language_filter == 'all' or row['language'] == language_filter)
        print('{} {} [{} / {}] {}\n    → {}\n    → {}'.format(
            '*' if wanted else '-', row['id'], row['status'], row['language'],
            row['original_path'], row.get('video_name') or '未匹配视频', row.get('final_name') or '无写入名称'))
        for warning in row.get('warnings', []):
            print('    提示：' + warning)
        if wanted:
            if not ready and not explicit_video and not duplicate_candidate:
                raise ValueError('选中了未就绪的文件；请先处理冲突或明确匹配，未提交上传。')
            selected.append({'id': row['id'], **({'video_name': video_name} if video_name else {})})
    for warning in preparation.get('warnings', []):
        print('提示：' + warning)
    if preparation.get('skipped_entries'):
        print('附件/未接受条目：' + json.dumps(preparation['skipped_entries'], ensure_ascii=False))
    if not selected:
        raise ValueError('没有可提交文件；可通过 --language 指定未知语言，或检查匹配结果。')
    selected_rows = [row for row in files if str(row['id']) in {str(item['id']) for item in selected}]
    names = [(video_name or row.get('video_name'), row['language'], row.get('extension')) for row in selected_rows]
    if len(names) != len(set(names)):
        raise ValueError('所选字幕仍映射到同名目标，请用 --select 只选择一份。')
    return selected


def wait_for_upload(api, upload_id, seconds):
    end = time.monotonic() + seconds
    state = None
    while True:
        state = api.call('GET', '/api/v1/subtitles/uploads/' + parse.quote(upload_id, safe=''))
        print('上传状态：{} / {}'.format(state['status'], state.get('stage')))
        if state['status'] != 'PROCESSING':
            break
        if time.monotonic() >= end:
            raise ValueError('上传仍在后台处理；请稍后使用 check ' + upload_id + ' 查看，勿重复提交。')
        time.sleep(2)
    if state['status'] == 'FAILED':
        raise ValueError('上传失败：' + str(state.get('error_message') or '请查看批次日志'))
    return state


def check_upload(api, upload_id):
    result = api.call('GET', '/api/v1/subtitles/uploads/' + parse.quote(upload_id, safe='') + '/check')
    for key, title in [('source', 'OpenList 源目录'), ('output', 'AS 输出目录')]:
        part = result[key]
        print('{}：{} — {}'.format(title, part['status'], part.get('message', '')))
        for item in part.get('files', []):
            if not item.get('matches_size'):
                print('  未确认：{}，预期 {} 字节，实际 {}'.format(item['name'], item['expected_size'], item.get('actual_size')))
    print('文件到位检查不代表字幕时间轴同步或 Emby 已完成索引。')
    return result


def authenticate(args):
    api = MediaNexus(args.backend, os.environ.get('MEDIANEXUS_TOKEN', '').strip())
    if not api.token:
        account = args.account or input('MediaNexus 账号：').strip()
        password = getpass.getpass('MediaNexus 密码（不显示、不保存）：')
        api.token = api.call('POST', '/api/v1/auth/login', {'account': account, 'password': password})['token']
    return api


def install(args):
    api = authenticate(args)
    source = args.source
    url = None
    if not args.archive:
        if not source:
            raise ValueError('请提供片名、字幕链接/ID，或 --archive。')
        try:
            url = subhd_download.source_url(source)
        except ValueError:
            if source.startswith(('http:', 'https:')):
                raise
            candidates = search_subhd(source)
            if args.yes:
                raise ValueError('片名搜索需明确选择字幕。非交互执行请传字幕链接/ID。')
            url = choose(candidates, '选择字幕版本', lambda x: x['title'])['url']
    title = args.title
    if not title:
        if args.yes:
            raise ValueError('--yes 需要 --title；不得用字幕发布名猜库内目录。')
        title = input('库内媒体标题（用于定位目标）：').strip()
    if not title:
        raise ValueError('库内媒体标题不能为空。')
    fields = {'media_type': args.media_type, 'title': title, 'original_title': args.original_title,
              'year': args.year, 'season_number': args.season, 'target_id': args.target_id,
              'language': args.language}
    if args.media_type == 'movie' and not args.year:
        raise ValueError('电影上传必须传 --year。')
    if args.media_type == 'series' and args.season is None:
        raise ValueError('剧集上传必须传 --season；不会默认猜季度。')
    query = parse.urlencode({k: v for k, v in fields.items() if v is not None and k not in ('target_id', 'language')})
    locations = api.call('GET', '/api/v1/subtitles/locations?' + query)['items']
    if not locations and not args.original_title:
        endpoint = '/api/v1/resources/' + ('movies' if args.media_type == 'movie' else 'series') + '/search?'
        catalog = api.call('GET', endpoint + parse.urlencode({'term': title}))['items']
        matches = [item for item in catalog if item.get('title') == title
                   and (args.year is None or item.get('year') == args.year)]
        if len(matches) == 1 and matches[0].get('original_title'):
            fields['original_title'] = matches[0]['original_title']
            print('按唯一目录结果补全原名：' + fields['original_title'])
            query = parse.urlencode({k: v for k, v in fields.items() if v is not None and k not in ('target_id', 'language')})
            locations = api.call('GET', '/api/v1/subtitles/locations?' + query)['items']
    if not locations:
        raise ValueError('后端未找到库内媒体目录，未下载或上传。请核对标题、原名、年份和季数。')
    if args.target_id:
        if not any(x['target_id'] == args.target_id for x in locations):
            raise ValueError('--target-id 不在此媒体的可用目标内。')
    elif len(locations) == 1:
        fields['target_id'] = locations[0]['target_id']
    elif args.yes:
        raise ValueError('存在多个媒体目标，请显式传 --target-id。')
    else:
        fields['target_id'] = choose(locations, '选择写入目标', lambda x: x['target_name'])['target_id']
    with tempfile.TemporaryDirectory(prefix='subhd-install-') as temporary:
        root = Path(temporary)
        if args.archive:
            archive = args.archive.expanduser().resolve()
            if not archive.is_file():
                raise ValueError('本地字幕原包不存在。')
        else:
            archive = root / 'download.bin'
            filename = subhd_download.browser_download(url, archive, args.timeout, args.resume_space)
            filename = Path((filename or 'download.bin').replace('\\', '/')).name
            named = root / filename
            if named != archive:
                archive.rename(named)
                archive = named
        print('\n正在提交原包供后端解析预览，不会在这一步写入媒体目录……')
        preparation = api.prepare(archive, fields)
        selected = preview_selections(preparation, args.select, args.filter_language, args.video_name)
        print('\n将写入 {} 个字幕，默认不覆盖已有文件。'.format(len(selected)))
        if not args.yes and input('确认写入？输入 yes：').strip().lower() != 'yes':
            print('已取消；临时预览由后端按有效期清理。')
            return 0
        result = api.call('POST', '/api/v1/subtitles/preparations/' + parse.quote(preparation['id'], safe='') + '/commit',
                          {'files': selected, 'overwrite': False})
        upload_id = result['id']
        print('已创建上传批次：' + upload_id, flush=True)
        wait_for_upload(api, upload_id, args.wait)
        checked = check_upload(api, upload_id)
        if checked['source']['status'] != 'COMPLETE':
            print('源目录尚未确认齐全；请稍后 check 此批次，不要重复上传。')
            return 2
    return 0


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    search = commands.add_parser('search', help='按片名列出 SubHD 当前页候选')
    search.add_argument('query')
    upload = commands.add_parser('upload', help='搜索/下载原包，经后端预览后上传')
    upload.add_argument('source', nargs='?', help='片名、字幕链接或六位 ID')
    upload.add_argument('--archive', type=Path, help='本地 ZIP/RAR/7z 或单字幕，不调用下载浏览器')
    upload.add_argument('--media-type', choices=('movie', 'series'), required=True)
    upload.add_argument('--title', help='库内媒体标题，需与关联作品一致')
    upload.add_argument('--original-title')
    upload.add_argument('--year', type=int)
    upload.add_argument('--season', type=int)
    upload.add_argument('--target-id')
    upload.add_argument('--language', choices=('unknown', 'chs', 'cht'), default='unknown', help='未知语言的后端解析缺省值')
    upload.add_argument('--filter-language', choices=('all', 'chs', 'cht'), default='all', help='从预览中选择的语言')
    upload.add_argument('--select', help='显式选择后端文件 ID，以逗号分隔；默认仅选择 READY')
    upload.add_argument('--video-name', help='明确选择目标目录中的视频名；歧义文件还必须用 --select 选择')
    upload.add_argument('--yes', action='store_true', help='已指定字幕和媒体身份时，确认全部所选 READY 文件')
    upload.add_argument('--timeout', type=int, default=25)
    upload.add_argument('--resume-space', type=int)
    upload.add_argument('--wait', type=int, default=120, help='后台上传等待秒数，最多 600')
    check = commands.add_parser('check', help='只读检查已上传批次的文件到位情况')
    check.add_argument('upload_id')
    for command in (upload, check):
        command.add_argument('--backend', default=os.environ.get('MEDIANEXUS_URL', 'http://127.0.0.1:8080'))
        command.add_argument('--account', help='无 Token 时使用的账号；密码在终端安全输入')
    args = parser.parse_args(argv)
    try:
        if args.command == 'search':
            search_subhd(args.query)
            return 0
        if args.command == 'check':
            result = check_upload(authenticate(args), args.upload_id)
            return 0 if result['source']['status'] == 'COMPLETE' else 2
        if not 5 <= args.timeout <= 60 or not 1 <= args.wait <= 600:
            raise ValueError('--timeout 范围 5–60，--wait 范围 1–600。')
        if args.resume_space is not None and (args.resume_space < 1 or args.archive):
            raise ValueError('--resume-space 只适用于准备交还控制的浏览器下载。')
        return install(args)
    except (ValueError, OSError, KeyError, EOFError) as exc:
        print('失败：' + str(exc), file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print('\n已中止；若已创建上传批次，请查询批次，勿重复上传。', file=sys.stderr)
        return 130


if __name__ == '__main__':
    sys.exit(main())
