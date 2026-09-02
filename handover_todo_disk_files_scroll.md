# Handover: 磁盘文件浏览滚动问题

## 问题描述

从 DeviceTasksActivity 点击磁盘（如 C:）进入 EtpExplorerActivity 浏览模式后：

1. **无滚动条** — 列表内容多时看不到滚动条，用户不知道还有更多内容
2. **默认滚动位置在底部** — 进入目录后自动滚到了列表末尾，而不是从顶部开始

## 相关代码位置

### EtpExplorerActivity 浏览模式

- `app/src/main/java/github/linsmod/sharetopc/ui/EtpExplorerActivity.java`
  - `renderBrowseFolder()` (line ~495) — 渲染目录内容
  - `rvExplorer` — RecyclerView 实例

- `app/src/main/res/layout/activity_etp_explorer.xml`
  - `rvExplorer` (line ~206) — RecyclerView 定义，当前无 `scrollbar` 属性

### DeviceTasksActivity 入口

- `app/src/main/java/github/linsmod/sharetopc/ui/DeviceTasksActivity.java`
  - `showEtpExplorerBrowse()` (line ~210) — 传 `EXTRA_INITIAL_PATH` 跳转

- `app/src/main/java/github/linsmod/sharetopc/ui/EtpExplorerActivity.java`
  - `EXTRA_INITIAL_PATH` (line ~63) — 接收初始路径
  - `onCreate()` (line ~165) — `applyMode(true)` + `browseViewModel.openFolder(initialPath)`

## 待修复

### 1. 添加垂直滚动条

在 `activity_etp_explorer.xml` 的 `rvExplorer` 添加：
```xml
android:scrollbars="vertical"
```

### 2. 进入目录后滚动到顶部

`browseViewModel.openFolder(path)` 触发目录加载，加载完成后 RecyclerView 可能保留了之前的滚动位置。

修复方向：
- `openFolder()` 完成后调用 `rvExplorer.scrollToPosition(0)`
- 或在 `renderBrowseFolder()` 末尾加 `rvExplorer.scrollToPosition(0)`

## 验证步骤

1. 从设备列表 → 长按设备 → 点击设备进入 DeviceTasksActivity
2. 点击磁盘（如 C:）进入浏览模式
3. 确认：有垂直滚动条、列表从顶部开始显示
4. 进入子目录后同样确认以上两点
