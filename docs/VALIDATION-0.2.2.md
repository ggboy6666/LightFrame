# LightFrame 0.2.2 GPU 补充探测与图层归属修复

日期：2026-10-01。输入：用户 vivo PA2573 / Android 16 的 LightFrame-diagnostics (1).txt 与第二次录屏。

## 实机证据与未解决范围

用户已安装 0.2.1，采集 UID 2000。诊断 CPU 使用率为 25.387966240130684%，CPU 频率与 RAM 均有值。thermalservice 当前 HAL 提供 CPU/GPU/SoC 三类 40.011°C；相同数值来自设备报告，不能据此认定是三个独立物理传感器。

GPU 使用率、频率为 null，已有 GED/gpufreq/Mali 节点返回 EACCES 或 ENOENT。因此不能把“重新勾选悬浮窗指标”作为读取权限修复，也不能承诺本次升级恢复 GPU 数据。

最近帧来源 #228671 的父图层是轻帧自己的 com.lightframe.monitor 悬浮窗 Window#228670。原先仅按候选名称排除自己的包名，漏掉匿名子缓冲区，可能记录自身绘制 FPS。VivoScreenRecorder 是另一棵图层树，不能把 #228671 误归为录屏。

## 修改

- 增补 /sys/module/ged/parameters/gpu_loading 自动只读探测。MediaTek 官方文档说明其为 0–100 的系统 GPU 总负载；该文档针对 Genio，尚不能证明此 vivo 固件存在或授权 shell 读取。
- 同时只读检查 gpu_dvfs_enable；明确为 0 时不把清零负载当真实空闲值。启用状态不可读时保留负载接口的有效原始百分比，状态未知可通过诊断复核。
- 诊断保留同目录 gpu_block、gpu_idle、gpu_dvfs_enable 及用户自定义节点。block/idle 不替代使用率；没有将限频配置冒充当前 GPU 频率。
- 实时页面与悬浮窗的空 GPU 数据在权限拒绝时显示“读取受限”，驱动禁用时显示“接口未启用”；CSV 仍为空，原始 0% 在确认启用时保留。
- 根据 RequestedLayerState 的 id / parentId 追溯归属，自动排除轻帧、明确系统浮窗、FakeGestureBar、录屏图层及其后代。
- 保留合法游戏匿名 buffer、普通 2038 游戏辅助层和 2030 Presentation；包名匹配可继承祖先。完整诊断/手动图层列表不做此过滤。
- 版本名 0.2.2，安装及 Shizuku 采集器版本码 4；沿用原测试签名，可覆盖安装。

## 本机验证

- 372 项 JVM 检查通过：60 项核心与 GED 模块解析、256 项图层、14 项发现/来源统计、42 项温度。
- Windows / ECJ Java 8 / Android SDK 35 编译成功。
- APK 包名 com.lightframe.monitor，versionCode 4，versionName 0.2.2，minSdk 26，targetSdk 35。
- APK v2/v3 签名、ZIP CRC、4 字节对齐验证通过；最终 DEX 包含新 GED 模块、归属筛选和显示逻辑。
- 签名证书 SHA-256 与原始 APK 一致：254b3f18ef8ff2393efb6b7be8271991165b55f80ebec5eb5cbb59d502fd6886。
- APK SHA-256：c94a6eae589a062dcd408a43ff58a6e5690dbb73072a9ce2a1521ff3193e7331。

## 手机验证

停止并保存旧记录，覆盖安装 dist/LightFrame-0.2.2-beta.apk，然后重新打开并开始新记录。GPU 使用率节点保持空白以自动探测。针对录屏中的游戏可填目标包名 com.tencent.tmgp.supercell.clashofclans；固定图层保持空白。

如果 GPU 仍为空或显示“读取受限”，导出 0.2.2 采集诊断，检查新增 /sys/module/ged/parameters/gpu_loading 及 enable 的实际返回。这台设备未通过 USB/ADB 连接此电脑，本次只完成电脑上的代码、编译和 APK 验证；尚未安装 0.2.2 或确认新接口可用。GPU 当前频率没有找到新的可信来源，仍可能为空。

## 一级来源

- [MediaTek 官方 GPU 监测说明](https://genio-community.mediatek.com/t/how-to-monitor-npu-resource-utilization-during-inference/477/2)：Android gpu_loading 的路径及系统负载口径。
- [OPPO 官方 MT6989 GED GPL 源码](https://github.com/oppo-source/android_kernel_modules_oppo_mt6989/blob/oppo_mt6989_u_14.0.1_oppo_find_x7/kernel/kernel_device_modules-6.1/drivers/gpu/mediatek/ged/src/ged_dvfs.c)：module 参数、负载更新、启用状态与限频配置含义。此为同类平台证据，不是本机 vivo 固件源码。
