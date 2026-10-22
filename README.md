# ColorOS 通知图标增强（自用改动线）

本仓库为自用改动线，用于个人设备。

## 上游来源

- [fankes/ColorOSNotifyIcon](https://github.com/fankes/ColorOSNotifyIcon)：原项目，版权归 Fankes Studio 所有
- [Mangi-11/Glyph](https://github.com/Mangi-11/Glyph)：基于原项目，用 libxposed API 102 重写 Hook 层、用 Miuix 重写 UI 的分支，本仓库的直接上游
- [robojamu/Glyph](https://github.com/robojamu/Glyph)：基于该项目，用修改状态栏为规则图标，通知栏为主题图标。
- [Android Notification Icon Project](https://github.com/BetterAndroid/android-notification-icon-project)：通知图标规则库，规则由该项目维护

## 说明

- 本仓库与上述项目无隶属关系，非官方发布。
- 上游仓库不负责本仓库的改动；相关问题与改动记录留在本仓库。
- 构建使用仓库内附带的公共开发证书（非生产密钥），任何人可用同一证书重新签名，不作官方发布使用。
- 本仓库基于上游 `Mangi-11/Glyph 3.4.0` 修改而来；改动明细与日期见 git 提交记录。

## 规则来源

沿用 [Android Notification Icon Project (ANIP)](https://github.com/BetterAndroid/android-notification-icon-project) 规则仓库：

- [Android Notification Icon Project](https://github.com/BetterAndroid/android-notification-icon-project)
- [ColorOS 规则清单](https://raw.githubusercontent.com/BetterAndroid/android-notification-icon-project/main/icons/system/coloros/manifest.json)
- [游戏规则清单](https://raw.githubusercontent.com/BetterAndroid/android-notification-icon-project/main/icons/game/manifest.json)
- [APP 规则清单](https://raw.githubusercontent.com/BetterAndroid/android-notification-icon-project/main/icons/app/manifest.json)

同步时按 ColorOS → 游戏 → APP 合并。同一包名出现多次的，以后者为准。

## 更新

App 内「关于 → 检查更新」读取本仓库的 Releases。本仓库版本号带 `-cn` 后缀（例如 `3.4.4-cn`），版本比较会忽略该后缀。

规则图标单独同步：首页「更新规则」按钮拉取 [ANIP](https://github.com/BetterAndroid/android-notification-icon-project)
的清单，无后台自动同步；补充规则应向 ANIP 提交。

## 注意事项

1. 本软件免费、兴趣驱动，仅供学习交流；付费获取的版本均非官方渠道。
2. 本软件采用 **AGPL 3.0** 许可证。分发或修改时必须遵守条款并提供源代码。
3. 保留原始版权声明与许可证信息，不得冒用原作者名义。
4. 本仓库为自用改动线，与上游作者无隶属关系；相关问题与改动记录留在本仓库，无需提交到上游仓库。

## 隐私政策

- [PRIVACY](PRIVACY.md)

## 许可证

- [AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html)

代码可自由查看、修改、分发，条件是保持同样的自由：分发修改版时保留版权声明、标明改动并提供源码。

原始项目版权归 Fankes Studio(qzmmcn@163.com) 所有；上游 Glyph 分支由 [Mangi-11](https://github.com/Mangi-11/Glyph) 维护；
本仓库的改动同样按 AGPL-3.0 发布。

## 致谢

感谢原作者 [fankes](https://github.com/fankes) 的开源基础，以及各位图标规则维护者的持续付出。

也感谢 [Mangi-11](https://github.com/Mangi-11/Glyph) 维护的 Glyph 分支，本仓库基于该分支。

通用占位符采用 Google Material Icons 的 [`circle_notifications`](https://github.com/google/material-design-icons)，按 [Apache-2.0](third_party/material-design-icons/LICENSE) 许可证使用。
