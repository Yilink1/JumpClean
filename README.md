# JumpClean

面向 Jump 客户端的 LSPosed 界面净化与体验增强模块。

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)

> [!IMPORTANT]
> **模块设置入口**：
> - **官方设置**：Jump「我的」页面 -> 点击「设置」->「JumpClean 模块设置」
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
| **个性拓展** | 诊断日志界面<br>开启调试日志<br>关键词屏蔽（支持仅推荐流 / 全局）<br>更换 App 图标（修复官方遗漏图标，含 21 款）<br>更多细项开关与特性，请在模块设置面板中自行配置 |

## 兼容性

| 项目         | 说明 |
|--------------|------|
| 目标应用     | Jump（包名 `com.vgjump.jump`） |
| 已测试版本   | Jump v3.0.17 ～ v3.0.30 |
| 适配说明     | 理论上支持 v3.0.17 及之后版本，但不保证后续版本完全兼容 |
| 系统要求     | Android 7.0（API 24）及以上 |
| 支持框架     | LSPosed 等兼容 Xposed API 82+ 的框架 |

## 安装使用

1. 从 [Releases](https://github.com/Yilink1/JumpClean/releases) 页面下载并安装最新版 APK。
2. 在 LSPosed 管理器中启用 JumpClean，并将作用域勾选为 **Jump**。
3. 强制停止 Jump 客户端并重新打开。
4. 在客户端「我的」->「设置」进入模块设置，或长按底部「我的」图标呼出。

## 开源协议

本项目采用 [GNU General Public License v3.0](LICENSE) 协议开源。

## 免责声明

本项目仅供个人技术研究与学习交流使用，与 Jump 官方无任何隶属关系。使用本项目请遵循相关法律法规及服务条款，由此产生的任何后果由使用者自行承担。
