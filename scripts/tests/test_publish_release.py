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

    def git(self, *arguments):
        return subprocess.run(["git", *arguments], cwd=self.root, check=True, capture_output=True, text=True).stdout.strip()

    def git_repository(self):
        self.git("init", "-q")
        self.git("config", "user.name", "Release test")
        self.git("config", "user.email", "release-test@example.invalid")
        self.git("config", "tag.gpgSign", "false")
        self.git("add", "README.md")
        self.git("commit", "-qm", "Initial project")
        remote = self.root / ".git/fixture-remote.git"
        self.git("init", "--bare", "-q", str(remote))
        self.git("remote", "add", "origin", str(remote))
        return self.git("rev-parse", "HEAD")

    def assert_tag_commands(self, message, commit, remote="origin"):
        self.assertIn(f"git tag native-v0.9.3 {commit}", message)
        self.assertIn(f"git push {remote} refs/tags/native-v0.9.3:refs/tags/native-v0.9.3", message)
        self.assertIn(str(self.root), message)

    def test_missing_tag_is_created_and_pushed_to_a_real_bare_repository(self):
        commit = self.git_repository()
        with patch.object(release, "verify_release_remote"):
            release.ensure_release_tag("native-v0.9.3", "owner/repo", commit)
        self.assertEqual(commit, release.local_tag_commit("native-v0.9.3"))
        self.assertEqual(commit, release.remote_tag_commit("native-v0.9.3", "origin"))
        self.assertEqual("", self.git("status", "--porcelain"))

    def test_local_only_annotated_tag_is_pushed_without_replacing_its_object(self):
        commit = self.git_repository()
        self.git("tag", "-a", "native-v0.9.3", "-m", "Release notes")
        tag_object = self.git("rev-parse", "refs/tags/native-v0.9.3")
        with patch.object(release, "verify_release_remote"):
            release.ensure_release_tag("native-v0.9.3", "owner/repo", commit)
        self.assertEqual(tag_object, self.git("rev-parse", "refs/tags/native-v0.9.3"))
        self.assertIn(tag_object, self.git("ls-remote", "origin", "refs/tags/native-v0.9.3"))

    def test_remote_only_annotated_tag_is_fetched_and_reused(self):
        commit = self.git_repository()
        self.git("tag", "-a", "native-v0.9.3", "-m", "Release notes")
        tag_object = self.git("rev-parse", "refs/tags/native-v0.9.3")
        self.git("push", "origin", "refs/tags/native-v0.9.3")
        self.git("tag", "-d", "native-v0.9.3")
        with patch.object(release, "verify_release_remote"), patch.object(release, "run", wraps=release.run) as command:
            release.ensure_release_tag("native-v0.9.3", "owner/repo", commit)
        self.assertEqual(tag_object, self.git("rev-parse", "refs/tags/native-v0.9.3"))
        self.assertEqual(commit, release.local_tag_commit("native-v0.9.3"))
        self.assertFalse(any(call.args[0][1] in ("tag", "push") for call in command.call_args_list))

    def test_existing_matching_tags_need_no_creation_fetch_or_push(self):
        commit = self.git_repository()
        self.git("tag", "native-v0.9.3")
        self.git("push", "origin", "refs/tags/native-v0.9.3")
        with patch.object(release, "verify_release_remote"), patch.object(release, "run", wraps=release.run) as command:
            release.ensure_release_tag("native-v0.9.3", "owner/repo", commit)
        self.assertFalse(any(call.args[0][1] in ("tag", "fetch", "push") for call in command.call_args_list))

    def test_local_tag_conflict_is_not_moved_and_prints_recovery_commands(self):
        original = self.git_repository()
        self.git("tag", "native-v0.9.3")
        self.git("commit", "--allow-empty", "-qm", "New changes")
        commit = self.git("rev-parse", "HEAD")
        with self.assertRaisesRegex(ValueError, "本地标签.*指向") as failure:
            release.ensure_release_tag("native-v0.9.3", "owner/repo", commit)
        self.assertEqual(original, release.local_tag_commit("native-v0.9.3"))
        self.assertIsNone(release.remote_tag_commit("native-v0.9.3", "origin"))
        self.assert_tag_commands(str(failure.exception), commit)

    def test_remote_tag_conflict_is_not_overwritten_or_created_locally(self):
        original = self.git_repository()
        self.git("tag", "native-v0.9.3")
        self.git("push", "origin", "refs/tags/native-v0.9.3")
        self.git("tag", "-d", "native-v0.9.3")
        self.git("commit", "--allow-empty", "-qm", "New changes")
        commit = self.git("rev-parse", "HEAD")
        with patch.object(release, "verify_release_remote"), self.assertRaisesRegex(ValueError, "远程标签.*指向") as failure:
            release.ensure_release_tag("native-v0.9.3", "owner/repo", commit)
        self.assertIsNone(release.local_tag_commit("native-v0.9.3"))
        self.assertEqual(original, release.remote_tag_commit("native-v0.9.3", "origin"))
        self.assert_tag_commands(str(failure.exception), commit)

    def test_network_failure_is_not_treated_as_a_missing_remote_tag(self):
        commit = self.git_repository()
        run = release.run
        def command(arguments, **kwargs):
            if arguments[1] == "ls-remote":
                raise subprocess.CalledProcessError(128, arguments, stderr="ssh: Could not resolve hostname github.com")
            return run(arguments, **kwargs)
        with patch.object(release, "verify_release_remote"), patch.object(release, "run", side_effect=command), \
                self.assertRaisesRegex(ValueError, "Could not resolve hostname") as failure:
            release.ensure_release_tag("native-v0.9.3", "owner/repo", commit)
        self.assertIsNone(release.local_tag_commit("native-v0.9.3"))
        self.assert_tag_commands(str(failure.exception), commit)

    def test_failed_push_keeps_the_local_tag_prints_commands_and_can_be_retried(self):
        commit = self.git_repository()
        run = release.run
        def command(arguments, **kwargs):
            if arguments[1] == "push":
                raise subprocess.CalledProcessError(128, arguments, stderr="Permission denied (publickey)")
            return run(arguments, **kwargs)
        with patch.object(release, "verify_release_remote"), patch.object(release, "run", side_effect=command), \
                self.assertRaisesRegex(ValueError, "Permission denied") as failure:
            release.ensure_release_tag("native-v0.9.3", "owner/repo", commit)
        self.assertEqual(commit, release.local_tag_commit("native-v0.9.3"))
        self.assertIsNone(release.remote_tag_commit("native-v0.9.3", "origin"))
        self.assert_tag_commands(str(failure.exception), commit)
        with patch.object(release, "verify_release_remote"):
            release.ensure_release_tag("native-v0.9.3", "owner/repo", commit)
        self.assertEqual(commit, release.remote_tag_commit("native-v0.9.3", "origin"))

    def test_tag_creation_failure_prints_the_actual_error_and_manual_commands(self):
        commit = self.git_repository()
        run = release.run
        def command(arguments, **kwargs):
            if arguments[1] == "tag":
                raise PermissionError("Cannot write the Git refs directory")
            return run(arguments, **kwargs)
        with patch.object(release, "verify_release_remote"), patch.object(release, "run", side_effect=command), \
                self.assertRaisesRegex(ValueError, "Cannot write the Git refs") as failure:
            release.ensure_release_tag("native-v0.9.3", "owner/repo", commit)
        self.assertIsNone(release.local_tag_commit("native-v0.9.3"))
        self.assertIsNone(release.remote_tag_commit("native-v0.9.3", "origin"))
        self.assert_tag_commands(str(failure.exception), commit)

    def test_dirty_worktree_stops_before_tag_creation_or_remote_access(self):
        commit = self.git_repository()
        (self.root / "README.md").write_text("Uncommitted changes", encoding="utf-8")
        with patch.object(release, "verify_release_remote") as remote, self.assertRaisesRegex(ValueError, "工作区存在未提交") as failure:
            release.ensure_release_tag("native-v0.9.3", "owner/repo", commit)
        remote.assert_not_called()
        self.assertIsNone(release.local_tag_commit("native-v0.9.3"))
        self.assert_tag_commands(str(failure.exception), commit)

    def test_release_remote_must_match_the_selected_repository(self):
        for urls in [("git@github.com:owner/repo.git", "https://github.com/OWNER/repo.git"),
                     ("ssh://git@ssh.github.com:443/owner/repo.git", "git@github.com:owner/repo.git")]:
            with self.subTest(urls=urls), patch.object(release, "run", side_effect=urls):
                release.verify_release_remote("owner/repo", "origin")
        for urls in [("git@github.com:other/repo.git", "git@github.com:owner/repo.git"),
                     ("git@github.com:owner/repo.git", "git@github.com:other/repo.git"),
                     ("git@github.com:owner/repo.git", "git@github.com:owner/repo.git\ngit@github.com:other/repo.git")]:
            with self.subTest(urls=urls), patch.object(release, "run", side_effect=urls):
                with self.assertRaisesRegex(ValueError, "唯一推送地址必须都指向"):
                    release.verify_release_remote("owner/repo", "origin")

    def test_missing_remote_reports_setup_create_and_push_commands(self):
        commit = self.git_repository()
        self.git("remote", "remove", "origin")
        with self.assertRaisesRegex(ValueError, "无法读取 Git remote origin") as failure:
            release.ensure_release_tag("native-v0.9.3", "owner/repo", commit)
        message = str(failure.exception)
        self.assertIn("git remote add origin git@github.com:owner/repo.git", message)
        self.assert_tag_commands(message, commit, "git@github.com:owner/repo.git")
        self.assertIsNone(release.local_tag_commit("native-v0.9.3"))

    def test_prepare_only_missing_tag_does_not_create_or_push_and_shows_commands(self):
        commit = self.git_repository()
        arguments = ["publish-release.py", "--tag", "native-v0.9.3", "--repo", "owner/repo", "--prepare-only"]
        with patch.object(release.sys, "argv", arguments), patch.object(release.shutil, "which", return_value="gh"), \
                patch.object(release, "ensure_release_tag") as ensure, patch.object(release, "build_apks") as build, \
                self.assertRaisesRegex(ValueError, "本地缺少标签") as failure:
            release.main()
        ensure.assert_not_called()
        build.assert_not_called()
        self.assertIsNone(release.local_tag_commit("native-v0.9.3"))
        self.assert_tag_commands(str(failure.exception), commit)

    def test_local_cli_hands_off_to_actions_without_building_or_uploading(self):
        arguments = ["publish-release.py", "--tag", "native-v0.9.3", "--repo", "owner/repo", "--remote", "github"]
        for pushed in (True, False):
            with self.subTest(pushed=pushed), patch.dict(release.os.environ, GITHUB_ACTIONS="false"), \
                    patch.object(release.sys, "argv", arguments), patch.object(release.shutil, "which", return_value="gh"), \
                    patch.object(release, "verify_worktree", return_value="commit"), \
                    patch.object(release, "get_releases", return_value=[]), \
                    patch.object(release, "ensure_release_tag", return_value=pushed) as ensure, \
                    patch.object(release, "find_apksigner") as signer, patch.object(release, "build_apks") as build, \
                    patch.object(release, "publish") as publish, patch("builtins.print") as output:
                release.main()
            ensure.assert_called_once_with("native-v0.9.3", "owner/repo", "commit", "github")
            signer.assert_not_called()
            build.assert_not_called()
            publish.assert_not_called()
            messages = "\n".join(call.args[0] for call in output.call_args_list)
            self.assertIn("https://github.com/owner/repo/actions/workflows/native-release.yml", messages)
            if pushed:
                self.assertIn("由 GitHub Actions 构建和上传", messages)
            else:
                self.assertIn("gh workflow run native-release.yml --repo owner/repo -f tag=native-v0.9.3", messages)

    def test_local_draft_request_does_not_push_a_public_release_tag(self):
        arguments = ["publish-release.py", "--tag", "native-v0.9.3", "--repo", "owner/repo", "--draft"]
        with patch.dict(release.os.environ, GITHUB_ACTIONS="false"), patch.object(release.sys, "argv", arguments), \
                patch.object(release.shutil, "which", return_value="gh"), patch.object(release, "ensure_release_tag") as ensure, \
                self.assertRaisesRegex(ValueError, "gh workflow run .* -f draft=true"):
            release.main()
        ensure.assert_not_called()

    def test_actions_cli_builds_and_uploads_the_existing_tag(self):
        apks = self.artifacts()
        arguments = ["publish-release.py", "--tag", "native-v0.9.3", "--repo", "owner/repo", "--draft"]
        events = []
        with patch.dict(release.os.environ, GITHUB_ACTIONS="true"), patch.object(release.sys, "argv", arguments), \
                patch.object(release.shutil, "which", return_value="gh"), \
                patch.object(release, "get_releases", return_value=[]), \
                patch.object(release, "find_apksigner", return_value="apksigner"), \
                patch.object(release, "ensure_release_tag") as ensure, patch.object(release, "verify_release_remote"), \
                patch.object(release, "build_apks", side_effect=lambda *args: events.append("build")), \
                patch.object(release, "verify_apks", return_value=apks), \
                patch.object(release, "verify_checkout", return_value="commit"), \
                patch.object(release, "remote_tag_commit", return_value="commit"), \
                patch.object(release, "publish", side_effect=lambda *args: events.append("publish")) as publish:
            release.main()
        ensure.assert_not_called()
        self.assertEqual(["build", "publish"], events)
        self.assertTrue(publish.call_args.args[-1])

    def test_remote_tag_removed_or_changed_during_build_prevents_upload(self):
        apks = self.artifacts()
        arguments = ["publish-release.py", "--tag", "native-v0.9.3", "--repo", "owner/repo"]
        for target in (None, "different-commit"):
            with self.subTest(target=target), patch.dict(release.os.environ, GITHUB_ACTIONS="true"), \
                    patch.object(release.sys, "argv", arguments), \
                    patch.object(release.shutil, "which", return_value="gh"), \
                    patch.object(release, "get_releases", return_value=[]), \
                    patch.object(release, "find_apksigner", return_value="apksigner"), \
                    patch.object(release, "verify_release_remote"), patch.object(release, "build_apks") as build, \
                    patch.object(release, "verify_apks", return_value=apks), \
                    patch.object(release, "verify_checkout", return_value="commit"), \
                    patch.object(release, "remote_tag_commit", side_effect=["commit", target]), \
                    patch.object(release, "publish") as publish, self.assertRaisesRegex(ValueError, "远程标签.*已缺失或指向其他提交") as failure:
                release.main()
            build.assert_called_once()
            publish.assert_not_called()
            self.assert_tag_commands(str(failure.exception), "commit")

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

    def test_version_specific_notes_keep_the_project_intro_and_full_checksum_information(self):
        notes_path = self.root / "doc/release-notes/native-v0.9.3.md"
        notes_path.parent.mkdir(parents=True)
        notes = "- 修复播放中断。\n- 优化更新说明。\n"
        notes_path.write_text(notes, encoding="utf-8-sig")
        output, checksums = self.prepared()
        body = (output / "RELEASE_NOTES.md").read_text(encoding="utf-8")
        self.assertTrue(body.startswith("## 更新摘要\n\n" + notes))
        self.assertIn("原生音乐播放器", body)
        self.assertIn("## APK SHA-256", body)
        for name, digest in checksums.items():
            self.assertEqual(release.sha256(output / name), digest)
            self.assertIn(f"/releases/download/native-v0.9.3/{name}", body)
            self.assertIn(digest, body)
            self.assertIn(f"{digest}  {name}\n", (output / "SHA256SUMS.txt").read_text())

    def test_empty_version_specific_notes_are_rejected(self):
        notes_path = self.root / "doc/release-notes/native-v0.9.3.md"
        notes_path.parent.mkdir(parents=True)
        notes_path.write_text(" \n", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Release notes must not be empty"):
            release.release_notes("owner/repo", "native-v0.9.3", "native-v0.9.2", {})

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
        notes_path = self.root / "doc/release-notes/native-v0.9.3.md"
        notes_path.parent.mkdir(parents=True)
        for notes in (None, "- 修复播放中断。\n"):
            with self.subTest(summary=notes is not None):
                if notes is not None:
                    notes_path.write_text(notes, encoding="utf-8")
                body = release.release_notes("owner/repo", "native-v0.9.3", "native-v0.9.2", {"all.apk": "a" * 64})
                self.assertIn("Fix playback", body)
                self.assertIn("Add bundled source", body)
                for commit in git("log", "--format=%H", "native-v0.9.2..native-v0.9.3").splitlines():
                    self.assertIn(f"[{commit[:7]}](https://github.com/owner/repo/commit/{commit})", body)
                self.assertIn("/compare/native-v0.9.2...native-v0.9.3", body)
                self.assertIn("a" * 64, body)
                self.assertNotIn("Initial project", body)
                self.assertNotIn("原生音乐播放器", body)
                if notes is not None:
                    self.assertTrue(body.startswith("## 更新摘要\n\n" + notes))

    def test_verification_checks_versions_abis_and_common_signer(self):
        self.artifacts()
        certificate = "Signer #1 certificate SHA-256 digest: " + "a" * 64
        with patch.object(release, "run", return_value=certificate):
            self.assertEqual(3, len(release.verify_apks("0.9.3", 9003, "apksigner")))
            with self.assertRaisesRegex(ValueError, "version"):
                release.verify_apks("0.9.3", 93, "apksigner")
        with patch.object(release, "run", side_effect=[certificate, certificate.replace("a" * 64, "b" * 64)]):
            with self.assertRaisesRegex(ValueError, "signing certificates differ"):
                release.verify_apks("0.9.3", 9003, "apksigner")

    def test_verification_accepts_old_and_build_tools_37_signer_labels(self):
        self.artifacts()
        labels = ["Signer #1", "V1 Signer:", "V2 Signer:", "V2 Signer #1:", "V3.0 Signer:",
                  "V3.0 Signer: (minSdkVersion=28, maxSdkVersion=32)",
                  "V3.1 Signer: (minSdkVersion=33, maxSdkVersion=2147483647)",
                  "V3.2 Hybrid Classical Signer: (minSdkVersion=37 (dev release=true), maxSdkVersion=2147483647)",
                  "V3.2 Hybrid PQC Signer: (minSdkVersion=37, maxSdkVersion=2147483647)"]
        for label in labels:
            output = f"Verifies\r\nNumber of signers: 1\r\n{label} certificate SHA-256 digest: {'a' * 64}\r\n"
            with self.subTest(label=label), patch.object(release, "run", return_value=output):
                self.assertEqual(3, len(release.verify_apks("0.9.3", 9003, "apksigner")))

    def test_verification_compares_all_signers_regardless_of_order_and_hex_case(self):
        self.artifacts()
        first = f"V2 Signer #1: certificate SHA-256 digest: {'a' * 64}\nV2 Signer #2: certificate SHA-256 digest: {'b' * 64}\n"
        second = f"V2 Signer #1: certificate SHA-256 digest: {'B' * 64}\nV2 Signer #2: certificate SHA-256 digest: {'A' * 64}\n"
        with patch.object(release, "run", side_effect=[first, second, first]):
            self.assertEqual(3, len(release.verify_apks("0.9.3", 9003, "apksigner")))
        with patch.object(release, "run", side_effect=[first, second.replace("A" * 64, "c" * 64)]):
            with self.assertRaisesRegex(ValueError, "signing certificates differ"):
                release.verify_apks("0.9.3", 9003, "apksigner")

    def test_verification_ignores_public_key_source_stamp_and_dn_digests(self):
        self.artifacts()
        outputs = [f"V2 Signer: certificate SHA-256 digest: {'a' * 64}\n"
                   f"V2 Signer: public key SHA-256 digest: {other * 64}\n"
                   f"Source Stamp Signer: certificate SHA-256 digest: {other * 64}\n"
                   f"V2 Signer: certificate DN: CN=Signer #2 certificate SHA-256 digest: {other * 64}\n"
                   for other in "bcd"]
        with patch.object(release, "run", side_effect=outputs):
            self.assertEqual(3, len(release.verify_apks("0.9.3", 9003, "apksigner")))

    def test_missing_or_malformed_signer_certificates_report_a_parse_error(self):
        self.artifacts()
        outputs = ["", f"V2 Signer: public key SHA-256 digest: {'a' * 64}\n",
                   f"Source Stamp Signer: certificate SHA-256 digest: {'a' * 64}\n",
                   *(f"V2 Signer: certificate SHA-256 digest: {digest}\n"
                     for digest in ("a" * 63, "a" * 65, "g" * 64))]
        for output in outputs:
            with self.subTest(output=output), patch.object(release, "run", return_value=output):
                with self.assertRaisesRegex(ValueError, "Could not read APK signing certificate SHA-256.*all.apk.*apksigner"):
                    release.verify_apks("0.9.3", 9003, "apksigner")

    def test_apksigner_failure_cannot_be_overridden_by_certificate_output(self):
        self.artifacts()
        error = subprocess.CalledProcessError(1, ["apksigner", "verify"],
                                            output=f"V2 Signer: certificate SHA-256 digest: {'a' * 64}\n")
        with patch.object(release, "run", side_effect=error) as command:
            with self.assertRaises(subprocess.CalledProcessError):
                release.verify_apks("0.9.3", 9003, "apksigner")
        command.assert_called_once()

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
                patch.object(release, "publish") as publish, self.assertRaisesRegex(ValueError, "工作区存在未提交"):
            release.main()
        build.assert_not_called()
        publish.assert_not_called()


if __name__ == "__main__":
    unittest.main()
