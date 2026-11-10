# 搜索、下载、预览并上传字幕

入口：`python3 tools/subtitle_upload.py`。Python 3.9+，不需要 pip 依赖；片名搜索和站点下载需要本机安装并运行 Ego Lite 的 `ego-browser`，本地原包上传不需要浏览器。后端必须包含本次新增的 `preparations`、`commit`、`check` 接口。

## 1. 设置后端并登录

```bash
# 直接使用 Java 后端服务根地址；不要填前端地址或 /java-api。
export MEDIANEXUS_URL='https://你的后端域名'
```

没有 Token 时，命令会询问账号和密码，密码不回显也不保存。已有 Token 可以通过 `MEDIANEXUS_TOKEN` 环境变量提供，不放命令参数或配置文件。远程地址要求 HTTPS；本机 `http://127.0.0.1:端口` 可用于开发或安全隧道。脚本拒绝携带认证信息的重定向。

## 2. 输入片名，选择字幕版本，确认目标后上传

```bash
python3 tools/subtitle_upload.py upload '银河特急' \
  --media-type series --title '银河特急 银河☆地铁' --season 1 \
  --language chs --filter-language chs
```

`source` 可用片名、SubHD 详情页链接或六位字幕 ID。片名搜索会展示当前搜索页的候选摘要，由你选中具体版本；不自动选第一条。同名剧场版、剧集、前作需核对。

`--title` 是后端用于定位媒体目录的作品标题，与搜索词可以不同。存在原始标题目录时传 `--original-title`；电影还必须提供 `--year`，剧集必须提供 `--season`。多账号命中时命令会询问目标，也可传后端返回的 `--target-id`。

```bash
# 已选好《紫罗兰》2020 剧场版的简繁 SRT 候选，下载 RAR 原包交给后端解包。
python3 tools/subtitle_upload.py upload 'https://subhd.tv/a/FpMmNG' \
  --media-type movie --title '紫罗兰永恒花园 剧场版' --year 2020 \
  --filter-language chs

# 也可只查候选，不登录、不下载、不上传。
python3 tools/subtitle_upload.py search '紫罗兰永恒花园'

# 手动下载的 ZIP / RAR / 7z / 单个字幕可直接上传，不需要浏览器。
python3 tools/subtitle_upload.py upload --archive '/你的路径/字幕.rar' \
  --media-type series --title '这个美术社大有问题！' --season 1
```

后端预览会显示每个文件的 ID、语言、匹配视频、最终文件名、冲突和警告。默认选择 `READY`；`00`、缺视频、歧义与冲突不会自动提交。字幕语言优先使用后端文件名/包内目录证据，`--language chs` 只给未知语言提供缺省值；`--filter-language chs` 则只选择最终识别为简体的文件，二者用途不同。

预览后输入 `yes` 才会写入 OpenList。`--select ID1,ID2` 可仅选择特定文件；`--video-name '实际视频文件名.mkv'` 可为显式选择的歧义条目指定视频，后端会核对该文件确实存在。脚本不提供自动覆盖选项。

非交互运行必须指定字幕链接/ID或本地文件、媒体身份，并显式 `--yes`。多账号时还要明确目标：

```bash
python3 tools/subtitle_upload.py upload n0ePQq \
  --media-type series --title '银河特急 银河☆地铁' --season 1 \
  --language chs --filter-language chs --yes
```

## 3. 查看文件是否到位

提交后会打印 `upload_id` 并轮询后台状态，默认最多 120 秒，可设 `--wait 300`。达到等待上限不代表上传失败，不要再次提交，改查已创建批次：

```bash
python3 tools/subtitle_upload.py check '上传批次ID'
```

命令分别显示 OpenList 源目录和 AS 输出目录结果。源文件齐全返回退出码 0；未确认齐全返回 2；请求或批次失败返回 1。AS 输出不可访问会显示 `UNAVAILABLE` 和原因，不会伪装成确认成功。文件到位不代表 Emby 已索引，也不代表时间轴同步。

## 运行和兼容说明

- 原包直接上传到后端，由后端统一执行格式识别、受限解包、语言/集号识别、命名和写入。脚本不重复拼 OpenList 路径，不需要 OpenList/AS 凭据。
- 浏览器正常下载遇验证或页面变化会停下并保留网页。手动下载后用 `--archive`；准备交回原浏览器任务时也可明确 `--resume-space 任务ID` 重试。
- 脚本单次发送上限 50 MiB，实际服务器可有更小限制。字幕来源、翻译质量、片源版本和同步仍需确认。
- 原下载整理工具 `subhd_download.py` 保留；使用 `--raw` 可只保存原包。本地整理模式仍只支持 ZIP，RAR/7z 请交给后端。

```bash
PYTHONDONTWRITEBYTECODE=1 python3 tools/test_subtitle_upload.py
PYTHONDONTWRITEBYTECODE=1 python3 tools/test_subhd_download.py
```
