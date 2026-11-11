# 原生版本构建与分发

原生应用 ID 为 `cn.toside.music.nativeapp`，使用独立的 `native-vMAJOR.MINOR.PATCH` 更新通道。原 RN 安装包及其他包名、签名的 APK 无法作为此应用的更新安装。原版数据请通过歌单备份或同步服务转入。

## 本地构建

在仓库根目录使用 JDK 17、Android SDK 36 和 Gradle Wrapper：

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

版本号在仓库根目录的 `gradle.properties` 中指定，Debug 和 Release 共用：

```properties
nativeVersionName=0.1.0
nativeVersionCode=1
```

`nativeVersionName` 是显示版本号，格式为 `主版本.次版本.修订号`；`nativeVersionCode` 是内部版本号，必须为正整数，覆盖升级时必须递增。修改文件后直接执行构建命令，无需传入版本参数。测试包与正式包须保持各自的固定签名，正式分发勿使用调试密钥或验证目录内的临时密钥。

Release 签名仅从仓库根目录的 `local.properties` 读取。将 `NewKeyStrore.jks` 放到仓库根目录，在现有 `sdk.dir` 配置后追加以下内容，并填写实际密码和密钥别名：

```properties
NATIVE_KEYSTORE_PATH=NewKeyStrore.jks
NATIVE_STORE_PASSWORD=
NATIVE_KEY_ALIAS=
NATIVE_KEY_PASSWORD=
```

| 配置项 | 用途 |
| --- | --- |
| `NATIVE_KEYSTORE_PATH` | 签名库路径，相对仓库根目录或绝对路径；省略时默认 `NewKeyStrore.jks` |
| `NATIVE_STORE_PASSWORD` | 签名库密码 |
| `NATIVE_KEY_ALIAS` | 密钥别名 |
| `NATIVE_KEY_PASSWORD` | 密钥密码 |

```powershell
.\gradlew.bat :app:assembleRelease :app:lintRelease -PnativeSplitApks=true
```

Release APK 输出到 `app/build/outputs/apk/release/`，保留原文件名前缀，在 `.apk` 前追加架构。例如版本 `0.9.3`：

- `lx-music-native-v0.9.3-armv7.apk`：仅含 `armeabi-v7a`。
- `lx-music-native-v0.9.3-armv8.apk`：仅含 `arm64-v8a`。
- `lx-music-native-v0.9.3-all.apk`：包含依赖提供的全部 ABI，兼容 ARM 及 x86/x86_64。

只有显式传入 `-PnativeSplitApks=true` 时才拆分三个 APK；不传时仍生成一个 `-all.apk`，Debug 构建保持原来的单包行为。

缺少密码、密钥别名或签名文件时，Release 构建会失败，并提示缺失项和配置位置；不会生成未签名 Release，也不会自动改用调试签名。Debug 构建不要求 Release 签名配置。

`local.properties`、`*.jks` 和 `*.keystore` 均被 Git 忽略，不要提交密码或密钥。路径建议使用 `/`；配置值中的反斜杠需写成 `\\`，开头空格需写成 `\ `。签名库密码和密钥密码即使相同，也需分别填写。

## GitHub Releases 发布脚本

本地发布入口是 `scripts/publish-release.py`，需要 Python 3.9+、Git 和已登录的 GitHub CLI（`gh auth login`）。提交源码后运行脚本。脚本会在当前 HEAD 补齐版本标签并推送到 `origin`，由 GitHub Actions 构建、签名和上传 APK。本地试打包使用 `--prepare-only`，需要 JDK 17、SDK 36 和上述 Release 签名配置。

```powershell
# 发布 gradle.properties 中的版本，缺少标签时自动创建和推送。
python scripts/publish-release.py

# 指定版本和仓库，标签缺失时同样自动补齐。
python scripts/publish-release.py --tag native-v0.9.3 --repo savet-save/lx-music-android

# 使用已有的本地标签试打包并生成发布说明。
python scripts/publish-release.py --tag native-v0.9.3 --prepare-only
```

省略 `--tag` 时读取 `gradle.properties` 中的 `nativeVersionName`，省略 `--repo` 时读取 `GH_REPO`、`GITHUB_REPOSITORY` 或当前 GitHub 仓库。发布脚本沿用原工作流的版本策略：按标签覆盖构建版本，versionCode 为 `major * 1000000 + minor * 1000 + patch`（major ≤ 2000，minor、patch ≤ 999，结果 > 0）。例如 `native-v0.9.3` 对应显示版本 `0.9.3`、versionCode `9003`；独立手动构建同一正式版时应使用相同值。

标签同步默认使用 `origin`，可用 `--remote github` 指定其他 Git remote。该远程的拉取地址及唯一推送地址必须均对应所选 GitHub 仓库。本地已有标签但远程缺失时自动推送；远程已有同一提交的标签时自动获取并复用，支持轻量标签和附注标签。已有标签指向其他提交时停止，不覆盖或移动标签。网络、认证、写入或推送失败时，提示包含具体错误、仓库目录、创建命令 `git tag native-vVERSION COMMIT` 和推送命令 `git push origin refs/tags/native-vVERSION:refs/tags/native-vVERSION`；推送失败保留本地标签，排除原因后可直接重试。仅准备模式缺少标签时也会给出手动命令。

Actions 运行单元测试及 Release Lint，构建三个 APK，并验证包名、版本、ABI 内容和一致的签名证书。兼容 Build Tools 36 及以前的 `Signer #1` 和 37 的 `V2 Signer:` 等证书输出格式。签名验证失败、证书解析失败和不同 APK 证书不一致会分别停止。上传前再次核对工作区及远程标签仍对应本次发布提交。产物保存在 `build/releases/native-vVERSION/`，包括三个 APK、`SHA256SUMS.txt` 和 `RELEASE_NOTES.md`。

- Release 标题只有版本名，例如 `0.9.3`。
- `doc/release-notes/native-vVERSION.md` 中的简短说明作为「更新摘要」置于完整正文前；仍生成下述提交记录、比较链接和 APK SHA-256 表，并提供 `SHA256SUMS.txt` 下载附件。已有说明文件为空时停止发布；没有说明文件时直接生成完整记录。客户端接收原始正文后优先展示摘要，无摘要时提取有效改动、隐藏提交链接和校验表；「完整更新说明」打开原始发布页。
- 首个原生正式 Release 使用 README 的项目介绍，后面附三个 APK 的 SHA-256。
- 后续 Release 使用上一个原生正式 Release 标签以来的非合并提交记录，包含提交链接、完整比较链接和三个 APK 的 SHA-256。旧 RN Release、预发布和草稿不作为前一版本。
- 先创建草稿，再依次上传通用 APK、两个 ARM APK 和校验文件，核对上传状态、大小及 GitHub 返回的哈希后才公开发布。失败保留草稿，可重试；已公开的版本不覆盖。通用包先上传，兼容旧版应用按第一个 APK 更新的逻辑；新版应用优先选择设备对应的 ARM 包，缺少对应包时回退通用包，仍兼容旧版单 APK。

## Actions 发布

`.github/workflows/native-release.yml` 在新建 `native-v*` 标签推送时自动运行。发布前配置仓库 Secrets `NATIVE_KEYSTORE_BASE64`（签名库 Base64）、`NATIVE_STORE_PASSWORD`、`NATIVE_KEY_ALIAS`、`NATIVE_KEY_PASSWORD`，并让目标标签指向包含发布工作流的提交。脚本会输出 Actions 页面地址。

已经推送的标签可在 **Native Android release** 工作流中手动补发。需要保留草稿时，勾选 `draft` 或传入 `-f draft=true`。

```powershell
gh workflow run native-release.yml -f tag=native-v0.9.3
```

工作流获取完整标签历史。签名库和 `local.properties` 在构建时临时还原并清理，三个 APK、说明、校验文件和构建报告另外归档为 Actions artifact。

## 安装与测试

应用由 DownloadManager 下载更新，重建页面或退出应用后仍由系统调度。安装前会复制到私有目录，再检查大小、发布接口提供的 SHA-256、包名、版本名称、递增 versionCode 和兼容签名；最终安装需系统授权及确认。

Linux/CI 连接测试设备后可运行 `bash scripts/device-regression.sh`：自动准备正确/错误签名的升级测试包，推送至设备临时目录，使用正常基线版本运行设备回归。该脚本不操作系统安装确认界面。真实平台联网检查需额外显式传入 `liveProviders=true`；完整命令和验证范围见 [VALIDATION.md](VALIDATION.md)。
