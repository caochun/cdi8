# ICF / GXLF Visualization

Next.js + Three.js 三维可视化原型。默认采用 GXLF 生命周期事件流驱动三维场景。

推荐从仓库根目录一键启动：

```bash
make dev
```

然后打开 `http://localhost:3001`。

事件桥默认处于 `idle`。打开页面后，用左下角 Engine 面板发送 Start/Pause/Resume/Stop/Reset；这些控制命令通过 `http://127.0.0.1:8765/commands` 进入后端，后端状态再通过 `/events` 回到 UI。

单独启动前端：

```bash
npm install
npm run dev
```

默认事件地址为 `http://127.0.0.1:8765/events`。启动事件桥：

```bash
make sim-bridge
```

如需覆盖事件地址：

```bash
NEXT_PUBLIC_GXLF_EVENTS_URL=http://127.0.0.1:8765/events npm run dev
```

事件桥接入可参考 `../../docs/simulation/EVENT_BUS_INTEGRATION.md`。
