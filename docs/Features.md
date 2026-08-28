# Vantage Android 功能特性

## 好用优先
- **开放扩展安装**：Firefox 生态核心（uBlock Origin 等开箱即用），`xpinstall.enabled=true` + `InstallAddonsPermission`
- **权限询问制**：通知 / 定位 / 相机 / 麦克风按需询问
- **保留密码保存与自动填充**（`OfferToSaveLoginsDefault=true`）
- **默认不清理浏览数据**（退出清理作为可选项保留）

## 隐私不缺席（对齐桌面版 Vantage）
- 遥测 / 崩溃报告 / Nimbus 实验全部禁用
- uBlock Origin 内置
- HTTPS-Only、DoH（阿里 DNS + DNSPod，TRR first 有回退）
- Canvas 指纹随机化、网络 ID 隐藏、推测连接禁用
- 移除 Mozilla 服务（Pocket / Suggest / Relay / AI 控制等）

## 品牌与本地化
- 蓝绿主题色、Vantage 图标
- **zh-MS 巨硬中文**语言包（全量硬翻，桌面版特色）
- 全语言支持；Vantage 自定义文案仅维护 en-US / zh-CN / zh-TW
- 默认搜索引擎 Bing + 百度 / Google / DuckDuckGo 可选

## 搜索引擎
- 默认：Bing；可选：百度 / Google / DDG（含 No AI / HTML / Lite）/ Marginalia / Mojeek / SearXNG / Startpage / Wikipedia / No Search
- 百度/Google/Bing 为中文用户新增（无跟踪参数的标准 OpenSearch 定义）
