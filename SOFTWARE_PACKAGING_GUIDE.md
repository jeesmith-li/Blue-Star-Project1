# 软件封装指南

## 蓝星 - 差评智控系统

版本：1.0.0  
封装日期：2026-09-15

---

## 目录

1. [封装方案概述](#封装方案概述)
2. [方案一：便携版（推荐）](#方案一便携版推荐)
3. [方案二：原生安装包](#方案二原生安装包)
4. [方案三：单 EXE 文件](#方案三单-exe-文件)
5. [用户部署指南](#用户部署指南)

---

## 封装方案概述

### 当前完成

✅ **便携版封装** - 使用批处理脚本，无需安装  
⏳ **原生安装包** - 使用 jpackage（需要 WiX Toolset）  
⏳ **单 EXE 封装** - 使用 launch4j（可选）

### 文件清单

```
项目根目录/
├── package.bat              # 打包脚本（创建便携版）
├── start.bat                # 启动脚本
├── stop.bat                 # 停止脚本
├── create-installer.bat     # 创建安装包脚本（jpackage）
└── target/
    └── meituan-review-automation-1.0.0-SNAPSHOT.jar
```

---

## 方案一：便携版（推荐）

### 特点

- ✅ 无需安装，解压即用
- ✅ 绿色软件，不写注册表
- ✅ 可放在 U 盘运行
- ✅ 数据库文件在应用目录
- ⚠️ 需要用户安装 Java 17+

### 创建步骤

#### 1. 运行打包脚本

```bash
package.bat
```

**输出：**
```
release/
└── 差评智控系统-v1.0.0/
    ├── app.jar              # 应用程序（166MB）
    ├── start.bat            # 启动脚本
    ├── stop.bat             # 停止脚本
    ├── 使用说明.txt          # 使用文档
    ├── data/                # 数据目录（自动创建）
    │   └── database/
    └── logs/                # 日志目录（自动创建）
```

#### 2. 打包发布

```bash
# 压缩为 ZIP
cd release
tar -a -c -f 差评智控系统-v1.0.0-便携版.zip 差评智控系统-v1.0.0
```

或使用 7-Zip / WinRAR 压缩

#### 3. 用户使用

```
1. 解压 ZIP 文件
2. 双击 start.bat
3. 浏览器自动打开 http://localhost:8080
4. 登录：admin / 123456
```

### 目录结构

运行后的目录：

```
差评智控系统-v1.0.0/
├── app.jar
├── start.bat
├── stop.bat
├── 使用说明.txt
├── data/
│   └── database/
│       └── badreview.db          # SQLite 数据库
└── logs/
    └── application.log           # 应用日志
```

### 优点

| 优点 | 说明 |
|------|------|
| 体积小 | 166MB（不含 JRE） |
| 启动快 | 使用系统 Java |
| 易更新 | 替换 app.jar 即可 |
| 易备份 | 复制整个目录 |
| 跨版本 | 支持 Java 17+ 任意版本 |

### 缺点

| 缺点 | 说明 |
|------|------|
| 需要 Java | 用户必须安装 JRE 17+ |
| 命令行 | 用户看到批处理窗口 |
| 无卸载器 | 手动删除目录 |

---

## 方案二：原生安装包

### 特点

- ✅ 包含 JRE，无需用户安装 Java
- ✅ Windows 原生安装体验
- ✅ 自动创建快捷方式
- ✅ 支持卸载程序
- ⚠️ 包体积大（约 300-400MB）
- ⚠️ 需要 WiX Toolset

### 前置条件

#### 1. 安装 WiX Toolset

**下载地址：** https://wixtoolset.org/releases/

```bash
# 下载 WiX 3.14+
# 安装后添加到 PATH
set PATH=%PATH%;C:\Program Files (x86)\WiX Toolset v3.14\bin
```

#### 2. 验证环境

```bash
# 检查 jpackage
jpackage --version

# 检查 WiX
candle.exe /?
```

### 创建步骤

#### 1. 运行安装包脚本

```bash
create-installer.bat
```

**执行过程：**
```
[1/6] 检查 Java 环境...
[2/6] 清理旧文件...
[3/6] 构建应用程序...
[4/6] 准备打包文件...
[5/6] 创建启动器...
[6/6] 创建 Windows 安装包...
```

**输出：**
```
installer/
└── 差评智控系统-1.0.0.msi          # 安装包（约 350MB）
```

#### 2. 安装包功能

- 📁 **安装目录：** `C:\Program Files\差评智控系统\`
- 🔗 **开始菜单：** 快捷方式
- 🖥️ **桌面图标：** 可选
- ❌ **卸载程序：** 控制面板
- ☕ **内置 JRE：** 包含 Java 17 运行时

#### 3. 自定义配置

编辑 `create-installer.bat` 中的 jpackage 参数：

```batch
jpackage ^
  --type msi ^                    # 安装包类型 (msi/exe)
  --name "差评智控系统" ^          # 应用名称
  --app-version 1.0.0 ^           # 版本号
  --vendor "蓝星科技" ^            # 供应商
  --icon app.ico ^                # 应用图标 (可选)
  --win-dir-chooser ^             # 允许选择安装目录
  --win-menu ^                    # 创建开始菜单
  --win-shortcut ^                # 创建快捷方式
  --java-options "-Xmx2048m"      # JVM 参数
```

### 高级选项

#### 添加应用图标

1. **准备图标文件**

```bash
# 将 PNG 转换为 ICO (256x256)
# 推荐工具: IcoFX, GIMP, ImageMagick

# 使用 ImageMagick
magick convert logo.png -resize 256x256 -define icon:auto-resize=256,128,64,48,32,16 app.ico
```

2. **添加到 jpackage**

```batch
--icon src\main\resources\app.ico ^
```

#### 指定 JRE 版本

```batch
--runtime-image "C:\Program Files\Java\jdk-17\jre" ^
```

#### 添加文件关联

```batch
--file-associations file-assoc.properties
```

**file-assoc.properties：**
```properties
extension=badreview
mime-type=application/x-badreview
description=差评数据文件
```

### 用户安装流程

```
1. 双击 差评智控系统-1.0.0.msi
2. 按照向导安装
3. 选择安装目录（默认 C:\Program Files\差评智控系统）
4. 完成安装
5. 从开始菜单启动应用
6. 浏览器自动打开
```

### 优缺点

| 优点 | 缺点 |
|------|------|
| ✅ 包含 JRE | ❌ 体积大（350MB+） |
| ✅ 专业安装体验 | ❌ 构建慢（5-10分钟） |
| ✅ 系统集成 | ❌ 需要 WiX Toolset |
| ✅ 自动更新支持 | ❌ 更新需要重新安装 |

---

## 方案三：单 EXE 文件

### 特点

- ✅ 单个 EXE 文件启动
- ✅ 无需命令行窗口
- ✅ 可自定义图标
- ⚠️ 仍需用户安装 Java
- ⚠️ 需要 launch4j 工具

### 前置条件

#### 1. 下载 launch4j

**下载地址：** https://launch4j.sourceforge.net/

解压到：`C:\Tools\launch4j\`

#### 2. 创建配置文件

**launch4j-config.xml：**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<launch4jConfig>
  <dontWrapJar>false</dontWrapJar>
  <headerType>gui</headerType>
  <jar>target\meituan-review-automation-1.0.0-SNAPSHOT.jar</jar>
  <outfile>差评智控系统.exe</outfile>
  <errTitle>差评智控系统</errTitle>
  <cmdLine></cmdLine>
  <chdir>.</chdir>
  <priority>normal</priority>
  <downloadUrl>https://www.oracle.com/java/technologies/downloads/</downloadUrl>
  <supportUrl>https://www.bluestar.com/support</supportUrl>
  <stayAlive>false</stayAlive>
  <restartOnCrash>false</restartOnCrash>
  <manifest></manifest>
  <icon>app.ico</icon>
  <jre>
    <path></path>
    <bundledJre64Bit>false</bundledJre64Bit>
    <bundledJreAsFallback>false</bundledJreAsFallback>
    <minVersion>17</minVersion>
    <maxVersion></maxVersion>
    <jdkPreference>preferJre</jdkPreference>
    <runtimeBits>64</runtimeBits>
  </jre>
  <versionInfo>
    <fileVersion>1.0.0.0</fileVersion>
    <txtFileVersion>1.0.0</txtFileVersion>
    <fileDescription>美团差评智能管理系统</fileDescription>
    <copyright>© 2026 蓝星科技</copyright>
    <productVersion>1.0.0.0</productVersion>
    <txtProductVersion>1.0.0</txtProductVersion>
    <productName>差评智控系统</productName>
    <companyName>蓝星科技</companyName>
    <internalName>BadReviewControl</internalName>
    <originalFilename>差评智控系统.exe</originalFilename>
  </versionInfo>
</launch4jConfig>
```

#### 3. 创建构建脚本

**create-exe.bat：**
```batch
@echo off
chcp 65001 >nul

echo 构建 JAR...
call mvn clean package spring-boot:repackage -DskipTests

echo 创建 EXE...
"C:\Tools\launch4j\launch4jc.exe" launch4j-config.xml

echo 完成！
pause
```

### 使用

```bash
create-exe.bat
```

**输出：**
```
差评智控系统.exe          # 可执行文件（166MB）
```

双击 EXE 即可启动，无命令行窗口。

### 优缺点

| 优点 | 缺点 |
|------|------|
| ✅ 单文件启动 | ❌ 仍需 Java |
| ✅ 无命令行窗口 | ❌ 体积大（166MB） |
| ✅ 自定义图标 | ❌ 配置复杂 |
| ✅ JRE 版本检测 | ❌ 启动稍慢 |

---

## 用户部署指南

### 方案对比

| 特性 | 便携版 | 原生安装包 | 单 EXE |
|------|--------|-----------|--------|
| 体积 | 166MB | 350MB | 166MB |
| 需要 Java | ✅ | ❌ | ✅ |
| 安装体验 | 解压即用 | 向导安装 | 双击启动 |
| 系统集成 | ❌ | ✅ | ❌ |
| 更新难度 | 容易 | 需重装 | 替换文件 |
| 适用场景 | 内部使用 | 外部发布 | 便携使用 |

### 推荐方案

#### 内部使用（IT 部门）
→ **便携版** - 简单快速，易于更新

#### 外部发布（客户）
→ **原生安装包** - 专业体验，包含 JRE

#### 演示/测试
→ **单 EXE** - 快速启动，无窗口

### 系统要求

**最低配置：**
- 操作系统：Windows 10 或更高版本
- 内存：4GB RAM
- 磁盘空间：500MB
- Java：JRE 17+ (便携版/单EXE) 或无需 (原生安装包)

**推荐配置：**
- 操作系统：Windows 11
- 内存：8GB RAM
- 磁盘空间：1GB
- 处理器：4 核以上

---

## 快速命令参考

```bash
# 创建便携版
package.bat

# 创建原生安装包（需要 WiX）
create-installer.bat

# 创建单 EXE（需要 launch4j）
create-exe.bat

# 测试运行
start.bat

# 停止应用
stop.bat
```

---

## 故障排除

### 问题：jpackage 命令不存在

**原因：** 使用了 JRE 而不是 JDK

**解决：** 安装 JDK 17+
```bash
# 下载 JDK
https://www.oracle.com/java/technologies/downloads/#java17
```

### 问题：WiX Toolset 不可用

**原因：** 未安装或未添加到 PATH

**解决：**
1. 安装 WiX Toolset 3.14+
2. 添加到系统 PATH
```batch
setx PATH "%PATH%;C:\Program Files (x86)\WiX Toolset v3.14\bin"
```

### 问题：应用启动失败

**原因：** 端口 8080 被占用

**解决：**
```bash
# 查找占用端口的进程
netstat -ano | findstr :8080

# 终止进程
taskkill /F /PID <进程ID>
```

### 问题：找不到 Java

**原因：** 未安装或 PATH 配置错误

**解决：**
```bash
# 检查 Java
java -version

# 配置 JAVA_HOME
setx JAVA_HOME "C:\Program Files\Java\jdk-17"
setx PATH "%PATH%;%JAVA_HOME%\bin"
```

---

## 总结

### ✅ 已完成

1. **便携版封装** - package.bat
2. **启动/停止脚本** - start.bat, stop.bat  
3. **原生安装包脚本** - create-installer.bat
4. **launch4j 配置** - 单 EXE 方案

### 📦 发布建议

**推荐：便携版**
- 快速构建：1 分钟
- 体积适中：166MB
- 易于更新：替换 JAR

**生产：原生安装包**
- 专业体验
- 包含 JRE
- 系统集成

---

**文档版本：** 1.0  
**更新日期：** 2026-09-15  
**技术支持：** 蓝星科技
