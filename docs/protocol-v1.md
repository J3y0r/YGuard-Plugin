# YGuard 协议 v1 补充约定

本文补充 `server-plugin.md`、`client-mod.md` 与 `native-libs.md` 中未定义的跨端细节。冲突时以本文为准。

## 通用编码

- 协议版本固定为 `1`。
- JSON 载荷使用无 BOM 的 UTF-8，UUID 使用标准带连字符小写字符串。
- RSA OAEP 的消息摘要与 MGF1 摘要均为 SHA-256，`PSource` 为空。
- Base64 字段使用带填充的标准 Base64；nonce 使用不带填充的 Base64 URL 编码。

## 挑战时序

服务端在玩家加入后的 `t=0s`、`t=5s`、`t=10s` 分别发送 attempt `1`、`2`、`3`。每次挑战有效五秒，新 attempt 发出后旧 attempt 立即失效。第三次挑战到期仍无有效证明时执行 `VERIFICATION_FAILED`。

Challenge 使用原始 UTF-8 JSON，并在原规格字段后增加当前连接的玩家 UUID：

```json
{
  "protocolVersion": 1,
  "sessionId": "UUID",
  "attempt": 1,
  "nonce": "base64url-encoded-32-random-bytes",
  "keyId": "2026-01",
  "expiresAtEpochMs": 0,
  "playerUuid": "UUID"
}
```

客户端必须将 `playerUuid` 与当前 play 连接的玩家 UUID 比较。

## 分片布局

`yguard:attestation_fragment` 的消息体使用网络字节序，不增加字符串、数组或消息级长度前缀：

| 偏移 | 长度 | 字段 |
| --- | --- | --- |
| 0 | 8 | session UUID most-significant bits，int64 |
| 8 | 8 | session UUID least-significant bits，int64 |
| 16 | 4 | attempt，int32 |
| 20 | 4 | fragmentIndex，int32 |
| 24 | 4 | fragmentCount，int32 |
| 28 | 4 | dataLength，int32 |
| 32 | dataLength | 原始信封 UTF-8 字节片段 |

- `fragmentIndex` 从 `0` 开始。
- `fragmentCount` 范围为 `1..22`。
- `dataLength` 必须等于消息剩余字节数。
- 完整消息体最大 `24576` 字节，因此单片数据最大 `24544` 字节。
- 重组后的信封最大 `524288` 字节。

## 结果载荷

`yguard:attestation_result` 使用原始 UTF-8 JSON：

```json
{
  "protocolVersion": 1,
  "sessionId": "UUID",
  "attempt": 1,
  "status": "ACCEPTED"
}
```

`status` 仅允许：

| 值 | 含义 |
| --- | --- |
| `ACCEPTED` | 有效证明未命中违规，关闭会话 |
| `REJECTED` | 有效证明命中确定性违规，关闭会话并执行处罚 |
| `EXPIRED` | 第三次挑战到期，关闭会话并执行 `VERIFICATION_FAILED` |

格式、密钥或解密失败且仍可重试时不发送结果。

## Native 状态与空值

状态按以下固定顺序输出：

1. `NATIVE_UNAVAILABLE`
2. `HWID_UNAVAILABLE`
3. `PACKAGES_UNAVAILABLE`
4. `HOOK_UNAVAILABLE`

状态掩码为零时仅输出 `READY`。`READY` 不与其他状态同时出现。

- `NATIVE_UNAVAILABLE`：`hwidSha256` 与 `loadedPackagesBase64` 均为 `null`。
- `HWID_UNAVAILABLE`：`hwidSha256` 为 `null`。
- `PACKAGES_UNAVAILABLE`：`loadedPackagesBase64` 为 `null`。
- 成功枚举但集合为空时，`loadedPackagesBase64` 为标准 Base64 对空字节串的编码，即空字符串。

## 客户端发布资源

```text
META-INF/yguard/build-id.txt
META-INF/yguard/public-keys/index.txt
META-INF/yguard/public-keys/<keyId>.pem
META-INF/yguard-native/windows-x64/yguard_native.dll
META-INF/yguard-native/windows-x64/yguard_native.dll.sha256
```

- `build-id.txt` 为单行不可变随机 UUID。
- `index.txt` 每行一个 `keyId`，空行忽略。
- 公钥文件为 SPKI PEM。
- SHA-256 文件为单行 64 位小写十六进制摘要。
- 私钥只允许存在于服务端私有数据目录或构建输出，不进入客户端或源码仓库。
