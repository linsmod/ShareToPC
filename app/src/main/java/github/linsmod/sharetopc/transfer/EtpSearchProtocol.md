根据 README 文档和 `EtpClient.java` 源码，ETP 协议的完整情况如下：

---

## 翻页支持

**服务器支持，但客户端未实现。** 协议层面有两个命令：

| 命令 | 说明 | 默认值 |
|------|------|--------|
| `SITE EVERYTHING OFFSET <n>` | 从第 n 条开始返回结果 | `0` |
| `SITE EVERYTHING COUNT <n>` | 最多返回 n 条结果（`0` 表示无限制） | `0` |

当前 `EtpClient.query()` 只设置了搜索、列、排序参数，没有发送 `OFFSET` / `COUNT`，所以每次查询都是全量返回。要支持翻页只需在 `query()` 方法里加上这两个参数即可。

---

## EVERYTHING 命令完整特性一览

表中 **✓ 已实现** 表示 `EtpClient.query()` 已封装，**✗ 未封装** 表示协议支持但客户端未暴露。

| 分类 | 命令 | 已实现 | 说明 |
|------|------|--------|------|
| **搜索匹配** | `CASE` | ✓ | 区分大小写 |
| | `WHOLE_WORD` | ✓ | 全字匹配 |
| | `PATH` | ✓ | 匹配完整路径 |
| | `REGEX` | ✓ | 正则搜索 |
| | `DIACRITICS` | ✗ | 变音符匹配 |
| **搜索设置** | `SEARCH` | ✓ | 主搜索词 |
| | `FILTER_SEARCH` | ✗ | 二次过滤搜索 |
| | `FILTER_CASE` | ✗ | 二次过滤区分大小写 |
| | `FILTER_WHOLE_WORD` | ✗ | 二次过滤全字匹配 |
| | `FILTER_PATH` | ✗ | 二次过滤匹配路径 |
| | `FILTER_DIACRITICS` | ✗ | 二次过滤变音符 |
| | `FILTER_REGEX` | ✗ | 二次过滤正则 |
| **分页** | `OFFSET` | ✗ | 起始偏移 |
| | `COUNT` | ✗ | 返回条数上限 |
| **排序** | `SORT` | ✓ | 支持 16 种排序方向 |
| **返回列** | `SIZE_COLUMN` | ✓ | 文件大小 |
| | `PATH_COLUMN` | ✓ | 路径 |
| | `DATE_MODIFIED_COLUMN` | ✓ | 修改日期 |
| | `DATE_CREATED_COLUMN` | ✓ | 创建日期 |
| | `ATTRIBUTES_COLUMN` | ✓ | 文件属性 |
| | `FILE_LIST_FILENAME_COLUMN` | ✓ | 文件列表名 |
| | `DATE_RECENTLY_CHANGED_COLUMN` | ✓ | 最近变更日期 |
| **执行** | `QUERY` | ✓ | 执行查询 |

---

## 关键点

1. **分页**：协议原生支持 `OFFSET` + `COUNT`，当前客户端 `query()` 没有暴露这两个参数。如果需要翻页，改造很简单——在 `query()` 方法签名中加 `offset` 和 `count` 参数，查询前发送 `sendEverything("OFFSET", offset)` 和 `sendEverything("COUNT", count)` 即可。

2. **二次过滤（FILTER_*）**：Everything 支持在主搜索结果上叠加第二层过滤条件，当前未使用。可用于精细筛选。

3. **当前缺失但值得加的**：主要是 **OFFSET/COUNT（翻页）** 和 **DIACRITICS（变音符）**，其他 FILTER 系功能对移动端文件搜索场景价值不大。