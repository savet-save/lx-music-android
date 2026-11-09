#!/usr/bin/env python3
"""Push a native release tag locally, or build and publish its APKs in GitHub Actions."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
from urllib.parse import quote, urlsplit
import zipfile

ROOT = Path(__file__).resolve().parent.parent
TAG_PATTERN = re.compile(r"native-v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
# Build Tools 37 adds scheme prefixes and SDK ranges to the signer certificate labels.
SIGNER_SHA256_PATTERN = re.compile(
    r"^(?:V\d+(?:\.\d+)? (?:Hybrid (?:Classical|PQC) )?)?"
    r"Signer(?: #\d+)?(?::(?: \([^\r\n]*\))?)?"
    r" certificate SHA-256 digest:[ \t]*([a-fA-F0-9]{64})[ \t\r]*$", re.MULTILINE
)
ARCHITECTURES = {"all": None, "armv7": "armeabi-v7a", "armv8": "arm64-v8a"}


def run(arguments, *, capture=True):
    result = subprocess.run(arguments, cwd=ROOT, check=True, text=True, encoding="utf-8",
                            stdout=subprocess.PIPE if capture else None,
                            stderr=subprocess.PIPE if capture else None)
    return result.stdout.strip() if capture else ""


def version_from_tag(tag):
    match = TAG_PATTERN.fullmatch(tag)
    if not match:
        raise ValueError("Tag must be native-vMAJOR.MINOR.PATCH")
    major, minor, patch = map(int, match.groups())
    code = major * 1_000_000 + minor * 1_000 + patch
    if not (major <= 2000 and minor <= 999 and patch <= 999 and 0 < code <= 2_100_000_000):
        raise ValueError("Version is outside the supported Android versionCode range")
    return tag.removeprefix("native-v"), code


def default_tag():
    properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")
    match = re.search(r"(?m)^nativeVersionName\s*=\s*(\S+)\s*$", properties)
    if not match:
        raise ValueError("Missing nativeVersionName in gradle.properties")
    return "native-v" + match.group(1)


def command_text(arguments):
    if isinstance(arguments, str):
        return arguments
    if os.name != "nt":
        return shlex.join(arguments)
    return " ".join(value if re.fullmatch(r"[A-Za-z0-9_./:=@+-]+", value)
                    else "'" + value.replace("'", "''") + "'" for value in arguments)


def command_failure(error):
    if not isinstance(error, subprocess.CalledProcessError):
        return str(error)
    message = f"命令执行失败（退出码 {error.returncode}）：{command_text(error.cmd)}"
    details = error.stderr or error.stdout
    return message + ("\n" + details.strip() if details else "")


def tag_error(reason, tag, remote="origin", commit="HEAD"):
    directory = command_text(["Set-Location", "-LiteralPath", str(ROOT)] if os.name == "nt" else ["cd", str(ROOT)])
    create = command_text(["git", "tag", tag, commit])
    push = command_text(["git", "push", remote, f"refs/tags/{tag}:refs/tags/{tag}"])
    return ValueError(f"{reason}\n\n请先处理上述原因，再在发布目录中执行：\n  {directory}\n"
                      f"  git status --short\n"
                      f"创建标签（仅在本地尚无该标签时）：\n  {create}\n"
                      f"推送标签：\n  {push}\n"
                      "已有标签请先核对其提交；若版本已被占用，请使用新的版本号和标签。脚本不会覆盖已有标签。")


def verify_worktree(tag, require_clean, remote="origin"):
    commit = "HEAD"
    try:
        commit = run(["git", "rev-parse", "HEAD"])
        if require_clean and run(["git", "status", "--porcelain"]):
            raise tag_error("工作区存在未提交修改，请先提交要发布的改动。脚本不会自动提交。", tag, remote, commit)
    except (OSError, subprocess.CalledProcessError) as error:
        raise tag_error(command_failure(error), tag, remote, commit) from error
    return commit


def local_tag_commit(tag):
    try:
        run(["git", "rev-parse", "--verify", "--quiet", f"refs/tags/{tag}"])
    except subprocess.CalledProcessError as error:
        if error.returncode == 1:
            return None
        raise
    return run(["git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"])


def verify_checkout(tag, require_clean, remote="origin"):
    commit = verify_worktree(tag, require_clean, remote)
    try:
        target = local_tag_commit(tag)
    except (OSError, subprocess.CalledProcessError) as error:
        raise tag_error(command_failure(error), tag, remote, commit) from error
    if target is None:
        raise tag_error(f"本地缺少标签 {tag}。如使用仅准备模式，需先补齐本地标签；该模式不创建或推送标签。", tag, remote, commit)
    if target != commit:
        raise tag_error(f"本地标签 {tag} 指向 {target}，当前 HEAD 为 {commit}。请检出正确的版本提交或使用新版本。", tag, remote, commit)
    return commit


def github_repository(url):
    if re.match(r"git@github\.com:", url, re.IGNORECASE):
        path = url.split(":", 1)[1]
    else:
        parts = urlsplit(url)
        if parts.scheme not in ("https", "ssh") or parts.hostname not in ("github.com", "ssh.github.com"):
            return None
        path = parts.path.lstrip("/")
    path = path.rstrip("/").removesuffix(".git")
    return path.casefold() if re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", path) else None


def verify_release_remote(repo, remote):
    try:
        fetch = run(["git", "remote", "get-url", remote])
        pushes = run(["git", "remote", "get-url", "--push", "--all", remote]).splitlines()
    except (OSError, subprocess.CalledProcessError) as error:
        setup = command_text(["git", "remote", "add", remote, f"git@github.com:{repo}.git"])
        raise ValueError(f"无法读取 Git remote {remote}。\n{command_failure(error)}\n"
                         f"若尚未配置此远程，可先执行：\n  {setup}") from error
    if len(pushes) != 1 or any(github_repository(url) != repo.casefold() for url in [fetch, *pushes]):
        raise ValueError(f"Git remote {remote} 的拉取地址和唯一推送地址必须都指向 github.com/{repo}。"
                         "请检查 git remote -v，或用 --remote 指定对应的 Git remote。")


def remote_tag_commit(tag, remote):
    ref = f"refs/tags/{tag}"
    try:
        output = run(["git", "ls-remote", "--tags", "--exit-code", remote, ref, ref + "^{}"])
    except subprocess.CalledProcessError as error:
        if error.returncode == 2:
            return None
        raise
    refs = {name: sha for sha, name in (line.split() for line in output.splitlines())}
    return refs.get(ref + "^{}", refs.get(ref))


def verify_remote_release_tag(tag, commit, remote):
    try:
        target = remote_tag_commit(tag, remote)
    except (OSError, subprocess.CalledProcessError) as error:
        raise tag_error("无法确认远程发布标签。\n" + command_failure(error), tag, remote, commit) from error
    if target != commit:
        raise tag_error(f"远程标签 {tag} 已缺失或指向其他提交，预期为 {commit}，已停止发布。", tag, remote, commit)


def ensure_release_tag(tag, repo, commit, remote="origin"):
    try:
        if verify_worktree(tag, True, remote) != commit:
            raise tag_error("标签同步前 HEAD 已改变，请重新运行发布脚本。", tag, remote, commit)
        target = local_tag_commit(tag)
        if target is not None and target != commit:
            raise tag_error(f"本地标签 {tag} 指向 {target}，当前 HEAD 为 {commit}。", tag, remote, commit)
        try:
            verify_release_remote(repo, remote)
        except ValueError as error:
            raise tag_error(str(error), tag, f"git@github.com:{repo}.git", commit) from error
        published = remote_tag_commit(tag, remote)
        if published is not None and published != commit:
            raise tag_error(f"远程标签 {tag} 指向 {published}，当前 HEAD 为 {commit}。", tag, remote, commit)
        if target is None:
            if published is None:
                run(["git", "tag", tag, commit])
                print(f"已创建本地标签 {tag} -> {commit}")
            else:
                run(["git", "fetch", "--no-tags", remote, f"refs/tags/{tag}:refs/tags/{tag}"])
                print(f"已获取远程标签 {tag}")
        if verify_checkout(tag, True, remote) != commit:
            raise tag_error("标签同步期间 HEAD 已改变，请重新运行发布脚本。", tag, remote, commit)
        if published is None:
            run(["git", "push", remote, f"refs/tags/{tag}:refs/tags/{tag}"])
            print(f"已推送标签 {tag} 到 {remote}")
        verify_remote_release_tag(tag, commit, remote)
        print(f"发布标签已就绪：{tag} -> {commit}（{remote}）")
        return published is None
    except (OSError, subprocess.CalledProcessError) as error:
        raise tag_error("无法同步发布标签。\n" + command_failure(error), tag, remote, commit) from error


def previous_release(releases, tag):
    current_version, _ = version_from_tag(tag)
    current = tuple(map(int, current_version.split(".")))
    candidates = []
    for release in releases:
        name = release.get("tag_name", "")
        if name == tag or release.get("draft") or release.get("prerelease") or not TAG_PATTERN.fullmatch(name):
            continue
        try:
            version, _ = version_from_tag(name)
        except ValueError:
            continue
        parts = tuple(map(int, version.split(".")))
        if parts >= current:
            raise ValueError(f"A newer native release already exists: {name}")
        candidates.append((parts, name))
    return max(candidates)[1] if candidates else None


def get_releases(repo):
    pages = json.loads(run(["gh", "api", "--paginate", "--slurp", f"repos/{repo}/releases?per_page=100"]))
    return [release for page in pages for release in page]


def find_apksigner():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk and (ROOT / "local.properties").is_file():
        # Read only the SDK path; never print or forward signing credentials.
        for line in (ROOT / "local.properties").read_text(encoding="utf-8").splitlines():
            key, separator, value = line.partition("=")
            if separator and key.strip() == "sdk.dir":
                sdk = value.strip().replace("\\:", ":").replace("\\\\", "\\")
                break
    if not sdk:
        raise ValueError("Set ANDROID_HOME or sdk.dir in local.properties")
    executable = "apksigner.bat" if os.name == "nt" else "apksigner"
    candidates = [path / executable for path in (Path(sdk) / "build-tools").iterdir()
                  if re.fullmatch(r"\d+\.\d+\.\d+", path.name) and (path / executable).is_file()]
    if not candidates:
        raise ValueError("No Android SDK Build Tools apksigner found")
    return str(max(candidates, key=lambda path: tuple(map(int, path.parent.name.split(".")))))


def build_apks(version, code):
    gradle = [str(ROOT / "gradlew.bat")] if os.name == "nt" else ["bash", "./gradlew"]
    run(gradle + ["--no-daemon", ":app:testDebugUnitTest", ":app:lintRelease", ":app:assembleRelease",
                  "-PnativeSplitApks=true", f"-PnativeVersionName={version}", f"-PnativeVersionCode={code}"], capture=False)


def verify_apks(version, code, apksigner):
    directory = ROOT / "app/build/outputs/apk/release"
    metadata = json.loads((directory / "output-metadata.json").read_text(encoding="utf-8"))
    if metadata.get("applicationId") != "cn.toside.music.nativeapp":
        raise ValueError("Release APK applicationId does not match the native app")
    elements = metadata["elements"]
    expected_names = {f"lx-music-native-v{version}-{architecture}.apk" for architecture in ARCHITECTURES}
    if len(elements) != 3 or {item["outputFile"] for item in elements} != expected_names:
        raise ValueError("Expected exactly armv7, armv8 and all release APK outputs")
    certificates = None
    result = {}
    for architecture, abi in ARCHITECTURES.items():
        name = f"lx-music-native-v{version}-{architecture}.apk"
        item = next(value for value in elements if value["outputFile"] == name)
        filters = item.get("filters", [])
        if item.get("versionName") != version or item.get("versionCode") != code:
            raise ValueError(f"Stale or mismatched APK version: {name}")
        expected_filters = [{"filterType": "ABI", "value": abi}] if abi else []
        if filters != expected_filters:
            raise ValueError(f"Wrong ABI metadata: {name}")
        path = directory / name
        with zipfile.ZipFile(path) as apk:
            packaged = {name.split("/")[1] for name in apk.namelist() if name.startswith("lib/") and name.endswith(".so")}
        if (abi and packaged != {abi}) or (not abi and not {"armeabi-v7a", "arm64-v8a"}.issubset(packaged)):
            raise ValueError(f"Wrong native libraries in {name}: {sorted(packaged)}")
        verification = run([apksigner, "verify", "--verbose", "--print-certs", str(path)])
        signers = {digest.lower() for digest in SIGNER_SHA256_PATTERN.findall(verification)}
        if not signers:
            raise ValueError(f"Could not read APK signing certificate SHA-256 for {name} from {apksigner}")
        if certificates is not None and signers != certificates:
            raise ValueError(f"APK signing certificates differ: {name}")
        certificates = signers
        result[architecture] = path
    return result


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def release_notes(repo, tag, previous, checksums):
    page = f"https://github.com/{repo}"
    if previous:
        run(["git", "rev-parse", "--verify", f"{previous}^{{commit}}"])
        commits = run(["git", "log", "--reverse", "--no-merges", "--format=%H%x09%s", f"{previous}..{tag}"])
        changes = []
        for line in commits.splitlines():
            commit, _, subject = line.partition("\t")
            subject = subject or "（无提交说明）"
            changes.append(f"- {subject} ([{commit[:7]}]({page}/commit/{commit}))")
        body = "## 更新记录\n\n" + ("\n".join(changes) or "本版本无新增提交。")
        body += f"\n\n[完整改动]({page}/compare/{quote(previous, safe='')}...{quote(tag, safe='')})"
    else:
        body = (ROOT / "README.md").read_text(encoding="utf-8").split("## 构建与验证", 1)[0].strip()
        body += f"\n\n支持 Android 9 及以上版本。[使用说明]({page}/blob/{quote(tag, safe='')}/DEVELOPMENT.md)"
    body += "\n\n## APK SHA-256\n\n| 文件 | SHA-256 |\n| --- | --- |\n"
    for name, digest in checksums.items():
        body += f"| [{name}]({page}/releases/download/{quote(tag, safe='')}/{quote(name, safe='')}) | `{digest}` |\n"
    return body


def prepare_artifacts(repo, tag, previous, apks, output):
    output.mkdir(parents=True, exist_ok=True)
    checksums = {}
    for path in apks.values():
        staged = output / path.name
        shutil.copy2(path, staged)
        checksums[staged.name] = sha256(staged)
    (output / "SHA256SUMS.txt").write_bytes("".join(f"{digest}  {name}\n" for name, digest in checksums.items()).encode("utf-8"))
    (output / "RELEASE_NOTES.md").write_bytes(release_notes(repo, tag, previous, checksums).encode("utf-8"))
    return checksums


def publish(repo, tag, version, existing, output, checksums, keep_draft):
    arguments = ["--repo", repo]
    if existing:
        if not existing.get("draft"):
            raise ValueError(f"Release {tag} is already published; published assets are never overwritten")
        existing = next((item for item in get_releases(repo) if item["tag_name"] == tag), None)
        if not existing or not existing.get("draft"):
            raise ValueError("Release draft changed during the build; nothing was uploaded")
        # All must remain first so older app versions, which select the first APK, can update safely.
        old_apks = [asset["name"] for asset in existing.get("assets", []) if asset["name"].endswith(".apk")]
        if any(name not in checksums for name in old_apks):
            raise ValueError("Existing draft contains unrelated APKs; remove that draft before retrying")
        run(["gh", "release", "edit", tag, *arguments, "--title", version, "--notes-file", str(output / "RELEASE_NOTES.md")])
        # Replacing only all.apk would move it behind existing ARM assets. Reset our draft APKs first.
        for name in old_apks:
            run(["gh", "release", "delete-asset", tag, name, *arguments, "--yes"])
    else:
        run(["gh", "release", "create", tag, *arguments, "--verify-tag", "--draft", "--title", version,
             "--notes-file", str(output / "RELEASE_NOTES.md")])
    paths = [output / name for name in checksums]
    run(["gh", "release", "upload", tag, str(paths[0]), *arguments, "--clobber"])
    run(["gh", "release", "upload", tag, *(str(path) for path in paths[1:]), str(output / "SHA256SUMS.txt"), *arguments, "--clobber"])
    uploaded = next((release for release in get_releases(repo) if release["tag_name"] == tag), None)
    if not uploaded or not uploaded.get("draft"):
        raise ValueError("Release draft could not be verified; publication was not requested")
    assets = {asset["name"]: asset for asset in uploaded["assets"]}
    for name, digest in checksums.items():
        asset = assets.get(name)
        if not asset or asset.get("size") != (output / name).stat().st_size or asset.get("state") != "uploaded":
            raise ValueError(f"Uploaded asset is incomplete: {name}; release was left as a draft")
        if asset.get("digest") and asset["digest"] != "sha256:" + digest:
            raise ValueError(f"Uploaded asset hash differs: {name}; release was left as a draft")
    checksum_asset = assets.get("SHA256SUMS.txt")
    if not checksum_asset or checksum_asset.get("state") != "uploaded" or checksum_asset.get("size") != (output / "SHA256SUMS.txt").stat().st_size:
        raise ValueError("Uploaded SHA256SUMS.txt is incomplete; release was left as a draft")
    if not keep_draft:
        run(["gh", "release", "edit", tag, *arguments, "--draft=false", "--latest"])
    print(uploaded["html_url"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", help="native-vMAJOR.MINOR.PATCH; defaults to nativeVersionName")
    parser.add_argument("--repo", help="OWNER/REPO; defaults to GH_REPO, GITHUB_REPOSITORY or the current repository")
    parser.add_argument("--remote", default="origin", help="Git remote used to fetch and push release tags (default: origin)")
    parser.add_argument("--draft", action="store_true", help="Keep the complete release as a draft when run by Actions")
    parser.add_argument("--prepare-only", action="store_true", help="Build APKs and notes without creating or modifying a remote release")
    options = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", options.remote):
        raise ValueError("--remote must be a Git remote name, for example origin")
    tag = options.tag or default_tag()
    version, code = version_from_tag(tag)
    if not shutil.which("gh"):
        raise ValueError("GitHub CLI (gh) is required. Install gh, then run gh auth login locally or set GH_TOKEN in CI")
    repo = options.repo or os.environ.get("GH_REPO") or os.environ.get("GITHUB_REPOSITORY")
    repo = repo or json.loads(run(["gh", "repo", "view", "--json", "nameWithOwner"]))["nameWithOwner"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Repository must be OWNER/REPO")
    local_handoff = not options.prepare_only and os.environ.get("GITHUB_ACTIONS") != "true"
    dispatch = command_text(["gh", "workflow", "run", "native-release.yml", "--repo", repo, "-f", f"tag={tag}"])
    if local_handoff and options.draft:
        raise ValueError(f"草稿发布请为已有远程标签手动运行 Actions：\n  {dispatch} -f draft=true")
    if local_handoff:
        commit = verify_worktree(tag, True, options.remote)
    else:
        commit = verify_checkout(tag, not options.prepare_only, options.remote)
    releases = get_releases(repo)
    existing = next((release for release in releases if release["tag_name"] == tag), None)
    if existing and not existing["draft"] and not options.prepare_only:
        raise ValueError(f"Release {tag} is already published")
    previous = previous_release(releases, tag)
    if local_handoff:
        pushed = ensure_release_tag(tag, repo, commit, options.remote)
        if pushed:
            print(f"标签 {tag} 已推送，由 GitHub Actions 构建和上传。")
        else:
            print(f"远程标签 {tag} 已存在，本次没有新的推送事件。若没有发布任务运行，可手动启动：\n  {dispatch}")
        print(f"https://github.com/{repo}/actions/workflows/native-release.yml")
        return
    if not options.prepare_only:
        verify_release_remote(repo, options.remote)
        verify_remote_release_tag(tag, commit, options.remote)
    apksigner = find_apksigner()
    build_apks(version, code)
    apks = verify_apks(version, code, apksigner)
    output = ROOT / "build/releases" / tag
    checksums = prepare_artifacts(repo, tag, previous, apks, output)
    print(f"Prepared {len(apks)} signed APKs: {output}")
    print(f"Title: {version}; notes: {'changes since ' + previous if previous else 'project introduction'}")
    if not options.prepare_only:
        if verify_checkout(tag, True, options.remote) != commit:
            raise ValueError("Release checkout changed during the build; nothing was uploaded")
        verify_remote_release_tag(tag, commit, options.remote)
        publish(repo, tag, version, existing, output, checksums, options.draft)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, KeyError, zipfile.BadZipFile) as error:
        print(f"Release failed: {command_failure(error)}", file=sys.stderr)
        sys.exit(1)
