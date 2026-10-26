# AstaPS Android APK

把 AstaPS（原神 7.1 私服）打包成一个**不需要 Termux** 的安卓 App，后台以常驻通知服务运行。

## 界面
- **启动 / 停止**：拉起 proot 内的 MongoDB + 服务端（dispatch 10086 / 网关 22101）
- **服务端日志 / MongoDB 日志**：分别查看保存在应用私有目录中的追加日志，重开应用后仍可查看
- **指令输入**：在服务端运行时输入 Grasscutter 控制台命令并发送
- **导入 JAR**：选一个 `grasscutter.jar`（自动替换内置版本）
- **导入资源包**：选 resources 压缩包（zip），App 内解压到 `Android/data/com.dudu2255.astaps/files/resources`
- **编辑 config.json**：内置编辑器，保存后重启服务生效

## 首次使用
1. 启动一次（或先导入 JAR），让 App 解压内置运行环境（Ubuntu 24.04 + JRE21 + MongoDB 7.0 + proot）
2. 导入资源包（AstaPS-Resource 打包的 zip，约 1.5–2 GB）
3. 点启动，状态显示 `运行中 · dispatch 127.0.0.1:10086`
4. 客户端需打补丁并指向 `http://<手机IP>:10086`

## 构建
推送到 `main` 触发 GitHub Actions：docker+binfmt 构建 arm64 rootfs → 打包 payload →
Gradle 编译签名 APK → 发布 Release（tag `build-<run>`）。失败时日志推到 `ci-log` 分支。
