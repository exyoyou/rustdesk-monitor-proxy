# rustdesk-monitor-proxy

RustDesk Android 端的应用内代理模块（仅 Android，不使用 VpnService）。
在本应用进程内运行 Xray 内核，只对 RustDesk 自身的信令/中继流量提供一个本机 SOCKS5 入口：
默认直连，直连失败时自动切到代理，直连恢复后自动切回。

属于 `rustdesk` 主仓库的 `flutter/android/monitor-*` 系列模块，和 `monitor-config`、`monitor-sync` 等一样以 git submodule 方式引入。

## 目录结构

```
monitor-proxy/
├── libxray/                  子模块：https://github.com/XTLS/libXray，固定在稳定版 tag
├── libs/                     libXray.aar 的存放位置（构建生成，不入库）
├── src/main/
│   ├── java/youyou/monitor/proxy/
│   │   ├── ProxyController.kt    状态机：Direct / StartingCore / Proxy / Probing
│   │   ├── ProxyHost.kt          宿主需要实现的接口（isOnline / setProxy / clearProxy）
│   │   └── core/
│   │       ├── ProxyCore.kt          内核接口
│   │       ├── XrayCore.kt           基于 libXray 的实现
│   │       ├── CoreConfigPatcher.kt  强制本机 SOCKS 入站、压低日志级别
│   │       └── CoreConfigStore.kt    配置获取、sha256 校验、缓存、assets 模板兜底
│   └── assets/xray_core_template.json   无密钥的配置模板
└── build.gradle
```

> 以上是设计目标，具体文件以仓库实际内容为准。

## 工作方式

1. 宿主（RustDesk Android）在服务上线时启动控制器，先清除上次遗留的代理设置。
2. 控制器通过宿主接口读取 RustDesk 的在线状态（是否已注册到 ID 服务器）。
3. 连续 `failThresholdSec` 秒未在线，则取得 Xray 配置、启动内核，并把 `127.0.0.1:随机端口`（带认证）设为 RustDesk 的 SOCKS 代理。
4. 代理模式下每隔 `recoverProbeIntervalSec` 探测直连，稳定 `recoverStableSec` 秒后清除代理，切回直连。
5. 页面上的“Socks5/Http(s) Proxy”设置项读取的就是这个代理配置，打开即可看到当前使用情况，不需要额外 UI。

走哪种协议（VMess / VLESS、WS+TLS / Reality 等）完全由 Xray 配置文件决定，客户端不关心。

## 配置

`proxy` 段放在 `monitor-config` 的 `MonitorConfig` 中，随现有的 WebDAV 配置同步下发：

| 字段 | 说明 |
|---|---|
| `enabled` | 总开关 |
| `mode` | `auto`（直连优先，失败切代理）、`always`、`off` |
| `failThresholdSec` | 直连未在线多久后切到代理 |
| `recoverProbeIntervalSec` | 代理模式下探测直连的间隔 |
| `recoverStableSec` | 直连稳定多久后切回直连 |
| `coreConfigUrl` | Xray 配置的位置：`http(s)://` 开头为完整 URL，否则视为 WebDAV 服务器上的相对路径 |
| `coreConfigSha256` | 配置文件的 sha256，校验失败时沿用上一次通过校验的缓存 |

Xray 配置取用顺序：远程下载 > 上次校验通过的缓存 > APK 内 `xray_core_template.json`（无密钥模板，仅用于结构参考，不含可用节点）。

### Xray 配置的约定

- 配置中需要有一个 tag 为 `local-proxy-socks` 的 `socks` 入站。
- 加载时模块会强制把它改为：监听 `127.0.0.1`、随机端口、用户名密码认证；删除其余入站；日志级别压到 `warning`。
- 配置允许带 `//` 注释。
- 真实节点信息（UUID、域名）不要放进仓库或 APK，只放在 WebDAV 上。本地调试用的配置文件命名为 `*.local.json`，已在 `.gitignore` 中排除。

## 构建 libXray

`libxray/` 固定在 libXray 的稳定版本（当前 `v26.10.31`，对应 Xray-core `v26.10.31`）。升级时只改子模块指向的 tag。

构建需要：Go、`gomobile`、Python 3、Android SDK 和 NDK（`ANDROID_HOME`、`ANDROID_NDK_HOME` 已设置）。

```bash
cd libxray
python3 build/main.py android
# 产物：libxray/libXray.aar，复制到 monitor-proxy/libs/
```

构建会生成包含 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` 的 AAR。Flutter 的 `--split-per-abi` 会自动只把对应架构的库打进对应的 APK。

libXray 的接口只有一个入口 `Invoke(requestJSON)`（`apiVersion: 3`），本模块用到 `runXray`、`stopXray`、`getXrayState`、`getFreePorts`。该项目声明不保证接口稳定，升级版本后需要重新核对。

注意：一个进程内只能加载一个 Go 运行时，不要在本应用里再引入其他独立构建的 Go/gomobile 库。

## 接入宿主

```gradle
// flutter/android/monitor_modules.gradle
include ':monitor-proxy'

// flutter/android/app/build.gradle
dependencies {
    implementation project(':monitor-proxy')
}
```

克隆主仓库时需要递归拉取子模块（本模块内还有 `libxray` 子模块）：

```bash
git clone --recursive <rustdesk-repo>
# 已克隆的仓库：
git submodule update --init --recursive
```

CI 的 `actions/checkout` 使用 `submodules: recursive` 即可。

## 服务端

Xray 服务端与 RustDesk 的 hbbs/hbbr 部署在同一台机器上（Docker）。出站规则必须只放行 hbbs/hbbr 的端口（21115–21119），其余全部拒绝，否则隧道会变成开放代理。

## 许可

- 本模块代码：与主仓库一致。
- libXray：MIT。
- Xray-core：MPL-2.0。
- RustDesk 本身为 AGPL-3.0，分发包含本模块的 APK 时需遵守相应的开源条款。
