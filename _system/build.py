# -*- coding: utf-8 -*-
"""15. 가게부어플 — APK 만들기 (Gradle·Android Studio 없이 SDK 도구를 직접 부른다)

    python build.py            → ..\\가계부_v<버전>.apk

필요한 도구 (이 폴더 tools\\ 아래, 시스템에 설치하지 않음):
    tools\\jdk\\                    JDK 17 (bin\\java.exe, javac.exe, keytool.exe)
    tools\\sdk\\platforms\\android-34\\android.jar
    tools\\sdk\\build-tools\\34.0.0\\   aapt2.exe, zipalign.exe, lib\\d8.jar, lib\\apksigner.jar

순서: aapt2 compile(리소스) → aapt2 link(매니페스트·assets 포함 APK 뼈대 + R.java) → javac → d8(classes.dex)
      → APK에 classes.dex 추가 → zipalign → apksigner(서명)
서명 열쇠(keystore\\gagyebu.jks)는 처음 한 번 만들어 계속 쓴다.
★ 이 열쇠를 지우면 새로 만든 APK를 기존 앱 위에 덮어 설치할 수 없다(지우고 다시 설치 = 폰의 가계부 자료 삭제).
"""
import os
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

REAL = Path(__file__).resolve().parent
# aapt2 등 SDK 도구는 한글이 든 경로를 읽지 못한다("failed to open directory").
# 그래서 임시 폴더에 영문 이름 연결(정션, 관리자 권한 불필요)을 만들어 그 경로로 빌드한다. 파일은 그대로 이 폴더에 있다.
LINK = Path(tempfile.gettempdir()) / "gagyebu_build_link"
if LINK.exists() and not (LINK / "build.py").exists():
    os.rmdir(LINK)                                   # 다른 곳을 가리키는 옛 연결 (연결만 지워지고 대상은 그대로)
if not LINK.exists():
    subprocess.run(["cmd", "/c", "mklink", "/J", str(LINK), str(REAL)], capture_output=True, creationflags=0x08000000)
HERE = LINK if (LINK / "build.py").exists() else REAL
APP = HERE / "app"
TOOLS = HERE / "tools"
JDK = TOOLS / "jdk" / "bin"
SDK = TOOLS / "sdk"
BT = SDK / "build-tools" / "34.0.0"
ANDROID_JAR = SDK / "platforms" / "android-34" / "android.jar"
BUILD = HERE / "build"
KEY_DIR = HERE / "keystore"
KEYSTORE = KEY_DIR / "gagyebu.jks"
KEY_PASS = KEY_DIR / "password.txt"
MIN_SDK, TARGET_SDK = "26", "34"
NO_WINDOW = 0x08000000


def run(args, **kw):
    r = subprocess.run([str(a) for a in args], capture_output=True, text=True, encoding="utf-8", errors="replace",
                       creationflags=NO_WINDOW, **kw)
    if r.returncode != 0:
        print((r.stdout or "") + (r.stderr or ""))
        sys.exit(f"실패: {Path(str(args[0])).name} {' '.join(str(a) for a in args[1:4])} …")
    return r.stdout


def main():
    need = [JDK / "java.exe", JDK / "javac.exe", JDK / "keytool.exe", ANDROID_JAR,
            BT / "aapt2.exe", BT / "zipalign.exe", BT / "lib" / "d8.jar", BT / "lib" / "apksigner.jar"]
    missing = [str(p) for p in need if not p.exists()]
    if missing:
        sys.exit("빌드 도구가 없습니다:\n  " + "\n  ".join(missing))

    version = re.search(r'VERSION\s*=\s*"([^"]+)"', (APP / "src/kr/gagyebu/app/MainActivity.java").read_text(encoding="utf-8")).group(1)
    code = str(int(float(version) * 100))                     # 1.0 → 100, 1.1 → 110 (새 판은 숫자가 커져야 덮어 설치된다)
    if BUILD.exists():
        shutil.rmtree(BUILD)
    for d in ("gen", "classes", "dex"):
        (BUILD / d).mkdir(parents=True)

    print("1/6 리소스")
    run([BT / "aapt2.exe", "compile", "--dir", APP / "res", "-o", BUILD / "res.zip"])
    run([BT / "aapt2.exe", "link", "-o", BUILD / "base.apk", "-I", ANDROID_JAR, "--manifest", APP / "AndroidManifest.xml",
         "-A", APP / "assets", "--java", BUILD / "gen", "--min-sdk-version", MIN_SDK, "--target-sdk-version", TARGET_SDK,
         "--version-code", code, "--version-name", version, BUILD / "res.zip"])

    print("2/6 컴파일")
    sources = [*APP.joinpath("src").rglob("*.java"), *BUILD.joinpath("gen").rglob("*.java")]
    run([JDK / "javac.exe", "-encoding", "UTF-8", "-source", "11", "-target", "11", "-Xlint:-options",
         "-cp", ANDROID_JAR, "-d", BUILD / "classes", *sources])

    print("3/6 dex")
    classes = list(BUILD.joinpath("classes").rglob("*.class"))
    run([JDK / "java.exe", "-cp", BT / "lib" / "d8.jar", "com.android.tools.r8.D8", "--release", "--min-api", MIN_SDK,
         "--lib", ANDROID_JAR, "--output", BUILD / "dex", *classes])

    print("4/6 묶기")
    with zipfile.ZipFile(BUILD / "base.apk", "a", zipfile.ZIP_DEFLATED) as z:
        z.write(BUILD / "dex" / "classes.dex", "classes.dex")
    run([BT / "zipalign.exe", "-f", "-p", "4", BUILD / "base.apk", BUILD / "aligned.apk"])

    print("5/6 서명")
    if not KEYSTORE.exists():
        KEY_DIR.mkdir(exist_ok=True)
        KEY_PASS.write_text(secrets.token_urlsafe(18), encoding="ascii")
        run([JDK / "keytool.exe", "-genkeypair", "-keystore", KEYSTORE, "-storepass", KEY_PASS.read_text(encoding="ascii"),
             "-alias", "gagyebu", "-keyalg", "RSA", "-keysize", "2048", "-validity", "36500", "-dname", "CN=Gagyebu"])
    pw = KEY_PASS.read_text(encoding="ascii").strip()
    signed = BUILD / "signed.apk"
    run([JDK / "java.exe", "-jar", BT / "lib" / "apksigner.jar", "sign", "--ks", KEYSTORE, "--ks-pass", "pass:" + pw,
         "--ks-key-alias", "gagyebu", "--out", signed, BUILD / "aligned.apk"])

    print("6/6 확인")
    print(run([JDK / "java.exe", "-jar", BT / "lib" / "apksigner.jar", "verify", "--verbose", signed]).strip().splitlines()[0])
    print(run([BT / "aapt2.exe", "dump", "badging", signed]).splitlines()[0])
    out = REAL.parent / f"가계부_v{version}.apk"
    shutil.copyfile(signed, out)
    print(f"완성: {out} ({out.stat().st_size / 1024:.0f} KB)")


if __name__ == "__main__":
    main()
