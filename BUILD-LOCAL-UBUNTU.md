# 在 Ubuntu 上本地构建 Operit2 Android APK

本文档记录一次完整、可复现的 Android APK 构建过程。所有步骤都在真实的 Ubuntu 24.04 机器上验证过。

## 为什么必须用 Linux

上游在 2026-10-25 引入了「为 Android 从源码编译 V8」（`feat(android): build pinned V8 from source for Android targets`）。原因是 `rusty_v8` 官方从不发布 Android 预编译库，必须源码构建。

而 Chromium 的构建系统在 `build/config/BUILDCONFIG.gn` 里硬编码了这条断言：

```gn
if (target_os == "android") {
  assert(host_os == "linux", "Android builds are only supported on Linux.")
  _default_toolchain = "//build/toolchain/android:android_clang_$target_cpu"
}
```

**结论：Windows 主机无法构建 Android APK**，与工具链是否装全无关。Windows 上会卡在 `prepareOperitAndroidV8` 任务，报 GN 断言失败。

## 环境要求

| 项目 | 版本 | 说明 |
| --- | --- | --- |
| 操作系统 | Ubuntu 24.04 LTS | 实测 24.04.4 |
| CPU / 内存 | 16 vCPU / 30 GB | V8 编译吃满核心；内存低于 16 GB 需调低并发 |
| 磁盘 | 根分区 >= 60 GB，/home >= 20 GB | 见「磁盘布局」 |
| JDK | 17 | Gradle 与 AGP 要求 |
| Rust | 1.95.0 | 上游 `RUST_TOOLCHAIN_VERSION`，需 `aarch64-linux-android` 目标 |
| Flutter | 3.41.10-ohos-0.0.2-beta.operit.9 | 由 FVM 按 `.fvmrc` 安装 |
| Node.js | 24 | 插件打包（corepack / pnpm 10.7.0） |
| Android SDK | platform 36、build-tools 36.0.0、NDK 28.2.13676358 | AGP 会自动下载 NDK |
| LLVM | **21.1.8** | bindgen 需要 libclang >= 21.1，Ubuntu 自带的 18 不够 |

## 一、系统依赖

```bash
sudo apt-get update
sudo apt-get install -y \
  build-essential cmake ninja-build pkg-config ccache \
  bison flex gawk patch unzip zip tar xz-utils bzip2 file \
  libssl-dev clang lld llvm \
  openjdk-17-jdk-headless curl ca-certificates git rsync python3-venv
```

### 安装 LLVM 21

Ubuntu 24.04 仓库只有 LLVM 18，而 V8 的 bindings 生成器要求 21.1+：

```bash
curl -sSL https://apt.llvm.org/llvm-snapshot.gpg.key \
  | sudo gpg --dearmor -o /usr/share/keyrings/llvm.gpg
echo "deb [signed-by=/usr/share/keyrings/llvm.gpg] http://apt.llvm.org/noble/ llvm-toolchain-noble-21 main" \
  | sudo tee /etc/apt/sources.list.d/llvm21.list
sudo apt-get update
sudo apt-get install -y libclang-21-dev lld-21 llvm-21-dev
```

**关键坑**：`prepare_android_v8.py` 按 `libclang.so.<数字>` 的模式查找库，而 Debian/Ubuntu 把它装成 `libclang-21.so.21`（前缀带连字符）。必须补一个符合命名规范的符号链接，否则会一直报 requires LLVM/libclang 21.1 or newer：

```bash
sudo ln -sf /usr/lib/x86_64-linux-gnu/libclang-21.so.21 /usr/lib/llvm-21/lib/libclang.so.21
sudo ldconfig
```

## 二、Rust / Node / Dart / FVM

```bash
# Rust 1.95.0 + Android 目标
curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal \
  --default-toolchain 1.95.0 --default-host x86_64-unknown-linux-gnu
. "$HOME/.cargo/env"
rustup target add aarch64-linux-android

# Node 24
curl -sSL https://nodejs.org/dist/v24.22.2/node-v24.22.2-linux-x64.tar.xz -o /tmp/node.tar.xz
sudo mkdir -p /opt/node24
sudo tar -xJf /tmp/node.tar.xz -C /opt/node24 --strip-components=1
sudo ln -sf /opt/node24/bin/node /usr/local/bin/node
sudo ln -sf /opt/node24/bin/npm  /usr/local/bin/npm
sudo ln -sf /opt/node24/bin/corepack /usr/local/bin/corepack

# Dart（FVM 的引导 SDK）
curl -sSL https://storage.googleapis.com/dart-archive/channels/stable/release/3.11.5/sdk/dartsdk-linux-x64-release.zip -o /tmp/dart.zip
sudo unzip -q /tmp/dart.zip -d /opt
sudo ln -sf /opt/dart-sdk/bin/dart /usr/local/bin/dart

# FVM 4.3.0
dart pub global activate fvm 4.3.0
export PATH="$HOME/.pub-cache/bin:$PATH"
```

**关键坑**：corepack 的 shim 必须真正落到 PATH 里。只执行 `corepack prepare pnpm@10.7.0 --activate` 不够——插件打包脚本会调用裸的 `pnpm` 命令，找不到就报 `sh: 1: pnpm: not found`，最终表现为 `Target dart_build failed`。必须：

```bash
sudo corepack enable --install-directory /opt/node24/bin pnpm
pnpm --version   # 应输出 10.7.0
```

## 三、Android SDK

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
mkdir -p "$ANDROID_HOME/cmdline-tools"
curl -sSL https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o /tmp/cmdline.zip
unzip -q /tmp/cmdline.zip -d "$ANDROID_HOME/cmdline-tools"
mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"

yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" --licenses
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$ANDROID_HOME" \
  platform-tools "platforms;android-36" "build-tools;36.0.0" "ndk;28.2.13676358"
```

## 四、克隆代码并准备构建环境

```bash
git clone https://github.com/sakurajima336/Operit2.git ~/Operit2
cd ~/Operit2

# 统一环境变量，后续所有步骤都 source 它
cat > ~/operit_env.sh <<EOF
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export ANDROID_HOME="$HOME/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export PUB_CACHE="$HOME/.pub-cache"
export PATH="$HOME/Operit2/apps/flutter/app/.fvm/flutter_sdk/bin:$HOME/.pub-cache/bin:$HOME/.cargo/bin:/opt/node24/bin:/opt/dart-sdk/bin:$ANDROID_HOME/platform-tools:/usr/local/bin:$PATH"
export RUSTFLAGS="-Awarnings"
export CI=true
export LIBCLANG_PATH=/usr/lib/llvm-21/lib
export LD_LIBRARY_PATH=/usr/lib/llvm-21/lib
# 出网代理（见「网络问题」一节）
export HTTP_PROXY=http://127.0.0.1:7897
export HTTPS_PROXY=http://127.0.0.1:7897
export http_proxy=http://127.0.0.1:7897
export https_proxy=http://127.0.0.1:7897
export NO_PROXY=localhost,127.0.0.1,::1
EOF
. ~/operit_env.sh

# 安装 FVM 锁定的 Flutter SDK
cd apps/flutter/app && fvm install --skip-pub-get && cd ~/Operit2

# Python venv（gradle 的插件同步 hook 硬编码 .venv/bin/python）
python3 -m venv .venv

# 插件构建工具
.venv/bin/python --version
cd plugins/packages/buildin/workflow
corepack pnpm install --frozen-lockfile
cd ~/Operit2
npm install --no-audit --no-fund --prefix .ci-tools/typescript typescript@5.9.2
npm install --no-audit --no-fund --no-save --no-package-lock esbuild@0.25.10 fflate@0.8.2 typescript@5.9.2
```

## 五、Android 运行时产物

`libbash.so`、`liboperit_busybox.so`、`liboperit_loader.so`、`liboperit_proot.so` 和 `rootfs.tar.gz.bin` 由 CI 在 Linux 上用 NDK 编译（`tools/android-runtime/build_*.sh`），本地通常没有。

推荐直接复用 CI 已产出的 artifact（与官方 workflow 的 `skip_runtime_build` 逻辑一致）：

```bash
MAIN=apps/flutter/app/android/app/src/main
mkdir -p "$MAIN/jniLibs/arm64-v8a" "$MAIN/assets/android-runtime/arm64-v8a"
tmp=$(mktemp -d)
curl -sSL -H "Authorization: Bearer $GH_TOKEN" \
  -o "$tmp/runtime.zip" \
  "https://api.github.com/repos/sakurajima336/Operit2/actions/artifacts/ARTIFACT_ID/zip"
unzip -q "$tmp/runtime.zip" -d "$tmp/x"
for n in libbash.so liboperit_busybox.so liboperit_loader.so liboperit_proot.so; do
  cp "$(find "$tmp/x" -name "$n" | head -1)" "$MAIN/jniLibs/arm64-v8a/$n"
done
for n in rootfs.tar.gz.bin rootfs.tar.gz.bin.sha256; do
  cp "$(find "$tmp/x" -name "$n" | head -1)" "$MAIN/assets/android-runtime/arm64-v8a/$n"
done
```

备选是本地编译：

```bash
export ANDROID_NDK_HOME="$HOME/.cache/operit-android-runtime/android-ndk-r29-beta4"
bash tools/android-runtime/fetch_ndk_wsl.sh
bash tools/android-runtime/build_android_tools_wsl.sh
bash tools/android-runtime/build_alpine_rootfs_wsl.sh
```

这些文件都在 `.gitignore` 里，不会污染工作区。

## 六、签名

debug 与 release 使用**同一个** keystore，这样两种包可以互相覆盖升级。`apps/flutter/app/android/app/build.gradle.kts` 已包含对应改动（`debug { signingConfig = signingConfigs.getByName("release") }`）。

```bash
mkdir -p tools/release/secrets
keytool -genkeypair -v \
  -keystore tools/release/secrets/operit2-release.keystore \
  -storetype PKCS12 -storepass operit2localdev -keypass operit2localdev \
  -alias operit2 -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Operit2 Local Build, OU=Local, O=Operit2, L=Local, ST=Local, C=CN"

cat > tools/release/secrets/android-signing.properties <<EOF
RELEASE_STORE_FILE=operit2-release.keystore
RELEASE_STORE_PASSWORD=operit2localdev
RELEASE_KEY_ALIAS=operit2
RELEASE_KEY_PASSWORD=operit2localdev
EOF
```

## 七、构建

```bash
. ~/operit_env.sh
cd ~/Operit2

# Release APK
.venv/bin/python tools/build_scripts/build_flutter_android.py \
  --build-name 2.0.0 --build-number 30

# Debug APK（同一签名）
cd apps/flutter/app
flutter build apk --debug --target-platform android-arm64 \
  --build-name 2.0.0 --build-number 30
```

产物位置：

```text
release: tools/release/dist/operit2-app-android-arm64-v8a-VERSION.apk
debug:   apps/flutter/app/build/app/outputs/flutter-apk/app-debug.apk
```

验证签名一致：

```bash
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs PATH_TO_APK
```

## 网络问题与处理

这台机器的网络环境特殊，踩了四个坑，全部与代码或工具无关。

### 1. 内网 DNS 不可达

阿里云实例默认把 systemd-resolved 指向 100.100.2.136，但该地址不可达，导致所有域名解析失败。修复：

```bash
sudo mkdir -p /etc/systemd/resolved.conf.d
printf "[Resolve]\nDNS=223.5.5.5 8.8.8.8\nFallbackDNS=1.1.1.1\nDNSSEC=no\n" \
  | sudo tee /etc/systemd/resolved.conf.d/operit.conf
sudo systemctl restart systemd-resolved
```

同时把 `/etc/apt/sources.list` 里的 `mirrors.cloud.aliyuncs.com` 换成 `archive.ubuntu.com`。

### 2. Chromium sysroot 被墙

`commondatastorage.googleapis.com` 直连超时（IPv4/IPv6 都不通）。这个文件必须由 `install-sysroot.py` 校验 SHA-256，所以做法是在能访问的机器上下载，传到 Ubuntu，手工解压并写 `.stamp`：

```bash
# 校验值必须匹配，可从 sysroots.json 查到
sha256sum sysroot.tgz   # 52d61d4446ffebfaa3dda2cd02da4ab4876ff237853f46d273e7f9b666652e1d

SYSROOT=$(ls -d ~/.cargo/registry/src/*/v8-*/build/linux/debian_bullseye_amd64-sysroot)
rm -rf "$SYSROOT"; mkdir -p "$SYSROOT"
tar mxf sysroot.tgz -C "$SYSROOT"
echo -n "https://commondatastorage.googleapis.com/chrome-linux-sysroot/52d61d4446ffebfaa3dda2cd02da4ab4876ff237853f46d273e7f9b666652e1d" > "$SYSROOT/.stamp"
```

`.stamp` 的内容必须与脚本拼出的 URL 完全一致，否则会重新下载。

### 3. ICU 数据与 gn/ninja 下载超时

需要访问 `chromium.googlesource.com` 和 `chrome-infra-packages.appspot.com`。这两个走代理即可，关键是要让 **Python 的 urllib 也走代理**——它只认环境变量，必须显式设 `HTTPS_PROXY`。

### 4. Gradle 静默卡死（最坑的一个）

现象：构建停住，`librusty_v8.a` 已生成，CPU 占用接近 0，日志 80 分钟不更新，看起来像死锁。

实际原因：Gradle 在下载 Maven 依赖。给 Gradle 配代理后，同样的步骤 100 秒完成。

```bash
cat > ~/.gradle/gradle.properties <<EOF
org.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8
org.gradle.parallel=true
systemProp.http.proxyHost=127.0.0.1
systemProp.http.proxyPort=7897
systemProp.https.proxyHost=127.0.0.1
systemProp.https.proxyPort=7897
systemProp.http.nonProxyHosts=localhost|127.0.0.1
EOF
```

诊断技巧：用 `jstack DAEMON_PID` 看主线程。如果停在 WAITING (parking) 且工作线程都在等 CountDownLatch，同时 `ss -tnp` 有大量 ESTAB 到 CDN 的连接，就是网络等待，不是死锁。

代理本身用 mihomo：

```bash
curl -sSL -o mihomo.gz https://github.com/MetaCubeX/mihomo/releases/download/v1.19.13/mihomo-linux-amd64-compatible-v1.19.13.gz
gunzip -f mihomo.gz && sudo mv mihomo /usr/local/bin/ && sudo chmod +x /usr/local/bin/mihomo
# config.yaml 里把订阅作为 proxy-provider，mixed-port 设为 7897
setsid nohup /usr/local/bin/mihomo -d ~/.config/mihomo > ~/mihomo.log 2>&1 &
```

## 磁盘布局（重要）

这台机器的 /home 只有 40 GB，而构建产物极大：

| 目录 | 占用 |
| --- | --- |
| `apps/flutter/native/operit-flutter-bridge/target` | 11 GB |
| `~/.gradle` | 5.7 GB |
| `apps/flutter/app/build` | 5.5 GB |

debug 包比 release 大（185 MB vs 157 MB），构建中途 /home 写满会导致 clang 报 **Bus error (core dumped)**，或者 Gradle 报 `No space left on device`。

解决：把重目录迁到根分区（108 GB），用符号链接接回：

```bash
sudo mkdir -p /opt/operit-build && sudo chown "$USER:$USER" /opt/operit-build

mv ~/Operit2/apps/flutter/native/operit-flutter-bridge/target /opt/operit-build/bridge-target
ln -s /opt/operit-build/bridge-target ~/Operit2/apps/flutter/native/operit-flutter-bridge/target

mv ~/Operit2/apps/flutter/app/build /opt/operit-build/app-build
ln -s /opt/operit-build/app-build ~/Operit2/apps/flutter/app/build

mv ~/.gradle /opt/operit-build/gradle-home
ln -s /opt/operit-build/gradle-home ~/.gradle
```

V8 编译完成后，`tools/release/work/android-v8/152.2.0/build/`（1.8 GB 中间产物）可以删除，只保留 `aarch64-linux-android/` 下的缓存。

## V8 缓存

V8 源码编译约 45 分钟（16 核）。成功后产物缓存到：

```text
tools/release/work/android-v8/152.2.0/aarch64-linux-android/
    librusty_v8.a    179 MB
    src_binding.rs    40 KB
    args.gn          856 B
    manifest.json    948 B
```

manifest.json 记录各产物的 SHA-256 和完整 GN 配置，`prepare_android_v8.py` 会校验后直接复用。后续构建跳过这一步。

可以下载 Release `local-build-preview-23` 里的 `operit2-android-v8-arm64-152.2.0.tar.gz`（35 MB）解压到上述路径，省掉首次编译。

## 常见失败速查

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `sh: 1: pnpm: not found` | corepack shim 不在 PATH | `corepack enable --install-directory /opt/node24/bin pnpm` |
| requires LLVM/libclang 21.1 or newer | 缺 `libclang.so.21` 符号链接 | 见「安装 LLVM 21」 |
| Failed to download chrome-linux-sysroot | GCS 不可达 | 手工放置 sysroot + `.stamp` |
| V8 preparation failed: urlopen error | Python urllib 没走代理 | 设 `HTTPS_PROXY` |
| Gradle 长时间无输出、CPU 接近 0 | Maven 下载停滞 | 给 Gradle 配代理 |
| clang Bus error (core dumped) | 磁盘写满 | 迁构建目录到根分区 |
| Cannot lock file hash cache | 残留 Gradle daemon | 杀 daemon，删 android/.gradle |
| Android builds are only supported on Linux | 在 Windows 上构建 | 换 Linux 主机 |
