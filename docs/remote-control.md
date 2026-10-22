# 局域网遥控协议逆向（坚果控 App ↔ 投影仪 Anymote）

对官方「坚果控」App（`com.jmgo.funcontrol`，versionName `5.0.8.001`）与投影仪预装服务
`GoogleRemote.apk`（包名 `com.softwinner.tvremoteserver`）之间的局域网遥控协议
（Google Anymote 精简版）的完整逆向记录，并与 [设置项 API](settings-api.md) 做了交叉验证。

结论先行：**自研 App 想遥控投影仪，只需在局域网内实现本协议即可**
（UDP 9101 发现 + TCP 9005 发 protobuf 按键消息，无鉴权、无配对）。

> 证据来源：`坚果控.apk` 反编译（`classes3.dex`，Java 原生控制层）与固件内
> `GoogleRemote.apk`（`classes.dex`）反汇编。文中 `@0x…` 为该 dex 内指令偏移，
> 可用本仓库思路（自研小工具）或 baksmali 复现。所用 App 层类均在
> `com.jmgo.devicecontrol.*`，服务端在 `com.softwinner.tvremoteserver.*`。

## 1. 架构与端口

| 通道 | 端口 | 协议 | 说明 |
|---|---|---|---|
| 设备发现 | UDP 9101 | 明文广播 | App 每 3s 广播一次 `discover…`，设备单播应答 |
| 遥控控制 | TCP 9005 | Anymote（protobuf，长度前缀） | **主通道**，所有按键/鼠标/文本走这里；无 TLS、无鉴权 |
| 配对 | TCP 9001 | TLS（ParingServer） | 官方 Google TV 遥控配对流程，坚果控默认**不用** |

设备端服务由 `TvRemoteService`（`GoogleRemote.apk`）启动，`onCreate`/`startServer`
（@0xf3f7c / @0xf411c）分别创建：

- `BroadcastRspThread(9000)`：监听 UDP 9101 并应答（应答里的"端口"字段是 9000，**不是**实际控制端口）；
- `AnymoteNoSSLServer(9005)`：TCP 9005 控制服务；
- `ParingServer(9001)`：TLS 配对服务（备用）。

App 端对应实现：`UdpDiscover`（发现）、`TcpClient`/`TcpClientV2`（默认端口 9005，
@0x14a928）、`RemoteMessageManager`（消息编解码）、`HeartPingManager`（心跳）。

## 2. 设备发现（UDP 9101）

**请求**（App → 子网广播地址 :9101，每 3000 ms 发一次，`UdpDiscover.scanDevices` @0x14bcc4、
`UdpDiscover$1.run` @0x14bbac；广播地址 = `ip & mask | ~mask`，取不到 DHPC 时退回 255.255.255.255）：

```
discover _anymote._tcp 9101\n
```

**应答**（设备单播回请求来源 IP，`BroadcastRspThread.handleBroadcast` @0xafca0）：

```
_anymote._tcp <model> <port> <wifiMac> <sn>\n
```

| 字段 | 来源（设备端） | 示例 |
|---|---|---|
| model | `ro.product.model`；若含 `S60` 则强制替换为 `JmGO_MC` | `JmGO_M6` |
| port | 构造参数（固定 9000，仅历史字段，App 忽略） | `9000` |
| wifiMac | `getLocalMacAddressFromIp()`（由本机 IP 反查 ARP） | `aa:bb:cc:dd:ee:ff` |
| sn | **`HolatekOSManager.getString(0, 147)`** | `M6xxxxxxx` |

App 端 `UdpBroadcast$ReceiveThread.run`（@0x14b6d0）收到后把来源 IP 拼到行首
（`"<ip> " + payload`）再交给上层按空白分割解析，最终 tokens =
`[ip, "_anymote._tcp", model, port, mac, sn]`。

> **交叉验证**：设备端取序列号用的 `HolatekOSManager.getString(0, 147)` 与
> [设置项 API](settings-api.md) 中逆向出的命令表 `147 = SN` 完全一致
> （两处独立逆向路径相互印证）。`TvRemoteService.onCreate` 中同样可以看到
> `getSystemService("holatekos_mananger")`。

## 3. 控制通道（TCP 9005）

- App 连接 `TcpClient`（默认 9005）后**不发任何握手**，直接发送 `RemoteMessage`
  帧（protobuf **length-delimited**：varint 长度 + message，`writeDelimitedTo`）。
- 设备端 `AnymoteNoSSLServer.run`（@0xaefa8）接受连接后用
  `AnymoteFactory.getServerAdapter` 包装 socket：
  1. 立即推送 `Data{type="volume", data="<当前音量>"}`（@0xaf04c）；
  2. 紧接着再推送一条 `Data{type="sysconfig", data="{\"product_model\":\"<model>\",\"volume\":\"<vol>\",\"device\":\"<model>\"}"}`
     （@0xaf2de，间隔 100 ms）。
- App 端把收到 `sysconfig` 当作"连接就绪"标志（`OverlayMainActivity.onEvent`
  日志 `connect success, wait sysconfig start` → `sysconfig received`，@0x1bb854）。
- 之后 App 按需发送按键/鼠标/文本/投屏消息；心跳由 `HeartPingManager.sendPingData`
  （@0x140e6c）周期发送 **空的 Fling 消息**（`Fling{uri=""}`）实现。

## 4. 消息 Schema（字段号逆向自双方 writeTo/Builder 调用）

以下为 protobuf 定义（字段号 >= 1）：

```proto
message RemoteMessage {
  optional uint32         sequence_number  = 1;
  optional RequestMessage request_message  = 2;   // App → 设备
  optional ResponseMessage response_message = 3;  // 设备 → App
}

message RequestMessage {
  optional KeyEvent     key_event_message    = 1;
  optional MouseEvent   mouse_event_message  = 2;
  optional MouseWheel   mouse_wheel_message  = 3;
  optional DataMessage  data_message         = 4;
  optional Connect      connect_message      = 5;
  optional Fling        fling_message        = 6;
}

message ResponseMessage {
  optional DataMessage  data_message      = 1;
  optional FlingResult  fling_result      = 3;
}

message KeyEvent {
  optional Action action  = 1;   // UP=0, DOWN=1（枚举名与 Android 相反，注意！）
  optional Code   keycode = 2;   // 标准 Android KeyEvent keycode
}

message MouseEvent { optional int32 x = 1; optional int32 y = 2; }
message MouseWheel { optional int32 dx = 1; optional int32 dy = 2; }
message DataMessage { optional string type = 1; optional string data = 2; }
message Connect { optional string device_name = 1; optional int32 version = 2; }
message Fling { optional string uri = 1; }
```

关键点：

- **动作枚举**：`Key.Action-><clinit>` 中 `UP=0`、`DOWN=1`（注意：发送
  "按下"事件时 action=**1**，"抬起"为 0）。
- **键码**：`Key.Code` 枚举与 `android.view.KeyEvent.KEYCODE_*` 逐值一致
  （`KEYCODE_UNKNOWN=0`、`HOME=3`、`BACK=4`、`CALL=5`…），直接填 Android keycode 数值。
- 坚果控实际只用到 `key_event_message`（遥控按键）、`data_message`（文本输入）、
  `mouse_event_message` / `mouse_wheel_message`（空鼠/触摸板）、`fling_message`
  （投屏消息，空 uri 兼作心跳），**不发 Connect 握手**。
- 设备端对 `Connect` 也支持（`Messages$Connect$Builder` 在服务端 dex 内存在），
  自研客户端可选用。

## 5. 最小客户端实现（Python，标准库）

以下脚本在局域网内发现设备并发送一次「Home 按下/抬起」，随后打印设备推送的
volume/sysconfig（无第三方依赖，protobuf 手工编码）：

```python
#!/usr/bin/env python3
import socket, time, threading

KEYCODE_HOME = 3
ACTION_UP, ACTION_DOWN = 0, 1

def varint(n):
    out = b''
    while True:
        b = n & 0x7f; n >>= 7
        if n: out += bytes([b | 0x80])
        else: return out + bytes([b])

def ld(field, data):          # length-delimited 字段
    return varint((field << 3) | 2) + varint(len(data)) + data

def vi(field, value):         # varint 字段
    return varint((field << 3) | 0) + varint(value)

def remote_message(request_payload):
    payload = ld(2, request_payload)            # RemoteMessage{request_message}
    return varint(len(payload)) + payload        # length-delimited 帧

def key_event(keycode, action):
    ke = vi(1, action) + vi(2, keycode)          # KeyEvent{action, keycode}
    return remote_message(ld(1, ke))             # RequestMessage{key_event}

def read_varint(buf, i):
    r = s = 0
    while True:
        b = buf[i]; i += 1; r |= (b & 0x7f) << s
        if not b & 0x80: return r, i
        s += 7

def read_msg(sock):
    n = s = 0; shift = 0
    while True:
        b = sock.recv(1)
        if not b: raise EOFError
        v = b[0]; n |= (v & 0x7f) << shift
        if not v & 0x80: break
        shift += 7
    return sock.recv(n)

def fields(buf):                                 # 只解析顶层 varint/bytes 字段
    out, i = {}, 0
    while i < len(buf):
        k, i = read_varint(buf, i)
        num, wire = k >> 3, k & 7
        if wire == 0:
            out[num], i = read_varint(buf, i)
        elif wire == 2:
            ln, i = read_varint(buf, i); out[num] = buf[i:i+ln]; i += ln
        else: break
    return out

# 1) 发现：UDP 9101 广播
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
s.settimeout(5)
s.sendto(b'discover _anymote._tcp 9101\n', ('255.255.255.255', 9101))
data, (ip, _) = s.recvfrom(1024)
print('发现设备:', data.decode().strip(), '@', ip)
s.close()

# 2) 控制：TCP 9005（length-delimited protobuf）
t = socket.create_connection((ip, 9005), timeout=5)

def dump():
    while True:
        msg = fields(read_msg(t))
        resp = fields(msg.get(3, b''))           # RemoteMessage.response_message
        if 1 in resp:                            # ResponseMessage.data_message
            d = fields(resp[1])
            print('推送:', d.get(1, b'').decode(), '=', d.get(2, b'').decode())

threading.Thread(target=dump, daemon=True).start()
t.sendall(key_event(KEYCODE_HOME, ACTION_DOWN))
t.sendall(key_event(KEYCODE_HOME, ACTION_UP))
time.sleep(2)
```

要点提示：

- 帧格式是 **varint 长度前缀 + protobuf**（兼容 Google 官方 Anymote 的
  `writeDelimitedTo`），不能直接 `send(protobuf_bytes)`。
- 连接后设备会立刻推 `volume`、`sysconfig` 两条 Data；收到它们说明链路正常。
- 长按 = 只发 `DOWN`，松开再发 `UP`；单击通常成对发送。
- 常用键码：HOME=3、BACK=4、DPAD_UP=19、DPAD_DOWN=20、DPAD_LEFT=21、
  DPAD_RIGHT=22、ENTER=23、MENU=82、VOLUME_UP=24、VOLUME_DOWN=25、
  POWER=26、MUTE=164、MEDIA_PLAY_PAUSE=85 等。

## 6. 与设置 API 的关系

- Anymote 只负责**按键注入**（等价于红外遥控器），不直接改设置值；
  亮度/投影方式/对焦等由设备本地 UI 或 [设置项 API](settings-api.md)
  （`HolatekOSManager`）完成。
- 设备端 `sysconfig` 推送与 `TvRemoteService` 的取 SN 行为说明 JMGO 定制层
  （Holatek）同时服务遥控与设置两条链路；两者共享 `holatekos_mananger` 系统服务。
- 自研 App 若运行在投影仪本机，直接本地调用 `HolatekOSManager` 更简单
  （见 [设置项 API](settings-api.md)）；若在手机端遥控，则实现本协议。

## 7. 安全说明

协议无任何认证/加密（LAN 内任一台设备均可模拟遥控），仅建议在受信局域网内使用。
本仓库仅做协议分析与自有设备研究，不提供任何绕过厂商附加服务的工具。

## 8. 复现命令

```bash
# 1) 提取坚果控 dex 并定位关键类（示例）
unzip -o 坚果控.apk 'classes*.dex' 'lib/arm64-v8a/*' -d work/nut
# 2) 用任意 dex 反汇编器（baksmali / 自研脚本）定位下列偏移
#    App 侧（classes3.dex）:
#      UdpDiscover.scanDevices        @0x14bcc4  (UDP 9101 + 3s 定时)
#      UdpDiscover$1.run              @0x14bbac  ('discover _anymote._tcp 9101\n')
#      UdpBroadcast$ReceiveThread.run @0x14b6d0  (来源 IP + 行拼接)
#      TcpClient.<init>               @0x14a928  (默认端口 9005)
#      RemoteMessageManager$1.run     @0x1481ac  (writeDelimitedTo 发送)
#      RemoteMessageManager.sendKeyEvent @0x148e20
#      HeartPingManager.sendPingData  @0x140e6c  (空 Fling 心跳)
#    设备侧（GoogleRemote.apk classes.dex）:
#      TvRemoteService.onCreate      @0xf3f7c   (Broadcast 9000 / NoSSL 9005 / Pair 9001)
#      TvRemoteService.startServer   @0xf411c
#      BroadcastRspThread.handleBroadcast @0xafca0 (发现应答 + getString(0,147))
#      AnymoteNoSSLServer.run        @0xaefa8   (连接后推 volume/sysconfig)
```
