#!/usr/bin/env python3
"""Build, verify and publish the three signed native Android release APKs."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
from urllib.parse import quote
import zipfile

ROOT = Path(__file__).resolve().parent.parent
TAG_PATTERN = re.compile(r"native-v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)")
ARCHITECTURES = {"all": None, "armv7": "armeabi-v7a", "armv8": "arm64-v8a"}


def run(arguments, *, capture=True):
    result = subprocess.run(arguments, cwd=ROOT, check=True, text=True, encoding="utf-8",
                            stdout=subprocess.PIPE if capture else None)
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


def verify_checkout(tag, require_clean):
    commit = run(["git", "rev-parse", "HEAD"])
    if commit != run(["git", "rev-parse", "--verify", f"{tag}^{{commit}}"]):
        raise ValueError("Check out the release tag before running this script")
    if require_clean and run(["git", "status", "--porcelain"]):
        raise ValueError("Commit your changes before publishing a release")
    return commit


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
        signers = set(re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([a-fA-F0-9]{64})", verification))
        if not signers or (certificates is not None and signers != certificates):
            raise ValueError(f"APKs must have the same valid signing certificate: {name}")
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
    parser.add_argument("--draft", action="store_true", help="Keep the complete release as a draft")
    parser.add_argument("--prepare-only", action="store_true", help="Build APKs and notes without creating or modifying a remote release")
    options = parser.parse_args()
    tag = options.tag or default_tag()
    version, code = version_from_tag(tag)
    if not shutil.which("gh"):
        raise ValueError("GitHub CLI is required. Install gh and run gh auth login, or set GH_TOKEN in CI")
    repo = options.repo or os.environ.get("GH_REPO") or os.environ.get("GITHUB_REPOSITORY")
    repo = repo or json.loads(run(["gh", "repo", "view", "--json", "nameWithOwner"]))["nameWithOwner"]
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("Repository must be OWNER/REPO")
    commit = verify_checkout(tag, not options.prepare_only)
    if not options.prepare_only:
        run(["gh", "api", f"repos/{repo}/git/ref/tags/{tag}"])
    releases = get_releases(repo)
    existing = next((release for release in releases if release["tag_name"] == tag), None)
    if existing and not existing["draft"] and not options.prepare_only:
        raise ValueError(f"Release {tag} is already published")
    previous = previous_release(releases, tag)
    apksigner = find_apksigner()
    build_apks(version, code)
    apks = verify_apks(version, code, apksigner)
    output = ROOT / "build/releases" / tag
    checksums = prepare_artifacts(repo, tag, previous, apks, output)
    print(f"Prepared {len(apks)} signed APKs: {output}")
    print(f"Title: {version}; notes: {'changes since ' + previous if previous else 'project introduction'}")
    if not options.prepare_only:
        if verify_checkout(tag, True) != commit:
            raise ValueError("Release checkout changed during the build; nothing was uploaded")
        publish(repo, tag, version, existing, output, checksums, options.draft)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, KeyError, zipfile.BadZipFile) as error:
        print(f"Release failed: {error}", file=sys.stderr)
        sys.exit(1)
