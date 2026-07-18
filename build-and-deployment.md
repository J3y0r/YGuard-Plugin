# YGuard 构建与部署指南

本文面向从源码构建并部署 YGuard 的管理员。服务端与客户端必须使用同一组 RSA-3072 密钥，且服务端 `allowedBuildIds` 必须包含客户端 JAR 内置的 `buildId`。

## 版本与前置条件

| 组件 | 要求 |
| --- | --- |
| 服务端 | Paper 1.21.11、Java 21 |
| 客户端 | NeoForge 1.21.11、Java 21、Windows x64 |
| Native 构建 | CMake 3.24+、Windows x64 C++ 工具链、Java 21 JDK |
| 存储 | 默认 SQLite；可选 MySQL 或 MariaDB |

客户端仅发布 Windows x64 Native DLL。在其他操作系统或架构上，客户端会报告 `NATIVE_UNAVAILABLE`；默认服务端策略会踢出该客户端。

所有命令均在 PowerShell 中执行。以下路径以仓库根目录为当前目录。

## 1. 生成并保管发布密钥

每个发布密钥由一个 `keyId`、一个 RSA-3072 PKCS#8 私钥和一个对应的 RSA-3072 SPKI 公钥组成。`keyId` 必须匹配 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`。私钥只能保存在受保护的构建环境和服务端，不得放入客户端 JAR、版本库或发布包。

使用 OpenSSL 生成生产密钥：

```powershell
$keyId = '2026-01'
$keyRoot = "$PWD\release\keys"
New-Item -ItemType Directory -Force "$keyRoot\public" | Out-Null
New-Item -ItemType Directory -Force "$keyRoot\private" | Out-Null
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$keyRoot\private\$keyId-private.pem"
openssl pkey -in "$keyRoot\private\$keyId-private.pem" -pubout -out "$keyRoot\public\$keyId.pem"
Set-Content -NoNewline -Encoding ascii "$keyRoot\public\index.txt" "$keyId`n"
```

如仅用于本地联调，可由客户端工程生成一对可导入的开发密钥：

```powershell
Push-Location YGuard-ClientSide-Mod
.\gradlew.bat generateYGuardDevelopmentKeyPair -PyguardKeyId=2026-01
Pop-Location
```

该任务输出到 `build\yguard-development`。其中 `public` 可作为客户端构建的公钥目录，`private\<keyId>-private.pem` 可复制到服务端插件数据目录。每次重新生成都会产生新密钥，因此生成后必须重新构建客户端，并同步更新服务端私钥。

## 2. 构建 Native DLL

在 `YGuard-ClientSide-Mod\YGuard-NativeLibs` 目录执行：

```powershell
Push-Location YGuard-ClientSide-Mod\YGuard-NativeLibs
$env:JAVA_HOME = 'C:\Program Files\Zulu\zulu-21'
cmake -S . -B build -G Ninja -DCMAKE_BUILD_TYPE=Release `
  "-DCMAKE_CXX_COMPILER=D:\Program Files\ollvm\bin\clang-cl.exe" `
  "-DYGUARD_JAVA_HOME=$env:JAVA_HOME"
cmake --build build --config Release
ctest --test-dir build --output-on-failure -C Release
Pop-Location
```

Ninja 单配置构建的输出为 `build\yguard_native.dll`；Visual Studio 多配置构建的输出为 `build\Release\yguard_native.dll`。客户端发布任务会自动识别这两个默认位置，也可通过 `-PyguardNativeDll` 明确指定 DLL。

## 3. 构建客户端 Mod

切换到 `YGuard-ClientSide-Mod`，准备公钥目录。目录必须包含 `index.txt` 和其中每个 `keyId` 对应的 `<keyId>.pem`：

```text
release\keys\public\
  index.txt
  2026-01.pem
```

`index.txt` 每行一个 `keyId`。公钥文件必须为 RSA-3072 SPKI PEM，即以 `-----BEGIN PUBLIC KEY-----` 开始。

生成一个固定的发布 `buildId`，然后执行发布构建：

```powershell
Push-Location YGuard-ClientSide-Mod
$buildId = [guid]::NewGuid().ToString()
.\gradlew.bat clean releaseJar --no-build-cache "-PyguardNativeDll=YGuard-NativeLibs\build\yguard_native.dll" "-PyguardPublicKeysDir=..\release\keys\public" "-PyguardBuildId=$buildId"
Pop-Location
```

若 Native DLL 位于 `build\Release`，将第一项改为 `YGuard-NativeLibs\build\Release\yguard_native.dll`。构建成功后的发布产物为：

```text
YGuard-ClientSide-Mod\build\libs\yguard-1.0-SNAPSHOT.jar
```

发布任务会把 `buildId`、公钥、DLL 和 DLL 的 SHA-256 写入 JAR；缺少 DLL、公钥索引、RSA-3072 公钥或非法 UUID 时构建会失败。发布前可执行：

```powershell
.\gradlew.bat test --rerun-tasks
jar tf build\libs\yguard-1.0-SNAPSHOT.jar | Select-String 'META-INF/yguard|META-INF/yguard-native'
```

记录 `$buildId`，后续服务端配置必须使用该值。每次新客户端发布都应使用新的 `buildId`。

## 4. 构建服务端插件

返回仓库根目录：

```powershell
.\gradlew.bat clean test shadowJar --no-build-cache
```

部署产物为：

```text
build\libs\YGuard-AntiCheat-1.0-SNAPSHOT.jar
```

该 JAR 已包含 Kotlin、SQLite JDBC、MySQL Connector/J 和 HikariCP；不要部署同目录的 `-plain.jar`。

## 5. 部署服务端插件

1. 停止 Paper 服务端。
2. 将 `YGuard-AntiCheat-1.0-SNAPSHOT.jar` 复制到 `<Paper>\plugins\`。
3. 启动一次服务端，使插件生成 `<Paper>\plugins\YGuard-AntiCheat\config.yml`，再停止服务端。
4. 创建 `<Paper>\plugins\YGuard-AntiCheat\keys\`，并将私钥复制为 `keys\2026-01-private.pem`。
5. 编辑 `config.yml`。配置严格校验，不能添加未知字段，所有检测类型都必须配置动作。

SQLite 示例：

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
allowedBuildIds:
  - REPLACE_WITH_CLIENT_BUILD_ID
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

将 `REPLACE_WITH_CLIENT_BUILD_ID` 替换为客户端发布命令产生的 `$buildId`。相对路径以插件数据目录为根，私钥路径不能指向该目录外。

首次启动会自动创建和迁移数据库。SQLite 数据库位于 `<Paper>\plugins\YGuard-AntiCheat\yguard.db`；将该文件纳入服务端备份。

## 6. 使用 MySQL 或 MariaDB

先建立数据库和最小权限账户。插件首次启动会创建并迁移 `schema_version`、`bans` 和 `audit_events` 表，因此账户需要目标数据库的 `CREATE`、`ALTER`、`INDEX`、`SELECT`、`INSERT`、`UPDATE` 与 `DELETE` 权限。

```sql
CREATE DATABASE yguard CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'yguard'@'PAPER_HOST' IDENTIFIED BY 'CHANGE_THIS_PASSWORD';
GRANT CREATE, ALTER, INDEX, SELECT, INSERT, UPDATE, DELETE ON yguard.* TO 'yguard'@'PAPER_HOST';
```

然后将 `config.yml` 的存储段改为：

```yaml
storage:
  type: mysql
  sqlite:
    file: yguard.db
  mysql:
    host: db.example.internal
    port: 3306
    database: yguard
    username: yguard
    password: CHANGE_THIS_PASSWORD
```

不要将数据库密码或私钥提交到版本库。生产环境应限制数据库仅接受 Paper 主机的连接，并定期备份数据库。

## 7. 部署客户端

将客户端 JAR 放入每个玩家的 NeoForge 1.21.11 实例的 `mods` 目录：

```text
<Minecraft instance>\mods\yguard-1.0-SNAPSHOT.jar
```

客户端需要 Windows x64 和 Java 21。Native DLL 由 Mod 从 JAR 解压到自身私有目录并校验 SHA-256 后加载，不需要单独分发 DLL 或手动复制到系统目录。

## 8. 联调与验收

1. 先确认 Paper 控制台出现 `YGuard enabled with sqlite storage` 或 `YGuard enabled with mysql storage`，且没有私钥、配置或数据库错误。
2. 使用安装了对应客户端 JAR 的 Windows x64 NeoForge 1.21.11 客户端连接服务端。
3. 等待至少 15 秒，覆盖 `t=0`、`t=5`、`t=10` 的挑战窗口。有效证明在首次成功后关闭会话，玩家不应被踢出。
4. 使用未安装 Mod 的客户端连接，默认配置下应在第三次挑战过期后被 `VERIFICATION_FAILED` 踢出。
5. 在服务端执行管理员命令验证持久化封禁管理：

```text
/yguard unban account <uuid>
/yguard unban hwid <64-lowercase-hex-sha256>
```

命令需要 `yguard.admin` 权限，默认授予 OP。封禁和解封操作都会记录到审计表。

## 9. 密钥与版本轮换

密钥轮换需要保证客户端先获得新公钥：

1. 生成新密钥对，将新公钥和旧公钥一起写入客户端 `index.txt`，构建并分发过渡版本客户端。
2. 在服务端 `privateKeys` 中同时配置新旧私钥，但保持旧 `activeKeyId`，重启并确认过渡版本客户端可验证。
3. 待过渡版本覆盖目标玩家后，将 `activeKeyId` 切换为新值并重启服务端。
4. 停用旧客户端后，移除旧私钥与旧公钥，再构建后续客户端版本。

客户端 `buildId` 轮换只需发布新 JAR，并在服务端 `allowedBuildIds` 中添加新 ID。确认旧版本停止使用后，再移除旧 ID。移除一个仍在使用的 `buildId` 会触发 `BUILD_ID_INVALID` 对应的动作。

## 10. 常见故障

| 现象 | 排查 |
| --- | --- |
| 插件启动即禁用 | 检查 `config.yml` 是否有未知字段、所有动作是否齐全、私钥是否为 RSA-3072 PKCS#8 PEM，及私钥路径是否位于插件数据目录内。 |
| 玩家立即因 `BUILD_ID_INVALID` 被处理 | 用构建时记录的 `$buildId` 更新 `allowedBuildIds`，并重启服务端。 |
| 玩家因 `NATIVE_UNAVAILABLE` 被处理 | 确认运行环境是 Windows x64、JAR 未被二次打包或篡改、Java 为 21，并检查客户端日志中的 DLL 加载失败信息。 |
| 客户端无法响应挑战 | 检查客户端与服务端均为 1.21.11，客户端 JAR 内含挑战 `keyId` 对应公钥，且该公钥与服务端私钥是一对密钥。 |
| MySQL 连接或迁移失败 | 检查网络、账户主机限制、密码及建表/索引权限；修复后重启服务端。 |
