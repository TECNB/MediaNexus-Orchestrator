#!/usr/bin/env python3
"""Download one SubHD selection through Ego Lite, or organize a local ZIP."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
from urllib.parse import urlsplit
import zipfile

MAX_ARCHIVE = 50 * 1024 * 1024
MAX_FILE = 10 * 1024 * 1024
MAX_TOTAL = 150 * 1024 * 1024
EXTENSIONS = {'.ass', '.ssa', '.srt', '.vtt'}
HOSTS = {'subhd.tv', 'subhd.me', 'subhd.one'}


def source_url(value):
    if re.fullmatch(r'[A-Za-z0-9]{6}', value):
        return 'https://subhd.tv/a/' + value
    url = urlsplit(value)
    if (url.scheme != 'https' or url.netloc not in HOSTS
            or not re.fullmatch(r'/(?:a|down)/[A-Za-z0-9]{6}', url.path)
            or url.query or url.fragment):
        raise ValueError('请输入 SubHD 六位字幕 ID 或 https://subhd.tv/a/ID 链接。')
    return 'https://' + url.netloc + '/a/' + url.path.rsplit('/', 1)[1]


def browser_download(url, destination, seconds, resume_space=None):
    if not shutil.which('ego-browser'):
        raise ValueError('未找到 ego-browser。可先手动下载，再使用 --archive 整理。')
    config = json.dumps({'url': url, 'dest': str(destination), 'timeout': seconds * 1000,
                         'resume': resume_space})
    # The script is passed over stdin, never interpolated into a shell command.
    script = r'''
const cfg = CONFIG;
const task = cfg.resume ? await takeOverTaskSpace(cfg.resume) : await taskSpace("SubHD 单条下载 " + Date.now());
console.log("浏览器任务：" + task.spaceId);
const page = task.page("p1");
try {
  await page.goto(cfg.url);
  console.log(await page.snapshot());
  const popup = page.waitForEvent("popup", {timeout: 15000}).then(
    page => ({page}), error => ({error})
  );
  await page.click('loc=role:button[name="下载字幕文件"]');
  const opened = await popup;
  if (opened.error) throw opened.error;
  const down = opened.page;
  await down.waitForURL(cfg.url.replace('/a/', '/down/'), {timeout: 15000});
  await down.waitForLoadState();
  console.log(await down.snapshot());
  const pending = down.waitForEvent("download", {timeout: cfg.timeout}).then(
    download => ({download}), error => ({error})
  );
  await down.click('loc=role:button[name="下载字幕文件"]');
  const result = await pending;
  if (result.error) throw result.error;
  await result.download.saveAs(cfg.dest);
  const fs = await import('node:fs/promises');
  await fs.writeFile(cfg.dest + '.download.json', JSON.stringify({filename: result.download.suggestedFilename()}));
  console.log("已取得：" + result.download.suggestedFilename());
  await task.finish({keep: []});
} catch (error) {
  const message = String(error.message || error);
  if (/control.*user|EGO_USER|hard stop|paused/i.test(message)) throw error;
  await task.handOff();
  console.error("未取得下载文件：" + message);
  console.error("已保留网页。请在浏览器手动完成后，用 --archive 整理下载文件。");
  throw new Error("需要手动完成，浏览器任务 " + task.spaceId);
}
'''
    result = subprocess.run(['ego-browser', 'nodejs'], input=script.replace('CONFIG', config), text=True)
    if result.returncode or not destination.is_file():
        raise ValueError('浏览器未完成下载。可在网页手动下载后用 --archive 重试。')
    metadata = destination.with_name(destination.name + '.download.json')
    return json.loads(metadata.read_text())['filename']


def zip_name(info):
    name = info.filename
    if not info.flag_bits & 0x800:
        try:
            name = name.encode('cp437').decode('gb18030')
        except UnicodeError:
            pass
    name = name.replace('\\', '/')
    if (name.startswith('/') or '..' in name.split('/') or ':' in name
            or any(ord(c) < 32 or ord(c) == 127 for c in name)):
        raise ValueError('压缩包包含不安全路径：' + repr(name))
    if stat.S_ISLNK(info.external_attr >> 16):
        raise ValueError('不接受 ZIP 内符号链接：' + repr(name))
    return str(PurePosixPath(name))


def inspect_subtitle(name, data):
    encodings = ('utf-16',) if data.startswith((b'\xff\xfe', b'\xfe\xff')) else ('utf-8-sig', 'gb18030', 'big5')
    for encoding in encodings:
        try:
            text = data.decode(encoding)
            break
        except UnicodeError:
            continue
    else:
        raise ValueError('字幕编码无法识别：' + name)
    if '\x00' in text:
        raise ValueError('字幕含二进制内容：' + name)
    if Path(name).suffix.lower() in {'.ass', '.ssa'}:
        rows = 0
        for line in re.findall(r'^Dialogue\s*:\s*(.*)$', text, re.M | re.I):
            fields = line.split(',', 9)
            if len(fields) != 10 or not fields[9].strip():
                continue
            times = [re.fullmatch(r'(\d+):([0-5]\d):([0-5]\d)\.(\d{2})', value.strip())
                     for value in fields[1:3]]
            if all(times):
                start, end = [sum(int(v) * weight for v, weight in zip(match.groups(), (360000, 6000, 100, 1)))
                              for match in times]
                rows += end > start
        valid = '[events]' in text.lower() and rows > 0
    else:
        rows = len(re.findall(r'\d{1,2}:\d{2}(?::\d{2})?[.,]\d{3}\s*-->\s*'
                              r'\d{1,2}:\d{2}(?::\d{2})?[.,]\d{3}', text))
        valid = rows > 0
    if not valid:
        raise ValueError('没有有效字幕事件，可能是错误页或空文件：' + name)
    language = 'unknown'
    # Prefer the file's explicit marker; a bilingual collection directory is ambiguous.
    for part in reversed(PurePosixPath(name).parts):
        chs = re.search(r'(?<![a-z])(?:chs|zh-cn|zh-hans)(?![a-z])|简中|简体', part.lower())
        cht = re.search(r'(?<![a-z])(?:cht|tc|big5|zh-tw|zh-hant)(?![a-z])|繁中|繁体', part.lower())
        if chs or cht:
            language = 'unknown' if chs and cht else 'chs' if chs else 'cht'
            break
    season_episode = re.search(r'(?i)\bS(\d{1,2})E(\d{1,3})\b', name)
    episode = re.search(r'第\s*(\d{1,3})\s*[话話集]|\[(\d{2,3})\]', name)
    return {'source_name': name, 'encoding': encoding, 'language': language,
            'season': int(season_episode.group(1)) if season_episode else None,
            'episode': (int(season_episode.group(2)) if season_episode else
                        int(episode.group(1) or episode.group(2)) if episode else None),
            'events': rows, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def organize(archive, stage, language):
    if archive.stat().st_size > MAX_ARCHIVE:
        raise ValueError('下载文件超过 50 MiB 限制。')
    if not zipfile.is_zipfile(archive):
        raise ValueError('当前只支持 ZIP 字幕包；文件可能是 RAR/7z 或错误页面。原文件未修改。')
    manifest, skipped, seen = [], [], set()
    total = 0
    with zipfile.ZipFile(archive) as package:
        if len(package.infolist()) > 500:
            raise ValueError('ZIP 条目超过 500 个限制。')
        for info in package.infolist():
            name = zip_name(info)
            if info.is_dir():
                continue
            if Path(name).suffix.lower() not in EXTENSIONS:
                skipped.append(name)
                continue
            if info.flag_bits & 1:
                raise ValueError('不支持加密 ZIP。')
            total += info.file_size
            if info.file_size > MAX_FILE or total > MAX_TOTAL:
                raise ValueError('字幕解压大小超限：单文件 10 MiB / 总计 150 MiB。')
            if info.file_size > max(info.compress_size, 1) * 1000:
                raise ValueError('字幕压缩比异常：' + name)
            data = package.read(info)  # zipfile also verifies CRC before returning.
            entry = inspect_subtitle(name, data)
            if language != 'all' and entry['language'] != language:
                skipped.append(name)
                continue
            key = name.casefold()
            if key in seen:
                raise ValueError('ZIP 中有重名字幕：' + name)
            seen.add(key)
            target = stage / 'subtitles' / name
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open('xb') as output:
                output.write(data)
            entry['output'] = target.relative_to(stage).as_posix()
            manifest.append(entry)
    if not manifest:
        raise ValueError('没有符合语言选择的有效字幕。')
    return manifest, skipped


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', nargs='?', help='SubHD /a/ 链接或六位字幕 ID')
    parser.add_argument('--archive', type=Path, help='整理已手动下载的 ZIP，不调用浏览器')
    parser.add_argument('-o', '--output', type=Path, help='新建的结果目录（拒绝覆盖）')
    parser.add_argument('--language', choices=('all', 'chs', 'cht'), default='all')
    parser.add_argument('--timeout', type=int, default=25, help='等待浏览器下载秒数，5–60，默认 25')
    parser.add_argument('--raw', action='store_true', help='仅保留下载原包，不在本地解压，适合交给后端')
    parser.add_argument('--resume-space', type=int, help='明确恢复已交还给你的 Ego 浏览器任务 ID')
    args = parser.parse_args()
    if not args.source and not args.archive:
        parser.error('请提供 SubHD 链接/ID，或 --archive ZIP路径。')
    if not 5 <= args.timeout <= 60:
        parser.error('--timeout 范围为 5–60 秒。')
    if args.resume_space is not None and (args.resume_space < 1 or args.archive or not args.source):
        parser.error('--resume-space 需要正整数任务 ID 和 SubHD 链接，不能与 --archive 合用。')
    try:
        url = source_url(args.source) if args.source else None
        local = args.archive.expanduser().resolve() if args.archive else None
        if local and not local.is_file():
            raise ValueError('本地 ZIP 文件不存在：' + str(local))
        identifier = url.rsplit('/', 1)[1] if url else local.stem
        output = (args.output or Path('subhd-downloads') / identifier).expanduser().resolve()
        if output.exists():
            raise ValueError('输出目录已存在，拒绝覆盖；请指定新的 -o 目录：' + str(output))
        output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='.subhd-', dir=output.parent) as temp:
            stage = Path(temp)
            archive = stage / 'source.bin'
            if local:
                if local.stat().st_size > MAX_ARCHIVE:
                    raise ValueError('本地 ZIP 超过 50 MiB 限制。')
                shutil.copyfile(local, archive)
            else:
                filename = browser_download(url, archive, args.timeout, args.resume_space)
                metadata = archive.with_name(archive.name + '.download.json')
                metadata.unlink(missing_ok=True)
            if args.raw:
                if archive.stat().st_size > MAX_ARCHIVE:
                    raise ValueError('下载文件超过 50 MiB。')
                entries, skipped = [], []
            else:
                entries, skipped = organize(archive, stage, args.language)
            suffix = '.zip' if zipfile.is_zipfile(archive) else (Path(filename).suffix if not local else local.suffix)
            archive.rename(stage / ('source' + (suffix or '.bin')))
            report = {'source_url': url, 'archive_sha256': hashlib.sha256((stage / ('source' + (suffix or '.bin'))).read_bytes()).hexdigest(),
                      'language_selection': args.language, 'files': entries, 'skipped': skipped,
                      'note': '语言和集号依据文件名，未验证片源同步或翻译质量；未写入媒体库。'}
            (stage / 'manifest.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
            output.mkdir()  # Exclusive reservation: never replace an existing directory.
            for child in stage.iterdir():
                shutil.move(str(child), str(output / child.name))
        print('已保存原包{}：{}'.format('并整理 {} 个字幕'.format(len(entries)) if not args.raw else '', output))
        print('文件清单为 manifest.json；本地整理模式的字幕在 subtitles/。')
        return 0
    except (ValueError, OSError, zipfile.BadZipFile, RuntimeError) as error:
        print('失败：' + str(error), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
