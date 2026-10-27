# Grasscutter Android

把 Grasscutter（原神 7.1 私服）打包成一个**不需要 Termux** 的安卓 App，兼容 Android 8.0 至 Android 16，后台以常驻通知服务运行。

## 界面
- **启动 / 停止**：拉起 proot 内的 MongoDB + 服务端（dispatch 10086 / 网关 22101）
- **服务端日志 / MongoDB 日志**：分别查看保存在应用私有目录中的追加日志，重开应用后仍可查看
- **指令输入**：在服务端运行时输入 Grasscutter 控制台命令并发送
- **导入 JAR**：选一个匹配资源版本的 `grasscutter.jar`（APK 不内置服务端 JAR）
- **导入资源包**：选 resources 压缩包（zip）；按 `folderStructure.resources` 自动保留 ZIP，或解压并整理为配置中的目录名
- **编辑 config.json**：内置编辑器，保存后重启服务生效

## 首次使用
1. 导入匹配资源版本的 `grasscutter.jar`
2. 导入对应版本的 Grasscutter 资源包 zip（约 1.5–2 GB）
3. 点启动，让 App 解压运行环境（Ubuntu 24.04 + JRE21 + MongoDB 7.0 + proot）并启动服务
4. 状态显示 `运行中 · dispatch 127.0.0.1:10086` 后连接游戏客户端
5. 客户端需打补丁并指向 `http://<手机IP>:10086`

默认资源路径为 `./Resources.zip`，导入时保留 ZIP。把 `folderStructure.resources` 改成例如 `./Resources/` 后，导入的 ZIP 会自动解压到 `Resources/`；压缩包只有一个共同顶层目录时会自动去掉该层并按配置目录名整理。默认水印文字为 `原神{uid}`，可在 `gameOptions.watermark` 中修改。

## 构建
推送到 `main` 触发 GitHub Actions：docker+binfmt 构建 arm64 rootfs → 打包 payload →
Gradle 编译签名 APK → 发布 Release（tag `build-<run>`）。失败时日志推到 `ci-log` 分支。
