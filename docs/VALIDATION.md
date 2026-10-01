# 验证记录 · LightFrame 0.2.0

日期：2026-10-01。

- Java 17 / Eclipse ECJ 3.38.0 编译成功；官方 Android SDK 35 / build-tools 35.0.0 打包成功。
- D8 release / min-api 26 编译成功。
- APK v2、v3 签名验证通过；开发测试密钥随源码保存。
- APK 包名 `com.lightframe.monitor`，版本 0.2.0 / code 2，minSdk 26、targetSdk 35。
- APK 文件 103179 字节。SHA-256：`75cbab6ef2a75c87d405536b15160036c334617df643b4b5feb2018a35a41dd8`。
- 50 项 JVM 数据逻辑检查通过。涉及 CPU guest 排除和差分、GPU GED / MTK 单位及索引、无数据 / 无效值、实际呈现时间排序去重、CSV 引号和中文、跨 128 行索引边界、按时间定位原始记录、曲线极值保留、原始行不被曲线合并改变、暂停旧帧过滤、Low / 帧时间百分位等。
- 1 万零 1 行合成 CSV 用于验证详细查看和图表；不代表手机的实际性能测试。
- Manifest 仅有悬浮窗、前台服务、通知、Shizuku API 权限；无联网、自启动、无障碍、相机、麦克风权限。
- APK 包含原创、Shizuku 和 AndroidX 许可证。

## 未完成的实机验证

没有运行模拟器或实体 Android 安装测试；原生界面布局、授权交互、悬浮窗行为和导出面板仍需实机确认。没有 vivo X300s / vivo Pad5 Pro 的诊断结果。Root / Shizuku / Sui 的实现通过编译，尚未验证设备上的权限链路和可读指标。GPU / CPU 热区节点、图层接口、采样准确性及实际内存、CPU、GPU、功耗开销均不能据此认定已适配。

界面根据用户录屏在原生 View / Canvas 中重写，尚未逐像素比对。没有自动获取、强制关闭其他应用或修改系统节点。FPS、Low、长帧和功率口径见 README。
