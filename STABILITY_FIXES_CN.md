# CamSwap 稳定性修复（第一阶段）

此阶段先修复帧流和解码器生命周期中的高风险竞态，不把 Camera1、Camera2、CameraX 和渲染器一次性重写，以免在没有真机回归测试的情况下引入更大回归。

## 已修改

### 1. VideoToFrames 的生命周期串行化

- `reset()` 不再在等待 500ms 后无条件清空线程引用、重置停止标志并启动第二个线程。
- 停止时会设置停止标志、发送中断并等待工作线程退出；超过 2 秒仍未退出时拒绝重启，避免旧解码器与新解码器并行发布帧。
- 每个解码线程在 `finally` 中清理自己的线程引用；在未运行或已经停止时不再发布帧。
- 解码器在切换素材、Camera1 停止/释放时会被停止。

### 2. 帧缓冲区所有权和同步

- `HookMain.publishFrame()` 在锁内更新共享帧；所有项目内读取点改成锁内复制或独立快照。
- `VideoToFrames` 不再把发布给消费者的同一工作数组立即交回 `BytePool` 复用。
- 可选的异步帧队列收到独立副本，避免队列消费者读到下一帧已覆盖的数据。
- 图像转换失败时也会关闭 `Image`，并按所有权回收工作数组。

### 3. 素材和相机生命周期

- 媒体源变化时先停止 Camera1 解码器并清空当前帧。
- `Camera.stopPreview()` 和 `Camera.release()` 时停止解码器并清空帧缓存。
- Camera1 回调所属的 Camera 对象发生变化时，先清掉上一个 Camera 的残留帧。
- 真实相机/旁路模式下直接调用原始回调，不再把虚拟帧复制到真实相机回调的数据中。
- `needStop` 改为在每次回调时重新判断，避免只在 Hook 安装时计算一次导致状态过期。

### 4. 可诊断性

- 对 Camera1 的 `setPreviewCallback`、`setOneShotPreviewCallback` 和 `setPreviewCallbackWithBuffer` 添加回调注册日志，用于确认回调注册顺序和回调类名。

## 尚待真机验证的事项

- 还没有在 Android 真机完成 Camera1、Camera2、CameraX、视频切换、前后台切换和扫码回归测试。
- 目标 App 自己注册 `setPreviewCallback()` 与扫码库注册 `setOneShotPreviewCallback()` 的冲突，是目标应用内部的回调生命周期问题。当前代码只增加通用诊断日志，没有为特定应用强行复原/多路分发其扫码回调，也没有修改其登录或身份验证业务逻辑。
- 本执行环境无法联网下载 Gradle，且没有 Android SDK，因此无法在这里完成 Android 构建。`javac` 静态检查受缺失 Android SDK 类影响，不能视为成功构建；已做改动文件的括号/引号结构检查、Manifest XML 解析以及共享帧直接读取点检查。
- 原始 ZIP 中的 `app/src/main/cpp/third_party/Dobby` 子模块目录不包含实际源码，完整构建还需要补齐 Dobby 子模块。

## 建议的真机验证步骤

1. 先构建并安装测试版本，确保 LSPosed/模块作用域只勾选你拥有或获准测试的应用。
2. 清空日志：`su -c 'logcat -c'`。
3. 打开测试应用，开始相机预览；切换素材 5–10 次，并观察画面是否还会冻结或回到旧素材。
4. 复现扫码时的回调注册过程，然后导出日志：

   ```sh
   su -c 'logcat -d -v time' | grep -E '【CS】|CamSwap-VideoDecoder|Camera1' > ~/camswap-scan.log
   ```

5. 上传 `camswap-scan.log`，重点查看 `setOneShotPreviewCallback` 与 `setPreviewCallback` 的先后顺序、回调类名，以及素材切换时是否出现“停止超时”。

## 构建注意

- 原工程要求 Android SDK 36、NDK 25.1.8937393、Gradle 8.11.1 和 Dobby 子模块。
- 此 ZIP 没有包含 Dobby 子模块的实际源码。若在这个 ZIP 上构建，可在工程根目录执行 `git clone --depth 1 https://github.com/jmpews/Dobby.git app/src/main/cpp/third_party/Dobby` 补齐它；如果使用 Git 仓库克隆，优先执行 `git submodule update --init --recursive`。
- 由于当前环境无法下载 Gradle 且没有 Android SDK，交付的是**源码修复包**，不是已经构建并在真机验证的 APK。
