import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("publish_release", ROOT / "scripts/publish-release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class PublishReleaseTest(unittest.TestCase):
    def setUp(self):
        temporary = ROOT / "build/release-script-tests"
        temporary.mkdir(parents=True, exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=temporary)
        self.root = Path(self.directory.name)
        self.root_patch = patch.object(release, "ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.addCleanup(self.directory.cleanup)
        (self.root / "README.md").write_text("# 项目介绍\n\n原生音乐播放器。\n\n## 构建与验证\n不属于发布介绍。", encoding="utf-8")

    def artifacts(self):
        output = self.root / "app/build/outputs/apk/release"
        output.mkdir(parents=True)
        apks = {}
        elements = []
        for architecture, abi in release.ARCHITECTURES.items():
            name = f"lx-music-native-v0.9.3-{architecture}.apk"
            path = output / name
            with zipfile.ZipFile(path, "w") as apk:
                for value in [abi] if abi else ["armeabi-v7a", "arm64-v8a", "x86", "x86_64"]:
                    apk.writestr(f"lib/{value}/libfixture.so", b"native fixture")
            apks[architecture] = path
            elements.append({"outputFile": name, "versionName": "0.9.3", "versionCode": 9003,
                             "filters": [{"filterType": "ABI", "value": abi}] if abi else []})
        (output / "output-metadata.json").write_text(json.dumps({"applicationId": "cn.toside.music.nativeapp", "elements": elements}), encoding="utf-8")
        return apks

    def prepared(self):
        output = self.root / "prepared"
        checksums = release.prepare_artifacts("owner/repo", "native-v0.9.3", None, self.artifacts(), output)
        return output, checksums

    def uploaded(self, output, checksums):
        assets = [{"name": name, "size": (output / name).stat().st_size, "state": "uploaded", "digest": "sha256:" + digest}
                  for name, digest in checksums.items()]
        assets.append({"name": "SHA256SUMS.txt", "state": "uploaded", "size": (output / "SHA256SUMS.txt").stat().st_size})
        return {"tag_name": "native-v0.9.3", "draft": True, "assets": assets, "html_url": "https://github.com/owner/repo/releases/tag/native-v0.9.3"}

    def test_tag_version_policy_and_rejections(self):
        self.assertEqual(("0.9.3", 9003), release.version_from_tag("native-v0.9.3"))
        for value in ["v0.9.3", "native-v01.2.3", "native-v0.9.3-beta", "native-v0.0.0", "native-v2001.0.0", "native-v1.1000.0"]:
            with self.subTest(tag=value), self.assertRaises(ValueError):
                release.version_from_tag(value)

    def test_previous_release_ignores_rn_drafts_previews_and_current_tag(self):
        values = [{"tag_name": "v99.0.0"}, {"tag_name": "native-v0.9.2"}, {"tag_name": "native-v0.9.1"},
                  {"tag_name": "native-v0.9.3", "draft": True}, {"tag_name": "native-v1.0.0", "draft": True},
                  {"tag_name": "native-v1.0.0", "prerelease": True}]
        self.assertEqual("native-v0.9.2", release.previous_release(values, "native-v0.9.3"))
        self.assertIsNone(release.previous_release(values[:1], "native-v0.9.3"))
        with self.assertRaises(ValueError):
            release.previous_release([{"tag_name": "native-v1.0.0"}], "native-v0.9.3")

    def test_first_release_uses_project_intro_and_actual_file_hashes(self):
        output, checksums = self.prepared()
        body = (output / "RELEASE_NOTES.md").read_text(encoding="utf-8")
        self.assertIn("原生音乐播放器", body)
        self.assertNotIn("不属于发布介绍", body)
        self.assertNotIn("更新记录", body)
        for name, digest in checksums.items():
            self.assertEqual(release.sha256(output / name), digest)
            self.assertIn(name, body)
            self.assertIn(digest, body)
            self.assertIn(f"{digest}  {name}\n", (output / "SHA256SUMS.txt").read_text())

    def test_subsequent_release_uses_real_git_range_and_no_project_intro(self):
        def git(*arguments):
            return subprocess.run(["git", *arguments], cwd=self.root, check=True, capture_output=True, text=True).stdout.strip()
        git("init", "-q")
        git("config", "user.name", "Release test")
        git("config", "user.email", "release-test@example.invalid")
        git("add", "README.md")
        git("commit", "-qm", "Initial project")
        git("tag", "native-v0.9.2")
        git("commit", "--allow-empty", "-qm", "Fix playback")
        git("commit", "--allow-empty", "-qm", "Add bundled source")
        git("tag", "native-v0.9.3")
        body = release.release_notes("owner/repo", "native-v0.9.3", "native-v0.9.2", {"all.apk": "a" * 64})
        self.assertIn("Fix playback", body)
        self.assertIn("Add bundled source", body)
        self.assertIn("/compare/native-v0.9.2...native-v0.9.3", body)
        self.assertNotIn("Initial project", body)
        self.assertNotIn("原生音乐播放器", body)

    def test_verification_checks_versions_abis_and_common_signer(self):
        self.artifacts()
        certificate = "Signer #1 certificate SHA-256 digest: " + "a" * 64
        with patch.object(release, "run", return_value=certificate):
            self.assertEqual(3, len(release.verify_apks("0.9.3", 9003, "apksigner")))
            with self.assertRaisesRegex(ValueError, "version"):
                release.verify_apks("0.9.3", 93, "apksigner")
        with patch.object(release, "run", side_effect=[certificate, certificate.replace("a" * 64, "b" * 64)]):
            with self.assertRaisesRegex(ValueError, "same valid signing"):
                release.verify_apks("0.9.3", 9003, "apksigner")

    def test_wrong_native_libraries_are_rejected(self):
        apks = self.artifacts()
        with zipfile.ZipFile(apks["armv7"], "w") as apk:
            apk.writestr("lib/arm64-v8a/libfixture.so", b"wrong architecture")
        with patch.object(release, "run", return_value="Signer #1 certificate SHA-256 digest: " + "a" * 64):
            with self.assertRaisesRegex(ValueError, "Wrong native libraries"):
                release.verify_apks("0.9.3", 9003, "apksigner")

    def test_uploads_all_first_and_only_publishes_after_verification(self):
        output, checksums = self.prepared()
        remote = self.uploaded(output, checksums)
        with patch.object(release, "run", return_value="") as command, patch.object(release, "get_releases", return_value=[remote]):
            release.publish("owner/repo", "native-v0.9.3", "0.9.3", None, output, checksums, False)
        calls = [call.args[0] for call in command.call_args_list]
        self.assertIn("--draft", calls[0])
        self.assertEqual("0.9.3", calls[0][calls[0].index("--title") + 1])
        self.assertTrue(calls[1][4].endswith("-all.apk"))
        self.assertIn("--draft=false", calls[-1])

    def test_failed_upload_or_bad_server_digest_leaves_draft(self):
        output, checksums = self.prepared()
        remote = self.uploaded(output, checksums)
        with patch.object(release, "run", side_effect=["", subprocess.CalledProcessError(1, "gh")]) as command:
            with self.assertRaises(subprocess.CalledProcessError):
                release.publish("owner/repo", "native-v0.9.3", "0.9.3", None, output, checksums, False)
        self.assertFalse(any("--draft=false" in call.args[0] for call in command.call_args_list))
        remote["assets"][0]["digest"] = "sha256:" + "0" * 64
        with patch.object(release, "run", return_value="") as command, patch.object(release, "get_releases", return_value=[remote]):
            with self.assertRaisesRegex(ValueError, "hash differs"):
                release.publish("owner/repo", "native-v0.9.3", "0.9.3", None, output, checksums, False)
        self.assertFalse(any("--draft=false" in call.args[0] for call in command.call_args_list))

    def test_missing_asset_or_checksum_file_leaves_draft(self):
        output, checksums = self.prepared()
        for missing in [0, -1]:
            remote = self.uploaded(output, checksums)
            remote["assets"].pop(missing)
            with patch.object(release, "run", return_value="") as command, patch.object(release, "get_releases", return_value=[remote]):
                with self.assertRaises(ValueError):
                    release.publish("owner/repo", "native-v0.9.3", "0.9.3", None, output, checksums, False)
            self.assertFalse(any("--draft=false" in call.args[0] for call in command.call_args_list))

    def test_published_releases_are_not_modified_and_draft_option_is_respected(self):
        output, checksums = self.prepared()
        with patch.object(release, "run") as command, self.assertRaises(ValueError):
            release.publish("owner/repo", "native-v0.9.3", "0.9.3", {"draft": False}, output, checksums, False)
        command.assert_not_called()
        remote = self.uploaded(output, checksums)
        with patch.object(release, "run", return_value="") as command, patch.object(release, "get_releases", return_value=[remote]):
            release.publish("owner/repo", "native-v0.9.3", "0.9.3", remote, output, checksums, True)
        self.assertFalse(any("--draft=false" in call.args[0] for call in command.call_args_list))
        calls = [call.args[0] for call in command.call_args_list]
        self.assertEqual(3, sum(call[2] == "delete-asset" for call in calls))
        uploads = [call for call in calls if call[2] == "upload"]
        self.assertTrue(uploads[0][4].endswith("-all.apk"))

    def test_draft_published_by_another_operator_is_not_modified(self):
        output, checksums = self.prepared()
        remote = self.uploaded(output, checksums)
        published = dict(remote, draft=False)
        with patch.object(release, "run") as command, patch.object(release, "get_releases", return_value=[published]), \
                self.assertRaisesRegex(ValueError, "changed during the build"):
            release.publish("owner/repo", "native-v0.9.3", "0.9.3", remote, output, checksums, False)
        command.assert_not_called()

    def test_prepare_only_cli_builds_notes_without_remote_writes(self):
        apks = self.artifacts()
        arguments = ["publish-release.py", "--tag", "native-v0.9.3", "--repo", "owner/repo", "--prepare-only"]
        with patch.object(release.sys, "argv", arguments), patch.object(release.shutil, "which", return_value="gh"), \
                patch.object(release, "run", return_value="commit") as command, \
                patch.object(release, "get_releases", return_value=[]), \
                patch.object(release, "find_apksigner", return_value="apksigner"), \
                patch.object(release, "build_apks") as build, \
                patch.object(release, "verify_apks", return_value=apks), patch.object(release, "publish") as publish:
            release.main()
        build.assert_called_once_with("0.9.3", 9003)
        publish.assert_not_called()
        self.assertTrue((self.root / "build/releases/native-v0.9.3/RELEASE_NOTES.md").is_file())
        self.assertTrue(all(call.args[0][0] == "git" for call in command.call_args_list))

    def test_dirty_checkout_is_rejected_before_build_or_upload(self):
        def command(arguments, **_):
            return " M source.kt" if arguments[1] == "status" else "commit"
        arguments = ["publish-release.py", "--tag", "native-v0.9.3", "--repo", "owner/repo"]
        with patch.object(release.sys, "argv", arguments), patch.object(release.shutil, "which", return_value="gh"), \
                patch.object(release, "run", side_effect=command), patch.object(release, "build_apks") as build, \
                patch.object(release, "publish") as publish, self.assertRaisesRegex(ValueError, "Commit your changes"):
            release.main()
        build.assert_not_called()
        publish.assert_not_called()


if __name__ == "__main__":
    unittest.main()
