# YGuard 服务端插件实现规格

## 职责

服务端插件运行于 Paper 1.21.11 与 Java 21，负责接收客户端证明、校验、持久化封禁和执行处罚。客户端证明只能作为风险信号，不能证明客户端绝对未被篡改。

通信只使用游戏内自定义载荷：

| 通道 | 方向 | 用途 |
| --- | --- | --- |
| `yguard:attestation_challenge` | 服务端 -> 客户端 | 发送单次挑战 |
| `yguard:attestation_fragment` | 客户端 -> 服务端 | 回传加密证明信封分片 |
| `yguard:attestation_result` | 服务端 -> 客户端 | 通知客户端结束当前会话 |

## 挑战与证明协议

协议版本固定为 `1`。玩家进入服务器后不限制移动、交互或聊天。服务端为该在线玩家创建验证会话，并在 `t=0s`、`t=5s`、`t=10s` 发送挑战。任一次完整校验成功即关闭会话；第三次挑战过期仍未成功时，执行 `VERIFICATION_FAILED` 对应处罚。

挑战为 UTF-8 JSON：

```json
{
  "protocolVersion": 1,
  "sessionId": "UUID",
  "attempt": 1,
  "nonce": "base64url-encoded-32-random-bytes",
  "keyId": "2026-01",
  "expiresAtEpochMs": 0
}
```

服务端必须绑定 `sessionId`、`attempt`、`nonce`、玩家 UUID 和在线连接，拒绝过期、重复、错序或跨玩家响应。

客户端加密前的证明为 UTF-8 JSON：

```json
{
  "protocolVersion": 1,
  "sessionId": "UUID",
  "attempt": 1,
  "nonce": "base64url-encoded-32-random-bytes",
  "playerUuid": "UUID",
  "buildId": "immutable-release-build-id",
  "hwidSha256": "64-lowercase-hex-or-null",
  "loadedPackagesBase64": "base64-of-utf8-lf-separated-package-names-or-null",
  "nativeStatuses": ["READY"]
}
```

包名按 Unicode 代码点升序排序、去重，以 LF 连接并 UTF-8 编码，再使用标准 Base64 编码。仅上传包名，不上传完整类名；空包名不得出现。

加密流程固定如下：

1. 对证明 JSON 执行 GZIP 压缩，解压后最大 1 MiB。
2. 生成随机 32 字节 AES 密钥与 12 字节 IV，使用 `AES/GCM/NoPadding` 加密，认证标签为 128 位。
3. 使用挑战 `keyId` 对应的 RSA-3072 公钥和 `RSA/ECB/OAEPWithSHA-256AndMGF1Padding` 封装 AES 密钥。
4. 生成以下 UTF-8 JSON 信封，所有二进制字段使用标准 Base64：

```json
{
  "protocolVersion": 1,
  "sessionId": "UUID",
  "attempt": 1,
  "keyId": "2026-01",
  "encryptedKey": "base64",
  "iv": "base64",
  "ciphertext": "base64-including-gcm-tag"
}
```

信封 UTF-8 字节串通过 `yguard:attestation_fragment` 分片。该载荷使用二进制编解码，包含 `sessionId`、`attempt`、`fragmentIndex`、`fragmentCount` 与原始信封片段。单个完整自定义载荷最大 24 KiB，重组后信封最大 512 KiB。服务端必须限制分片数量、拒绝重复分片，并在挑战过期后释放缓存。

私钥以 PKCS#8 PEM 存放于插件私有数据目录；公钥以 SPKI PEM 内置于客户端。`keyId` 支持旧客户端过渡，服务端仅接受已配置且未撤销的私钥。

## 校验与处罚

在 AES-GCM 解密、GZIP 解压和字段绑定全部通过后，依次校验：

1. `hwidSha256` 是否已封禁。
2. `buildId` 是否在允许发布版本集合中。
3. 包名是否命中可疑包精确规则或包前缀规则。
4. `nativeStatuses` 是否包含 `NATIVE_UNAVAILABLE`、`HWID_UNAVAILABLE`、`PACKAGES_UNAVAILABLE` 或 `HOOK_UNAVAILABLE`。

上述是确定性违规，首次命中立即处罚。一次证明的全部命中必须写入审计，且只执行最严动作：`BAN_HWID_ACCOUNT` > `BAN_ACCOUNT` > `KICK` > `WARN`。无响应、分片不完整、格式错误、密钥错误或解密失败可继续重试，第三次仍无有效证明时执行 `VERIFICATION_FAILED` 对应处罚。

| 动作 | 行为 |
| --- | --- |
| `BAN_HWID_ACCOUNT` | 永久写入 HWID 和玩家 UUID 封禁，并断开连接 |
| `BAN_ACCOUNT` | 永久写入玩家 UUID 封禁，并断开连接 |
| `KICK` | 仅断开当前连接 |
| `WARN` | 写入审计并通知拥有 `yguard.admin` 权限的在线管理员 |

账号封禁在登录阶段检查；HWID 封禁在客户端证明到达后检查。每个检测类型都必须配置处罚，缺失配置时插件拒绝启动。

## 配置、存储与管理

存储后端支持 `sqlite` 与 `mysql`，默认 SQLite。至少维护以下表：

| 表 | 必填内容 |
| --- | --- |
| `bans` | 主键、主体类型、主体值、来源检测、创建时间、创建者、解除时间 |
| `audit_events` | 主键、玩家 UUID、用户名快照、会话 ID、检测类型、动作、详情、创建时间 |

封禁和审计永久保存，直到管理员手动处理。提供受 `yguard.admin` 保护的命令：

```text
/yguard unban account <uuid>
/yguard unban hwid <sha256>
```

命令解除后必须写入审计；管理员也可直接维护 SQLite 或 MySQL 数据库。

```yaml
storage:
  type: sqlite
  sqlite:
    file: yguard.db
  mysql:
    host: localhost
    port: 3306
    database: yguard
    username: yguard
    password: change-me
keys:
  activeKeyId: 2026-01
  privateKeys:
    2026-01: keys/2026-01-private.pem
allowedBuildIds: []
suspiciousPackages:
  exact: []
  prefixes: []
actions:
  HWID_BANNED: BAN_HWID_ACCOUNT
  BUILD_ID_INVALID: KICK
  SUSPICIOUS_PACKAGE: KICK
  NATIVE_UNAVAILABLE: KICK
  HWID_UNAVAILABLE: KICK
  PACKAGES_UNAVAILABLE: KICK
  HOOK_UNAVAILABLE: WARN
  VERIFICATION_FAILED: KICK
```

## 验收测试

- 合法证明在首次响应后结束会话，不限制玩家。
- 无响应或无客户端 Mod 时，第三次挑战过期后执行 `VERIFICATION_FAILED`。
- 已封 HWID、非法 `buildId`、可疑包和 Native 状态均在首次有效证明后立即执行配置动作。
- 篡改密文、nonce 重放、跨玩家响应、超限或重复分片、GZIP 解压超限均不得通过验证。
- SQLite 与 MySQL 均完成迁移、封禁查询、解封与审计写入。
