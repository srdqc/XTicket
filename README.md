# 高校综合活动平台——C端票务交易与履约服务

面向高校综合活动运营，作为统一活动平台中的 C 端票务子模块，服务于学术会议及分会场、校园演出和大型晚会、体育赛事、对外开放讲座、校内培训与场馆活动等业务。

> 当前代码仍保留电影票务系统的领域命名和部分页面文案，例如 `movie`、`cinema`、`movie_schedule`、`wish`。本轮只重构文档，代码领域模型将在后续改造阶段处理。

## 1. 项目定位

本项目当前可验证的核心是 C 端票务交易链路：活动/场次浏览、座位图查询、座位锁定、创建待支付订单、积分模拟支付、超时关单、资源释放和用户关注。完整履约能力如电子票、核销、退款、运营后台仍处于规划中，不能作为当前成果描述。

## 2. 目标业务流程

| 流程节点 | 状态 | 说明 |
| -- | -- | -- |
| 活动及场次浏览 | 已实现 | 由现有 `movie`、`cinema`、`movie_schedule` 查询能力承载 |
| 票档或座位选择 | 部分实现 | 已实现固定座位选择；未实现票档管理 |
| 座位暂占 | 已实现 | `seat_lock` 表 + Redisson 场次级锁，15 分钟过期 |
| 创建待支付订单 | 已实现 | 写 `ticket_order`，绑定锁座 token，扣减库存 |
| 模拟支付或预约确认 | 已实现 | 当前为积分扣减式 Mock 支付 |
| 超时关单和资源释放 | 已实现 | 定时扫描 + 支付入口懒过期，释放 DB/Redis 库存和锁座 |
| 电子票生成 | 规划中 | 当前无电子票表、实体或生成逻辑 |
| 入场核销 | 规划中 | 当前无核销码、二维码和核销接口 |
| 用户取消与退款 | 部分实现 | 已支持用户取消待支付订单；退款申请和退款状态机未实现 |
| 消息通知 | 规划中 | 当前只有 RocketMQ 订单事件扩展点，没有真实通知通道 |
| 运营统计 | 规划中 | 当前无统计接口、指标或报表 |

## 3. 当前代码实现范围

- C 端浏览：活动/电影列表、场馆/影院列表、场次、座位图、搜索、城市。
- C 端交易：锁座、建待支付订单、积分支付、订单列表、订单详情、用户取消待支付订单。
- 一致性控制：Redisson 场次级/订单级有界锁、`seat_lock` 和 `order_seat` 唯一索引、Redis Lua 库存预扣/回滚、MySQL 乐观锁扣减库存、订单状态 CAS。
- 缓存与限流：Caffeine + Redis Cache-Aside，`@RateLimit` + AOP + Redis Lua 滑动窗口/令牌桶。
- MQ：RocketMQ 订单事件、想看写回、可选锁座缓冲。

## 4. 当前真实交易链路

```text
查询活动/场次
  -> GET /api/seat/layout 查询座位图
  -> POST /api/seat/lock 同步锁座，返回 lockToken
  -> POST /api/order/create 创建待支付订单
     -> Redis schedule:stock:{scheduleId} Lua 预扣
     -> MySQL movie_schedule 乐观锁扣库存
     -> ticket_order 插入订单
     -> seat_lock 绑定 order_no
  -> POST /api/payment/pay?orderNo= 积分支付
     -> sys_user 条件扣积分
     -> ticket_order status 0 -> 1
     -> order_seat 写已售座位
     -> seat_lock status 1 -> 2
  -> 超时或用户取消
     -> ticket_order status 0 -> 2
     -> 回滚 DB/Redis 库存
     -> 删除仍处于锁定中的 seat_lock
```

注意：当前没有 Redis Lua 单座锁座、Waiting Room、Outbox、RocketMQ 延迟关单、支付流水、电子票或核销。

## 5. 技术架构

| 层级 | 技术 |
| -- | -- |
| 前端 | Next.js 14、React 18、TypeScript、Tailwind CSS、Zustand、Axios |
| 后端 | Spring Boot 3.2、Java 17 源码目标、Maven 多模块单体、MyBatis-Plus |
| 数据库 | 本地 H2；Docker profile 使用 MySQL 8 |
| 缓存/锁 | Caffeine、Redis、Redisson |
| 消息队列 | RocketMQ |
| 部署 | Docker Compose、Nginx |

未使用：RabbitMQ、Dubbo、Sentinel、Guava RateLimiter、Seata、微服务拆分、分库分表。

## 6. 数据一致性边界

- DB 是最终权威：座位唯一性最终由 `seat_lock(schedule_id,row_num,col_num)` 和 `order_seat(schedule_id,row_num,col_num)` 约束兜底，库存最终由 `movie_schedule.available_seats` 和 `version` 控制。
- Redis 的真实职责：用于库存预扣、库存展示、缓存、限流和想看计数；它不是交易最终权威。
- 分布式锁的真实职责：降低同场次锁座/建单、同订单支付的并发冲突；锁失效时仍依赖 DB 约束兜底。
- MQ 的真实职责：订单事件扩展通知、想看异步写回、可选锁座缓冲；MQ 不参与最终支付状态和库存决策。
- 当前补偿方式：Redis 库存回滚失败会尝试写 `stock:dirty:rollback`，`ScheduleService.reconcileStock()` 每 5 分钟处理脏标并用 DB 库存覆盖 Redis。

## 7. 目标业务与旧代码概念映射

| 当前代码概念 | 目标业务概念 |
| -- | -- |
| `movie` | 活动 |
| `cinema` | 场馆 |
| `hall` | 会场/场地 |
| `movie_schedule` | 活动场次 |
| `order_seat` | 已出票/已确认座位 |
| `wish` | 活动关注/感兴趣 |

当前代码仍保留原电影票务领域命名，后续代码改造阶段再进行领域模型重命名。

## 8. 已知限制

- 电子票、核销码、二维码、入场核销、重复核销幂等未实现。
- 退款申请、退款状态机、支付流水、第三方支付回调未实现。
- 活动管理后台、场馆管理、场次管理、票档管理、库存人工校正、运营查询未实现。
- 请求级 `idempotencyKey` 未实现，建单重复提交主要依赖锁座、库存和状态约束兜底。
- RocketMQ 没有 Outbox、事务消息、完整消费幂等表、死信补偿后台。
- 多级缓存没有真正 singleflight，也没有空值缓存。
- 无自动化测试、压测脚本、真实 QPS/P95/P99 数据、traceId 和 Micrometer 指标。

## 9. 运行方式

本地开发默认使用 H2 内存数据库，不依赖 MySQL、Redis、RocketMQ，适合先看基础功能。默认配置排除了 Redis/Redisson 自动配置，相关能力会降级到 DB 或直接放行。

### 启动后端

```powershell
cd backend
.\mvnw.cmd -pl provider -am spring-boot:run
```

后端默认地址：

```text
http://localhost:8080
```

H2 控制台：

```text
http://localhost:8080/h2-console
JDBC URL: jdbc:h2:mem:maoyan
User: sa
Password: 留空
```

### 启动前端

```powershell
cd frontend
npm install
npm run dev
```

前端默认地址：

```text
http://localhost:3000
```

前端会通过 Next.js rewrites 把 `/ajax`、`/api`、`/dianying` 代理到 `http://localhost:8080`。

### Docker Compose 启动

Docker 模式会启动 MySQL、Redis、RocketMQ、后端、前端、Nginx。

```powershell
Copy-Item .env.example .env
```

修改 `.env` 里的密码和 `JWT_SECRET` 后，先打包后端 JAR：

```powershell
cd backend
.\mvnw.cmd -q -DskipTests package
cd ..
```

启动全栈：

```powershell
docker compose up -d --build
```

默认 Nginx 地址：

```text
http://localhost
```

## 10. 许可证

仅用于学习、课程设计、面试展示。正式商用前请自行补充许可证、合规声明和安全审计。保留仓库现有 LICENSE、版权或署名声明；不得删除许可证要求的法律声明。
