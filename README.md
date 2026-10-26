# AstaPS Android APK

把 AstaPS（原神 7.1 私服）打包成一个**不需要 Termux** 的安卓 App，后台以常驻通知服务的方式运行。

## 原理
- APK 内嵌 `payload.tar.gz`：Ubuntu base (arm64) + JRE 21 + MongoDB 7.0 + proot + `grasscutter.jar`
- 首次启动解压到 App 私有目录，用 proot 起 `mongod` 与 `java -jar grasscutter.jar`
- 前台服务 + WakeLock，锁屏/切后台不中断
- 资源包（2.5 GB）不内嵌，App 里点「下载资源」从 AstaPS-Resource 仓库下载后「解压资源」

## 使用
1. 安装 APK → 打开 → 点「下载资源」，等下载完成
2. 点「解压资源」，等解压完成（约 5-10 分钟）
3. 点「启动」，状态变为 `运行中 · dispatch 127.0.0.1:10086`
4. 游戏客户端按需打补丁并指向 `http://<手机IP>:10086`

端口：dispatch 10086 / 网关 22101(UDP) / MongoDB 27017
存储占用：约 4 GB（运行环境 ~1.2 GB + 资源 ~2.5 GB）

## 构建
推送到 `main` 分支后 GitHub Actions 自动：下载 ubuntu-base/JRE/MongoDB/jar → 打包 payload →
Gradle 编译签名 APK → 上传 artifact 并发布 Release。
