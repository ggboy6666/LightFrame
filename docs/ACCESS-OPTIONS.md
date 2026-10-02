# 无 Root GPU 与授权整合

2026-10-01，用户选择同时推进现有授权整合和内置无线 ADB 研究。

## 0.2.5 实机更新（2026-10-02）

在未 Root 的 vivo PA2573 / Android 16 上，Shell UID 2000 可以读取 Perfetto `power/gpu_work_period` 和 `power/gpu_frequency`。0.2.5 已接入这条通道，游戏短时测试返回 GPU 工作忙碌率约 87.5%–88.6%，无需放开原有 `/sys` 节点权限。该数字表示同一 GPU 已报告完整工作时间的并集占比，保护内容可能未报告；它不是核心利用比例，也不保证覆盖全部硬件工作。

追踪约 8 秒完成后才解析，验证时钟、结束与刷新完成、缓冲丢失等证据，再发布延迟的 1 秒窗口。无完整工作区间或追踪不完整时显示缺失原因；暂停 / 结束清理自己的子进程和临时文件。其他厂商、系统版本及设备不自动列为已适配。具体证据与短时测试范围见 [0.2.5 验证记录](VALIDATION-0.2.5.md)。

当前保持 Shizuku / Root 授权向导，没有加入可用的内置无线 ADB 启动器。液冷不改变权限；本机没有已验证的无 Root 超频接口，未修改频率、电压或温控。

## 0.2.4 时的设备结论（历史记录）

LightFrame-diagnostics (4).txt 证实 vivo PA2573 / Android 16 的 Shizuku 采集 UID 为 2000，GPU 使用率、当前频率节点均未取得可信值。前台任务接口已在设备上成功读取。GPU 温度服务有读数，但温度不是 GPU 使用率。

不能据此说所有无 Root 手机都无法读取 GPU。当前这台设备的已探测接口被拒绝；即使把启动器放进轻帧，Shell 身份的权限仍不会自动升级。权限还取决于设备的能力集和 SELinux 上下文。[Shizuku API 权限说明](https://github.com/RikkaApps/Shizuku-API#differents-of-the-privilege-betweent-adb-and-root)

另一条待验证路径是 Perfetto 的 GPU 数据源。0.2.4 手动导出诊断时会查询当前已注册的数据源，最多 3 秒和 128KB，不启动追踪，不影响常规录制。注册某个数据源不能证明有有效利用率计数器；频率和渲染阶段也不能冒充使用率。下一步须根据设备返回的描述符验证短追踪，再决定是否接入。[GPU 数据源](https://perfetto.dev/docs/data-sources/gpu)、[命令说明](https://perfetto.dev/docs/reference/perfetto-cli)、[Shell 消费端权限模型](https://perfetto.dev/docs/design-docs/security-model)

## 0.2.4 已实现的授权整合

主页“采集授权向导”集中检测授权服务、显示 Shell / Root 身份、跳转 Shizuku、请求授权，并在返回轻帧后自动复检。未安装时可打开官方安装入口；首次无线调试的系统设置和配对仍由用户完成。录制后的“采集状态”按实际返回值显示各指标能否取得。

已有 Root 的设备可用原有 Root 后端；Sui 使用同一 API。Sui 是 Magisk 模块，需要设备已有相关 Root 条件，不能让普通 APK 自行取得 Root。Root 身份也不能保证厂商驱动暴露每一种 GPU 数据。[Sui 官方说明](https://github.com/RikkaApps/Sui)、[Android SELinux](https://source.android.com/docs/security/features/selinux)

## 内置无线 ADB 的可行方案

技术上可以减少切换其他应用：系统开启无线调试并提供配对码 → 轻帧内配对 → 发现本机连接端口 → 验证 Shell UID → 启动自己的采集进程 → 日常在轻帧启动 / 停止记录。Android 11 及以上支持在设备上完成无线启动；首次配对和系统确认无法省略，重启后需要重新启动服务。[Shizuku 官方启动流程](https://shizuku.rikka.app/guide/setup/)

现有 Shizuku 的内部配对服务未导出，普通第三方应用不能通过一个 Intent 调用其内部配对入口。完全内置需要实现自己的 ADB 配对和连接层。[Shizuku 官方组件声明](https://github.com/RikkaApps/Shizuku/blob/master/manager/src/main/AndroidManifest.xml)

候选为 libadb-android：提供配对、连接和 shell 服务 API，可保存设备本地生成的身份密钥，并对端口变化重新发现。具体落地需验证 Android 16 TLS 配对、进程启动和超时清理、应用更新后的采集器切换，以及手机 / 平板的分屏输入流程。使用 socket 还需要网络权限与 JNI / TLS 依赖；接入时应分开评估采集路径开销和启动器开销。当前 0.2.5 保持原依赖和权限，**没有内置可用的无线 ADB 启动器**。[libadb 官方仓库](https://github.com/MuntashirAkon/libadb-android)

许可边界：Shizuku 本体 Apache-2.0、API MIT；Sui 本体 GPL-3.0；libadb 有 Apache-2.0 / GPL-3.0 选择，另包含 LGPL 依赖。完整内置前需要保留对应许可并检查选用组件，不应仅把外部 APK 隐藏打包成普通 API。[Shizuku 许可](https://github.com/RikkaApps/Shizuku#license)、[API 许可](https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE)、[libadb 许可说明](https://github.com/MuntashirAkon/libadb-android#license)

研究结论：现有入口可以集中操作，首启仍需系统配对；自行内置 ADB 可减少额外应用依赖，但不会改变 GPU 的 Shell 权限边界。0.2.5 已验证这台设备的 GPU 工作区间和频率事件；无线 ADB 内置方案仍需独立实现与验证。
