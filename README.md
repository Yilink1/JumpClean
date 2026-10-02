<div align="center">

# JumpClean

面向 Jump 客户端的 LSPosed 界面净化与体验增强模块

[![Release](https://img.shields.io/github/v/release/Yilink1/JumpClean?color=00B875&label=Release)](https://github.com/Yilink1/JumpClean/releases)
[![Stars](https://img.shields.io/github/stars/Yilink1/JumpClean?style=flat&color=yellow&label=Stars)](https://github.com/Yilink1/JumpClean/stargazers)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)

<br>

<img src="https://count.yilink.uk/get/@jumpclean?theme=rule34" alt="JumpClean 访问量" />

</div>

---

> [!IMPORTANT]
> **模块设置入口**：
> - **常规入口**：「我的」 -> 「设置」->「模块设置」
> - **快捷手势**：在 Jump 首页**长按底部「我的」图标**即可直接呼出

## 功能

| 分类             | 功能说明 |
|------------------|----------|
| **启动与弹窗**   | 跳过开屏广告<br>屏蔽营销弹窗<br>屏蔽通知开启引导<br>屏蔽应用内通知广告 |
| **首页**         | 隐藏顶部话题栏<br>屏蔽首页轮播广告<br>隐藏「Jumper 热议」卡片<br>屏蔽推荐流与帖子内嵌广告<br>屏蔽 Jump 小酱推广贴<br>隐藏发布按钮 |
| **发现**       | 屏蔽顶部广告<br>屏蔽轮播广告 |
| **游戏折扣页**   | 屏蔽会员广告<br>屏蔽促销横幅广告<br>屏蔽低价排名广告<br>屏蔽滚动营销弹幕<br>隐藏「购前体验」按钮<br>隐藏「二手比价」栏 |
| **内容详情**   | 允许长按复制文本<br>恢复帖子完整年份<br>查看游戏评价总结 |
| **个人中心**     | 隐藏 Jump+ 会员卡片<br>隐藏「我的订单」<br>隐藏截图展示墙<br>隐藏小组件会员标识 |
| **底栏** | 隐藏「Jump 赏」<br>隐藏「抽奖 / 全新 App」|
| **个性拓展** | 诊断日志界面<br>开启调试日志<br>关键词屏蔽（支持仅推荐流 / 全局）<br>更换 App 图标（内置 21 款主题图标自由切换）<br>更多细项开关与特性，请在模块设置面板中自行配置 |

## 兼容性

- **目标客户端**：Jump（包名 `com.vgjump.jump`）
- **适配版本**：基于运行时特征动态探测，支持 Jump v3.5.0 ～ 最新版（理论兼容后续更新）
- **系统要求**：Android 7.0（API 24）及以上
- **支持框架**：LSPosed 等兼容 Xposed API 82+ 的框架

## 安装使用

1. 从 [Releases](https://github.com/Yilink1/JumpClean/releases) 页面下载并安装最新版 APK。
2. 在 LSPosed 管理器中启用 JumpClean，并将作用域勾选为 **Jump**。
3. 强制停止 Jump 客户端并重新打开。
4. 在客户端「我的」->「设置」进入模块设置，或长按底部「我的」图标呼出。

## 架构结构

```text
app/src/main/java/com/yilink/jumpclean/
├── MainHook.kt                  # 模块入口：单主进程过滤 / 生命周期分发 / 动态探针
├── SettingsActivity.kt          # 专属页面：激活检测 / 版本标识 / 双路径指引
├── constants/
│   └── JumpConstants.kt         # 配置常量：SharedPreferences 键名 / 默认值 / 宿主特征
├── hooks/                       # 核心拦截与净化引擎
│   ├── JumpHooks.kt             #   基础调度：广告拦截与通用 Hook 分发
│   ├── FeedHooks.kt             #   动态流引擎：双列适配 / 实时折叠与还原 / 流畅度优化
│   └── ViewCleanHooks.kt        #   控件净化：游戏折扣页净化 / 通知拦截 / 网络层阻断
└── ui/                          # 宿主内嵌设置交互组件
    ├── SettingsDialog.kt        #   主设置面板：免重启实时切换 / 打工终端结算
    ├── KeywordDialog.kt         #   关键词过滤：仅推荐流与全局双模式 / 多行自适应
    ├── LogDialog.kt             #   诊断日志：200 行运行日志持久化 / 错误高亮 / 异常防抖
    ├── EasterEggDialog.kt       #   实验性大饼：特性交互与彩蛋触发器
    └── IconPickerDialog.kt      #   图标选择器：内置 21 款主题图标自由切换
```

## 开源协议

本项目采用 [GNU General Public License v3.0](LICENSE) 协议开源。

## 免责声明

本项目仅供个人技术研究与学习交流使用，与 Jump 官方无任何隶属关系。使用本项目请遵循相关法律法规及服务条款，由此产生的任何后果由使用者自行承担。
