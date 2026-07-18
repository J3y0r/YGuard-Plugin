# YGuard 客户端 Mod 实现规格

## 职责与生命周期

客户端 Mod 运行于 NeoForge 1.21.11 与 Java 21。它只在收到 `yguard:attestation_challenge` 后构建并提交证明，不主动连接外部服务，也不在验证期间限制玩家行为。

初始化顺序固定如下：

1. 读取编译进 JAR 的不可变发布 `buildId` 和内置 RSA 公钥集合。
2. 提取 Windows x64 Native DLL，验证其 SHA-256 后调用 `System.load`。
3. 初始化 `NativeBridge`，安装 JVMTI 防护并保留状态。
4. 注册 YGuard 自定义载荷处理器。
5. 对每个有效挑战收集新快照、构建证明、加密、分片并回传。

每个可发布 Mod 必须生成新的随机 `buildId`，并将其编译为只读常量。服务端通过 `allowedBuildIds` 管理允许和撤销的发布版本，不能以 Gradle 版本号替代 `buildId`。

## Native 加载与 JNI 边界

DLL 位于 JAR 资源路径 `META-INF/yguard-native/windows-x64/yguard_native.dll`。构建时同时生成包含 SHA-256 的资源清单。Mod 将 DLL 提取到按 Mod 版本和摘要命名的私有目录；现有文件摘要不匹配时必须覆盖后再加载。

Java 侧 JNI 边界固定为：

```java
package me.jeyor.yguard.nativebridge;

public record NativeSnapshot(String hwidSha256, String[] packageNames, int statusMask) {}

public final class NativeBridge {
    public static native NativeSnapshot collectSnapshot();
}
```

`collectSnapshot()` 每次挑战重新枚举 JVM 包名；一次进程生命周期内只尝试安装一次 JVMTI Hook。状态位如下：

| 位 | 名称 | 含义 |
| --- | --- | --- |
| `0x01` | `HWID_UNAVAILABLE` | 无法生成 HWID SHA-256 |
| `0x02` | `PACKAGES_UNAVAILABLE` | 无法获得 JVM 包名集合 |
| `0x04` | `HOOK_UNAVAILABLE` | 无法安装一个或两个 JVMTI 拦截 |

DLL 无法提取、校验或加载时，Java 侧构建 `NATIVE_UNAVAILABLE` 状态，不调用 JNI。上传前状态名称按固定顺序输出；状态掩码为零时仅上传 `READY`。Native 状态不能被本地异常吞掉或替换为成功。

## 证明构建与上传

客户端必须完全遵循 [服务端协议](server-plugin.md) 的字段、加密和分片约定。

对每个挑战：

1. 检查 `protocolVersion`、`keyId`、`attempt` 和 nonce 格式；未知协议或未知 `keyId` 不响应。
2. 调用 `NativeBridge.collectSnapshot()`，将包名按 Unicode 代码点排序、去重并移除空项。
3. 以 LF 连接包名、UTF-8 编码并使用标准 Base64，填充 `loadedPackagesBase64`。
4. 将 `sessionId`、`attempt`、nonce、当前 Minecraft 玩家 UUID、`buildId`、HWID 哈希和状态集合写入证明 JSON。
5. 对 JSON 执行 GZIP、AES-256-GCM 加密，并使用挑战 `keyId` 对应 RSA-3072 公钥以 OAEP-SHA-256 封装 AES 密钥。
6. 将 UTF-8 信封按 24 KiB 自定义载荷上限分片，发送至 `yguard:attestation_fragment`。

客户端不能缓存上一挑战的密文、AES 密钥、IV 或包名编码结果用于另一 nonce。服务器的 `attestation_result` 仅用于关闭对应会话的本地发送状态；服务器未返回结果时仍以新挑战为准。

客户端只上报 SHA-256 HWID，绝不将 MachineGuid、BIOS UUID、主板序列号或卷序列号传递给 Java 层、日志或网络。

## 验收测试

- 合法挑战可生成与服务端兼容的单分片和多分片证明。
- 资源摘要不匹配、DLL 无法加载、HWID 失败、包枚举失败、Hook 失败均产生对应状态。
- 相同包集合总是生成相同的 Base64 包清单；新挑战总是使用新的 AES 密钥和 IV。
- 未知 `keyId`、无效 nonce、过期挑战或非当前玩家 UUID 的挑战不会产生响应。
