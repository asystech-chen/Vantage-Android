# Vantage Android 安全浏览（Safe Browsing）说明

## 工作原理

- 安全浏览数据库由**谷歌**提供，Vantage 定期更新以防范已知恶意网站
- 遇到潜在威胁时，Vantage 可能向谷歌提交**可疑 URL 的部分哈希**（非完整 URL）
- **隐私保护**：Vantage 会代理这些连接，防止你的公共 IP 与请求关联

## 相关设置

- 位置：设置 → Vantage 设置 → 安全 → 安全浏览
- 默认启用；可关闭（降低保护）
- 文案（多语言）：`patches/fenix-overlay/.../res/values*/ironfox_strings.xml` 的 `preference_safe_browsing_enabled_description`

## 与桌面版 Vantage 的关系

对齐桌面版的隐私理念：默认启用但代理连接，兼顾安全与隐私。桌面版 VirusDetector（银狐木马检测）为独立项目，未集成到 Android 版。

## 实现位置（开发者）

- 引擎层：gecko 的 Safe Browsing 实现（未禁用）
- 代理逻辑：GeckoView 内置（URL 哈希查询走代理）
- 相关 patch：`patches/gecko-overlay/`（如有改动）
