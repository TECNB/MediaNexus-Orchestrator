# SubHD 单条字幕下载与整理

一个 Python 3.9+ 脚本，不需要 pip 安装依赖。自动下载使用本机已安装、已运行的 **Ego Lite / `ego-browser` CLI**；不安装 MoviePilot、字幕服务或浏览器扩展。其他机器若没有 Ego Lite，也能先在网页下载，再使用离线整理模式。

在 Media 工作区根目录运行：

```bash
# 输入字幕详情页，自动下载并整理；也可以把 URL 换成 ID：gbjp7H
python3 tools/subhd_download.py 'https://subhd.tv/a/gbjp7H' \
  -o './subhd-downloads/美术社'

# 只提取简体字幕（不改字幕内容、文件名和时间轴）
python3 tools/subhd_download.py gbjp7H --language chs \
  -o './subhd-downloads/美术社-简体'

# 已经手动下载了 ZIP：不用浏览器、不联网
python3 tools/subhd_download.py --archive '/你的路径/字幕.zip' \
  -o './subhd-downloads/手动下载'
```

输出目录必须不存在，重复运行请换一个目录。省略 `-o` 时默认输出到当前目录下 `subhd-downloads/字幕ID/`；离线模式默认使用 ZIP 文件名。

```text
美术社/
  source.zip       原始字幕包，完整保留
  subtitles/       仅提取 ASS / SSA / SRT / VTT，保留包内目录和原名
    简中/...
    繁中/...
  manifest.json    来源、SHA-256、语言、季集、编码、字幕事件数及被跳过附件
```

`--language all` 默认保留所有文本字幕；`chs` / `cht` 优先依据字幕文件名标记，缺少标记时再参考父目录，简繁混合集目录不猜测。未识别语言的文件仅在 all 模式保留。语言、集号是文件名线索，不是内容质量或片源同步证明。不会把字幕自动改成视频同名，也不会上传 OpenList、调用 Emby 或覆盖库里的文件。

自动下载走正常页面按钮，不调用私有 API。等待下载默认 25 秒，可设 `--timeout 60`。如果登录、验证、页面变化或超时导致未取得文件，脚本返回非零并保留网页交给你操作：在网页手动完成下载，再用 `--archive` 整理。脚本不会自动处理验证码，也不会在你接管浏览器后继续操作。

若你尚未手动下载，而是希望在已交还的浏览器任务中重试，明确传入终端显示的任务 ID（只在你准备好交还控制后运行）：

```bash
python3 tools/subhd_download.py gbjp7H --resume-space 33 -o './subhd-downloads/重试'
```

此命令在原任务重新打开该字幕的正常下载流程，不会沿用已超时的下载监听。

当前只支持 **ZIP 文本字幕包**。RAR/7z、单个字幕下载和图片字幕 SUP 暂不支持，会明确报错。ZIP 文件名支持 UTF-8 和本次样本使用的 GB18030；不执行字体说明、torrent 等附件。拒绝路径穿越、符号链接、加密包和超限字幕；失败时不会发布未完成的结果目录。原始 source.zip 仍会包含站点原包附件，整理出的 subtitles/ 只含字幕。

```bash
# 离线检查，无真实站点请求
python3 tools/test_subhd_download.py
```

网站会变动，脚本无法保证无人值守长期可用。下载、结构校验通过后，仍需核对库内片源、抽样查看同步和 ASS 字体显示。
