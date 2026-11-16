# NetworkStorage — Android 网络存储（SMB / WebDAV / SFTP）

一个只做一件事的 Android 示例应用：**添加网络存储**，然后浏览、读写远程目录。
参考 MT 管理器等文件管理器的「添加网络存储」交互，用最小可运行代码复刻其核心链路。

## 下载 APK

- **最新版（推荐）**：[Releases 页面](https://github.com/zkkm2580/network-storage-app/releases/latest)
- **直链下载**：[network-storage-debug.apk](https://github.com/zkkm2580/network-storage-app/releases/download/latest/network-storage-debug.apk)

每次推送到 `main` 且 CI 构建成功后，会自动更新名为 `latest` 的 Release，直链始终指向最新构建，无需登录、不会过期。

## 功能

- **添加存储**：选择协议 → 填写地址 / 端口 / 账号 / 密码 / 共享名或路径 → 后台连接校验 → 加密保存
- **列表管理**：首页展示所有已保存的存储，长按删除
- **浏览目录**：层级浏览远程文件与目录，支持返回上级
- **文件操作**：点击文件可下载到应用私有目录、删除、查看详情；工具栏可上传文件、新建目录
- **凭据安全**：密码通过 `EncryptedSharedPreferences`（Android Keystore 托管的 AES-256 主密钥）加密存储，不落明文
- **深色模式**：通过 `values` / `values-night` 主题资源跟随系统

## 支持协议

| 协议 | 实现依赖 | 认证方式 | 默认端口 |
|---|---|---|---|
| WebDAV | OkHttp（PROPFIND / PUT / DELETE / MKCOL） | HTTP Basic，仅 HTTPS | 443 |
| SMB2 / SMB3 | [smbj](https://github.com/hierynomus/smbj) | NTLM（可选域） | 445 |
| SFTP | [mwiede/jsch](https://github.com/mwiede/jsch) | 密码 | 22 |

## 架构

```
model/RemoteFile.kt        统一文件模型（名称/路径/大小/是否目录/修改时间）
client/IStorageClient.kt   协议统一抽象 + 文件名安全校验工具
client/WebDavClient.kt     WebDAV 实现（OkHttp + 防 XXE 的 DOM 解析）
client/SmbClient.kt        SMB 实现（smbj，流式读取）
client/SftpClient.kt       SFTP 实现（JSch，TOFU 主机密钥锁定）
data/StorageAccount.kt     配置模型 + 按协议创建客户端
data/StorageRepository.kt  EncryptedSharedPreferences 持久化（JSON 数组）
ui/MainActivity.kt         列表 / 新增 / 删除
ui/AddStorageActivity.kt   表单 + 后台连通性校验
ui/BrowseActivity.kt       目录浏览 + 读写删（串行后台线程 + 生命周期检查）
```

所有客户端实现均为**同步阻塞式**；UI 层用 `Thread` + `runOnUiThread`，
通过 `synchronized` 串行化连接、请求序号丢弃过期结果，不依赖协程库。

## 安全策略

- **凭据**：Keystore 不可用时只退化为内存态（配置退出即丢失）并明确提示，绝不写入明文磁盘。
- **SFTP 主机密钥**：首次连接锁定服务器公钥并写入应用私有 `files/ssh/known_hosts`，
  之后以 `StrictHostKeyChecking=yes` 严格校验；密钥变更会直接拒绝连接（TOFU）。
- **WebDAV**：全局不再放行明文流量，表单拒绝 `http://`；XML 解析禁用 DOCTYPE 与外部实体（防 XXE）。
- **路径**：服务端返回的文件名经 `isSafeEntryName` 过滤，拒绝 `.` / `..` / 斜杠，避免路径穿越。
- **传输**：三个客户端均改为流式读取，不再把整个文件读入内存。

## 构建

本地构建需要 JDK 17、Gradle 8.7 与 Android SDK 34（仓库未包含 Gradle wrapper）：

```bash
gradle :app:assembleDebug
```

或直接推送到 GitHub，由 `.github/workflows/build-apk.yml` 在云端编译并发布到 Releases。
临时构建产物也可在 Actions 运行页面的 **Artifacts → network-storage-debug-apk** 下载。

## 已知限制

- 上传为整体读入内存后写入，单次限制 16 MB；暂无断点续传、多线程传输与进度显示
- 下载固定落到应用私有 `files/downloads/`，没有导出到公共存储、没有文件选择器另存
- WebDAV 只支持 Basic 认证（未实现 Digest）；自签名 HTTPS 证书会被系统拒绝
- SMB 仅访问单个共享，未实现共享枚举；SMB 修改时间依赖服务端返回，异常时回填 0
- SFTP 删除目录使用 `rmdir`（要求目录为空），SMB 使用递归 `rmdir`
- 未实现凭据导入 / 导出；`androidx.security:security-crypto:1.0.0` 的 `MasterKeys` 为遗留 API
- 无 Gradle wrapper、无单元测试、R8 未开启、包名仍为示例 `com.example.networkstorage`
- 发布的是 **debug 签名** APK，不能覆盖安装同包名的正式版

## 许可

示例代码，可自由修改使用。
