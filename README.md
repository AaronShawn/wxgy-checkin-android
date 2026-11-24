# 查寝打卡 · Android

无锡工艺职业技术学院晚查寝打卡的安卓客户端。桌面版 [wxgy-checkin](https://github.com/AaronShawn/wxgy-checkin) 的移动端移植：同一套 CAS 登录、同一套签名接口，改成**按一下、拍一张、就完事**。

> **仅供学习研究。** 本项目复刻的是一条绕过地理围栏、用单帧照片通过静默活体的流程。拿它完成实际考勤属于伪造考勤记录，可能被认定为违纪。是否使用、后果如何，由使用者自行承担。

## 和桌面版的区别

| | 桌面版 | 安卓版 |
| --- | --- | --- |
| 打卡方式 | 传一张静态自拍，21:30 后自动重试 | 点开就拍，拍完即传 |
| 定时 / 自动 | 有，后台常驻进程 | **删掉**，不做任何定时任务 |
| 照片 | 存放本地磁盘，反复复用 | **只走内存**，拍完上传，不落盘、不进相册 |
| 打卡状态 | 网页面板 | 首页大字状态，进应用自动刷新 |
| 跨天重置 | 零点定时器 + 看门狗 | 不需要，每次读取的是服务端实时状态 |
| 账号存储 | 明文 `data.json` | Android Keystore 加密后存 SharedPreferences |

保留的只有三件事：登录、看今天打没打、现场拍照提交。

## 安装

到 [Releases](https://github.com/AaronShawn/wxgy-checkin-android/releases/latest) 下载 `wxgy-checkin-android-v1.0.1.apk`，允许「安装未知来源应用」后安装。

- 支持 Android 7.0（API 24）及以上，arm64 与 armeabi-v7a 通吃
- 首次打开授予相机权限；不打字、不上传相册，只用相机
- 大小约 3 MB

## 用法

1. 打开应用，输入学号和密码登录（CAS 账号，和校内系统一致）；
2. 首页会显示姓名、学号、班级和今天的打卡状态；
3. 到 21:30 之后，点「现场拍照打卡」，把脸放进取景框，按快门；
4. 应用自动上传并轮询人脸核验结果，成功/失败都会直接提示。

打卡窗口由服务端控制（21:30 - 24:00），应用不做本地拦截，提前点会收到学校返回的「不在有效打卡时间段」。

## 相机为什么不会歪、不会拉伸

这是移植时最容易踩的坑，处理方式如下：

**预览不拉伸。** `PreviewView` 用 `FILL_CENTER`，按比例裁剪填满屏幕，而不是把画面按控件宽高强行拉伸；同时用 `UseCaseGroup` + `ViewPort` 把预览和拍照的裁剪区域对齐，做到取景框里看到什么、拍到的就是什么。

**竖屏锁定 + 固定 targetRotation。** Activity 锁竖屏，`Preview` 和 `ImageCapture` 的 `targetRotation` 都固定为 `Surface.ROTATION_0`。如果照抄网上「读 `previewView.display.rotation`」的写法，在锁屏应用里手持角度一变就会拿到 90/270，照片直接横躺。

**按 EXIF 真转像素。** 相机的 `SENSOR_ORIENTATION` 因机型而异（常见前置 270°、后置 90°），CameraX 只在 JPEG 里写一个 EXIF 方向标记，像素本身还是横的。上传前读取 EXIF，用 `Matrix` 把像素真正转正，再重新编码——重新编码会丢掉 EXIF，服务端拿到的就是一张方向确定的竖图，不依赖它怎么解释元数据。

**只在内存里走一遍。** 拍照用 `ImageCapture.takePicture(executor, OnImageCapturedCallback)`，拿到的是内存中的 `ImageProxy`，转成 byte 数组后直接进上传流程，`PhotoHolder` 用完即清。整个过程不写临时文件、不进 MediaStore、不申请存储权限。

**不同机型兜底。** 没有前置摄像头就退回后置；`ViewPort` 在某些设备上不被支持时退回普通绑定；解码按 2 的幂降采样，避免高像素机型 OOM；最长边压到 1280，重编码质量 88，单张通常在 200-400 KB。

## 技术说明

- **登录**：CAS OAuth2。学校登录页把密码倒序后做裸 RSA（**没有 PKCS#1 填充**，输出小写 hex），本应用用 `java.math.BigInteger` 复刻同一过程，并有单元测试与学校 JS 库逐字节比对；拿到 `ST-` ticket 后换 `accessToken`。
- **签名**：`appId=wxgycq` + `timestamp` + `nonce` 排序拼接 `&`，接原始 JSON body 与密钥做 SHA-256，放进 `sign` 头。
- **提交**：`POST /app-api/attendance/check/studentChecKlnSecret`，照片是 JPEG data URL，并在第 30 个字符处插入标记 `KwLiSlaC`；返回 `requestId` 后每 2 秒轮询核验结果。
- **登录态**：token 失效时用本地保存的账密自动重登一次，失败才回登录页。
- **网络**：学校站点证书链不完整，OkHttp 侧对这两个域名放宽了校验（与桌面版 `NODE_TLS_REJECT_UNAUTHORIZED=0` 等价）；CAS 走 http，`network_security_config` 只对 `wxgyxy.edu.cn` 放行明文。

## 自己构建

需要 JDK 17 与 Android SDK（compileSdk 34）。

```bash
cd android
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

`keystore/wxgy-release.jks` 与 `keystore.properties` 一并提交在仓库里，方便复现同一签名的安装包（覆盖安装不报签名冲突）。这只是一个个人项目用的演示密钥，不要拿它签任何正式产品。

## 目录

```
app/src/main/java/com/wxgy/checkin/
  Api.kt            # CAS 登录、RSA、签名、打卡、状态轮询
  Api 之外的三件事：
  ImageTools.kt     # EXIF 转正 + 降采样 + 重编码（全程内存）
  PhotoHolder.kt    # 照片内存暂存，用完即清
  Prefs.kt          # Keystore 加密的本地配置
  LoginActivity.kt  # 登录页
  MainActivity.kt   # 首页：今日状态 + 打卡入口
  CameraActivity.kt # 现场拍照页
```

## 免责声明

1. **用途限定。** 仅用于个人学习与安卓相机 / 网络鉴权实践。作者不提供打卡服务，不代打卡，不对任何账号的打卡结果负责。
2. **违纪风险。** 本项目具备绕过前端地理围栏、用单张照片通过静默活体的能力，用于实际考勤性质是伪造考勤记录，可能违反校纪校规。
3. **账号与数据风险。** 账号密码经 Android Keystore 加密后保存在本机，照片不落盘；作者不收集、不上传、无法获取任何使用者数据。频繁请求可能触发学校系统风控。
4. **可用性风险。** 接口改版、参数增删、加签变更、时间窗调整都会让工具失效，作者不承诺可用性。
5. **无关联声明。** 本项目与无锡工艺职业技术学院及其任何部门无隶属、合作或授权关系。

## 许可

[MIT](LICENSE) © 2026 wxgy-checkin contributors
