# 拷贝即译

在做英语阅读时不想复制再去搜 因此有了这个软件
<img width="1329" height="817" alt="image" src="https://github.com/user-attachments/assets/d54ba3fd-8b6f-47b1-ae82-fe0bcfe844b4" />
<img width="1323" height="762" alt="image" src="https://github.com/user-attachments/assets/09d0047a-0354-460e-aca7-2d6623dfa1f6" />
<img width="1291" height="786" alt="image" src="https://github.com/user-attachments/assets/69405af6-75ce-4950-aef3-1cc1c69496ea" />



## 功能

- 复制或选中英文单词后，查询免费词典接口并优先显示中文释义
- 使用悬浮窗在屏幕右上角弹出释义
- 默认 5 秒后自动消失
- 支持在应用内自定义消失时长
- 兼容 Android 16，项目已设置 `compileSdk = 36`、`targetSdk = 36`

## 触发方式

当前版本优先使用 `AccessibilityService`：

- 服务名：`复制单词悬浮释义`
- 实现文件：`WordAccessibilityService.kt`
- 服务配置：`app/src/main/res/xml/word_accessibility_service.xml`
- 悬浮窗类型：辅助功能专用 `TYPE_ACCESSIBILITY_OVERLAY`

这个方案会监听：

- 文本选择变化
- 复制菜单点击
- 剪贴板变化
- 窗口内容变化中的复制相关事件

在 Android 10+，尤其是 Android 16/MIUI 上，系统可能会拒绝后台应用或无障碍服务读取剪贴板。因此当前后台体验优先依赖“选中文本事件”：只要当前 App 向辅助功能暴露了选中的英文单词，服务会直接在后台弹出中文释义，不需要切回本软件。

为提高可用性，项目也注册了不显示界面的 `Process Text` 入口：

- 选中英文单词
- 在系统文本菜单中选择“悬浮释义”
- 使用普通悬浮窗在右上角显示中文释义，并自动返回原 App

同时保留了一个备用前台剪贴板监听服务：

- 实现文件：`ClipboardMonitorService.kt`
- 需要普通悬浮窗权限
- 受 Android 10+ 后台剪贴板限制影响更明显

## 免费接口

当前优先接入有道免费建议接口和金山词霸建议接口，用于显示中文释义；英文接口作为兜底：

`https://dict.youdao.com/suggest?q=hello&doctype=json`

`https://dict.iciba.com/dictionary/word/suggestion?word=hello`

`https://api.dictionaryapi.dev/api/v2/entries/en/hello`

## 如何运行

1. 用 Android Studio 打开这个目录
2. 同步 Gradle
3. 安装运行 App
4. 点击“打开辅助功能设置”
5. 在系统辅助功能列表中启用“复制单词悬浮释义”
6. 返回 App，可按需设置自动消失时长
7. 在其他应用里复制或选中英文单词测试

## 备用模式

如果某台设备的辅助功能事件不稳定，可以在 App 中：

1. 打开普通悬浮窗权限
2. 点击“启动备用监听”
3. 复制英文单词测试

## Android 10 到 Android 16 的限制

Android 10（API 29）开始，系统限制后台应用读取前台应用剪贴板。
## 注意
本项目采用“辅助功能服务 + 选中文本事件 + 剪贴板变化”的组合方式，尽量接近“复制即弹出”。但不同 App 和不同 ROM 对辅助功能事件暴露程度不同，所以仍可能出现某些页面无法触发的情况，因此引入root权限用于稳定性保活，可不授权。

