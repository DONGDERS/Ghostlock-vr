# GhostLock-App

> 基于上游 [YuKongA/ghostlock-app](https://github.com/YuKongA/ghostlock-app) main 分支

## 本分支变更

### W1c：vr.ko sys_exit 探针中和

部分厂商内核（如 vivo 的 `vr.ko`）会在 `__tracepoint_sys_exit` 上挂探针，在进程退出路径上核查已提权任务。本分支在执行链的 W1 阶段尾部加入 W1c 全局中和：

- 内核 tracepoint 的 `funcs` 指针为 `NULL` 即"未注册"态（回调迭代以 `funcs` 为空判据）。W1c 对 `__tracepoint_sys_exit->funcs` 做一次零写，使厂商探针永久失联——纯数据写，不挂钩子、不留回调
- profile 提供 `vr_sys_exit_tp`（由提取器从 kallsyms/BTF/反汇编解析出的 funcs 槽地址）；字段缺省时自动跳过，不影响无 vr 内核
- 写入未命中时仅降级为任务级清除，不会导致整次执行失败

### zstd 原生解码

OTA 提取链路的 zstd 帧解码由 Java 侧 aircompressor 改为 Rust 侧原生 `zstd` crate（`tools/extract_rs` 新增 `--zstd-decode <in> <out> <expected>` 服务模式），由 native 提取器直接完成解码与一致性校验，修复 `REPLACE_ZSTD` 场景的解码崩溃。

### 内置设备 profile

新增 `6.6.89-android15-8-gb57af212129c-abogki457297774-4k`（kernel_profiles 内置）。

## 文档

- Kernel Profile 适配指南：`docs/profile/`（模板位于 `docs/kernel_profiles/`）
- 提取器源码：`tools/extract_rs`

## 构建

```bash
./gradlew :app:assembleRelease
```
## License
本项目使用 [Apache License 2.0](LICENSE)。

This project is licensed under the [Apache License 2.0](LICENSE).
