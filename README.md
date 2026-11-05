# rustdesk-monitor-proxy

RustDesk Android 端的应用内代理模块（仅 Android，不使用 VpnService）。
在本应用进程内运行 Xray 内核，在 `127.0.0.1` 上为 ID 服务器的每个端口开一个本地转发（dokodemo-door），
只有 RustDesk 连服务器的流量会走隧道：默认直连，直连失败时自动切到隧道，直连恢复后自动切回。
RustDesk 自带的代理设置、界面显示的服务器地址都不会被修改。

属于 `rustdesk` 主仓库的 `flutter/android/monitor-*` 系列模块，和 `monitor-config`、`monitor-sync` 等一样以 git submodule 方式引入。

## 目录结构

```
monitor-proxy/
├── build.gradle              含 libXray 预编译 AAR 的下载与校验
├── libs/                     libXray.aar 的存放位置（构建时自动下载，不入库）
├── src/main/
│   ├── java/youyou/monitor/proxy/
│   │   ├── ProxyController.kt    状态机：Direct / Proxy
│   │   ├── ProxyRuntime.kt       模块入口，组装控制器
│   │   ├── ProxyHost.kt          宿主需要实现的接口（isOnline / userProxy / serverHost / hasNetwork / setRedirect）
│   │   └── core/
│   │       ├── ProxyCore.kt          内核接口
│   │       ├── XrayCore.kt           基于 libXray 的实现
│   │       ├── CoreConfigPatcher.kt  生成本机 dokodemo-door 入站和路由、压低日志级别
│   │       └── CoreConfigStore.kt    配置获取、sha256 校验、缓存优先、后台刷新
│   └── assets/xray_core_template.json   无密钥的配置模板（仅作结构参考）
└── build.gradle
```

## 工作方式

1. 宿主（RustDesk Android）在服务上线时启动控制器。
2. 控制器通过宿主接口读取 RustDesk 的在线状态（是否已注册到 ID 服务器）。设备没有网络时不计入离线时间，也不启动内核；用户自己在 RustDesk 里设置了代理时不接管。
3. 连续 `failThresholdSec` 秒未在线，则取得 Xray 配置（缓存优先，后台刷新）、启动内核，然后让 RustDesk 连接服务器的目标临时换成 `127.0.0.1`（端口不变），经本地转发 -> 隧道 -> 服务器。RustDesk 的配置不改，界面显示的服务器地址不变。
4. 代理模式下每隔 `recoverProbeIntervalSec` 用一个独立的 TCP 连接探测直连（不经过 RustDesk，设备一直在线），连续可达 `recoverStableSec` 秒后关闭内核、恢复直连。刚恢复就又掉线说明探测不可靠（例如 TCP 通但 UDP 不通），下次探测间隔会指数退避。
5. 代理状态下如果设备持续不在线，会重新下载最新内核配置（至少间隔 60 秒）；内容有变化才用新配置重启内核，相同则只记日志。运行中 ID 服务器或 `forwardPorts` 变化时也会自动重启内核。

走哪种协议（VMess / VLESS、WS+TLS / Reality 等）完全由 Xray 配置文件决定，客户端不关心。

注意：
- 隧道服务端必须允许 UDP，hbbs 的注册只走 UDP 21116。
- 转发期间，控制端到本机的 P2P 打洞会被强制改走中继。
- 局域网直连（IP 直连）不受影响。

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
| `forwardPorts` | 需要走隧道的服务器端口，默认 `[21115, 21116, 21117]`。21118 / 21119 是 RustDesk 本机自己占用的端口，写了也会被跳过 |

Xray 配置取用顺序：本地缓存（启动快，同时后台刷新）> 远程下载。没有可用配置时保持直连，约 60 秒后重试。APK 内的 `xray_core_template.json` 只是结构参考，不会被加载。

### Xray 配置的约定

- 出站（`outbounds`）里要有一个 tag 为 `proxy` 的出站；没有时依次取路由规则里指向的出站、第一个非 freedom / blackhole 的出站。
- 入站不用写：加载时模块会把 `inbounds` 整个替换为每个转发端口一个 `dokodemo-door`（监听 `127.0.0.1`，TCP+UDP，目标是 ID 服务器主机的同一端口），并在路由最前面加一条规则交给上面的出站；日志级别压到 `warning`。
- 配置允许带 `//` 注释。
- 真实节点信息（UUID、域名）不要放进仓库或 APK，只放在 WebDAV 上。本地调试用的配置文件命名为 `*.local.json`，已在 `.gitignore` 中排除。

## libXray

直接使用 libXray 官方 release 里预编译好的 `libxray-android.zip`（内含 `libXray.aar`），**不需要 Go、gomobile**，本地和 GitHub Actions 都不用额外步骤。

- 当前版本：`v26.10.31`（对应 Xray-core `v26.10.31`）。
- `build.gradle` 在配置阶段检查 `libs/libXray.aar`：不存在或版本不一致时，从 GitHub release 下载、校验 sha256 后解出 AAR；已存在则跳过。
- 升级版本：修改 `build.gradle` 顶部的 `libXrayVersion` 和 `libXraySha256`（release 页面上 `libxray-android.zip` 旁边显示的 sha256）。
- 构建机器需要能访问 GitHub。无法联网时，手动下载 zip，把 `libXray.aar` 放进 `libs/`，并在 `libs/libXray.version` 里写入 `<版本号> <sha256>`。
- AAR 包含 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`。Flutter 的 `--split-per-abi` 会自动只把对应架构的库打进对应的 APK；universal 包会包含全部四个架构，体积较大。

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

克隆主仓库时需要递归拉取子模块：

```bash
git clone --recursive <rustdesk-repo>
# 已克隆的仓库：
git submodule update --init --recursive
```

CI 的 `actions/checkout` 使用 `submodules: recursive` 即可，不需要改动现有工作流。

## 服务端

Xray 服务端与 RustDesk 的 hbbs/hbbr 部署在同一台机器上（Docker）。出站规则必须只放行 hbbs/hbbr 的端口（21115–21117），其余全部拒绝，否则隧道会变成开放代理；并且必须允许 UDP。

## 许可

- 本模块代码：与主仓库一致。
- libXray：MIT。
- Xray-core：MPL-2.0。
- RustDesk 本身为 AGPL-3.0，分发包含本模块的 APK 时需遵守相应的开源条款。
