# LAME

Source: https://github.com/lameproject/lame

Pinned commit: `1f5cc9487284d5950343aa5d4f70de433468070a`.

`libmp3lame/`, `include/` and `COPYING` are copied from that upstream commit.
LAME is licensed under GNU Library GPL version 2 or later; see COPYING.
The Android configuration, CMake project and dynamically registered JNI adapter
are maintained one directory above. LAME is built as a separate shared library,
not statically linked into the application. Its license is included in the APK
and displayed in the About page.

To rebuild/relink, use JDK 17, NDK 27.2.12479018 and CMake 3.22.1, then run
`gradlew :app:assembleDebug` from the repository root. Both native libraries use
16 KB ELF page alignment. A modified APK can be signed with a user-owned key;
the application does not check the LAME binary signature or hash.
