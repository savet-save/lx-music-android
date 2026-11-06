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

统一入口为 `scripts/publish-release.py`，支持 Windows 和 Linux，需要 Python 3.9+、Git、GitHub CLI（`gh`）、JDK 17、SDK 36 和上述 Release 签名配置。本地使用先执行 `gh auth login`；CI 使用 `GH_TOKEN`。脚本不创建或推送 Git 标签，发布前需提交源码，并确保工作区 HEAD 与目标标签一致。

```powershell
# 已有远程标签、且当前检出该标签时，单独运行发布脚本。
python scripts/publish-release.py --tag native-v0.9.3 --repo savet-save/lx-music-android

# 仅构建和生成说明；不创建、上传或修改远程 Release，本地标签无需推送。
python scripts/publish-release.py --tag native-v0.9.3 --prepare-only

# 上传完整产物后保留为草稿。
python scripts/publish-release.py --tag native-v0.9.3 --draft
```

省略 `--tag` 时读取 `gradle.properties` 中的 `nativeVersionName`，省略 `--repo` 时读取 `GH_REPO`、`GITHUB_REPOSITORY` 或当前 GitHub 仓库。发布脚本沿用原工作流的版本策略：按标签覆盖构建版本，versionCode 为 `major * 1000000 + minor * 1000 + patch`（major ≤ 2000，minor、patch ≤ 999，结果 > 0）。例如 `native-v0.9.3` 对应显示版本 `0.9.3`、versionCode `9003`；独立手动构建同一正式版时应使用相同值。

脚本运行单元测试及 Release Lint，构建三个 APK，并验证包名、版本、ABI 内容和一致的签名证书。产物保存在 `build/releases/native-vVERSION/`，包括三个 APK、`SHA256SUMS.txt` 和 `RELEASE_NOTES.md`。

- Release 标题只有版本名，例如 `0.9.3`。
- 首个原生正式 Release 使用 README 的项目介绍，后面附三个 APK 的 SHA-256。
- 后续 Release 使用上一个原生正式 Release 标签以来的非合并提交记录，包含提交链接、完整比较链接和三个 APK 的 SHA-256。旧 RN Release、预发布和草稿不作为前一版本。
- 先创建草稿，再依次上传通用 APK、两个 ARM APK 和校验文件，核对上传状态、大小及 GitHub 返回的哈希后才公开发布。失败保留草稿，可重试；已公开的版本不覆盖。通用包先上传，兼容旧版应用按第一个 APK 更新的逻辑；新版应用优先选择设备对应的 ARM 包，缺少对应包时回退通用包，仍兼容旧版单 APK。

## Actions 自动发布

`.github/workflows/native-release.yml` 响应 `native-v*` 标签，也支持在 Actions 页面手动指定已有标签。配置仓库 Secrets `NATIVE_KEYSTORE_BASE64`（签名库 Base64）、`NATIVE_STORE_PASSWORD`、`NATIVE_KEY_ALIAS`、`NATIVE_KEY_PASSWORD` 后，提交并推送代码，再推送发布标签：

```powershell
git tag native-v0.9.3
git push origin native-v0.9.3
```

工作流获取完整标签历史，运行同一个发布脚本，完成后自动公开 Release。手动触发时勾选 `draft` 可保留草稿。一次发布选择 Actions 或本地脚本之一即可。签名库和 `local.properties` 仍只在构建时临时还原并清理，三个 APK、说明、校验文件和构建报告另外归档为 Actions artifact。本次开发验证不创建远程 Release。

## 安装与测试

应用由 DownloadManager 下载更新，重建页面或退出应用后仍由系统调度。安装前会复制到私有目录，再检查大小、发布接口提供的 SHA-256、包名、版本名称、递增 versionCode 和兼容签名；最终安装需系统授权及确认。

Linux/CI 连接测试设备后可运行 `bash scripts/device-regression.sh`：自动准备正确/错误签名的升级测试包，推送至设备临时目录，使用正常基线版本运行设备回归。该脚本不操作系统安装确认界面。真实平台联网检查需额外显式传入 `liveProviders=true`；完整命令和验证范围见 [VALIDATION.md](VALIDATION.md)。
