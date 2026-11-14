# NetworkStorage — Android 网络存储（SMB / WebDAV / SFTP）

一个只做一件事的 Android 示例应用：**添加网络存储**，然后浏览远程目录。
参考 MT 管理器等文件管理器的“添加网络存储”交互，用最小可运行代码复刻其核心链路。

## 功能

- **添加存储**：选择协议 → 填写地址 / 端口 / 账号 / 密码 / 共享名或路径 → 后台连接校验 → 加密保存
- **列表管理**：首页展示所有已保存的存储，长按删除
- **浏览目录**：进入某个存储后可按层级浏览远程文件与目录
- **凭据安全**：密码通过 `EncryptedSharedPreferences`（Android Keystore 托管的 AES-256 主密钥）加密存储，不落明文

## 支持协议

| 协议 | 实现依赖 | 认证方式 | 默认端口 |
|---|---|---|---|
| WebDAV | OkHttp（PROPFIND / PUT / DELETE / MKCOL） | HTTP Basic | 80 / 443 |
| SMB2 / SMB3 | [smbj](https://github.com/hierynomus/smbj) | NTLM（可选域） | 445 |
| SFTP | [JSch](http://www.jcraft.com/jsch/) | 密码 | 22 |

## 架构

```
model/RemoteFile.kt        统一文件模型（名称/路径/大小/是否目录/修改时间）
client/IStorageClient.kt   协议统一抽象（connect / listFiles / openInputStream / upload / createDirectory / delete / disconnect）
client/WebDavClient.kt     WebDAV 实现（OkHttp + DOM 解析 multistatus）
client/SmbClient.kt        SMB 实现（smbj，FILE_ATTRIBUTE_DIRECTORY 位判断目录）
client/SftpClient.kt       SFTP 实现（JSch，stat 判断目录）
data/StorageAccount.kt     配置模型 + 按协议创建客户端
data/StorageRepository.kt  EncryptedSharedPreferences 持久化（JSON 数组）
ui/MainActivity.kt         列表 / 新增 / 删除
ui/AddStorageActivity.kt   表单 + 后台连通性校验
ui/BrowseActivity.kt       目录浏览（后台线程 + runOnUiThread）
```

所有客户端实现均为**同步阻塞式**，UI 层用 `Thread` + `runOnUiThread` 调用，不依赖协程库。

## 构建

本地构建需要 JDK 17+ 与 Android SDK 34：

```bash
gradle :app:assembleDebug
```

或直接推送到 GitHub，由 `.github/workflows/build-apk.yml` 在云端编译，
产物在 Actions 运行页面的 **Artifacts → network-storage-debug-apk** 下载。

## 已知限制

- 只做“添加 + 浏览 + 基础读写”，没有做上传下载的文件选择器、断点续传、多线程传输
- WebDAV 目前只支持 Basic 认证，未实现 Digest；自签名 HTTPS 证书会被系统拒绝
- 列表操作会一次性把文件读入内存（`ByteArrayInputStream`），超大文件需改为流式处理
- SMB 仅访问单个共享，未实现共享枚举；SMB 列表未回填修改时间
- 未实现凭据的导入 / 导出、指纹校验（host key pinning）

## 许可

示例代码，可自由修改使用。
