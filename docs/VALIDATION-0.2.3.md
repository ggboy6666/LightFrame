# LightFrame 0.2.3 历史浏览、FPS 与录制开销修复

日期：2026-10-01。安装及采集器版本码 5。输入为用户的 `LightFrame-diagnostics (3).txt`、115.98 秒录屏 `a943530450a1ea9160f1c62944d03a68.mp4`，以及用户确认“没有 Root”。

## 设备证据

- vivo PA2573 / Android 16，原安装版本 0.2.2，Shizuku 采集 UID 2000。最新日志中的 GPU 使用率和频率为 null；已探测的 GED、gpufreq、Mali 节点，包括 `/sys/module/ged/parameters/gpu_loading`，被拒绝访问或不存在。无 Root 条件下，当前没有验证通过的 GPU 使用率、当前频率来源。
- 录屏可见 CPU、RAM、温度数据；FPS 反复出现约 50 / 140 的平台并爬升，不能仅解释为一次启动暖机。历史 FPS 曲线有数据，但点选未展开详细数值，其他标签曾显示空详情。
- 日志的首次诊断采样里 CPU / 网络为空，是新采集器尚未形成差值基线；不能据此认定整段 CPU 记录缺失。CPU 策略频率和 RAM 已读到。
- thermalservice 报告 CPU / GPU / SoC 三类同为 57.811°C；相同数值来自系统，不能认定为三个独立物理传感器。
- 旧诊断的 `lastRecordingSample` 为空，无法用该字段复核录屏中全部游戏帧源。新版另存最后一次完整采样，停止或应用重启后诊断可回读。

## 历史浏览与数值

- CSV 索引改用 64KB 字节缓冲和稀疏偏移；保留 UTF-8、BOM、CRLF、引号、真实偏移与固定文件长度快照。半写尾行不进入统计，原始文件不改写。
- 修复会话、标签、选点和默认详情回填之间的异步冲突；用户显式选择优先，默认首行不会覆盖点选结果。
- 增加经过时间 / 本地日期时间跳转、起止区间、同步拖动、双指缩放、缩放 / 复位 / 半窗移动按钮。时间输入按毫秒显示，边界按原始实际时间处理。
- 详情显示最近实际行、时间差、全部指标、原始字段和缺失原因；上一条 / 上一帧限定在当前区间。空区间显示说明。
- 曲线按所选区间重新读取原始数据并取绘制包络；区间统计使用全部有效原始值，暂停和缺失单独计数。支持完整或当前区间采样 / 逐帧 CSV 导出。
- 旧记录打开时从原始 CSV 恢复缺失或损坏的摘要；正在采集或后台分析的记录只读快照，刷新后更新。

## FPS

- 使用固定容量原始时间数组，按最近呈现时间的完整约 1 秒窗口计算，避免以应用启动后累计时间作分母造成假爬升。首次进入、换源和恢复时先显示“采样中”。采集延迟不直接压低最近窗口 FPS；没有新呈现超过 1 秒时单独处理停止更新。
- 自动模式读取前台任务包名，按图层归属筛选；无法确认前台时提示填写目标包名。显式目标包名、固定图层仍可使用。
- 删除每 5 秒因候选探测时刻较新而替换仍有效图层的逻辑，减少反复换源和窗口重建。保留旧版对轻帧、系统浮窗、录屏及其匿名后代的排除。
- 检测有限帧缓冲未衔接，记录捕获缺口并分段重建窗口；缺失时间不伪造为一条长帧。暂停、换源、缺口不会计成跨段帧间隔。
- 历史旧版已经写错的 FPS 不能仅凭重算采样 CSV 自动纠正，缺失帧无法还原；请用新版开始新记录检验。

## 录制开销与离线统计

- 录制只写原始数据并维护必要实时 FPS，移除每次采样的 Low 直方图扫描和周期全记录摘要。固定数组减少逐帧对象分配。
- 停止时先关闭并保存原始文件、关闭采集器，再在后台流式计算均值、标准差、Low、分位数和长帧估算，显示进度。
- 摘要临时写入并原子替换；异常、取消、断写、原始数据变化不破坏已有摘要或原始数据。同记录并发恢复通过锁和指纹避免重复计算。
- 正常采集对读取失败节点退避 30 秒；手动诊断重新读取。温度解析按实际 thermalservice 更新缓存，保留真实数据年龄；悬浮窗绘制最多约每 500ms 一次。
- 增加“应用低开销预设”：帧 250ms、硬件 1s、温度 5s，下一次记录生效。高帧率且采集耗时较长时，有限缓冲仍可能溢出，应参考读取状态调整帧间隔。
- GPU 继续显示“读取受限”，原始空值保留为空，未取得的指标不会补成 0。

## 电脑验证

- 1,273 项 JVM 检查通过：核心 60、图层 256、发现 / 来源 14、温度 42、帧统计 678、节点退避 6、历史 94、离线分析 123。
- FPS 检查覆盖 1 / 5 / 30 / 60 / 120 / 165 / 240fps、采集延迟与抖动、重叠缓冲、暖机、暂停、换源、缓冲缺口；历史检查覆盖 60,001 行快照、区间、选点事件竞争、缩放和时间输入，包含末时刻向上 / 向下舍入的真实 CSV。
- 离线分析使用 200,000 行数据，32MB JVM 堆限制下通过；覆盖损坏摘要、部分尾行、极值、分段、取消、并发恢复、原子替换失败以及原始数据不变。
- Windows / ECJ Java 8 / Android SDK 35 完整编译成功。最终 APK 包名 `com.lightframe.monitor`，versionCode 5、versionName 0.2.3、minSdk 26、targetSdk 35，大小 140,043 字节。
- APK v2 / v3 签名、ZIP CRC、4 字节对齐均通过。DEX 已确认包含新历史、离线分析、前台任务、节点退避、时间边界处理和版本代码；测试用 JSON 实现未打入 APK。
- 签名证书 SHA-256 与原测试版一致：`254b3f18ef8ff2393efb6b7be8271991165b55f80ebec5eb5cbb59d502fd6886`。
- 最终 APK SHA-256：`435ffd914aea6ad9633f033a0b5d156ac1542f3f7bfbe981f2e4fb43beeb7a6b`。
- 本地源码：`D:\LightFrame-local\lightframe`；APK：`D:\LightFrame-local\dist\LightFrame-0.2.3-beta.apk`；源码 ZIP：`D:\LightFrame-local\dist\LightFrame-0.2.3-source.zip`。旧版 APK、源码 ZIP 和用户提供的诊断保持保留。

测试只验证数据与状态逻辑，不代表 Android 触摸事件、vivo 前台隐藏接口、Shizuku 连接、后台保活或实机 CPU / 内存 / 功耗已经验证。桌面大文件处理耗时也不是手机性能测量。

## 手机使用与核对

1. 停止旧记录，覆盖安装 `LightFrame-0.2.3-beta.apk`；不要卸载，以保留应用私有记录。启动 Shizuku 并授权轻帧，授权方式可选“Shizuku / Sui”。
2. 设置中应用低开销预设；固定图层留空。游戏包名可以留空自动确认；若显示无法确认前台应用，填写实际目标包名。本次录屏游戏可用 `com.tencent.tmgp.supercell.clashofclans`。
3. 进入游戏开始一条新记录；首次约 1 秒窗口显示“采样中”是预期行为。观察是否仍反复丢源或缓冲未衔接。
4. 停止并保存，等待统计完成。在记录打开指标标签，点选数值、横拖、双指缩放，使用“时间浏览”精确选点或选区间；用区间 CSV 交叉核对边界与原始值。
5. 实机开销可用悬浮窗中的轻帧 CPU、RSS 和采集耗时辅助观察，并与未记录时比较；这些指标不包含 Shizuku 管理器及系统代为处理请求的全部开销。

本次未连接手机，没有执行安装、触摸交互或游戏性能对比。电脑上完成源码、测试、编译和制品检查；GPU 权限限制没有解除。

## 官方接口核对来源

- [AOSP Android 16 ActivityTaskManagerService](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/services/core/java/com/android/server/wm/ActivityTaskManagerService.java)：前台根任务接口及权限检查。
- [AOSP Android 16 IActivityTaskManager](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/android16-release/core/java/android/app/IActivityTaskManager.aidl)：任务接口声明。
- [AOSP Shell 权限清单](https://android.googlesource.com/platform/frameworks/base.git/+/master/packages/Shell/AndroidManifest.xml)：系统 Shell 权限；厂商固件实际结果仍需手机诊断确认。
