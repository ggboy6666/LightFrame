# LightFrame 0.2.1 本机修复与验证

日期：2026-10-01。输入：用户的 vivo PA2573 / Android 16 录屏和 LightFrame-diagnostics.txt。

## 已确认的问题

诊断 UID 为 2000，CPU/RAM 有读数，Shizuku shell 连接正常。图层列表的 200 个条目为 RequestedLayerState 调试描述，旧采集器直接把整段描述传给按精确名称匹配的帧接口；自动发现还仅尝试前 8 个候选。GPU 的 GED / gpufreq / Mali 节点明确报 EACCES，不能通过调整数值解析取得读取权限。

## 本次修改

- 精确提取图层名称，保留内部空格、时间和 #id；模糊格式不擅自截断。
- 优先应用缓冲区，跨采样轮推进候选游标，不把所有候选限制为 8 个；隔离单个读取失败，全轮无数据后等待 5 秒再发现。
- 自动模式定期重新发现，仍是近期实际呈现时间的启发式；填写目标包名可减少多应用同时绘制时的来源歧义。
- 帧接口失败立即断开统计来源，避免保留旧 FPS/帧时间或把跨来源间隔算成超长帧。
- 增加 thermalservice 只读回退，区分 CPU/GPU/SoC、电池、皮肤和 BCL 百分比；不以电池温度替代芯片温度。
- 保留服务读取时间和数据来源；服务缓存未提供原始传感器更新时间时明确标记。
- 首页增加采集状态；GPU 权限拒绝明确显示，数值保留为空。
- 诊断增加当前配置、最近采样、图层原始描述、实际帧接口返回及温度服务原文。
- 安装版本和 Shizuku 采集器版本升为 3，显示版本 0.2.1。

## 已完成的本机验证

- 原有逻辑：50 项通过。
- 图层解析和真实诊断样本：229 项通过。
- 候选发现、异常隔离和跨来源统计：14 项通过。
- 温度服务解析和分类：42 项通过。
- Windows / ECJ Java 8 / Android SDK 35 编译成功；APK v2/v3 签名及 ZIP 对齐验证通过。
- 包名 com.lightframe.monitor，versionCode 3，minSdk 26，targetSdk 35。证书摘要与原始下载 APK 一致，支持覆盖安装。
- APK SHA-256：c279569e0dd9341d46ef9686ac384bb5ad961f3dae949acd149a56e4a82d07ec。

## 实机验证步骤与范围

停止旧记录，将 dist/LightFrame-0.2.1-beta.apk 传到手机覆盖安装，再重新打开轻帧。针对录屏中的游戏，目标包名填写 com.tencent.tmgp.supercell.clashofclans；固定图层保持为空。开始新的记录，进入游戏操作约 30 秒，再看 FPS 和采集状态。

尚未在该设备安装运行。旧诊断没有实际帧时间或 thermalservice 返回，不能据本机编译与纯逻辑测试断言实机 FPS、温度或开销已经恢复。受 EACCES 限制的 GPU 频率和使用率可能继续为空。如果 FPS 或芯片温度仍为空，导出新诊断；新日志中的 lastRecordingSample、latencyProbeSamples 和 thermalServiceRaw 可以区分图层选择、接口能力、服务数据与权限限制。

主源依据：[Android 16 SurfaceFlinger](https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android16-release/services/surfaceflinger/SurfaceFlinger.cpp)、[ThermalManagerService](https://android.googlesource.com/platform/frameworks/base/+/a6471a746be00bfe7702b8c3cd20afc470467327/services/core/java/com/android/server/power/ThermalManagerService.java)、[温度类型](https://android.googlesource.com/platform/hardware/interfaces/+/refs/heads/main/thermal/aidl/android/hardware/thermal/TemperatureType.aidl)。
