# MQTT 库存订阅

服务端可以向自己的 MQTT broker 发布库存状态，供 Home Assistant 或看板订阅。
Android、Windows 仍先写本地 SQLite，再通过原有同步上传；本地尚未同步的变化
不会立即出现在 MQTT 中。MQTT 默认关闭，客户端不需要 MQTT 设置。

## 配置

需要一个已有的 broker，例如 Home Assistant 使用的 Mosquitto。此服务是发布者，
不内置 broker，也不会自动连接公共 broker。

可以直接进入管理网页“设置”，填写 MQTT 连接表单后点击“保存 MQTT 配置”。
保存成功后按页面提示重启服务，配置才会生效；上方“运行状态”始终显示当前进程
的实际连接，不会因为保存成功而显示已连接。密码留空保留现值，勾选“明确清除已保存
密码”才删除它。页面提供状态手动刷新。

网页保存值在下次启动时优先于 `MQTT_*` 环境变量，保存在服务器 SQLite 的
`mqtt_configuration` 中。密码不通过 GET 或保存响应返回，但数据库备份包含
broker 凭据，应按服务器配置保护。尚未通过网页保存时，使用下表中的环境配置。

| 环境变量 | 默认值 | 用途 |
| --- | --- | --- |
| `MQTT_ENABLED` | `false` | 启用发布 |
| `MQTT_HOST` | 空 | broker 主机名或 IP，不含协议或账号 |
| `MQTT_PORT` | `1883` | TCP 端口；TLS 部署通常配置 `8883` |
| `MQTT_TLS` | `false` | 启用使用系统 CA 验证的 TLS |
| `MQTT_USERNAME` | 空 | broker 用户名 |
| `MQTT_PASSWORD` | 空 | broker 密码 |
| `MQTT_TOPIC_PREFIX` | `component-vault` | topic 前缀，不允许 `+` 或 `#` |
| `MQTT_CLIENT_ID` | `component-vault-server` | broker 上唯一的发布者 ID |

本地启动时在服务进程环境中设置这些变量。也可以复制 `server/.env.example` 为
`server/.env`，填写后在 `server` 目录执行
`.\.venv\Scripts\python.exe -m uvicorn app.main:app --env-file .env --host 0.0.0.0 --port 8787`；
现有 `run-dev.ps1` 需要显式加载 `.env`。Docker 部署推荐通过管理台设置。
如需使用环境变量，在 Compose 的 `api.environment` 下传入所需的 `MQTT_*`。
例如填入 `MQTT_HOST: ${MQTT_HOST:-}`，才会读取部署目录 `.env` 中的
`MQTT_HOST`。以下是可传入的配置示例：

```dotenv
MQTT_ENABLED=true
MQTT_HOST=192.168.1.20
MQTT_PORT=1883
MQTT_TLS=false
MQTT_TOPIC_PREFIX=component-vault
MQTT_CLIENT_ID=component-vault-server
```

按 broker 的实际认证要求设置用户名和密码。修改 Docker 环境变量后，重新创建 API
容器以加载配置。已有网页保存配置时应从网页修改。容器内的
`localhost` 是 API 容器本身；请使用容器能访问到的 broker 地址。当前支持一个
API 进程、一个 publisher，启动 Uvicorn 时不要增加 `--workers`。

## Topic 与消息

每个元件的 topic 为：

```text
component-vault/components/<percent-encoded component id>/state
```

这里使用同步实体 `id`，不是 SKU。普通 UUID 不需要转义；`/`、`+` 等特殊字符
会被百分号编码。可以先订阅 `component-vault/components/+/state`，再按收到的
topic 为具体元件配置传感器。示例消息：

```json
{
  "event_id": "component:example-component:42",
  "id": "example-component",
  "sku": "C49208388",
  "name": "MSKSEMI MSAP3032KTR-G1",
  "category": "LED驱动",
  "package_name": "SOT-23-6",
  "location": "嘉立创盒子",
  "quantity": 14,
  "min_stock": 1,
  "updated_at": "2026-09-15T01:00:00Z",
  "deleted": false,
  "sync_revision": 42
}
```

这是服务端接受后的完整状态快照，不是扣减命令，也不是库存差值。订阅者应将
`quantity` 作为最新值；不要每收到一次消息就再扣一次库存。说明文字和原始二维码
不发布。删除元件会发布相同结构、`deleted: true` 的 retained 消息；看板应隐藏它，
传感器应将其视为不可用。

发布使用 QoS 1 和 retained。数据库提交与 outbox 写入在同一事务中，回滚、被 LWW
拒绝的旧数据不会产生消息。后台线程依次发送，收到 PUBACK 才移除队列项，失败保留
并重连重试。进程可能在确认后、移除队列项前退出，因此消息可能重复；需要处理事件的
订阅者可用 `event_id` 去重，并用 `sync_revision` 比较同一数据库历史中的新旧状态。

首次启用、禁用后重新启用，或更换 host/port/TLS/prefix 时，会按当前数据库重新生成
全部元件快照（含删除标记）。更换目的地时，旧目的地的待发送历史会被当前快照替代；
原 broker 或旧 prefix 上已经 retained 的消息不会自动清理。正常重启继续发送原队列。
若同一个 broker 丢失 retained 数据，可以先从当前配置来源关闭 MQTT 并启动一次服务，再启用并重启，
触发完整快照。MQTT 关闭期间不累计新事件；重新启用恢复的是当前状态。

## Home Assistant 示例

先将 Home Assistant 的 MQTT 集成连接到同一个 broker。下面配置需将两处 topic
和 `unique_id` 替换为实际元件 ID。若已有 `mqtt:` 配置，请合并其 `sensor` 列表。

```yaml
mqtt:
  sensor:
    - name: "C49208388 库存"
      unique_id: "component_vault_example_component_stock"
      state_topic: "component-vault/components/example-component/state"
      value_template: "{{ value_json.quantity }}"
      unit_of_measurement: "个"
      qos: 1
      availability_topic: "component-vault/components/example-component/state"
      availability_template: "{{ 'offline' if value_json.deleted else 'online' }}"
      payload_available: "online"
      payload_not_available: "offline"
```

retained 状态让后来连接的订阅者得到最后已发布的库存。这里的 availability 仅反映
元件是否删除，不表示手机在线或服务端在线；本版没有 Home Assistant 自动发现或
服务端在线心跳。配置方式参见 [Home Assistant MQTT Sensor 官方文档](https://www.home-assistant.io/integrations/sensor.mqtt/)。

## 状态与排查

携带现有 API bearer token 请求 `GET /admin-api/mqtt/status`，响应包括
`enabled`、`connected`、`pending`、`last_publish_at`、`error`。不返回账号或密码。
`last_publish_at` 是本次服务进程最后确认发布的时间，重启后可能为空。

配置读取和保存使用同样鉴权的 `GET/POST /admin-api/mqtt/config`。
响应中的 `restart_required` 表示保存值和本次运行设置不同。

- `enabled=false`：检查 API 进程实际环境，修改配置后重启。
- `connected=false`：检查 broker 地址、端口、认证、证书与防火墙。
- `pending` 持续增加：broker 不可用时队列保存在 SQLite 中，应监控磁盘空间。
- 手机已改数量但订阅值未变：先确认客户端同步成功，再检查发布队列。

已有服务升级前，停止 API 并备份 SQLite。启动会新增 `mqtt_outbox`、`mqtt_state`、`mqtt_configuration`，
不改元件数量。关闭 MQTT 可停止发布，原本的客户端本地操作与同步继续工作。
开发测试使用临时数据库与模拟 PUBACK；实际 broker 和 Home Assistant 仍需部署后联调。
