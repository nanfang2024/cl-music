---
design_type: feature
created_at: 2026-09-03
---

# 映月 v1.1 — 底部导航 + 设置页 UI 升级

## Intent Contract

```yaml
intent: 为映月增加底部导航（首页/设置），设置页提供下载列表管理、下载路径展示、亮/暗/跟随系统三态主题切换与 Telegram 频道引流入口，同时将音源标签改名为芸朵/绿鹅
constraints:
  - API 层不变：仍使用 gdstudio 聚合 API，source 参数仍为 netease/joox，仅显示文案改名
  - 现有搜索/播放/下载功能行为不回归
  - minSdk 26 与 targetSdk 34 不变；下载仍保存至 Music/映月（本版不做自定义路径）
success_criteria:
  - 底部栏两个标签可切换，首页功能与 v1.0.1 一致
  - 设置页四项功能全部可用：下载列表（播放/删除/分享）、路径展示、主题三态切换（重启后保持）、Telegram 入口拉起浏览器
  - 下载列表持久化，App 重启后仍可见
risk_level: low
```

## Verification Contract

```yaml
verify_steps:
  - run: gradle assembleRelease（沙箱内，阿里云镜像源）
  - check: aapt dump badging 确认包名/版本；apksigner verify 签名通过
  - check: 代码走查——Fragment 生命周期、MediaPlayer 释放、SharedPreferences 读写、主题重建逻辑
  - confirm: APK 构建成功且无编译警告级错误
```

## Governance Contract

```yaml
approval_gates:
  - 设计文档（本文件）经用户确认后才进入实现
  - 构建出的 APK 交用户真机验证 UI 与下载功能
rollback: 项目保留 v1.0.1 APK 产物与 git 提交点，UI 改动集中于 UI/包内，可整体回退
ownership: 用户（产品决策）；Agent（实现与构建）
```

## Scope

| In | Out |
|---|---|
| 音源显示名改「芸朵」「绿鹅」 | API source 参数、请求逻辑（不变） |
| BottomNavigationView + 首页/设置两个 Fragment | 平板双栏布局 |
| 下载列表：历史持久化（SharedPreferences JSON） | Room 数据库、下载队列管理 |
| 列表操作：系统播放器播放、删除记录、分享文件 | 删除物理文件（仅删记录） |
| 下载路径只读展示 `Music/映月` | 自定义路径（SAF，留待后续版本） |
| 主题三态：亮/暗/跟随系统，选择持久化 | 主题色自定义、动态取色 |
| 设置页 Telegram 入口（@ngtool） | 内嵌 WebView 打开频道 |

## Decisions

| # | 决策 | 选择 | 否决的替代 |
|---|---|---|---|
| 1 | 导航结构 | 单 Activity + 2 Fragment + BottomNavigationView | 多 Activity（状态割裂）；Navigation Component（超出需要，YAGNI） |
| 2 | 下载历史存储 | SharedPreferences + Gson JSON 列表 | Room（引入架构组件过重，列表量小） |
| 3 | 文件播放/分享 | Android 10+ 直接用 MediaStore content URI 授权分享/播放；旧版本用 FileProvider | 统一 FileProvider（MediaStore URI 本身可授权，无需复制） |
| 4 | 主题切换 | AppCompatDelegate.setDefaultNightMode + values-night 资源 | 运行时 setTheme 重建（无法全局即时生效） |
| 5 | 删除记录 | 仅清历史条目，不动已下载文件 | 同步删文件（有误删风险，且用户未要求） |

## Surface

**界面层**：新增 SettingsFragment、DownloadHistoryAdapter；MainActivity 精简为导航容器；HomeFragment 承接现有搜索/播放/下载逻辑（从 MainActivity 迁移）。布局新增 `activity_main` 改造为 Fragment 容器 + BottomNavigationView，新增 `fragment_settings`、`item_download`。

**存储层**：新增 DownloadHistory（SharedPreferences 封装，记录 曲名/歌手/格式/大小/时间/URI 字符串），下载成功后写入。

**资源**：strings.xml 改音源名、新增设置页文案；colors.xml 拆分亮/暗两套（values + values-night）；drawable 新增底部栏图标（首页/设置）、分享/删除/播放小图标、Telegram 图标。

**清单**：manifest 注册 FileProvider（分享旧版本 file:// 路径所需）。

**设置项**：主题选择（三态单选，SharedPreferences 持久化，MainActivity 启动时应用）。

## Risks & Open Questions

- **亮色主题工作量**：现有深色 UI 全靠硬编码颜色资源，values-night 需要全套映射，遗漏处会刺眼——逐资源核对。
- **Activity 重建时主题切换**：setDefaultNightMode 会触发重建，需确认 Fragment 状态（搜索结果、播放状态）不丢失或可接受重建。
- **旧设备 file:// 分享**：FileProvider 路径配置仅覆盖 Android 8/9，量小但无法真机全覆盖验证，沙箱仅能静态检查。
- **下载列表 URI 失效**：MediaStore URI 在文件被用户手动删除后失效，播放/分享需容错提示。
