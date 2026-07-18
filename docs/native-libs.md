# YGuard NativeLibs 实现规格

## 范围

NativeLibs 是供客户端 Mod 通过 JNI 调用的 Windows x64 动态库。首版使用现有 CMake/C++11 项目，目标 JVM 为 Java 21 HotSpot。它负责生成不可逆 HWID 哈希、列举已加载 JVM 包名，并尽力阻断 JVMTI `RetransformClasses` 与 `RedefineClasses`。

该防护属于纵深防御：拥有本机进程控制权的攻击者可以绕过或篡改 NativeLibs。实现不得宣称或依赖绝对防注入能力。

## JNI 合约

NativeLibs 实现 `me.jeyor.yguard.nativebridge.NativeBridge.collectSnapshot()`，返回客户端 Mod 文档中定义的 `NativeSnapshot`。实现必须：

- 每次调用重新收集包名和 HWID。
- 使用线程安全的一次性初始化安装 JVMTI Hook。
- 将失败编码到 `statusMask`，而不是抛出导致游戏崩溃的未处理异常。
- 不写入原始硬件标识、包清单或 Hook 地址到日志。

Java VM 无法提供 JVMTI、JNI 初始化失败或 DLL 本身无法加载时，由 Java 层生成 `NATIVE_UNAVAILABLE`；Native 侧不得伪造成功快照。

## HWID 哈希

NativeLibs 读取以下 Windows 标识：

| 组件 | 来源 |
| --- | --- |
| `MACHINE_GUID` | `HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid` |
| `BIOS_UUID` | SMBIOS/WMI BIOS UUID |
| `BASEBOARD_SERIAL` | WMI 主板序列号 |
| `SYSTEM_VOLUME_SERIAL` | Windows 系统卷序列号 |

每个值必须去除前后空白和 NUL、转换为不依赖区域设置的大写 UTF-8；缺失值使用单个 `-`。按固定顺序拼接：

```text
MACHINE_GUID=<value>\n
BIOS_UUID=<value>\n
BASEBOARD_SERIAL=<value>\n
SYSTEM_VOLUME_SERIAL=<eight-uppercase-hex-digits>\n
```

对上述 UTF-8 字节计算 SHA-256，并返回 64 个小写十六进制字符。所有来源均不可用时，返回空 HWID 并设置 `HWID_UNAVAILABLE`。原始组件只在本地哈希计算期间存在，不得返回 JNI、写文件或上传。

## JVM 包名收集

NativeLibs 通过 `JavaVM::GetEnv(..., JVMTI_VERSION_1_2)` 获取 JVMTI 环境，并使用 `GetLoadedClasses` 与 `GetClassSignature` 枚举已加载类。

类签名必须规范化为二进制类名后提取包名：移除数组维度，处理 `Lpackage/Class;` 引用类型，将 `/` 转为 `.`，取最后一个 `.` 前的部分。原始类型、默认包类型和无法解析的签名不加入结果。返回数组不保证顺序；Java 侧负责最终排序和去重。

无法获得 JVMTI 环境、缺少能力或任一关键枚举调用失败时，返回空数组并设置 `PACKAGES_UNAVAILABLE`。

## JVMTI 防护

初始化阶段尝试进程内拦截 `RetransformClasses` 和 `RedefineClasses` 的实际 JVMTI 调用路径。两个拦截都成功时，后续命中调用不得执行原始重转换或重定义，而是直接返回 `JVMTI_ERROR_MUST_POSSESS_CAPABILITY`。

这是尽力而为的保护：不支持的 JVM 构建、符号无法解析、页面保护无法安全修改或任一拦截安装失败时，保留游戏运行并设置 `HOOK_UNAVAILABLE`。不得因 Hook 失败终止 JVM、无限递归、修改不属于当前进程的内存或调用外部注入程序。

Hook 安装和状态读取必须线程安全；拦截函数不得分配内存、写日志或执行 JNI 回调，以避免 JVM 重入和死锁。

## 构建与测试

- CMake 仅生成 Windows x64 `yguard_native.dll`，并显式使用目标 Java 21 的 JNI 头文件与导入库。
- 发布构建将 DLL 与 SHA-256 清单放入客户端 Mod 的资源路径；调试产物不得进入发布 JAR。
- 为 HWID 规范化与 SHA-256 输入构造单元测试，为类签名到包名的转换构造表驱动测试。
- 在支持的 Java 21 客户端进程中执行 JNI 冒烟测试，验证快照返回、状态位传播和两项 JVMTI Hook 的成功/失败路径。
