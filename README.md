# JumpClean

面向 Jump 客户端的 LSPosed 界面净化与体验增强模块。

[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin-purple.svg)](https://kotlinlang.org)

> **设置入口指引**  
> 本模块无独立桌面图标。安装并激活后，在 Jump 客户端首页**长按底部导航栏「我的」Tab**，即可直接唤起模块设置面板。

## 功能

### 启动与隐私
- 跳过开屏广告
- 解除文本复制限制
- 禁止后台读取剪贴板

### 首页
- 隐藏推荐流广告
- 隐藏首页轮播广告
- 隐藏顶部话题
- 隐藏 Jumper 热议
- 隐藏发帖悬浮按钮

### 发现
- 隐藏顶部广告
- 隐藏轮播广告

### 内容与详情
- 隐藏帖子内嵌广告

### 个人中心
- 隐藏 Jump+ 会员卡片
- 隐藏我的订单入口
- 隐藏截图展示墙

### 底栏与小组件
- 隐藏底栏「Jump 赏」
- 隐藏底栏「抽奖 / 全新 App」
- 隐藏消息通知开启引导
- 隐藏小组件会员标识

### 个性化与拓展
- 更换 App 图标（修复官方遗漏图标，内置 21 款可选）
- 更多细项开关与实用特性，请在模块设置面板中自行探索与配置。

## 兼容性

- **目标应用**：Jump（包名 com.vgjump.jump）
- **系统要求**：Android 7.0（API 24）及以上
- **支持框架**：LSPosed / KernelSU / Magisk 等兼容 Xposed API 82+ 的框架

## 安装使用

1. 从 Releases 页面下载并安装最新版 APK。
2. 在 LSPosed 管理器中启用 JumpClean，并将作用域勾选为 Jump。
3. 强制停止 Jump 客户端并重新打开。
4. 在客户端首页**长按底部「我的」Tab**进入模块设置。

## 开源协议

本项目采用 GNU General Public License v3.0 协议开源。

## 免责声明

本项目仅供个人技术研究与学习交流使用，与 Jump 官方无任何隶属关系。使用本项目请遵循相关法律法规及服务条款，由此产生的任何后果由使用者自行承担。
