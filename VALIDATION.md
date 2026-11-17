# 原生迁移验证记录

## 2026-11-11：0.9.13 版本验证

版本更新为 `0.9.13 / 9013`，包含固定试听列表、播放降码率、下载文件优先与歌词保存、统一 Toast，以及启动更新检查的失败重试和并发请求合并。`doc/release-notes/native-v0.9.13.md` 提供四条用户可见更新摘要。

48 项 JVM 单元测试、37 项发布脚本测试、Debug/测试 APK 构建、三架构正式签名 APK 构建及 Release Lint 通过（0 错误、216 项警告）。三个 APK 已通过发布脚本的包名、版本、ABI 内容及一致签名校验，并与本地 0.9.12 正式包的证书比对一致。构建记录为 `build/native-v0.9.13-validation.txt`，安装包核验结果为 `build/native-v0.9.13-apk-checks.json`，发布脚本测试结果为 `build/native-v0.9.13-script-tests.txt`。功能设备回归见下述对应记录，本轮版本构建没有安装或卸载设备应用。

## 2026-11-11：启动更新检查重试与请求合并

将更新检查收敛至应用级 `UpdateChecker`：成功检查后间隔 24 小时，失败后间隔 5 分钟，由下一次启动或返回前台重试；手动检查绕过间隔，手动与自动入口共享进行中的请求。成功或失败均在请求完成后记录时间，旧 `version.lastCheck` 不作为成功记录，下一次保存新历史时移除。页面销毁只取消等待，应用级请求仍完成并保存缓存；自动提示检查前台生命周期，Activity 内也避免重复等待启动检查。检查成功但没有可用版本时同步清除旧通知缓存。

48 项 JVM 单元测试、Debug 应用及测试 APK 构建、Debug Lint 通过（0 错误、215 项警告）。新增 11 项单元测试覆盖成功与失败间隔边界、手动立即重试、双向并发合并、共享失败、取消页面等待、无等待者时保存结果、取消请求、空响应及系统时钟回拨。首轮两项用例使用异常引用相等断言，调整为验证异常类型及消息后全部通过。

独立 `.updatecheck` 包在 YYA-H618 / Android 12 的 5 项定向回归通过：真实启动检查与关于页按钮共用一次请求、旧时间戳迁移及历史持久化、既有版本渠道过滤、ABI 选择和关于页摘要/忽略行为。设备结果保存在忽略的 `build/update-check-validation/device-results.txt`。隔离应用和测试包已清理，正式应用安装路径前后核对一致，没有操作用户手机。

## 2026-11-11：统一 Toast 并优先显示最新提示

参考用户提供的 ToastUtils，将主界面、播放服务、下载、分享、播放设置及歌单浏览的 Toast 统一到 `utils/ToastUtils.kt`。使用应用 Context，所有显示操作都在主线程执行；全局取消当前 Toast，合并待显示请求，并用代数检查阻止旧请求恢复显示。支持从任意线程显示、短/长提示和显式取消。源码搜索确认仅工具内部保留 `Toast.makeText`。

独立 `.toastcheck` 包在 YYA-H618 / Android 12 的 4 项回归全部通过。通过系统无障碍 Toast 事件验证主线程连续 50 条和后台线程连续 50 条均只显示最后一条；新消息在 2.5 秒内替换已显示的长 Toast，取消后无旧提示继续弹出，随后新提示仍能显示。另回归三次网络重试后的缓存清理提示和完整下载页面流程。结果为忽略的 `build/toast-device-first-run.txt`。

最终 37 项 JVM 单元测试、常规 Debug/测试 APK 构建及 Debug Lint 通过（0 错误、212 项警告），输出为 `build/toast-validation.txt`。隔离测试包已清理，正式应用安装路径前后核对一致，没有操作用户手机。

## 2026-11-11：已下载文件优先与部分缓存续传重试

播放在线入口先匹配本应用同平台、同歌曲的已完成下载，优先首选码率，没有时使用其他可用下载文件；缺失、被截断或损坏的文件回退到缓存及网络，保留下载记录与文件。之后直接使用完整或部分播放缓存保存的 URI，不再因缓存未完整而先调用音源取链；开启降码率时可优先使用较低码率缓存。

网络读取明确设置为首次请求加三次重试，关闭 OkHttp 的额外自动重试。同一次音质播放的预算不因分片取得少量进度而重置，避免反复断开时无限续传。耗尽后释放播放器，删除该未完成缓存及 URI/长度/重定向元数据，再提示用户；停止时播放栏保留具体原因。保留其他歌曲、完整缓存及已下载文件，后续是否降码率继续遵循设置；解析和解码错误不作网络重试。

独立 `.playbackcachecheck` 包在 YYA-H618 / Android 12 完成 8 项定向回归，全部通过。覆盖缓存导出下载及真实系统下载的优先选择、跨码率下载选择、下载文件损坏及丢失时的回退、96 KiB 部分缓存直接播放和仅对缺失 Range 进行两次失败后的续传、导出字节完整一致、每次读取部分数据后断开的四次请求上限、清理目标缓存且保留其他缓存、播放栏错误文字，以及既有降码率、暂停/倍速/音量/位置/换曲、完整缓存离线重播、LRU 和下载页面。结果为 `build/playback-cache-priority-device-first-run.txt`，使用本地 HTTP 与静音 WAV，不请求真实音乐音频。

该阶段 37 项 JVM 单元测试、Debug/测试 APK 构建及 Debug Lint 通过（0 错误、212 项警告），输出为 `build/playback-cache-priority-validation.txt`。隔离测试包已清理，正式应用路径一致，没有操作用户手机。

## 2026-11-11：完整播放缓存转下载及自动歌词

下载和音质可用性检测优先识别同歌曲、同码率的完整播放缓存。缓存分片通过仅缓存的数据源顺序导出，不取链、不请求音频网络、不移走播放缓存；不完整、码率不同或复制期间缓存被淘汰时转入正常下载。导出中断或任务被移除时清理未完成文件。新音频与同名 UTF-8 LRC 保存到 `Download/LX Music/`，旧 `Music/LX Music/` 记录继续显示，不迁移旧文件。

音频完成后由独立 JobService 保存歌词，优先读取歌词缓存，否则请求原平台；下载完成广播和下次启动可继续处理。歌词错误最多重试三次，未找到及失败状态可在下载详情查看，不改变已完成音频的状态。导出包含译文与罗马音；本地音乐可按下载文件名关联完整歌词缓存，私有缓存清理后可回读同名 LRC。任务移除会清理其音频和歌词，迟到的歌词结果不保留外部文件。

独立 `.cachedownloadcheck` 包在 YYA-H618 / Android 12 的最终 9 项设备回归全部通过：完整分片缓存导出的字节一致性、未调用取链、缓存保留、公共文件名与目录、后台自动 LRC、无缓存时通过本地 HTTP 获取原文及译文、不同本地 ID 的歌词关联与清理缓存后回读、不完整/不同码率回退、复制前淘汰时禁止补拉音频、歌词失败与无歌词、移除期间的迟到结果，以及原有下载页面、系统下载、下载完成后歌词广播、缓存 LRU 和停用音源后的重播。首轮 7 项也通过，输出位于忽略的 `build/cache-download-device-first-run.txt` 与 `build/cache-download-device-results.txt`。测试只使用本地音频/歌词响应及人工分片，不请求真实音乐音频。

最终 37 项 JVM 单元测试、常规 Debug/测试 APK 构建及 Debug Lint 通过（0 错误、212 项警告），记录为 `build/cache-download-validation.txt`。Android 9 的存储权限及文件写入分支已编译，本轮未在该版本设备实测。隔离包已清理，正式应用安装路径前后核对一致，没有操作用户手机。

## 2026-11-11：播放失败自动降低码率

播放设置新增「无法播放时自动降低码率」，缺省开启并持久保存。在线歌曲取链失败、取链超时或真实音频 HTTP/解码失败时，按 Hi-Res、无损、320k、128k 逐级重试，跳过元数据和音源不支持的高音质，全部失败后停止。关闭后严格使用所选码率；下载仍按指定音质取链。重试保留位置、暂停意图、倍速及音量，不改写首选音质，音频缓存按实际码率区分。

独立 `.qualityfallbackcheck` 包在 YYA-H618 / Android 12 完成 10 项定向回归，全部通过。覆盖默认开关及关闭后恢复、码率候选过滤、取链失败、20 秒音源请求超时与调用方取消的区别、HTTP 404、无法解码、码率耗尽停止、指定音质下载、缓存隔离、重试期间暂停、7000 ms 位置保持和换曲取消，并回归自动换源、音频卸载、MediaPlayer 到 Media3 回退及停用音源后的完整缓存重播。音频使用本地 HTTP 服务与静音 PCM，不解析真实音乐音频。首轮 5 项基础检查也通过，结果分别保存在忽略的 `build/quality-fallback-device-first-run.txt` 与 `build/quality-fallback-device-results.txt`。

最终代码的 36 项 JVM 单元测试、常规 Debug 应用及测试 APK 构建、Debug Lint 通过（0 错误、212 项警告），构建记录保存在 `build/auto-lower-quality-validation.txt`。隔离测试包已清理，正式应用安装路径经前后核对一致，没有操作用户手机。

## 2026-11-11：固定试听列表与搜索试听自动加入

数据库升级至 v7，为新安装及旧数据库补入内置「试听列表」，保留已有列表 ID 和歌曲；列表固定排在收藏之前。搜索结果行点击及歌曲菜单中的「播放」先将所选歌曲加入试听列表，按平台和歌曲 ID 去重，播放继续使用原搜索结果队列。试听列表沿用内置列表的禁止删除、禁止改名规则，允许移除歌曲及清空，并复用原有备份和同步。

36 项 JVM 单元测试、Debug 应用及测试 APK 构建、Debug Lint 通过（0 错误、214 项警告）。新增数据库用例覆盖 v6 升级、已有试听数据保留、固定顺序、去重、同步事件、删除保护及清空；搜索用例覆盖仅加入点击歌曲、重复点击、菜单播放及不同平台相同 ID；列表菜单用例补充试听列表的顺序与禁用删除检查。这些设备用例已编译通过，本轮未执行设备回归，也未安装或卸载手机应用。

## 2026-11-10：完整发布说明与客户端摘要

修正发布脚本在发现版本专用说明后直接返回、遗漏提交记录和校验表的行为。简短说明现在仅作为「更新摘要」置于正文前，仍生成真实 Git 提交链接、版本比较链接和全部 APK 的 SHA-256 表及校验附件。无摘要文件时沿用完整记录，空摘要文件仍拒绝发布。

客户端保留收到的原始正文，仅在关于页展示时优先提取「更新摘要」；没有有效摘要时继续从完整记录过滤提交链接、版本维护和校验信息，最多显示 5 条。补充「更新原生版本」维护提交过滤，避免摘要缺失时出现版本噪声。完整说明按钮继续打开发布页。

36 项 JVM 单元测试和 37 项发布脚本测试通过，覆盖实际 0.9.8 旧格式、恢复后的 0.9.12 完整正文、摘要边界及空摘要回退，以及有无摘要时完整提交与哈希的保留。独立 `.releasenotescheck` 包在 YYA-H618 / Android 12 的设置页回归通过，确认只显示两条摘要、原始模型仍含提交链接和校验表、说明完整换行并可滚动、完整说明链接正常。首轮测试未同步摘要中的句号，修正预期后通过，XML 保存在忽略的 `build/release-notes-device-first-run.xml` 与 `build/release-notes-device-passed.xml`，预览为 `app/build/ui-review/update-summary-full-release.png`。

已恢复 GitHub `native-v0.9.12` 的完整正文，包含 3 条真实提交、比较链接和 3 个 APK 校验值。校验值使用 GitHub 已发布资产的 digest，并与下载的 `SHA256SUMS.txt` 及该附件自身 digest 比对，未使用本地重新构建的 APK 哈希。修改前后 4 项发布资产的 ID、名称、大小、digest 一致；备份与核验结果保存在忽略的 `build/release-notes-restoration/`。

Debug/测试 APK 构建及 Debug Lint 通过（0 错误、212 项警告）。隔离测试包已清理，正式应用安装路径未变，没有操作用户手机。

## 2026-11-10：0.9.12 版本验证

版本更新为 `0.9.12 / 9012`，包含歌单搜索封面网格、平板自适应列数、封面与滚动位置稳定，以及「我的列表」入口合并和历史列表恢复。`doc/release-notes/native-v0.9.12.md` 仅保留两条用户可见改动，不附提交链接或校验表。

33 项 JVM 单元测试、37 项发布脚本测试、Debug/测试 APK 构建及 Release Lint 通过（0 错误、213 项警告）。通用、ARMv7、ARM64 三种正式签名 APK 已通过发布脚本的包名、版本、ABI 内容及一致签名校验，并与 0.9.11 的签名证书比对一致。安装包、校验文件和简短说明保存在忽略的 `build/releases/native-v0.9.12/`，构建记录为 `build/native-v0.9.12-validation.txt`。

功能回归范围和结果见下述网格及列表入口记录；本轮版本构建没有安装或卸载任何设备应用。

## 2026-11-10：歌单搜索封面网格

歌单搜索从文字行改为「方形封面 + 两行歌单名」网格，右上角显示平台缩写；不再单独显示作者和进入箭头。手机为三列，平板随实际内容宽度增加列数（本轮 600 dp 窗口四列、1024 dp 窗口七列）。五个平台的搜索结果保留封面字段，复用歌单浏览的封面加载、四项并发限制、8 MiB 内存缓存和失败占位图。搜索与浏览共用 `PlaylistCoversAdapter`，换肤、重建和重置搜索时取消旧图片任务。

新增回归发现加权父布局会先后使用多个临时宽度测量内容，直接在每次测量时调整列数会重置滚动位置。初次布局后改按实际 View 宽度调整列数，并使用网格自身的 Parcelable 状态保存分页、换肤、设置返回及歌单详情返回位置；重复布局保留已加载的封面。

独立 `.playlistgridcheck` 包在 YYA-H618 / Android 12 完成 10 项定向回归，全部已通过：7 项搜索页面回归、1 项五平台字段解析和 2 项既有歌单浏览回归。新增用例覆盖三种窗口宽度、封面和平台对应关系、缺图占位、重复布局的位图稳定、追加结果、详情点击及返回位置、歌曲/歌单切换和清空；既有用例覆盖搜索重建、迟到结果、换肤、取消预测性返回、歌单浏览及大屏封面超过缓存容量后的稳定性。

最终产品代码下其余 9 项在组合运行中通过；新增用例移除在布局恢复前额外跳到顶部的截图步骤、等待真实滚动完成后单独复测通过。结果保存在忽略的 `build/playlist-grid-actual-width-first-run.xml` 与 `build/playlist-grid-position-passed.xml`，早期位置问题记录在 `build/playlist-grid-first-run.xml`；预览为 `app/build/ui-review/search-cover-grid-{360,600,1024}.png`。这些预览使用本地测试封面和原生 View 渲染，不代表手机硬件实拍或真实平台联网验收。

33 项 JVM 单元测试、常规 Debug/测试 APK 构建及 Debug Lint 通过（0 错误、212 项警告，新增两项为 KTX 写法建议）。测试后隔离包已清理，正式应用安装路径未变，没有操作用户手机。

## 2026-11-10：合并列表入口并记住上次选择

移除手机抽屉和平板侧栏中重复的「我的歌单」入口；「我的列表」直接加载上次打开的列表，没有历史或历史列表已删除时回退到「我的收藏」。列表选择写入持久偏好，切换本地音乐、搜索和新建 Activity 后仍可恢复。更多菜单使用相同入口名称及行为；点击页面标题仍可手动打开列表选择器。

列表查询取消或用户已经切换到其他页面时，不再用迟到的查询结果跳回列表页。独立 `.mylistnavcheck` 包在 YYA-H618 / Android 12 完成 4 项相关设备回归，覆盖手机/平板入口去重与高亮、首次收藏回退、手动切换、跨页面和新建 Activity 后的历史恢复、删除历史列表后的回退、快速转入设置、横向切页及列表菜单。首次新增用例没有等待手动选择器关闭动画，补齐等待后复测通过，结果保存在忽略的 `build/my-list-navigation-first-run.xml` 与 `build/my-list-navigation-passed.xml`。

33 项 JVM 单元测试、Debug/测试 APK 构建及 Debug Lint 通过。临时测试包已清理，正式应用的安装路径未变，没有操作用户手机。

## 2026-11-10：0.9.11 发布准备

版本更新为 `0.9.11 / 9011`，包含下述播放回归的音频权限前提修复；应用权限交互保持现有行为。发布说明明确这是自动化测试修复，避免将测试环境中的连续超时表述为应用功能故障。

33 项 JVM 单元测试、37 项发布脚本测试、Debug/测试包构建及 Release Lint 通过（0 错误、210 警告）。通用、ARMv7、ARM64 三个正式签名 APK 已由 `verify_apks` 核对版本、ABI 和一致签名，产物、校验文件及简短说明保存在忽略的 `build/releases/native-v0.9.11/`。本地已完成修复前复现及修复后 5 项设备回归；发布标签前先由相同提交的 API 28/35 CI 检查完整回归。

## 2026-11-10：API 35 连续启动超时的权限前提修复

用户提供的 `logs_115852917449.zip` 对应 [dev 回归 42777821816](https://github.com/savet-save/lx-music-android/actions/runs/42777821816)，检出 `f1b0e4f`。API 35 的 205 项测试中有 19 项失败、11 项跳过；API 28 回归通过。相同提交的 [标签回归 42777821783](https://github.com/savet-save/lx-music-android/actions/runs/42777821783) 在 API 35 同样出现 19 项失败，首个失败及其前一个用例均一致。

首次失败发生在 `chartRowClickPlaysTheDisplayedChartQueue` 启动 Activity 时。前一个 `coverLoadsAfterActivityStopsAndBluetoothLyricsUseServiceMetadata` 用例于 07:52:40.090 弹出系统 `GrantPermissionsActivity`，随后于 07:52:40.608 销毁主界面，07:52:40.654 结束；下一条测试于 07:52:40.655 开始，并在 45 秒后超时。线程栈显示应用主线程停在空闲的 `MessageQueue.nativePollOnce`，测试启动线程等待 `Instrumentation.startActivitySync`，没有应用主线程网络或绘制阻塞的证据。

该封面用例还会点击「本地音乐」，却没有准备音频库权限；直接操作后续页面并结束 Activity 时，遗留了未处理的系统权限请求。使用全新、未授权的 `.ci115852` 包在 YYA-H618 / Android 12 运行「封面 → 排行榜」两项用例，复现了相同的后一项启动超时。因此问题不只存在于 API 35，之前部分测试运行已授予媒体权限，掩盖了该前提缺失。

只在需要进入本地库的封面用例中，按系统版本授予并核对测试包的音频权限，不修改正式应用或首次授权测试。修复后从全新隔离包运行该用例及随后四项播放回归，5 项全部通过、无跳过。Debug/测试 APK 构建与 Debug Lint 通过；本地未配置 API 35 模拟器，该平台的最终结果仍需修复提交后的 CI 验证。

原始日志、两次 API 35 附件、本地修改前结果和修改后 XML 保存在忽略的 `build/ci-115852917449/`。临时测试包已清理，正式应用的安装路径未变，没有操作用户手机。

## 2026-11-10：0.9.10 发布验证

版本更新为 `0.9.10 / 9010`，包含下载音质可用性检测、取消歌单侧滑返回后的白屏修复、更新说明自适应高度，以及本地音乐导航状态修复。`doc/release-notes/native-v0.9.10.md` 提供四条简短说明，不附提交链接或哈希表。

33 项 JVM 单元测试、37 项发布脚本测试及 Release Lint 通过（0 错误、210 警告）。通用、ARMv7、ARM64 三个正式签名 APK 已构建；通过项目 `verify_apks` 核对包名、版本、内部版本号、ABI 内容及一致签名，并在忽略的 `build/releases/native-v0.9.10/` 准备安装包、校验文件和更新说明。

发布前合并远程已发布的 0.9.9 历史，解决验证记录及说明布局的重复改动；合并后的文件内容与本地最新功能提交一致。

## 2026-11-10：下载音质可用性检测

打开音质选择框后，各选项右侧显示主题色进度条，复用正式下载的指定音质取链流程，最多同时检测两项。每项仅在内存中读取前 10 KiB（10,240 字节），成功显示 `√`，失败或超时显示 `×`；关闭窗口或选择音质会取消剩余检测。选项仍可点击，实际下载时重新取链。检测不创建下载记录、不使用音频或 HTTP 磁盘缓存。

HTTP 请求携带 `Range: bytes=0-10239`，读取也有独立上限；读完后主动取消连接再关闭响应，避免服务器忽略 Range 或返回错误正文时继续排空内容。非空短响应视为可下载；HTTP 错误、空响应以及明确标记为 HTML/JSON/XML 的错误页视为不可用。此检测只验证链接前缀的可读性，不保证整首文件完整或链接之后仍有效。

6 项新增 JVM 回归通过，覆盖 Range、忽略 Range/未知长度、重定向、短响应、错误/空正文/错误页、取消连接及超时；全部 33 项 JVM 测试通过。独立 `.downloadprobecheck` 包在 YYA-H618 / Android 12 完成两项设备回归：蓝色/深色主题的进度、成功与失败标记及两种关闭路径，和实际取链失败、音质选择、重试下载、完成后播放流程。首次 UI 用例对异步关闭回调缺少等待，补齐后复测通过。结果在忽略的 `build/download-probe-first-run.xml`、`build/download-probe-passed.xml`，截图在 `app/build/ui-review/download-probe-blue.png`、`download-probe-black.png`。

Debug/测试 APK 构建及 Debug Lint 通过。测试完成后隔离包已清理，正式应用的安装路径未变；没有操作用户手机。

## 2026-11-10：更新说明自适应高度

关于页的说明文字及按钮明确使用 `MATCH_PARENT × WRAP_CONTENT`；说明支持多行换行并顶端对齐。设置页已有的外层 `ScrollView` 保留，内容容器高度明确设为 `WRAP_CONTENT`，由整页统一滚动。

在独立 `.updatewrapcheck` 包中验证两行说明的实际文字高度，600×360 dp 短窗口下展开许可证后可滚动到内容末尾。既有手机、平板及短窗口布局回归通过。首次新增滚动检查使用 `Int.MAX_VALUE` 触发系统滚动坐标计算溢出，改用实际内容高度后复测通过；未因此调整产品滚动逻辑。记录位于忽略的 `build/update-wrap-initial.xml`、`build/update-wrap-passed.xml`，截图为 `app/build/ui-review/update-summary-wrap.png`。Debug/测试包构建及 Debug Lint 通过；独立测试包已清理，正式应用安装路径未变。

## 2026-11-10：取消歌单返回手势后白屏

歌单搜索页与歌单详情页使用同一份 Activity 内容视图。预测性返回创建上一页的预览视图时，原实现会立即从详情页移走内容；取消手势销毁预览后，内容失去父视图，当前页只剩白屏，全局播放器仍在。新增设备用例在修改前复现了 `content.parent == null`。

`MusicPageFragment` 改为在 `onResume` 接管共享内容，刷新时也检查当前页是否已恢复；预览页创建时仅创建容器。内容已经属于当前容器时直接保留，避免取消返回后的重复移除和添加。

独立 `.backcancelcheck` 包在 YYA-H618 / Android 12 完成 10 项相关设备回归，包括连续三次取消返回、确认返回后恢复搜索词/歌单结果/分页游标、搜索与换肤、歌单浏览返回与重建、本地导航及播放期间的页面切换。新用例通过 [OnBackPressedDispatcher 的测试入口](https://developer.android.com/reference/androidx/activity/OnBackPressedDispatcher) 派发开始、进度和取消/确认事件，并确认真实 Fragment 预览已创建；取消过程中歌单请求次数保持一次。此验证覆盖 Fragment 返回流程，尚未在 Android 14 及以上通过物理侧滑验证系统动画。

Debug/测试 APK 构建、27 项 JVM 单元测试及 Debug Lint 通过（0 错误）。修改前失败记录与修复后结果保存在忽略的 `build/back-cancel-before-fix/`、`build/back-cancel-related-passed.xml` 和 `build/back-cancel-final-passed.xml`。测试仅运行在指定测试设备，完成后独立测试包已清理，正式应用的安装路径未变。

## 2026-11-10：本地音乐导航状态

本地音乐注册为独立的可选中导航项，初始页面、进入本地音乐及重建恢复后的标题均显示「本地音乐」；选择实际歌单后才高亮「我的列表」。本地音乐和歌单切换先更新数据来源，再刷新导航状态；点击「我的列表」打开歌单选择器。

独立 `.localselectioncheck` 包在 YYA-H618 / Android 12 完成 5 项相关设备回归：新增手机/平板切换与重建用例、横向导航手势、歌单菜单、播放期间切换页面、英文/繁体重建。首轮出现新用例布局等待超时及菜单输入注入失败；补齐布局就绪等待后，新用例和既有菜单用例分别复测通过，记录保存在忽略的 `build/local-selection-first-run/` 与 `build/local-selection-passed.xml`。Debug/测试包构建通过，Debug Lint 无错误。

## 2026-11-10：0.9.9 发布验证

版本更新为 `0.9.9 / 9009`，包含本地音乐入口去重、更新说明精简和左侧导航文字颜色统一。为本次版本提供 `doc/release-notes/native-v0.9.9.md`，发布脚本优先采用其中的三条简短改动，便于旧版应用直接显示；校验值继续通过 `SHA256SUMS.txt` 附件提供。

27 项 JVM 单元测试和 37 项发布脚本回归全部通过。三架构 Release 构建、实际包名/版本/ABI/同证书签名验证通过，Release Lint 0 错误（210 项警告）；说明文件为空时发布被拒绝，使用独立说明时仍生成正确的校验附件。本轮发布验证没有安装或卸载设备应用。

## 2026-11-10：更新说明精简

核对已发布 `native-v0.9.8` 的实际正文，确认包含原始 Markdown、提交/比较链接和 APK SHA-256 表。关于页改为通过 `UpdateNotes` 显示简短改动预览，最多 5 条、每条 140 个 Unicode 字符；截图对应的实际发布正文精简为「减少设置弹窗」。完整说明按钮打开该版本发布页。修正说明、当前版本和许可文本沿用单行样式而被截断的问题。

- 27 项 JVM 单元测试通过，包含 7 项更新说明回归：实际 0.9.8 正文、Markdown/链接清理、技术章节边界、版本与重复记录、长度和 Emoji 边界、空正文，以及保留有意义的安装/版本相关修复。
- Android 12 / YYA-H618 使用预先核验包名的独立 `.updatenotescheck` 应用完成 3 项设备回归；多条说明换行调整后复测页面与完整说明按钮通过，无跳过或失败。只选择原测试设备，原应用 APK 路径在测试前后相同，临时包已清理。
- Debug/设备测试包构建通过，Debug Lint 0 错误（210 项警告）。页面截图保存在忽略的 `app/build/ui-review/update-summary.png`。此次修改保留在本地，现有线上 Release 未变更。

## 2026-11-10：歌单列表移除重复的本地音乐入口

`PlaylistPicker` 仅显示真实歌单，移除固定的本地音乐行及其菜单，并按实际歌单位置维护选中高亮；本地音乐继续通过独立导航入口访问。更新原有权限、歌单菜单和播放回归，验证打开歌单选择器不申请媒体权限，独立入口仍可完成拒绝与重试，收藏和自建歌单的切换、菜单和高亮正常。

Debug/设备测试包构建通过，Debug Lint 0 错误（210 项警告）。YYA-H618 / Android 12（API 31）使用预先核验包名的独立 `.localpickercheck` 应用执行 3 项设备回归，全部通过，无跳过；测试结束后确认原应用 APK 路径未变，临时包已由框架清理。

## 2026-11-09：减少弹窗与设置内容页内化

同步连接、历史授权和冲突处理、版本检查与更新说明/下载、诊断日志、播放与歌词和桌面歌词改为设置页内内容；开源许可就地展开。常规设置、音质、歌单分类、音源操作和更多菜单复用 `PlatformPopup`。同步冲突不再主动打开窗口，离开页面不提交默认选择；同步地址草稿在重建后保留，同步码不写入界面保存状态。播放服务重新绑定后补齐设置控件，从播放详情进入设置后返回原播放详情。

- Debug/设备测试包及项目签名的 Release 构建通过，20 项 JVM 测试通过，Debug Lint 0 错误（210 项警告）。
- YYA-H618 / Android 12（API 31）使用独立 `.uitest` 应用执行 30 项设备回归，全部通过；最后调整后复测设置、播放和同步冲突 13 项，全部通过，其中新增服务重新绑定检查。合计 31 项不同设备测试，无跳过或失败。
- 覆盖手机/平板/短窗口测量、深色主题与英文大字号、实时主题切换、设置返回栈、地址与同步码恢复、页面内更新说明/许可、音质浮层选择与返回关闭、后台冲突跨界面重建，以及歌词字号、偏移和跳转。实际同步使用本地测试服务器，不依赖用户的同步服务。
- 首次未隔离的设备测试因签名不匹配安装失败，但测试框架仍执行了卸载清理，意外移除了设备上的原应用。检查确认其私有目录已不存在，本地未找到数据备份；已重新安装并核对项目签名的 0.9.7/code 9007 正式包，旧私有数据未恢复。后续回归全部改用独立 `.uitest` ID，并确认临时包由框架清理。界面截图保存在忽略的 `app/build/ui-review/`；本轮未推送或发布。

## 2026-11-09 发布交由 Actions

本地脚本同步版本标签后返回。新推送的 `native-v*` 标签触发 Actions 构建、签名和上传。已有标签可手动补发，手动工作流支持草稿发布。35 项发布脚本测试通过，覆盖本地交接、Actions 上传和构建期间标签变更的拦截。工作流 YAML 解析通过，实际发布待提交并推送版本标签后验证。

## 2026-11-06：发布标签自动同步与签名输出兼容

发布脚本读取版本后，在干净的当前提交上自动补齐 `native-vVERSION` 标签。本地和远程均缺失时创建并推送；本地已有时补推送；远程已有且指向当前提交时获取并复用。支持轻量标签和附注标签。推送目标默认 `origin`，可用 `--remote` 指定，拉取和推送地址均需对应所选 GitHub 仓库。

- 已有标签指向其他提交时停止，保留原标签。网络、权限、远程配置或创建/推送失败时，输出实际原因、发布目录、`git tag TAG COMMIT` 和完整 `git push REMOTE refs/tags/TAG:refs/tags/TAG` 命令；缺少 remote 时还输出配置命令。推送失败保留本地标签，重试复用该标签。远程查询按 [Git 官方的 `--exit-code` 语义](https://git-scm.com/docs/git-ls-remote#Documentation/git-ls-remote.txt---exit-code) 区分“无匹配标签”和连接失败。
- 正式发布先检查工作区，构建后再次确认本地提交和远程标签。远程标签在构建期间删除或更改时停止上传。`--prepare-only` 保持不创建/推送标签；缺少本地标签时给出完整手动命令。
- 按当前本地发布流程，`native-release.yml` 改为仅 `workflow_dispatch` 手动触发，防止脚本自动推送标签时另起一份 Actions 发布。普通构建及设备回归工作流未调整。操作方式已更新至 `RELEASING.md`。
- 同时保留前一轮签名校验修复：本机 Build Tools 37 输出 `V2 Signer: certificate SHA-256 digest:`，旧解析器只识别 `Signer #1 certificate SHA-256 digest:`，导致首个 all APK 误报。现在识别新旧标签、SDK 范围及多签名者，区分证书输出无法解析与证书不一致；公钥、Source Stamp 和 DN 中的摘要不计为 APK 签名证书。签名有效性仍由 [apksigner verify](https://developer.android.com/tools/apksigner#verify-command) 的成功退出状态确认。
- Python 3.14 执行 33 项发布脚本测试全部通过。标签创建、推送、远程附注标签获取、复用、冲突和重试使用 `build/` 下的临时真实 Git 仓库及裸远程；网络和权限失败使用定向故障注入。正式命令行验证了未提交修改时先停止并输出创建/推送命令。SnakeYAML 验证手动发布工作流及必填标签输入通过。
- 现有 `0.9.4/code 9004` 三个真实 APK 通过 Build Tools 36.1 和 37 的签名及同证书检查，修复前用 37 能复现原报错。临时复制并篡改的 APK 被签名工具拒绝，另行签名的 ARM64 副本被同证书检查拒绝；原始三个 APK 哈希保持不变，测试副本和临时密钥已清理。
- 输出位于忽略的 `build/release-tag-validation/` 和 `build/release-signature-validation/`。本轮未提交代码，也未对项目远程创建、移动或推送标签，未上传真实 Release。

## 2026-11-06：菜单窗口边界断言 CI 修复

用户提供的 `logs_114339440161.zip` 对应 [运行 42198892068](https://github.com/savet-save/lx-music-android/actions/runs/42198892068)，检出 `22eb172`。构建检查、13 项发布脚本测试、API 28 全部通过；API 35 仅剩 `PlatformPopupTest.providerMenusAnchorToSearchPlaylistsAndChartsAndDoNotResetCurrentChoices` 失败。完整堆栈定位到原第 71 行的水平对齐断言，由第 153 行的歌单平台菜单检查触发，本轮没有再报告窗口令牌、本地化或下载失败。

- 旧断言始终要求菜单与按钮的原始边缘重合，未考虑按钮在水平滚动容器中被部分裁剪的情况。组件原有行为会将菜单限制在应用窗口与可见区域的交集内。Android 的 [PopupWindow 定位说明](https://developer.android.com/reference/android/widget/PopupWindow#showAsDropDown(android.view.View,%20int,%20int,%20int)) 也明确指出空间不足时会调整位置，不能无条件假定原始锚点对齐。
- 在本地 Android 12 的独立 `.ci114339` 包中，将原测试的应用窗口设为 240×800 dp 并开启触摸模式，复现了同一 `verify` 断言及同一歌单检查调用点：按钮右边缘为 1154 px，窗口和菜单右边缘均为 1119 px，相差 35 px。API 35 原日志未记录这些矩形，因此这里的具体坐标属于本地受控复现。
- 对齐检查现在校验菜单完整位于可见应用窗口内，并比较经过窗口边界限制后的准确位置；原有 1 px 水平误差范围保留。失败信息包含按钮、菜单、窗口矩形及对齐方向。打开搜索、歌单、排行榜菜单前，先等待锚点及窗口布局完成。
- 新增左右两侧部分裁剪锚点的设备回归，分别验证左对齐、右对齐菜单贴合窗口边缘，保留选中状态、颜色及无调暗背景检查。修改范围为设备回归测试及本记录。
- 原先失败的 240 dp 窄窗口场景修复后通过。正式测试类在 YYA-H618 / Android 12（API 31）执行菜单、歌单、排行榜、搜索、设置、本地化和 Insets 回归，共 27 项，25 项通过、2 项未开启的真实联网测试按设计跳过、0 失败；五项菜单测试全部通过。Debug/测试 APK 构建、20 项 JVM 单元测试、Debug Lint 通过（0 错误、175 警告）。
- 原始日志、受控复现及设备状态记录位于忽略的 `build/ci-114339440161/`。临时测试包完成后卸载。本地仍无 API 28/35 模拟器，本次未推送，API 35 最终结果需以包含本次修改的 Actions 为准。

## 2026-11-06：英文资源与菜单输入焦点 CI 修复

用户提供的 `logs_114318333251.zip` 对应 [运行 42190921135](https://github.com/savet-save/lx-music-android/actions/runs/42190921135)，检出 `7e4c65f`，尚未包含发布脚本提交 `48f85ed`。构建检查及首次授权均通过，上次的排行榜和下载项没有再失败；API 28 剩余本地化检查 1 项失败，API 35 另外有平台菜单测试 `BadTokenException`。

- 英文 `settings_source_hint` 使用了中文品牌文字，触发现有的英文未翻译检查。英文提示改为 `Wildflower🌷`，保留原有检查规则。
- 菜单测试之前只等待窗口可见就注入返回键，未确认弹窗获得输入焦点；后续对话框若仍使用已退出的 Activity，就可能遇到失效窗口令牌。原日志只提供两帧框架栈，不能据此确认异常的具体应用调用点。本次改为等待菜单窗口获得焦点后发送返回键，并等待宿主重新获得焦点，明确断言宿主未结束或销毁。[Android 窗口焦点说明](https://developer.android.com/reference/android/view/View#hasWindowFocus())
- 同一测试的窗口外点击原来固定偏移 450 像素，在高 DPI 屏幕上仍可能落在菜单内。现在根据当前应用可见窗口和菜单实际矩形计算点击位置，并新增 1.0、1.33、2.75、4.0 密度下的坐标检查。菜单等待超时包含具体阶段，没有增加重试或放宽断言。
- CI 失败处理会把 JUnit XML 中的完整失败堆栈打印到运行日志，同时保存 `build/device-diagnostics/test-failures.txt`。Bash 语法检查与模拟失败验证通过：保留原退出码 7，完整输出四层示例堆栈及原有三份诊断文件。
- Debug APK、仪器测试 APK、20 项 JVM 单元测试及 Debug Lint 通过（0 错误、175 警告）。YYA-H618 / Android 12（API 31）使用独立 `.ci114318` 包按 CI 的两阶段筛选和样本参数执行：首次授权 1 项通过，其余 191 项中 181 项通过、10 项按设计跳过，共 182 项通过、0 失败。包括本次本地化、平台菜单焦点/外部触摸以及全部播放、同步和升级包检查；跳过项为未开启的真实联网验收及设备条件限制。
- 原始构建、脚本验证与完整设备状态记录位于忽略的 `build/ci-114318333251/`。临时测试包及设备上的两份 APK 样本已清理。当前本地没有配置 API 28/35 模拟器，本次未推送，两个系统版本的最终结果仍以修复提交后的 Actions 为准。

## 2026-11-06：三架构 APK 与 Releases 发布脚本

新增 `scripts/publish-release.py`，由 `native-release.yml` 复用。发布版本 `0.9.3` 对应三个完整文件名 `lx-music-native-v0.9.3-armv7.apk`、`lx-music-native-v0.9.3-armv8.apk`、`lx-music-native-v0.9.3-all.apk`；标题仅为 `0.9.3`。首次原生正式发布使用项目介绍，后续按前一原生正式 Release 标签以来的提交生成改动记录，每次均附三个 APK 的 SHA-256 及 `SHA256SUMS.txt`。

- 13 项 Python 发布流程测试通过，覆盖首次/后续说明、真实 Git 标签范围、标签版本策略、ABI/签名校验、上传顺序、缺包/哈希错误、失败保留草稿、草稿重试、已发布版本不覆盖、仅准备模式及未提交源码拒绝发布。GitHub 写入使用模拟命令验证，本次未创建、上传或修改任何远程 Release。
- 使用独立输出目录及临时密钥真实构建三包，versionName `0.9.3`、versionCode `9003`。脚本的实际校验函数确认：armv7 包仅含 `armeabi-v7a`，armv8 包仅含 `arm64-v8a`，all 包含 `armeabi-v7a/arm64-v8a/x86/x86_64`；三个 APK 签名均有效且证书一致。生成的说明与校验文件内哈希和实际已签名 APK 一致。
- 20 项 JVM 单元测试通过；Release Lint 为 0 错误、175 警告。未开启拆包时的 Debug APK/测试 APK 构建通过，Debug 仍为单一 `app-debug.apk`，Debug Lint 为 0 错误、175 警告。
- YYA-H618 / Android 12（API 31）独立 `.releasecheck` 包执行 `AboutDiagnosticsTest`，4 项全部通过，0 失败、0 跳过；覆盖 ARM 选包、通用包回退、旧单包兼容、错误哈希过滤以及原有版本通道、诊断和关于页行为。临时测试包完成后卸载。
- 构建、包结构及签名检查、哈希示例和设备原始结果保存于忽略的 `build/release-validation/`。真实三包为测试密钥签名，不能作为正式版本分发；实际发布仍需仓库既有的正式签名配置和 GitHub 登录/Secrets。

## 2026-11-06：第二次 Actions 设备回归修复

用户提供的 `logs_114299436692.zip` 对应 [运行 42183665546](https://github.com/savet-save/lx-music-android/actions/runs/42183665546)，检出合并提交 `3400265`（包含 `1afcca3`）。该次构建检查及两个系统版本的首次授权测试均通过，版本样本已正确使用 `0.9.4`；上次的关于页版本和签名错误未再出现。此运行早于内置音源提交 `351ea05`。

- API 28 有 1 项失败：排行榜抽屉回归等待列表状态超时。对照设备测试确认，关闭后立即重开抽屉并在布局完成前设置滚动位置，会被后续布局覆盖。测试现在等待列表与窗口布局完成，再定位到中间行，避免末行底部对齐随窗口高度变化影响恢复断言；超时信息增加具体阶段及列表状态，保留原有超时上限。
- API 35 有同一项排行榜失败，另有平台菜单 `BadTokenException` 及 3 项下载失败。歌单 ID 确认后的平台菜单现在等待输入对话框完全关闭，再获取当前页面锚点；宿主正在退出/销毁/重建时不创建菜单，并清理窗口令牌失效时的残留状态。新增仍附着的退出中 Activity 锚点回归。
- 3 项下载日志均停在 `STATUS_PENDING`（0 字节、reason 0），并非上次旧版本记录被取消。原日志缺少调度器状态，不能据此断言具体的调度约束。CI 通过仅在仪器测试中启用的 `runDownloadJobs=true` 显式触发这些下载对应的系统任务，再验证真实 HTTP 传输、文件内容及安装前安全检查；应用的下载实现未修改。任务 ID 映射依据 [Android 15 官方 AOSP 实现](https://github.com/aosp-mirror/platform_packages_providers_downloadprovider/blob/android-15.0.0_r1/src/com/android/providers/downloads/Helpers.java)。失败时额外上传 JobScheduler、连接状态和 logcat，保留原始失败退出码。
- 本地 Debug/测试 APK 构建、20 项 JVM 单元测试和 Debug Lint 通过（0 错误、183 警告、1 提示）。Bash 语法检查通过；模拟构建退出码 7，确认失败处理仍返回 7，并生成全部 3 份诊断文件。
- YYA-H618 / Android 12（API 31）独立 `.ciregression` 包定向复验：`ChartsPageTest`、`PlatformPopupTest`、`DownloadsTest`、`NativeUpdatesTest` 共 11 项，10 项通过、0 失败、1 项未开启的真实榜单联网测试按设计跳过。显式开启下载任务触发，并使用 `0.9.3/code 93` → `0.9.4/code 94` 的同签名及错误签名样本。旧布局顺序的受控对照测试超时，修复后同一滚动/重建检查通过。
- 输出位于忽略的 `build/ci-114299436692/`；临时测试包和设备上的测试 APK 已清理。本地设备均为 API 31，不能替代 API 28/35 完整回归；本次尚未推送，远程结果需以修复提交后的 Actions 为准。

## 2026-11-06：内置默认野花音源

将用户指定的 [flower/latest.js](https://raw.githubusercontent.com/pdone/lx-music-source/main/flower/latest.js) 原始内容保存为 `app/src/main/assets/script/flower.js`，名称「野花🌷」、版本 `1`。新音源库默认选中该脚本，现有音源库迁移时补入该脚本并保留原有选择、停用及更新提示设置；停用或删除后重启不自动恢复。可继续使用既有音源导入、切换、导出、删除和更新提示功能。

- 原始下载、资源文件以及 Debug APK 内的脚本 SHA-256 均为 `15c166987edde41cf5917b3160c0ab253ff17e5e6bc6765c9eaa11a822c4aa97`，脚本未修改，Git 对该文件保留原始字节。
- Debug APK、仪器测试 APK 构建通过，20 项 JVM 单元测试全部通过；Debug Lint 为 0 错误、183 警告、1 提示。
- YYA-H618 / Android 12（API 31）使用独立 `.builtincheck` 包执行 `UserSourceLibraryTest`、`UserSourceTest`、`OnlineSourceImportTest` 和 `SettingsFragmentTest`，24 项全部通过，0 失败、0 跳过。覆盖首次创建、旧单脚本/v1 库迁移、重复脚本去重、停用/删除持久化、现有自定义脚本与更新提示设置保留、导入失败回退和设置页操作。
- 显式传入 `liveBuiltinSource=true`，未修改的内置脚本通过原生 QuickJS 运行时初始化，并支持 `kw/kg/tx/wy/mg` 的 `musicUrl` 动作。本次未下载真实音乐，也不把动作支持声明记作五平台音频播放验收。该联网初始化测试在未传参数时跳过；其他新增音源库测试不依赖第三方网络。
- 原始输出位于忽略的 `build/builtin-source-validation/`；测试包完成后卸载。脚本随 APK 打包，初始化与解析仍依赖脚本自己的在线服务，API 28/35 设备结果以对应 Actions 运行记录为准。

## 2026-11-06：Actions 设备回归版本样本修复

运行 [42154972751](https://github.com/savet-save/lx-music-android/actions/runs/42154972751) 的 API 28 和 API 35 作业各有相同的 4 项失败，构建检查与首次授权测试通过。根因是版本升级后测试仍使用 `0.1.0/0.1.1`：关于页断言与安装版本不符；错误签名样本的 code 2 先被版本校验拒绝；两项下载测试的旧版本记录被 `NativeUpdates.progress()` 主动取消，随后表现为连接重置或等待超时。缓存写入冲突、模拟器启动期间的 ADB offline 警告不是本次作业失败原因。

- 回归脚本先读取基线 APK 的元数据，再将 versionName 的补丁号、versionCode 各加 1，生成同签名和不同签名的升级样本。测试中的更新版本也从实际安装版本推导；关于页从 PackageManager 读取期望显示版本。更新管理器的版本、签名和完整性校验保持原有规则。
- 本地 Debug APK、仪器测试 APK 构建通过，20 项 JVM 单元测试全部通过。Debug Lint 为 0 错误、183 警告、1 提示，Bash 语法检查通过；对脚本内实际使用的 Python 生成逻辑执行 7 项检查，覆盖旧版、当前版、两位数补丁号、后续版本、非法版本及版本上限。
- YYA-H618 / Android 12（API 31）使用独立 `.ciactioncheck` 包验证 `0.9.2/code 92` → `0.9.3/code 93` 样本，`AboutDiagnosticsTest` 与 `NativeUpdatesTest` 共 6 项全部通过，0 失败、0 跳过，包含上述 4 项失败。未自动确认安装升级包，测试包完成后卸载。
- 构建输出、版本生成检查及设备原始结果保存于忽略的 `build/ci-42154972751/`。本地 Android 12 验证不能代替 API 28/35 的完整回归；远程结果以本次修复提交后的 Native Android Actions 运行为准。

## 2026-11-06：平台浮层与榜单左侧列表

平台选择共用原生 PopupWindow，覆盖搜索、歌单、排行榜及仅输入歌单 ID 的平台选择入口。菜单贴合触发按钮，歌单菜单右对齐，当前平台使用主题色；搜索名称遵循别名/原名设置，默认仍为聚合搜索。重复选择当前平台不清空已有结果、不重复请求。菜单支持外部点击、返回键、短窗口滚动和锚点移除时关闭，边界限制在应用窗口与可见区域的交集内。

榜单选择复用侧边抽屉，从标题下方的内容区左侧展开，宽度为一半、最多 320 dp；顶部标题和底部播放栏保持可用。当前榜单显示主题色及箭头，支持长列表滚动、遮罩、返回键和向左滑动关闭。打开时阻止主页横向切页；重建后恢复展开状态与榜单列表位置。

- YYA-H618 / Android 12（API 31）相关设备回归 41 项全部通过，0 失败、0 跳过，包含平台浮层 2 项、排行榜 4 项、歌单页 4 项、设置 7 项、搜索 5 项、歌单接口 7 项、窗口适配 5 项、主页手势/主抽屉/Insets 各 1 项、实际音频播放 4 项。默认在线歌单与热歌榜显式开启联网验证。
- 检查三处菜单的锚点边缘、无调暗背景的窗口标志、选中色、重复选择不重载、外部触摸及返回键关闭；暗色、英文、1.6 倍字号在 600×280 dp 短窗口验证滚动、窗口边界及重建后关闭。平台浮层的手机布局使用设备内 360×800 dp 的原生应用窗口；榜单抽屉的 360×800、1024×768 dp 预览使用 Android View 测量与绘制，均不等同于对应实体手机实测。
- 验证右侧主导航偏好下榜单仍从左侧展开，底部播放器不受遮罩拦截，实际 MediaPlayer 在抽屉打开时可以暂停及继续；从播放器焦点按返回只收起榜单列表。验证滑动不切换主页、重建不重复请求并保留长列表位置。
- 最终补验歌单 ID 入口后，平台浮层 2 项设备测试再次全部通过。完整回归日志为 `build/popup-validation/device-final-tests.txt`，最终浮层补验为 `popup-final-tests.txt`。
- JVM 单元测试 20 项通过。最终 Debug Lint 为 0 错误、183 警告、1 提示，Release Lint 为 0 错误、179 警告；详细构建、设备测试、预览与签名验证输出位于 `build/popup-validation/`。签名 Release 文件名仍为 `lx-music-native-v0.9.2.apk`，versionCode 92，`apksigner verify --verbose` 通过。隔离测试包已卸载。

## 2026-11-06：平板封面稳定性与排行榜页面

针对用户提供的 1920×1080 / Android 12 录屏，确认同一位置的歌单图片与 LX 占位反复交替。封面现在对同一 URL 的已显示内容保持幂等，固定方形图片更新像素时不触发父容器重新测量，底部加载更多改为覆盖层以保持网格高度稳定；手机仍使用三列布局。

- YYA-H618 / Android 12（API 31）最终 38 项相关设备测试全部通过，0 失败、0 跳过。包括歌单页 4 项、排行榜 3 项、设置 7 项、搜索 5 项、歌单接口 7 项、窗口适配 5 项、主页手势/抽屉/Insets 各 1 项及真实 MediaPlayer 播放 4 项。
- 平板专项使用本地 HTTP 返回的多个 512×512 图片，可见位图总量超过 8 MiB 封面 LRU；连续触发 15 次父容器布局后，检查所有可见图片仍非占位，位图对象身份不变，布局次数能够收敛。该专项在本轮宽屏设备实际执行，未按屏宽跳过。
- 排行榜使用独立 Navigation 目的地、ViewModel 与 SavedStateHandle，默认酷我别名“小蜗音乐”热歌榜（id 16）。验证平台/榜单选择、音质/时长显示、分页失败同页重试、迟到响应隔离、返回/旋转保留位置及原搜索页查询；点击排行榜歌曲验证实际 MediaPlayer 播放该排行榜队列。
- 显式开启 `liveCharts=true` 和 `livePlaylists=true`，默认热歌榜与默认歌单、封面图片的真实接口验证通过。其余平台使用既有解析和筛选样本；本轮未重新执行五平台全量联网验收，也未下载真实音乐音频。
- 排行榜的 360×800、1024×768 dp 界面使用同一台设备的真实 Android View 测量与绘制，预览不等于对应尺寸实体手机实测。
- JVM 单元测试 20 项通过；Debug Lint 为 0 错误、178 警告、1 提示，Release Lint 为 0 错误、174 警告。签名 Release `lx-music-native-v0.9.2.apk`（code 92）构建成功，`apksigner verify --verbose` 通过。

最终记录位于 `build/chart-validation/device-final-tests.txt`、`release-build.txt`、`signature-verification.txt`；截图位于同目录 `screenshots/`，录屏抽帧仅用于诊断。设备测试使用独立 `.chartcheck` 包，验证结束后卸载。

```powershell
.\gradlew.bat :app:assembleRelease :app:testDebugUnitTest :app:lintDebug :app:lintRelease
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=cn.toside.music.nativeapp.ui.PlaylistBrowserTest,cn.toside.music.nativeapp.ui.ChartsPageTest,cn.toside.music.nativeapp.ui.SettingsFragmentTest,cn.toside.music.nativeapp.ui.SearchPageTest,cn.toside.music.nativeapp.online.OnlinePlaylistsTest,cn.toside.music.nativeapp.ui.AdaptiveLayoutTest,cn.toside.music.nativeapp.ui.HomeNavigationTest,cn.toside.music.nativeapp.ui.PhoneNavigationDrawerTest#drawerUsesSelectedSideBlocksPageSwipesAndLeavesMiniPlayerInteractive,cn.toside.music.nativeapp.ui.ScreenInsetsTest,cn.toside.music.nativeapp.player.PlaybackSettingsTest#playlistAndSettingsNavigationKeepAudioAndTheGlobalMiniPlayer,cn.toside.music.nativeapp.player.PlaybackSettingsTest#settingsFragmentThemeChangesKeepTheSameActivityAndAudioPlaying,cn.toside.music.nativeapp.player.PlaybackSettingsTest#languageAndFontChangesKeepPlaylistSearchAndScrollWhileAudioContinues,cn.toside.music.nativeapp.player.PlaybackSettingsTest#chartRowClickPlaysTheDisplayedChartQueue' '-Pandroid.testInstrumentationRunnerArguments.chartScreenshots=true' '-Pandroid.testInstrumentationRunnerArguments.playlistScreenshots=true' '-Pandroid.testInstrumentationRunnerArguments.liveCharts=true' '-Pandroid.testInstrumentationRunnerArguments.livePlaylists=true'
```

## 2026-11-06：Navigation 导航与封面歌单页

主内容、歌单浏览、歌单歌曲和设置已接入 `NavHostFragment` / `main_navigation.xml`；抽屉、侧栏及底部播放栏在导航宿主外，播放仍由 Service 维护。歌单浏览独立使用 ViewModel / SavedStateHandle，保留筛选、分页与 GridView 原生位置状态。

- YYA-H618 / Android 12（API 31）最终 33 项相关设备测试全部通过，0 失败、0 跳过。包含歌单浏览 3 项、封面与排序等歌单接口 7 项、设置 7 项、搜索 5 项、窗口适配 5 项、主页手势/抽屉/Insets 各 1 项、真实 MediaPlayer 播放连续性 3 项。
- 验证三列方形封面、两行标题、宽屏增列、最新/最热切换、歌曲详情返回、旋转保留浏览数据和位置、搜索页返回恢复，以及分页失败重试同一页、重复/空页停止、旧筛选的迟到响应隔离。验证 GridView 在测量阶段更新列数，避免布局后清空卡片；验证播放状态刷新不会让设置页播放栏重新出现。
- 显式启用 `livePlaylists=true`，默认酷我“最新”真实接口、封面元数据及图片解码显示通过；其他平台封面字段使用固定样本验证，不将本轮记录写成五平台全量联网验收。播放使用静音 PCM，未下载真实音乐音频。
- 对实际 Android View 按 360×800、600×800、1024×768 dp 测量和绘制歌单页；同组设置/搜索测试覆盖深色、英文和 130% 字号。尺寸验证在同一台设备上完成，并非对应尺寸实体手机实测。
- JVM 单元测试 20 项通过。Debug Lint：0 错误、171 警告、1 提示；Release Lint：0 错误、167 警告。Release `lx-music-native-v0.9.2.apk`（code 92）构建成功，`apksigner verify --verbose` 通过。

记录位于 `build/navigation-validation/device-final-tests.txt`、`release-build.txt`、`signature-verification.txt`，截图位于同目录 `screenshots/`。设备测试使用独立 `.navigationcheck` 包，完成后已卸载。

```powershell
.\gradlew.bat :app:assembleRelease :app:testDebugUnitTest :app:lintDebug :app:lintRelease
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=cn.toside.music.nativeapp.ui.PlaylistBrowserTest,cn.toside.music.nativeapp.ui.SettingsFragmentTest,cn.toside.music.nativeapp.ui.SearchPageTest,cn.toside.music.nativeapp.online.OnlinePlaylistsTest,cn.toside.music.nativeapp.ui.AdaptiveLayoutTest,cn.toside.music.nativeapp.ui.HomeNavigationTest,cn.toside.music.nativeapp.ui.PhoneNavigationDrawerTest#drawerUsesSelectedSideBlocksPageSwipesAndLeavesMiniPlayerInteractive,cn.toside.music.nativeapp.ui.ScreenInsetsTest,cn.toside.music.nativeapp.player.PlaybackSettingsTest#playlistAndSettingsNavigationKeepAudioAndTheGlobalMiniPlayer,cn.toside.music.nativeapp.player.PlaybackSettingsTest#settingsFragmentThemeChangesKeepTheSameActivityAndAudioPlaying,cn.toside.music.nativeapp.player.PlaybackSettingsTest#languageAndFontChangesKeepPlaylistSearchAndScrollWhileAudioContinues' '-Pandroid.testInstrumentationRunnerArguments.playlistScreenshots=true' '-Pandroid.testInstrumentationRunnerArguments.livePlaylists=true'
```

## 2026-11-06：Fragment 设置页与即时主题配色

设置主页面由 `SettingsFragment` 和 Activity 返回栈承载，移除「完成」按钮。主题选择立即更新设置页、主页和系统栏；换色保留同一个 Activity、Fragment、播放服务、分类和滚动位置。手动选主题会关闭跟随系统；语言、字号和导航布局修改立即重建界面并恢复设置状态。

- Android 12（YYA-H618）独立测试包最终 14 项专项设备测试全部通过，无失败或跳过，覆盖 `SettingsFragmentTest` 7 项、`SearchPageTest` 5 项和真实 MediaPlayer 播放连续性/语言字号恢复 2 项。使用本地 HTTP、静音 PCM 和受控搜索结果，不下载真实音乐。
- 验证亮色到深色连续切换、返回键、主页滚动位置、分类/音源链接草稿跨重建恢复、换色不中断音源导入及聚合搜索；修复界面恢复时输入监听误清结果或取消搜索的问题。按 360×800、600×800、1024×768、640×360 dp 绘制设置页面，并检查深色英文 130% 字号；尺寸验证在同一设备完成。
- JVM 单元测试 20 项通过。Debug Lint 为 0 错误、151 警告、1 提示；Release Lint 为 0 错误、147 警告。签名 Release `lx-music-native-v0.9.2.apk`（code 92）构建成功，`apksigner verify --verbose` 通过。

最终设备记录位于 `build/fragment-validation/device-final-tests.txt`，构建和签名记录位于同目录 `release-build.txt`、`signature-verification.txt`，截图位于 `screenshots/`。测试使用独立 `.fragmentcheck` 包，完成后已卸载。复现专项可运行：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:assembleRelease
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=cn.toside.music.nativeapp.ui.SettingsFragmentTest,cn.toside.music.nativeapp.ui.SearchPageTest,cn.toside.music.nativeapp.player.PlaybackSettingsTest#settingsFragmentThemeChangesKeepTheSameActivityAndAudioPlaying,cn.toside.music.nativeapp.player.PlaybackSettingsTest#languageAndFontChangesKeepPlaylistSearchAndScrollWhileAudioContinues' '-Pandroid.testInstrumentationRunnerArguments.adaptiveScreenshots=true'
```

## 2026-11-05：设置适配与在线音源导入

设备为 YYA-H618 / Android 12（API 31）；JDK 17、Gradle 8.13、SDK 36。Debug 构建成功，14 项 JVM 单元测试通过；24 项相关设备测试全部通过，无失败或跳过，覆盖设置页、音源下载/脚本库及主页导航、屏幕适配、语言与主题回归。状态文案修正后 6 项设置页测试再次通过；最终 Debug Lint 为 0 错误、64 警告、1 提示。

- 对实际 Android View 分别按 360×800、600×800、1024×768、640×360 dp 测量和绘制，检查八个分类及其控件边界；检查深色主题、英文和 130% 字号。尺寸渲染在同一台 Android 12 设备上完成，并非四种实体手机/平板实测。
- 验证分类和链接草稿跨 Activity 重建恢复、开关持久化、关闭设置应用主题、导入期间切换分类仍保持忙碌状态，以及关闭页面取消下载。
- 本地 HTTP 测试验证重定向、无扩展名链接、UTF-8/BOM、非法协议、404、空响应、HTML 网页、带/不带 Content-Length 的 2 MB 上限及请求取消。下载脚本经真实 QuickJS 验证，导入成功后列表显示启用状态；下载/脚本失败保留旧选择，支持重试。
- 原有 32 个通用设置键均保留。截图人工检查后修正返回按钮、平板侧栏背景、横向分类可见位置和停用音源的状态文案。

本次相关结果归档于 `build/settings-validation/device-tests.xml` 和 `settings-final-tests.xml`；手机、平板和深色英文设置截图位于同目录的 `phone-sources.png`、`tablet-sources.png`、`phone-dark-sources.png`。Gradle 原始截图输出在 `app/build/outputs/connected_android_test_additional_output/debugAndroidTest/connected/YYA-H618 - 12/settings-validation/`。

设置与下载专项可复现为：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=cn.toside.music.nativeapp.ui.SettingsFragmentTest,cn.toside.music.nativeapp.source.OnlineSourceImportTest' '-Pandroid.testInstrumentationRunnerArguments.adaptiveScreenshots=true'
```

日期：2026-11-04。设备：YYA-H618，Android 12（API 31）。JDK 17，Gradle 8.13，Android SDK 36。

## 实际执行结果

| 验证 | 结果 |
| --- | --- |
| JVM 单元测试 | 14 项通过 |
| 全量设备回归 | 145 项通过，0 失败、0 跳过（显式启用了联网及升级包测试） |
| 五平台联网检查 | 5 个平台，每平台 12 个分项，共 60 个分项通过，已计入上述设备回归 |
| Debug Lint | 最终复验 0 错误、58 警告（全量回归时另含依赖新版本等提示，共 62 警告、1 提示） |
| Release Lint | 0 错误、58 警告 |
| APK 构建 | Debug、测试 APK、临时密钥签名 Release 构建成功，apksigner 验证通过 |
| 系统覆盖升级 | 独立原生包 0.1.0/code 1 → 同签名 0.1.1/code 2，私有目录测试数据保留，随后卸载临时测试安装 |
| RN 删除后复验 | app:clean 后仅使用本工程 Gradle Wrapper，从空输出重新构建；14 项单元测试及后台同步/歌曲信息编辑/更新共 11 项设备测试通过 |

Lint 警告包括依赖新版本提示、RTL、可访问性、原平台协议所需加密方式及复数资源建议等；未通过禁用 Lint 掩盖错误。后续追加长歌词编辑/重建/保存修复，避免截断或将大文本放入 Activity 状态事务；相关 5 项歌曲编辑测试再次全部通过，其 XML 随专项报告归档。

## 覆盖内容

- 原生后台歌单/不喜欢同步、独立冲突、断网重试、跨存储实例变更、持久化发送队列、远程操作不回发、服务恢复、通知断开及显式退出。使用本地授权与真实 WebSocket/RPC 对等端，验证 Home 后、Activity 销毁及重建后的双向同步。
- 队列编辑/保存、历史、启动偏好、倍速和音量、暂停保持/跳转、定时停止、媒体会话及通知；使用静音 PCM/本地 HTTP，验证 MediaPlayer 和 Media3；系统媒体进程失败后回退 Media3。
- Media3 上游不可达时读取缓存、动态 LRU、音源停用且播放服务重建后的完整缓存离线重播、后台封面加载。
- KRC/QRC/YRC/MRC、扩展歌词、逐字时间、逐曲微调、缓存、导入、内嵌歌词、简繁转换及桌面歌词设置。
- 多音源导入/选择/删除/更新提示、网络/二进制/multipart/取消/加密桥接，本地歌词/封面动作，换源匹配与并发缓存、失败隔离。
- 歌单元数据、批量/区间操作、排序/移动及同步协议回放；旧版备份与 allData 列表范围、按 ID 恢复；私有音频内容去重/元数据及无媒体库权限的恢复。
- 本地 WAV 名称/歌手/专辑/封面/LRC 写入、读取与删除，原 PCM 字节保留，错误图片拒绝，MediaStore 文件描述符写入，原生编辑页面重建；修改在各歌单、备份和同步中一致。
- 五平台搜索、第二页、建议、热搜、热门/最新评论、歌词、推荐歌单/详情、歌单搜索、排行榜和分类。修复网易旧热搜及分页大小、咪咕建议返回网页的问题。
- 外部 VIEW/SEND、LX 深链/控制/searchPlay、脚本/备份导入、分享；酷狗特殊链接响应与原 H5 签名输出，网易私有歌单凭据独立加密及清除。
- 首启协议/倒计时/拒绝/延迟服务与深链；语言/字号/主题切换保留播放、当前页面和滚动位置；简体/繁体/英文资源与格式占位符；窗口 Insets/横向手势/导航及退出；诊断日志有界、顺序、脱敏。
- 更新通道过滤，真实 DownloadManager 后台下载和状态重建；APK 私有副本、完整性、大小、签名、版本/包名拒绝及 FileProvider 只读安装 URI。没有自动点击系统安装确认；另用 Android 包管理器验证真实同签名覆盖安装与数据保留。

## 复现

在仓库根目录，连接 Android 测试设备：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
.\gradlew.bat :app:connectedDebugAndroidTest
```

默认设备测试有 7 项需要显式参数：5 项真实平台联网、2 项升级 APK/错误签名完整检查。未传参数时按设计跳过，不能将默认结果写成 145 项全部通过。

完整回归先准备同一调试签名、版本号高于当前构建的 APK（versionName 的补丁号和 versionCode 各加 1），以及版本/包名相同但签名不同的测试 APK，推送到设备临时目录，然后恢复当前基线版本运行。`scripts/device-regression.sh` 会读取基线 APK 的 `output-metadata.json` 自动完成这些步骤，避免应用升级后测试样本变成降级包：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest `
  '-Pandroid.testInstrumentationRunnerArguments.liveProviders=true' `
  '-Pandroid.testInstrumentationRunnerArguments.updateFixture=/data/local/tmp/lx-native-update-fixture.apk' `
  '-Pandroid.testInstrumentationRunnerArguments.wrongSignerFixture=/data/local/tmp/lx-native-wrong-signer.apk' `
  '-Pandroid.testInstrumentationRunnerArguments.migrationScreenshots=true'
```

Linux/CI 可用 `bash scripts/device-regression.sh` 自动准备测试 APK 并运行不含真实平台联网的回归。单独启用联网检查可传 `class=cn.toside.music.nativeapp.online.ProviderSmokeTest` 和 `liveProviders=true`。脚本不会发布或自动确认安装。

## 报告和边界

原始全量 XML、专项报告、Lint、签名构建、覆盖升级结果和四张英文/繁体界面截图归档在 `app/build/reports/migration-validation/`；清理输出前另保存于 `build/migration-validation/before-clean/`。这些目录是忽略的本地构建产物，不入 Git。

本次没有解析或下载真实音乐音频。真实 DSP 卸载、蓝牙终端、其他系统/设备/音频格式、所有第三方脚本、真实私有或过期分享仍需对应样本覆盖。系统同步超时回调在 Android 12 模拟调用，未在 Android 15 真机运行到时限。

API 28/35 模拟器回归、签名及原生发布草稿工作流已写入，但没有在远程执行，也没有推送、打标签或发布版本。RN 源码与工具链已在全量回归通过后移除，删除后完成独立构建与关键功能复验。

## 分包重构验证（2026-11-05）

源码及测试按 `ui`、`utils`、`data`、`player`、`lyrics`、`online`、`source`、`sync`、`update` 职责分包，同时更新 Manifest 组件路径和跨包引用。版本查询与发布模型从“关于”对话框提取到 `update/NativeReleases.kt`。逐文件比对确认原有 134 个 Kotlin 文件的业务及测试代码、提取的版本查询逻辑保持一致。

| 验证 | 本轮结果 |
| --- | --- |
| Debug APK 和仪器测试 APK 构建 | 通过 |
| JVM 单元测试 | 14 项通过，0 失败 |
| Debug Lint | 0 错误、58 警告 |
| Android 12 定向设备回归（YYA-H618） | 29 项通过，0 失败、0 跳过 |

设备回归范围为 `cn.toside.music.nativeapp` 下的 `ui.HomeNavigationTest`、`ui.PlayerDetailTest`、`ui.AboutDiagnosticsTest`、`data.LocalMetadataTest`、`player.PlaybackSettingsTest`、`sync.SyncServiceTest`，覆盖导航、关于与版本查询、日志、元数据编辑、播放与界面重建、后台同步服务等入口。

## 工程根目录调整验证（2026-11-05）

Android 工程已移至仓库根目录，`app/`、`gradle/`、`scripts/`、Gradle 配置与 Wrapper 可直接使用；CI 的触发路径、构建目录、设备回归入口、产物归档及发布草稿报告路径已同步更新。详细使用与开发说明位于 [DEVELOPMENT.md](DEVELOPMENT.md)。迁移的 197 个源码、资源、构建及脚本文件与 Git 中的原文件逐字节一致，文档中的本地链接检查通过。

在仓库根目录执行 `:app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug` 全部通过；JVM 单元测试 14 项通过，Lint 为 0 错误、58 警告。设备回归脚本和 Unix Gradle Wrapper 均通过 `bash -n` 语法检查。

## 手机与平板样式验证（2026-11-05）

参考桌面版的侧栏、歌曲分列及移动版的导航、播放器结构，实现随当前窗口宽度切换的原生界面。手机与平板共用主题、图标、选中背景、触摸反馈和同步设置表单。封面在测量阶段限制为容器内的正方形，避免首次布局或窗口缩小时越界。

| 验证 | 本轮结果 |
| --- | --- |
| Debug APK、仪器测试 APK 构建 | 通过 |
| JVM 单元测试 | 14 项通过，0 失败 |
| Debug Lint | 0 错误、53 警告 |
| Android 12 界面回归 | 12 项通过，0 失败、0 跳过 |

界面回归包含新增的 `AdaptiveLayoutTest` 5 项，以及 `HomeNavigationTest`、`PlayerDetailTest`、`ScreenInsetsTest`、`LocalizationTest`、`PreferencesAndThemesTest`。覆盖导航与歌曲列、播放详情重建后保留歌词页、同步字段及对话框宽度、深色主题、英文、1.3 倍字号、右侧导航，以及既有手势和窗口 Insets。

在设备上使用 Android 原生 View 的测量和绘制，验证 360×800、600×800、1024×768 dp 的完整界面，检查歌曲区域有足够高度、底部控制不越界、封面尺寸和歌词分栏，并导出列表及播放详情预览。测试歌曲仅存在于 Activity 内，没有写入歌单数据库。

设备为 YYA-H618（Android 12，电视固件）。该固件会把竖屏 Activity 压成横屏窗口，因此三种尺寸预览属于原生 View 测量渲染，不是手机硬件截图；手机竖屏的系统窗口和实际输入法行为尚未完成硬件实测。交互、重建、主题和 Insets 测试在设备原有窗口运行。测试后物理显示保持 1920×1080、213 dpi。

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class cn.toside.music.nativeapp.ui.AdaptiveLayoutTest,cn.toside.music.nativeapp.ui.HomeNavigationTest,cn.toside.music.nativeapp.ui.PlayerDetailTest,cn.toside.music.nativeapp.ui.ScreenInsetsTest,cn.toside.music.nativeapp.ui.LocalizationTest,cn.toside.music.nativeapp.ui.PreferencesAndThemesTest -e adaptiveScreenshots true cn.toside.music.nativeapp.test/cn.toside.music.nativeapp.NativeTestRunner
adb pull /sdcard/Android/data/cn.toside.music.nativeapp/files/adaptive-validation build/adaptive-validation/verified
```

本轮输出位于 `build/adaptive-validation/verified/`，其中 `measured-360-*`、`measured-600-*`、`measured-1024-*` 为三种尺寸的 View 渲染；`tablet-dark-player.png`、`tablet-sync.png` 等为设备窗口内的 View 渲染。最终测试输出保存在 `build/adaptive-validation/final-tests.txt`，调试包为 `app/build/outputs/apk/debug/app-debug.apk`。

## Android 9 CI 兼容修复（2026-11-05）

检查 [运行 41966937543 的 API 28 作业](https://github.com/savet-save/lx-music-android/actions/runs/41966937543/job/127046002197)，确认有四项失败：下载测试缺少旧版写入权限、授权测试无法点击旧版系统弹窗、随后播放详情启动超时、更新 APK 的签名信息读取失败。同次运行的构建检查与 API 35 作业通过。

- 下载测试在 Android 9 按实际下载策略授予 `WRITE_EXTERNAL_STORAGE`；CI 先单独执行首次授权流程测试，再执行其余回归，避免预先授予的存储权限影响授权测试。两阶段报告均上传为设备测试产物。
- 授权测试同时识别 Android 9 的 PackageInstaller 和新版 PermissionController，重试时重新获取按钮节点，并在结束时关闭两种系统授权窗口，避免遗留弹窗影响下一项界面测试。
- Android 9 的 `getPackageArchiveInfo` 仅在包含 `GET_SIGNATURES` 时收集 APK 证书；解析时额外传入该标志，仍通过 `SigningInfo` 严格验证签名、包名和版本，没有放宽安装校验。[Android 9 框架实现](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r61/core/java/android/content/pm/PackageManager.java)

本地 Debug/测试 APK 构建、14 项 JVM 单元测试、Debug Lint（0 错误、53 警告）和 `bash -n scripts/device-regression.sh` 均通过。API 28 与 API 35 的完整设备回归结果以本次修复提交对应的 Native Android Actions 运行为准；本地 Android 12 设备不能替代 Android 9 的兼容性验收。

## 本地音乐与歌单顶栏调整（2026-11-11）

本地音乐和「我的列表」移除重复的第二行标题，搜索入口移入顶栏；当前歌单名称显示在顶栏并可点击切换，本地音乐标题不再打开歌单抽屉。搜索支持筛选当前列表、清空和收起，主题重建保留筛选。顶栏仅在内容或交互状态变化时更新，避免干扰在线歌单分页的滚动位置恢复。

Debug/测试 APK 构建、48 项 JVM 单元测试和 Debug Lint 通过。Android 12（API 31）平板上的独立验证包通过首次授权流程，以及 `LocalNavigationTest`、`SearchPageTest`、`PlatformPopupTest` 的全部 17 项回归。覆盖 360×800、1024×768 dp 的顶栏布局、歌单切换、页内搜索、页面重建、平台弹层和在线歌单分页。日志保存在 `build/header-validation/permission-tests.txt`、`build/header-validation/final-all-tests.txt`。

原生 View 截图位于 `build/header-validation/final-screens/`；手机尺寸预览来自平板窗口的布局绘制，不是手机硬件截图。本轮未运行 API 28/35 设备回归，也未推送或发布。

## 侧栏「更多」对齐与菜单去重（2026-11-12）

平板侧栏底部的「更多」改为与导航条目相同的 40 dp 图标列，整行保持可点击。菜单移除当前侧栏或手机抽屉已有的页面入口，合并指向播放设置页的重复项；手机的返回桌面和退出入口保留在抽屉底部。过滤菜单后继续使用原始动作 ID，点击设置项仍进入对应分类。

Debug/测试 APK 构建、48 项 JVM 单元测试和 Debug Lint 通过。Android 12 盒子的独立验证包执行 `PlatformPopupTest`、`PhoneNavigationDrawerTest` 和 `LocalNavigationTest`：12 项通过，1 项因真实窗口不是手机尺寸跳过。覆盖 600/1024 dp 左右侧栏图标中心对齐、360 dp 左右抽屉菜单去重、弹层边界和菜单动作分发。日志及截图位于 `build/menu-validation/`；原生 View 测量截图不等同于手机硬件截图。

使用原正式签名构建 32 位 ARM 包，覆盖更新盒子上的 0.9.14，并实际打开左下角菜单确认对齐及去重效果。盒子截图为 `build/menu-validation/box-menu-open.png`，安装包和签名核对记录为 `build/menu-validation/verified-update.json`。

## 内置酷我 320 kbps 与盒子升级（2026-11-13）

0.9.16 新增内置 OIAPI 酷我音源。音源目录升级到版本 3，仅补充新增的酷我脚本，保留当前选择、停用状态、更新提醒偏好以及此前删除的野花；新目录写入后删除酷我也不会在重启时恢复。新安装仍默认选择野花，可在设置中切换到酷我。

同签名 Release ARMv7 包在 YYA-H618（Android 12 / API 31）从 0.9.14 原位覆盖到 0.9.16 / 9016，首次安装时间保持不变。Release 构建、48 项 JVM 单元测试和 Release Lint 通过。最终音源库/在线导入设备回归报告为 `OK (16 tests)`，旧野花联网检查未开启。验证新增源初始化、旧版目录和单脚本迁移、禁用、删除、去重与原偏好保留；一次实时取链出现 DNS 失败，检查连通后重试通过，未更改系统 DNS。

使用显式开启联网的 `BuiltinKuwoPlaybackTest` 调用正式应用的播放服务和下载管理流程，验证《茶花开了》—王睿卓（酷我 ID `442087398`）。播放设置为 320k，自动降低音质关闭，实际 Media3 解码格式为 320,000 bit/s / 44.1 kHz，进度在 6 秒内正常推进，无播放错误。随后应用完整下载成功，下载页显示 100%、下载完成、320K。Android MediaExtractor 检测文件为 320,000 bit/s，时长 270,027 ms，总长 10,802,282 字节。

拉取盒子实际下载的文件并核对 SHA-256，顺序扫描全部 10,338 个 MP3 帧，码率均为 320 kbps / 44.1 kHz，末尾保留 128 字节标签，无截断帧。SHA-256 为 `72bee22ac28b0d2b264b99525726a3277494ffacc3c2af6ac8164bc17c968414`。

该联网测试默认跳过，显式执行命令为：

```powershell
adb shell am instrument -w -e class cn.toside.music.nativeapp.source.UserSourceLibraryTest -e liveKuwoSource true cn.toside.music.nativeapp.test/cn.toside.music.nativeapp.NativeTestRunner
adb shell am instrument -w -e class cn.toside.music.nativeapp.player.BuiltinKuwoPlaybackTest -e liveKuwoPlayback true cn.toside.music.nativeapp.test/cn.toside.music.nativeapp.NativeTestRunner
```

本轮使用与正式包相同证书的 Release 测试包，没有卸载目标应用。播放/下载测试会实际播放示例歌曲、插入队列并保留完成的下载；成功后保留酷我音源、320k 和关闭自动降质的设置并暂停播放。设备生成的报告位于应用外部文件目录 `verification-320.json`；本地独立核验报告及下载页截图分别为 `build/source-diagnostics/box-320-verification.json`、`build/source-diagnostics/box-download-complete.png`。结果仅覆盖该歌曲和本次接口状态。

## 播放页手机和平板参考样式（2026-11-13）

按提供的两张参考图调整播放详情：手机为歌名/歌手分行、大封面、进度两端时间、大播放按钮及歌词/收藏/模式/评论图标；平板为左侧封面和三行歌曲信息、右侧居中渐隐歌词，进度、工具栏和右侧播放控制固定在整页底部。移除分栏分隔线和播放按钮圆形底色，使用已有 LX 字体图标。保留主题、歌词拖动定位、收藏、评论、队列、定时、模式和播放设置入口；歌词默认居中，显式设置的左/右对齐仍保留。重建页面或从设置返回时保留歌词显示状态。

修复检查中发现的歌词绘制越过内容区域问题，保证不覆盖底部工具栏；首次布局和尺寸改变时立即将当前歌词居中，再对正常播放中的换行平滑滚动。进度条使用主题色及浅色细轨道。手机采用独立的当前时间与总时长，平板保留底部合并时间。

Release/设备测试 APK 构建、48 项 JVM 单元测试、Release Lint 通过。Android 12 / API 31（YYA-H618）13 项设备检查全部通过：`PlayerDetailLayoutTest`、`AdaptiveLayoutTest`、`PlayerDetailTest`、`ScreenInsetsTest`、`PlayerSettingsTest`，以及 `PlaybackSettingsTest` 的设置/歌词校准和英文/繁体切换检查。验证 360×780、360×640、600×800、1024×700、844×390 dp 的控件边界和遮挡，另含深色、1.3 倍字体及页面重建。

已保留数据覆盖安装盒子上的本地验证包，实际打开播放页检查平板效果；未更改版本号、创建提交或推送远程。手机尺寸图片是在盒子上测量并绘制真实 Android View 的预览，不是手机硬件窗口、状态栏或输入法实测。

验证日志为 `build/player-style-validation/final-device-tests.txt`；手机封面页为 `build/player-style-validation/final-rendered/360x800-cover.png`，平板渲染图为 `build/player-style-validation/final-rendered/1024x700-cover.png`，实际盒子截图为 `build/player-style-validation/box-player-final.png`。其他尺寸和歌词页预览在同一 `final-rendered/` 目录中。

## 最多三个音源与顺序接续（2026-11-16）

自定义音源页将已启用和未启用分区，最多同时启用 3 个；已启用区支持上移、下移和停用，顺序持久保存。达到上限时新导入脚本保存在未启用区。音源库升级到 v4，从旧版仅迁移原先选中的音源为启用，保留脚本、删除记录及更新提示偏好。

每个启用脚本使用独立 QuickJS 运行时，按顺序解析所选音质。取链失败、无效地址、HTTP/解码播放失败时尝试后续启用脚本；播放只有在自动降低音质开启时，才在该音质全部音源失败后尝试较低音质。下载保持原音质，系统下载失败或中断后由 DownloadCompleteReceiver 和 DownloadFallbackJob 自动接续，保存已尝试音源及任务记录；应用重启后可继续，全部失败则停止，手动重试重新开始。删除任务后不会被切换过程重新创建。

验证使用 YYA-H618 / Android 12 / API 31 上的独立 `.multisourcecheck` 包，正式应用及歌单数据未覆盖。最终专项报告 `device-final.txt` 为 14 项全部通过，包含 13 项新增测试及修正后的在线导入回归；结合受影响功能回归，去重后 50 项设备测试通过，2 项第三方联网检查按默认设置跳过，无剩余失败。覆盖三源上限、排序和重启、旧版迁移、独立脚本状态、取消、320k 请求不降为 128k、无效链接、播放器实际失败、真实系统下载失败/中断、自动后台接续、全部失败和删除防恢复，以及缓存下载、音质回退、设置页面布局和导入。

48 项 JVM 单元测试通过；Debug/正式签名 Release APK 构建和两种配置 Lint 均无错误。手机 360×800 dp 和平板 1024×768 dp 使用真实 Android View 在盒子上测量并绘制，未作为手机硬件窗口实测。本轮音频为本地 HTTP 静音 PCM 样本，未重新验证第三方歌曲的真实码率或服务可用性。

记录位于忽略目录 `build/multi-source-validation/`：`verification.json` 汇总去重结果，`device-first.txt`、`device-regression.txt` 和 `device-final.txt` 保留原始日志（中间回归中的旧单选断言已修正并通过最终复测）；`build-final.txt` 记录构建和 Lint。最终分区截图为 `screenshots/multi-source-validation/360-sources.png` 和 `1024-sources.png`。测试包验证后移除，本轮版本号仍为 0.9.17，代码修改保留在工作区。

## B站搜索、MP3 转码与歌词下载（2026-11-16）

新增独立的 B站视频搜索入口及原生 B站音源。音源库升级为 v5，向已有库补入未启用的 B站入口，保留最多三个已启用音源及其顺序、停用与更新偏好。接口参考 bilibili-plugins/bilibili-api-collect（本轮读取提交 cfc5fddcc8a94b74d91970bb5b4eaeb349addc47），实现匿名 Cookie、WBI 参数签名、视频信息与可访问 AAC 音轨解析，当前只处理多 P 视频的第 1 P。B站上传者独立标记为 UP，不作为歌词歌手。已有下载和完整缓存仍优先使用，缓存播放不依赖启用音源或重新请求视频信息。

B站下载先由系统下载管理器写入应用私有临时目录，再经 Android MediaCodec 解码与 LAME 编码生成真正的 MP3，最终 MP3 和匹配到的同名 UTF-8 LRC 写入 Download/LX Music/。编码设置为 320 kbps，不将重新编码描述为提升原始音质。转码完成前检查实际音频时长，任务删除或取消时清理未发布的输出，后台任务可在进程恢复后继续。歌词在五个音乐平台按歌名、歌手、版本和不超过 5 秒的时长差匹配，并拒绝时间轴明显超出音频的结果；兼容两种歌名/歌手顺序并复用搜索结果。找不到歌词时保留音频并显示无歌词。歌曲菜单新增搜索并下载歌词，本地歌曲也可核对元数据后通过系统文件选择器保存 LRC，同时缓存到应用供播放使用。

设置页新增 B站 SESSDATA 页面，验证账户后使用 Android Keystore 加密保存，不回填到密码输入框，不提供给自定义脚本或音频 CDN。清除凭据会取消正在验证的操作，并对写入/清除串行处理，避免迟到的验证结果重新写入。登录接口使用独立请求客户端且不跟随重定向。

MP3 编码使用两个独立共享库 libmp3lame.so 与 liblxmp3.so。JNI 在 JNI_OnLoad 中通过 RegisterNatives 注册，配合 Keep 与导出映射，仅导出 JNI_OnLoad 入口，不使用 Java_ 静态绑定符号。NDK 27.2.12479018 / CMake 3.22.1 使用 max-page-size=16384 和 common-page-size=16384；构建了 ARMv7、ARM64、x86、x86_64。修复通用包混入 x86 QuickJS 却缺少相应编码器的问题，保留 ARMv7 / ARM64 / 通用三个发布文件，通用包完整包含四个 ABI。新增脚本在 CI 和发布前检查编码器 ABI 一致性、全部 64 位 ELF LOAD 段和未压缩库的 ZIP 对齐；最终 Debug/Release 校验及 zipalign -c -P 16 -v 4 通过。LAME 源码固定提交、重建方式和 LGPL 许可见 app/src/main/cpp/lame/UPSTREAM.md，许可也包含在 APK 的关于页面。

独立 .bilicheck 包在 YYA-H618 / Android 12 / API 31 上，合并去重后 56 项设备测试通过，2 项原有第三方联网检查默认跳过。覆盖 WBI 例子、搜索解析、音轨筛选、账户加密与清除取消、歌词时长/版本/标题顺序、本地下载界面、系统下载到后台转码及同名 LRC、流请求 Referer/User-Agent 且无 Cookie、缓存离线重播、v4 迁移、三音源管理和旧搜索/设置回归。音频端到端用例使用本地 HTTP 静音 PCM，产出由 MediaExtractor 确认为 audio/mpeg，时长与 12 秒输入相符，检查 MPEG-1 Layer III 的 320 kbps 帧头及 LRC 时间标签。

48 项 JVM 单元测试、41 项 Python 脚本测试通过，Debug/正式签名 Release 构建与两种 Lint 均无错误。四个 ABI 的动态符号检查只发现 JNI_OnLoad 入口；ARM64/x86_64 的 MP3、LAME 和 QuickJS 库均通过 16 KB ELF 与 APK 检查。盒子实际 PAGE_SIZE 为 4096，没有 16 KB 页设备运行验证。本轮正式应用及数据未覆盖，验证结束移除独立测试包；版本仍为 0.9.17，改动保留在本地工作区。

真实匿名 B站搜索在本机及盒子均收到 code=0、data.v_voucher 的验证要求，应用会显示验证错误。用户选择稍后自行登录，因此真实 B站歌曲播放、MP3 下载与歌词匹配未计入已通过项目；BilibiliLiveTest 保留为显式 liveBilibili=true 的后续验收入口。原始首次失败、修复复测、最终构建、对齐和符号记录在忽略的 build/bilibili-validation/ 目录；verification.json 汇总结果，device-regression.txt / device-final.txt / device-complete.txt / device-lyrics-final.txt 为最终设备记录，build-delivery.txt 与 python-complete.txt 为最终构建和脚本记录，native-delivery.txt 为对齐及导出记录。

## 指定手机 ARM64 与页大小核验（2026-11-16）

设备 9dd0fd82 为 M2007J17C，Android 12 / API 31，内核 4.19.113-perf-gd70e19d0749a；getconf PAGE_SIZE 返回 4096。该系统使用 4 KB 页，不能作为真正 16 KB 页环境的运行验证。

在独立 .bilicheck 包中安装 ARM64 APK，系统确认 primaryCpuAbi=arm64-v8a。BilibiliMp3Test 的系统下载到 MP3 转码与同名 LRC、流播放请求两项，以及 UserSourceTest 的 QuickJS 初始化和异步请求一项全部通过（OK (3 tests)）。音频使用本地 HTTP 静音 PCM，转码检查实际 MP3、320 kbps 帧头及输出时长；没有进行真实 B站登录或联网歌曲验收。

本轮重新检查 ARM64 Debug 与正式签名 Release APK：liblxmp3.so、libmp3lame.so、libquickjs-android-wrapper.so 均通过 16 KB ELF LOAD 段与 APK 存储对齐检查，Debug 包另经 zipalign -c -P 16 -v 4 验证通过。静态对齐与 4 KB 手机上的 ARM64 功能检查均通过，真正 16 KB 页运行仍待 PAGE_SIZE=16384 的设备或模拟器验证。测试后仅移除本轮新建的两个独立测试包，原有正式应用及数据未覆盖；记录位于 build/page-size-validation-9dd0fd82/。

## 播放详情全屏 Fragment 与系统栏（2026-11-16）

播放详情从原生 Dialog 迁移为 PlayerDetailFragment，由 MainActivity 的独立 FragmentContainerView 覆盖整个浏览区域及迷你播放栏，加入 Activity 的 Fragment 返回栈并设为主导航 Fragment。关闭只弹出播放页，保留原浏览目的地和播放服务；手机歌词/封面状态由 Fragment 保存，重建及从播放设置返回时恢复。View 销毁时释放歌词和封面引用。根 FrameLayout 统一处理状态栏、导航栏、刘海及键盘 Insets，不在播放内容重复加留白；系统栏透明且图标明暗随主题，取消系统对比度遮罩。透明主题图片下补充不透明底色，避免下层列表透出。

MainActivity 显式启用 OnBackInvokedCallback，播放 Fragment 的进入、返回采用 Animator 属性动画，交由 AndroidX FragmentManager 处理手势进度、取消和提交，未添加抢占返回的播放页回调。[Android 官方文档](https://developer.android.com/guide/fragments/animate#predictive-back) 要求预测返回 Fragment 动画运行于 Android 14 及以上；本地手机和盒子都是 Android 12，因此新增的左右边缘取消/提交测试按系统版本跳过，尚无 Android 14+ 真机或模拟器的跟手动画验收。

独立 .playercheck 包在 M2007J17C（9dd0fd82，ARM64 / API 31）去重后 14 项通过、2 项跳过；YYA-H618（ARMv7 / API 31）15 项通过、1 项跳过，无剩余失败。共同跳过预测返回动画，手机另跳过仅横屏适用的状态栏预留测试。覆盖播放页与 Activity 共用窗口、Insets 只应用一次、重复打开不叠加、返回不弹出下层下载页、设置往返保留歌词页、重建恢复、真实播放与歌词拖动，以及 360/600/1024 dp 等布局、深色背景、英文和 1.3 倍字体。背景不透明修复后，手机和盒子各复测主题、Insets 和开关三项，均通过；真实手机和盒子截图已检查，状态栏和底部控制显示完整，背景不再透出列表。

48 项 JVM 单元测试通过；最终 Debug/正式签名 Release APK 构建、Debug/Release Lint 均无错误，ARM64 与通用包的 16 KB 原生库检查通过。手机首次安装需要手动确认；MIUI 拦截测试器从后台启动 Activity，回归时通过 ADB 显式将测试应用切到前台完成验证，未修改系统权限。最初超时记录保留在 phone-initial-tests.txt，不计入最终结果。

按用户要求保留手机上的 cn.toside.music.nativeapp.playercheck 和 .playercheck.test，后续使用相同包名覆盖更新；盒子独立测试包已清理，两端正式应用及数据均未覆盖。版本仍为 0.9.17，本轮修改未提交或推送。日志、verification.json 和最终构建记录位于 build/player-fragment-validation/；phone-regression.txt、phone-final-tests.txt、box-tests.txt、box-final-tests.txt 为最终回归记录，phone-final-screens/ 和 box-screens/ 包含系统栏的真实设备截图。

## B站登录主题统一、扫码登录与版本目录（2026-11-16）

BilibiliLoginActivity 改用与主界面相同的 NativeThemes、UiPalette、LxStyles、字体和语言设置，包含返回顶栏、主题色操作、滚动内容及最大 640 dp 的表单宽度。使用统一 ScreenInsets 处理状态栏、刘海、导航栏和键盘，不再显示默认灰色系统栏及系统按钮；背景图下保留不透明底色。手动 SESSDATA 收入可展开区域，仍禁用密码输入框的状态保存和自动填充。

新增本地生成二维码和 B站客户端扫码确认流程，参考[扫码接口文档](https://github.com/bilibili-plugins/bilibili-api-collect/blob/master/docs/login/login_action/QR.md)。二维码请求和轮询仅访问官方 passport HTTPS 接口，不发送现有账户 Cookie，也不跟随 SSO 重定向；成功后提取 SESSDATA，验证账户再用原 Android Keystore 加密存储。处理未扫码、已扫描待确认、成功、过期、网络失败及手动刷新；有效期按 180 秒限制，每两秒查询，页面进入后台暂停轮询。刷新、手动验证或清除会取消旧操作，并检查取消状态及当前二维码身份，阻止迟到结果写回。

本轮调用官方匿名接口发现二维码页面已返回 account.bilibili.com/h5/account-h5/auth/scan-web，已与旧 passport.bilibili.com/h5-app/passport/login/scan 地址同时兼容，并核对返回密钥匹配。二维码由 ZXing 3.5.4 在本地编码，不经过图片服务；许可随包提供并在「关于」展示。Android 10+ 使用 MediaStore 保存到 Pictures/LX Music，供 B站扫一扫从相册识别；Android 9 使用系统文件选择器保存 PNG，不额外申请广泛写入权限。Android 9 的文件选择器路径本轮仅完成构建检查，未进行该系统的设备验收。

M2007J17C（9dd0fd82，ARM64 / API 31）12 项、YYA-H618（ARMv7 / API 31）13 项专项回归全部通过，无跳过或剩余失败。包含真实匿名二维码生成和待扫码查询；模拟确认结果验证账户校验、加密保存、输入框不回填、旧二维码刷新/清除后的迟到结果、过期重试、相册 PNG 实际保存并解码为原登录 URL，以及旧手动输入、本地歌词、许可展开和深色/英文/1.3 倍字号、360/600/1024 dp 布局。截图专项在两端另各执行两项并通过，等待系统入场动画结束后核对状态栏和内容。真实账户扫码确认及认证后的 B站搜索、播放与下载未计入已通过结果，仍由用户稍后自行登录验收；测试凭据均为假数据，原有凭据在测试结束后恢复。

48 项 JVM 单元测试通过，Debug 与正式签名 Release 构建及两种 Lint 无错误。ARM64、通用包的 16 KB 原生库和 APK 对齐检查通过，Release 签名 SHA-256 保持 91333f72261757beb2ec9d1eff01713cacd3db76ee863f59ca37b086678c5f84。Gradle 插件和库统一迁入 gradle/libs.versions.toml，核对原有 12 个依赖和 2 个插件版本均未变化，仅新增 ZXing。

MIUI 对测试器直接启动内部 Activity 的限制，通过仅测试代码的 foregroundTestHost 参数处理：先显式打开测试应用主界面，再由已在前台的 Activity 按实际用户路径启动登录页，不修改系统权限。初始启动超时及修正前的布局断言日志保留，不计入最终回归。手机 .playercheck 主包和测试组件继续保留，盒子独立测试包已移除，两端正式应用均未覆盖。版本仍为 0.9.17，本轮未提交或推送。记录位于 build/bili-login-validation/，phone-tests.txt / box-tests.txt 为最终专项，*-screenshot-tests.txt 为截图复测，phone-screens/ / box-screens/ 包含使用假二维码的浅色、深色真机截图，build-final.txt 和 build-screenshots.txt 为最终构建与 Lint。

## 顶栏返回按钮统一与居中（2026-11-16）

B站登录、播放详情和设置统一使用 LxStyles.backButton：相同字体图标、24 sp 图标大小、48×48 dp 点击区域、主题色、无字体额外留白和水平/垂直居中。三个顶栏统一左侧 4 dp 留白并关闭基线对齐；设置页移除 Unicode ‹ 字符，标题也取消字体额外留白，解决返回按钮看起来靠下的问题。平板播放页顶栏提高到 56 dp，完整容纳一致的触控区域；手机播放页继续容纳歌名和歌手两行内容。

手机 M2007J17C（ARM64 / API 31）6 项、盒子 YYA-H618（ARMv7 / API 31）6 项回归全部通过。覆盖三处主题色、设置页返回与标题的垂直中心、播放页 48 dp 触控区域及顶栏居中、360/600/1024 dp 等布局、平板及短横屏、深色、英文和 1.3 倍字号。未新增纯样式测试类，检查加入既有的播放布局、设置布局和登录页回归。Debug/正式签名 Release 构建、两种 Lint 及最终 16 KB 原生库/APK 对齐检查通过。

预览已检查：登录页与深色播放页为包含系统栏的设备截图；360 dp 设置页和播放页为原生 View 测量绘制，使用设备当前歌曲文本，封面未加载时显示占位图。本轮未重新验证真实 B站账户登录或音频下载。手机测试包及测试组件按要求覆盖更新并保留，盒子独立测试包已清理，两端正式应用未覆盖，版本仍为 0.9.17，改动未提交或推送。记录位于 build/back-button-validation/；phone-main-tests.txt / phone-login-tests.txt / box-tests.txt 为测试日志，phone-screens/ / box-screens/ 为预览，build.txt 为构建和 Lint，native-alignment.txt 为对齐检查。

## B站原始 AAC 下载、码率选择及手动 MP3 转换（2026-11-16）

B站下载弹窗按实际返回的 AAC 音轨列出码率，使用稳定音轨 ID 解析所选音频，直接保存 M4A，不再自动转码。已有完整缓存时先显示离线选项，点击「查看其他可用码率」才查询其他音轨。下载仍先检查完整缓存，再补充第 1 P 信息及解析链接；播放缓存记录音轨 ID、码率和时长，相同音轨的原始播放缓存可用于指定码率下载，不跨音轨误用缓存。旧缓存没有码率元数据时显示近似平均码率。

手机及平板播放页提供「更多 → 转换为 MP3」。转换使用本地下载文件或完整缓存导出的 M4A，另建持久化转换任务，生成 MP3 后保留原文件及缓存；移除转换任务仅移除新输出，失败可独立重试。转码时按输入文件的实际时长校验输出，避免视频标题或旧元数据时长导致误判。旧版本已经排队的自动 MP3 任务保留兼容处理。音源脚本原文及其指纹未修改，设置页单独更新原生 B站音源说明。

手机 M2007J17C（9dd0fd82 / ARM64 / API 31）新增 AAC 数据用例 5 项、码率浮窗及播放更多入口 2 项均通过：真实 AAC/M4A 样本由设备编码器生成；验证离线缓存导出无需视频信息或音频请求、MediaPlayer 直接播放、低码率只使用对应缓存、显式音轨复用同一原始播放缓存、系统直接下载 M4A、不自动转码、转换输入持久化、失败重试及原文件字节不变。B站解析 10 项、旧 MP3 2 项、普通下载浮窗及播放布局复测通过；普通缓存下载、下载管理和音源管理回归也有通过记录。两项旧界面测试改用项目已有 ForegroundTestActivities，以适配 MIUI 前台启动限制。

仍有 1 项未通过：MultiSourceDownloadsTest.interruptedTransferUsesNextSourceAfterHistoryReloadAndKeepsQuality 在该手机上多次等待 90 秒超时，最后启用 runDownloadJobs=true 后仍复现；未把它计为通过，原因尚未确定。日志保留在 build/aac-conversion-phone-regression.txt、build/aac-conversion-phone-final.txt、build/aac-conversion-phone-complete.txt。新增功能专项均通过，但本轮没有验证真实 B站账号及联网歌曲，未将模拟的码率列表或本地 AAC 样本描述为真实 B站端到端验收。

48 项 JVM 单元测试通过，Debug、测试包和正式签名 Release 构建通过；Debug Lint 为 0 errors / 262 warnings，Release Lint Vital 通过，apksigner 验签及 zipalign 16 KB 对齐检查通过。构建记录为 build/aac-conversion-final.txt，码率浮窗预览为 build/aac-bitrate-options.png。正式 APK 为 app/build/outputs/apk/release/lx-music-native-v0.9.17-all.apk；版本保持 0.9.17 / 9017。只覆盖更新手机上的独立 .playercheck 测试包，正式应用未覆盖，未提交或推送。

用户随后要求覆盖手机正式版：2026-11-16 18:06:27（Asia/Shanghai）通过 adb install -r 将上述 release 安装到手机 9dd0fd82 的 cn.toside.music.nativeapp，保留应用数据。安装返回 Success，版本仍为 0.9.17 / 9017，primaryCpuAbi=arm64-v8a；正式 MainActivity 冷启动返回 Status: ok，进程存活。已安装 base.apk 与构建产物 SHA-256 一致（86e16d00689970acfdf527439907022703153c291c365bda28b5d5be677f24f0）。记录为 build/aac-release-phone-update.txt；本次没有更新盒子。

## B站真实音频 403 与统一三次自动重试（2026-11-16）

用户反馈正式版「【Hi-Res】绅士-薛之谦」无法播放。手机日志确认音频 CDN 返回 HTTP 403。对同一公开视频 BV1VaWu61E1g / CID 43468915745 的新取链结果进行手机端对照：三个主/备用地址均在 Android 浏览器 UA 下返回 403；桌面浏览器 UA 的普通 GET 返回 200，Range 请求返回 206。新地址的有效期仍约两小时，调整 Range 或视频 Referer 无法修复 Android UA 的 403。BilibiliApi 统一改用网页音频兼容的桌面 UA，仍发送 B站 Referer，不向 CDN 发送账户 Cookie。

播放请求的取链、地址刷新和 HTTP 续传共用首次尝试后最多三次重试，避免重建播放器后重复获得额度。401/403/404/410 转由服务刷新链接，解析主/备用 CDN 并避开已拒绝的地址；B站允许在有限额度内重新使用同一原生音源。重试期间保持准备状态、error=null，全部失败才显示一次最终提示。刷新地址清除 Media3 旧跳转元数据，相同 B站音轨保留有效分片；不同音轨或脚本编码不混用。B站原生音频适配器提取接口，专项通过注入实际原生适配路径的测试替身验证，不依赖不支持 original 音质的自定义脚本模拟。

52 项 JVM 测试、28 项手机专项全部通过。覆盖三次 403 后第四次成功、四次均失败后才提示、取链前三次失败后成功、拒绝主 CDN 后播放备用地址、续传共享额度、失败缓存定向清理、旧重定向失效、音轨变更、原有换源及 AAC 下载/转换；包含公开音轨 CDN 请求对照。早期模拟脚本的三项失败日志保留在 build/bili-playback-retry-phone-tests.txt，不计为通过；最终记录为 build/bili-playback-retry-phone-complete.txt 和 build/bili-cdn-probe-final.json。Debug/测试包/正式签名 Release 构建、Debug Lint（0 errors / 263 warnings）、Release Lint Vital、apksigner 及 16 KB zipalign 检查通过。前一轮系统下载中断换源超时用例本轮未重跑，不视为已修复。

2026-11-16 18:42:07（Asia/Shanghai）保留数据覆盖手机正式版，仍为 0.9.17 / 9017。直接播放用户原来失败的同一歌曲：正式进程 AudioTrack 于 18:42:32 开始输出，播放状态为 PLAYING，18:44:47 验证后暂停，实际播放位置为 135156 ms，界面显示 02:15 / 04:50，无播放错误。该结果仅代表这条公开视频的真实验收。已安装 APK 与本次 release 的 SHA-256 一致：705057cc44fc83ee56e3ec93fafe55c64c91e51b5700251455e5dcd81c0434f2。构建、安装、播放状态和汇总分别见 build/bili-playback-retry-final-build.txt、build/bili-playback-retry-release-install-final.txt、build/bili-playback-retry-real-song-paused.txt、build/bili-playback-retry-verification.json。诊断不保存账号 Cookie、完整签名 URL 或音频内容；未更新盒子，改动尚未提交或推送。

## 下载总大小与封面保存（2026-11-16）

下载列表的手机行新增独立总大小信息，宽屏状态栏使用第二行显示大小，详情同步显示；总大小尚未获取时显示未知。下载完成后独立后台任务保存同名 JPG 到 Download/LX Music/，优先读取封面缓存，旧任务缺少封面字段时自动加入补齐队列。本地歌曲先读内嵌封面，再读下载的 JPG；封面保存后刷新正在播放的本地歌曲。B站图片地址在网络请求时升级为 HTTPS 并使用 B站图片请求头，已有缓存键保持可复用。

封面网络失败最多尝试 3 次，后台退避重试，音频完成状态和原始文件均不受影响；移除下载会移除其封面，保存过程中移除任务不会产生迟到文件。独立 MP3 转换保留原始音频及 JPG，为新 MP3 保存各自的 JPG。

M2007J17C（API 31）独立测试包中 18 项下载回归通过，包含 6 项新增封面测试：旧记录迁移及离线缓存复用、503 后重试成功、三次失败后停止且保留音频、保存中删除无残留、真实后台 Job 与 MP3 转换文件独立性、系统下载 URI 的封面读取；同时覆盖 AAC 原样保存、所选音轨缓存、LRC 和下载页面总大小断言。旧页面用例显式选择所导入的测试音源，适配多音源优先级；AAC 播放用例按实际进度等待，移除设备上不稳定的固定 200 ms 假设。最终日志为 build/download-cover-device-tests.txt；早期失败不计为通过。52 项 JVM 测试、Debug 和正式三架构构建、两种 Lint（0 errors）、签名与 16 KB zipalign 检查通过，最终构建记录为 build/native-v0.9.18-final-build.txt。版本递增至 0.9.18 / 9018，并补齐自上一个 tag 后的更新摘要。

2026-11-16 19:22:03（Asia/Shanghai）保留数据覆盖手机正式版为 0.9.18 / 9018，安装包 SHA-256 与本地 ARM64 正式包一致：c23b9565c8041aa526244a3d904661b67bdc95d8fa666794913ec5a700d2c80b。用户原有「生僻字」下载显示 5.62 MiB，详情确认同名 JPG 已自动补齐。真实播放旧下载时发现 DownloadProvider 的 DISPLAY_NAME 返回歌名而非文件名，已改为优先按原始下载 URI 关联封面，保留文件名匹配供媒体库 URI 使用；最终冷启动后同一本地歌曲正确显示保存的封面，播放停留在试听后的 00:34。截图见 build/native-v0.9.18-download-size.png 与 build/native-v0.9.18-local-playback.png。

首次远程完整回归（Actions 43857248673）构建检查通过，设备回归发现旧测试前提尚未跟随本批功能调整：语言重建用例仍假定默认进入本地页、平台弹窗仍按 6 个选项计数、Android 9 用例调用了 API 29 的 WindowInspector、预测返回用例在 CI 全局关闭 Animator 时验证动画。分别改为显式打开本地页再验证重建、新增 B站后的 7 个搜索选项、直接读取应用弹窗，以及仅在动画专项临时恢复 Animator 时长并在 finally 还原；保留原断言。手机上相关的语言、平台菜单、AAC 码率及独立转换 4 项复测通过，记录为 build/native-v0.9.18-ci-fixes-phone-tests.txt；Android 14+ 的预测返回仍由远程 API 35 回归验证。

第二轮完整回归中 Android 28 已通过，Android 35 仅剩预测返回的手势中 Fragment 标签查询断言失败。对照 AndroidX 官方 FragmentAnimatorTest 的动画与取消用例，改为手势中检查原视图仍附着且动画随进度变化，取消后等待同一 Fragment 恢复 RESUMED，并检查回退栈、主导航 Fragment 和播放服务仍保持；提交手势仍要求播放器关闭而 Activity 与服务保留。该调整验证实际可见行为与取消恢复状态，不依赖过渡期间的标签索引。

第三轮 Android 28 继续通过，Android 35 的手势中视图与动画断言通过，但取消恢复等待仍失败。将取消后的视图检查改为 Fragment 当前视图，允许框架重建视图，同时增加失败时的 Fragment、生命周期、当前视图附着和回退栈诊断，以进一步区分视图重建与恢复异常。

详细诊断确认 Android 35 取消手势后，同一播放器已恢复 RESUMED、当前视图仍附着、回退栈为 1，但 alpha 停留在 0.27300477，属于实际界面恢复缺陷。PlayerDetailFragment.onResume 现在显式将透明度、横纵缩放还原为 1；回归同时验证左右两侧取消后的透明度、缩放、同一 Fragment、主导航与服务，以及完成手势后的退出行为。Android 15 验收继续以远程 API 35 结果为准。

补丁后的 5 项手机播放器回归、52 项 JVM 测试、正式三架构构建与 Debug/Release Lint 均通过；手机测试覆盖返回只关闭播放器、设置返回歌词页、系统栏布局、AAC 码率与独立 MP3 菜单，记录为 build/native-v0.9.18-player-final-phone-tests.txt。20:20:26 保留数据覆盖手机正式版，最终 ARM64 APK 与手机安装包 SHA-256 一致：24308f12b942db32e0f746ee823b7512912325b98c1d7398f23d96e71deb9791；正式签名保持不变，三个 APK 的 16 KB zipalign 通过。

## bilibili 搜索与歌词偏好、内置音源删除限制（2026-11-17）

搜索平台显示名称改为 bilibili，仅在内置「B站音乐」启用时提供，停用当前选中的 bilibili 后回到聚合搜索并清除旧结果。自定义音源页增加 bilibili 歌词方式及说明，默认优先匹配歌词，可改为优先视频字幕；两种方式支持回退、分别缓存，切换后重载当前 bilibili 歌词，手动导入的 LRC 优先。仅内置音源的删除菜单项变灰不可点击，导入音源保持可删除。

52 项 JVM 测试、Debug 应用及测试包构建通过，Debug Lint 为 0 errors / 268 warnings。在线 YYA-H618（Android 12 / API 31）使用独立 `.bilisettingscheck` 包完成 29 项设备测试：BilibiliLyricsTest 7 项、BilibiliSettingsUiTest 2 项、BilibiliTest 11 项、SearchPageTest 8 项、MultiSourceSettingsTest 1 项；设置界面 2 项在第二轮再次通过。覆盖字幕时间轴及空隙、字幕语言与人工轨道优先级、两种歌词方式的首选与失败回退、取消请求、缓存隔离与手动导入优先、内置脚本识别、搜索入口随启用状态变化、设置持久化、删除项的禁用状态与灰色，以及原搜索分页/切换/主题和音源排序回归。

记录为 build/bilibili-settings-build.txt、build/bilibili-settings-checks.txt、build/bilibili-settings-device-tests.txt 和 build/bilibili-settings-regression.txt。字幕接口按公开实现接入，本轮使用固定数据验证解析与选择逻辑，未使用真实 B站账号验证联网字幕。正式应用未覆盖，版本仍为 0.9.18 / 9018，未提交或推送。

## 评论独立页面与平台回退（2026-11-17）

评论入口迁移到非浮动的 CommentsActivity，包含热门/最新、刷新、分页、头像、日期、正文、仅展示的灰色点赞数，以及带相同信息的缩进回复行。查看完整回复也打开普通 Activity，删除原 CommentsDialog。返回保留原搜索输入，重建保留 ViewModel、所选标签和列表位置；界面适配窄屏、宽屏、深色主题和大字号。

原音乐平台请求失败或评论为空时，按现有歌曲匹配规则尝试其他音乐平台，并显示实际来源；后续分页和回复固定来源，刷新重新尝试原平台。bilibili 始终读取当前视频，使用用户指定镜像文档的 WBI 主评论和回复接口，不加入跨平台回退。所有评论读取路径均不包含点赞、发布或追加回复操作。

收到仅使用手机 9dd0fd82 的限制后，后续安装与测试全部指定该序列号，使用独立的 cn.toside.music.nativeapp.commentscheck 包；未覆盖正式应用，也未继续操作另一台设备。最终 28 项设备检查通过，其中 26 项持久化回归覆盖六平台解析、头像/引用/大 ID、WBI 模式与游标、回退与来源固定、失败重试、取消隔离、计数切源清理、只读点赞、完整窗口触摸分发下的分页和回复入口、返回与重建、窄/宽屏和深色英文大字号；另外 2 项临时联网检查验证公开接口及真实页面。

真实联网结果：酷我 Monsters 热门 20 条、最新 20 条、下一页 20 条，首屏 17 条带头像；bilibili 公开视频 BV1GJ411x7h7 热门 20 条、最新 18 条、下一页 20 条、楼中楼 20 条，首屏 20 条带头像。bilibili 使用隔离包的匿名状态，本轮未验证登录账号或受限视频。跨平台回退使用固定数据验证，联网检查分别读取酷我与 bilibili，不将其视为真实跨平台切换验收。

52 项 JVM 测试、Debug 应用及测试包构建、Debug Lint（0 errors / 284 warnings）通过。最初触控用例被手机系统拒绝跨应用事件注入，已改为从应用窗口派发 MotionEvent 并验证完整视图分发，最终 28 项全部通过；早期失败不计为通过。最终记录为 build/comments-final-build.txt、build/comments-touch-build.txt、build/comments-final-phone-tests.txt 和 build/comments-phone-validation/live-comments.json；真实页面截图为同目录的 phone-live-kw.png、phone-live-bili.png。临时联网用例和隔离构建设置仅保存在忽略的 build/ 下，不加入日常离线回归。当前版本仍为 0.9.19 / 9019，本批改动尚未提交或推送。

## 评论单行标题、缩略图与图片详情（2026-11-17）

评论和回复标题最多一行，超长省略；刷新移至标题右侧，回复页整行隐藏热门/最新，消除空白工具栏。配图显示为可换行的页内缩略图，点击打开非导出的独立 CommentImageActivity，不再调用浏览器或图片 Dialog。

详情页默认按可用区域完整、等比居中显示，支持原生双指缩放、超出视口后的四向拖动、双击放大或恢复。能够完整显示的方向始终居中，超出的方向限制在图片边界内。重建页面保留同一图片 ViewModel、缩放倍数和可见中心，返回原评论 Activity。缩略图使用 256 px 解码上限，普通封面沿用 512 px 与原缓存键，详情使用独立缓存、4096 px 长边和 800 万像素解码上限；沿用有界网络读取、bilibili 图片请求头及失败重试。

60 项 JVM 测试通过，含 8 项新增 ImageViewport 测试，覆盖横/长/小图适配、焦点缩放、四边拖动约束、单轴居中、倍数边界、尺寸变化和无效输入。仅在手机 9dd0fd82 的独立 .commentscheck 包验证，另一台设备及正式应用未操作。31 项设备综合运行中的 27 项评论/标题检查及其他图片检查通过，最初图片手势断言错误地要求未超出视口的方向也能移动；随后按设备实际缩放启动阈值重复手势，明确放大到两轴均超出后验证拖动。最终单独复测的 4 项图片检查全部通过，覆盖尺寸缓存隔离及离线复用、缩略图到内部页面及返回、双指缩放/拖动/重建/双击恢复、加载失败重试及无效 URL 排除。早期综合运行整体失败，不记为整轮通过；当前 31 项各项均已有通过记录。

Debug 与测试包构建、Debug Lint（0 errors / 287 warnings）通过。记录为 build/comment-images-final-build.txt、build/comment-images-gesture-build.txt、build/comment-images-final-phone-tests.txt 和 build/comment-images-gesture-phone-tests.txt。已目视核对单行回复标题、缩略图、默认适配和放大后的截图，保存于 build/comment-images-validation/；截图图片为本地 HTTP 测试素材。本轮未新增真实平台联网验收，版本保持 0.9.19 / 9019，改动尚未提交。

## 评论图片大小设置与失败原因（2026-11-17）

手机截图确认问题来自张雨生《大海》（BV1pP4y1G7hU）第二页热门评论中的第三张海边照片，而非之前《悟空》的配图。手机上三张原图均返回 HTTP 200、JPEG，实际响应大小分别为 5,926,862、5,957,955、8,839,771 字节；第三张约 8.43 MiB，超过原来复用封面加载器的 8 MiB 限制，缩略图和详情均抛出“文件或响应内容过大”。评论 API 的 img_size 小于实际 CDN 响应，不用该字段代替真实下载限制。原始证据为 build/comment-image-current-before-fix.json；最初未找到评论的诊断尝试不计为成功，之后已按游标定位到对应条目。

通用设置新增「评论图片加载上限」，选项为 1、2、4、8、16、32 MiB，默认 16 MiB，缩略图和详情共用，重试重新读取偏好。普通封面下载仍保持 8 MiB。已知 Content-Length 超限时提前终止，未知长度仍有流式读取上限；缓存解码、下载大小与解码像素限制分开。图片详情保留 4096 px 长边、800 万像素上限。错误状态携带类型与状态码，使用本地化文案说明大小超限、HTTP、超时、DNS、安全连接、解码或连接中断；文案多行居中，限制宽度，四周保留 24 dp 间距。

60 项 JVM 测试、Debug 应用/测试包构建及 Debug Lint（0 errors / 287 warnings）通过。仅在手机 9dd0fd82 的隔离 .commentscheck 包完成 14 项检查：7 项评论页面、6 项图片/设置，以及 1 项临时真实配图诊断。新增回归使用超过 8 MiB 的有效 PNG，验证普通封面仍受限、缩略图按所选 32 MiB 成功、详情在用户选择 8 MiB 时显示实际大小与居中提示、调高至 16 MiB 后重试成功，以及设置菜单的偏好持久化。原 HTTP 503 重试用例同步检查具体状态码，原缩放/拖动/重建和标题检查通过。

修复后，原第三张图片在手机上成功加载为 252×189 缩略图和 2016×1512 详情图，真实 HTTP 状态与结果保存于 build/comment-image-current-after-fix.json。最终构建与设备日志为 build/comment-image-settings-build.txt、build/comment-image-settings-phone-tests.txt；居中错误示例已目视核对，保存于 build/comment-image-settings-validation/comment-image-size-error-centered.png，该示例使用本地测试图。未继续操作另一台设备，正式应用未覆盖；当前版本仍为 0.9.19 / 9019，改动尚未提交。

图片加载上限默认值按用户要求调整为 16 MiB，同步偏好回退、设置页默认选项、三种语言说明与文档，保留已有手动选择。60 项 JVM 测试、Debug/测试包构建及 Lint 通过；手机 9dd0fd82 隔离包的设置专项复测通过，包含独立空偏好下的 16 MiB 默认读取与设置保存断言。记录为 build/comment-image-default16-build.txt 和 build/comment-image-default16-phone-tests.txt，本次调整合并到最近一次本地提交。
