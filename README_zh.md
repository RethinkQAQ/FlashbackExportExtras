# Flashback Export Extras

Flashback Export Extras 是一个 Fabric 模组扩展，用于增强 Flashback 的 Minecraft 回放导出功能。

## 功能简介

- 导出深度图。
- 导出包含颜色和 `Depth.Z` 通道的多层 OpenEXR。
- 可选导出场景线性 HDR 颜色，用于 OpenEXR 后期处理。
- 在 HDR Mod 兼容可用时导出 HDR10 视频。
- 导出 GLB 格式的摄像机路径。
- 通过可选兼容代码支持 Iris 光影环境。

OpenEXR 输出主要用于 Blender、After Effects 等软件进行合成和后期处理。颜色、深度和摄像机路径按照相同的帧编号导出，便于逐帧匹配。
