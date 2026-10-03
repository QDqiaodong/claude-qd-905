# 玻璃深加工厂 · 原片与工序流水管理

以「**原片 → 切割 → 磨边 → 钢化 → 镀膜/中空/夹胶 → 检验 → 成品架**」为主线的玻璃深加工生产管理系统。

## 技术栈

- 后端：Spring Boot 3.3.0 + Java 17 + Spring Data JPA(Hibernate 6.5) + MySQL 8 + Redis
- 前端：Vue 3 + Element Plus + Vite（主色 `#1a237e`，玻璃拟态）
- 端口：前端 `8295`、后端 `8395`、MySQL `3595`、Redis `6595`

## 启动

```bash
./start.sh            # 镜像已存在时直接起
./start.sh --build    # 首次或改过代码后必须带 --build
./stop.sh             # 停止
./clean.sh            # 收摊（down -v）
```

## 五个模块

| # | 模块 | 主要页面 / 交互 |
|---|------|------------------|
| 1 | 原片与库位 | 立体货架图（区 → 架 → 层），玻璃批次块按种类配色，点块右侧滑出详情；规格串反解 |
| 2 | 切割与优化 | SVG 切割布局图 + 利用率条（<70% 橙、>95% 绿）+ 出片规格清单即时重算 |
| 3 | 磨边与钢化 | 钢化炉温控隧道（加热 → 淬冷 → 冷却），玻璃片逐段前进，参数滑块越界变红，破碎率环形图 |
| 4 | 深加工工序 | 玻璃结构剖面（镀膜膜层 / 中空气体腔 / 夹胶 PVB），分类参数记录与投产登记 |
| 5 | 检验与成品架 | 玻璃板缺陷分布打点（点大小按尺寸、颜色按类型）+ 类型占比条 + 成品架发货 |
| 6 | 返修质量闭环 | 返修批次工作区（隔离 / 处理中 / 待复检 / 已放行看板）+ 批次时间线（来源、归并解释、处置与冲突审计）+ 处置登记 / 复检放行 |

## 返修质量闭环：隔离 → 处置 → 复检放行

检验、深加工、成品架共享同一份批次质量状态（`rework_batch`），核心规则：

1. **判返修即隔离**：任一工序检验判 `REWORK` 即在同事务生成可追溯的返修隔离记录，
   保留来源工序、涉及片数、缺陷类型、原批次号 / 订单号与检验员。
2. **历史残缺记录的可解释归并**（每笔落 `rework_merge_log`，时间线可见）：
   - 批次号是实物强标识：批次号相同即同一实物，跨工序、不同单号也归并（`SAME_BATCH`）；
   - 订单号是弱标识：只有订单号且唯一匹配一张实物批次才归入（`ORDER_MATCH`）；
     仅订单号的记录先独立隔离（`ORDER_ONLY`），待批次号坐实后桥接收编（`ORDER_BRIDGE`，临时单留 `MERGED`）；
   - 订单同时指向多个实物批次（`REFUSE_ORDER_AMBIGUOUS`）或批次号与订单号冲突
     （`REFUSE_BATCH_CONFLICT`）一律拒绝合并、独立隔离待核实，绝不误并不同实物；
   - 批次号 / 订单号皆无的更老记录独立成单（`ISOLATED_NO_KEY`），旧记录不失联。
3. **受控流转**：`QUARANTINED → PROCESSING → INSPECTING → RELEASED`，
   只有合格复检才能放行；复检再判返修回隔离并递增返修轮次；放行后再判返修自动重新隔离。
   非法跳转由服务端拒绝。
4. **并发相反结论**：复检提交用行级悲观写锁串行化 + JPA `@Version` 乐观锁，
   两名检验员几乎同时提交相反结论时只有一个最终状态；落败方收到 409 明确冲突，
   其结论以独立事务写入 `CONFLICT` 审计事件（外层回滚不影响留痕）。工作区提供「双检验员并发对拍」演示。
5. **成品架质量门**：上架与发货在服务端统一读取批次质量状态（批次号优先、订单号兜底），
   隔离 / 处理中 / 待复检一律拦截并给出原因；已放行才可继续。已发运数据只读不倒改。
   前端在成品架行内显示质量门标签 / 拦截原因，深加工与缺陷打点页共享同一状态标签。

## 后端数据访问标识：JPA `@Access` 混合访问

实体整体为字段访问（`@Access(AccessType.FIELD)`），下列派生 / 受控列改为属性访问（`@Access(AccessType.PROPERTY)` 标在 getter 上）：

| 实体 | 派生列 | 规则 |
|------|--------|------|
| `GlassStock` | `spec_text` | 「6mm 2440×1830」拼接串；getter 拼出、setter 反解析回厚度 / 长 / 宽 |
| `CuttingOrder` | `utilization_rate` | 出片面积合计 / 原片面积，四舍五入两位；setter 校验 0~100%，越界抛 `BizException` |
| `CuttingPiece` | `area_sqm` | 长 × 宽 × 数量 / 1,000,000 |
| `TemperingBatch` | `temp_range` | 「660-700」温控区间串；getter 拼合、setter 拆成 tempLow / tempHigh |
| `FinishedRack` | `area_sqm` | 长 × 宽 × 片数 / 1,000,000；setter 反校验与规格片数是否吻合 |

接口返回里可以直接看到这些派生 / 解析后的值，例如 `GET /api/stock/list` 的 `specText`、
`GET /api/cutting/list` 的 `utilizationRate`、`GET /api/inspection/racks` 的 `areaSqm`。

## 手写 JPQL（`@Query`）

- `GlassStockRepository#statByGlassType` —— 按玻璃种类统计批次、片数与面积
- `InspectionRecordRepository#statVerdictByProcess` —— 按工序统计合格 / 返修 / 报废
- `CuttingOrderRepository#remnantRank` —— 原片余料排行
- `EdgeGrindingRepository#statByEdgeType`、`TemperingBatchRepository#statByFurnace`、
  `DeepProcessRepository#statByProcessType`、`InspectionRecordRepository#statByDefectType`、
  `FinishedRackRepository#statByShipStatus`

## 业务规则（service 抛 `BizException`）

1. 切割出片面积合计不得超过原片面积 —— 超出报错并给出超出面积
2. 钢化炉加热温度必须在 `620~720℃`
3. 中空玻璃露点高于 `-40℃` 判不合格
4. 夹胶 PVB 厚度与玻璃厚度不匹配不允许投产（给出该厚度可用的 PVB 规格）
5. 报废 / 破碎片数不得超过出炉片数
6. 原片库位、成品架承重超过上限不允许再上架 —— 报错并给出剩余承重
7. 磨边进给速度必须在边型允许区间内

## 主要接口

```
GET    /api/stock/list | /filter | /warehouse | /locations
POST   /api/stock/{id}/move   POST /api/stock/{id}/spec
GET    /api/cutting/list | /{id} | /remnant-rank    POST /api/cutting | /preview
GET    /api/process/grinding | /grinding/stat | /tempering | /tempering/{id} | /furnace-stat
POST   /api/process/tempering/{id}/advance | /discharge
GET    /api/deep/list | /list/{type} | /stat        POST /api/deep
GET    /api/inspection/list | /defect-map | /defect-stat | /racks | /rack-stat
POST   /api/inspection/racks/{id}/load | /ship
GET    /api/rework/batches?status= | /batches/{id}/timeline | /open
POST   /api/rework/batches/{id}/start | /dispose | /reinspect   （并发复检冲突返回 409 + CONFLICT 事件）
GET    /api/stats/overview | /glass-type | /verdict | /remnant | /edge | /furnace | /deep | /defect | /rack
```

## 目录

```
backend/src/main/java/com/glass/works/   domain / dto / repository / service / controller
backend/src/main/resources/schema.sql    mysql/schema.sql（两份相同，建表 + 种子数据）
frontend/src/                            App.vue（玻璃幕墙导航）、views/、api/、router/、styles/
```
