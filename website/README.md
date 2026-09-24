# Moons website

黑白灰风格的 Moons 中英文官网. 使用静态 HTML/CSS/JavaScript，不需要安装前端依赖；`dist/` 是完整的可部署网站，也可直接打开 `dist/index.html`.

功能介绍按 Xray、ClickGUI、Configs、YSM 四个模块切换，同屏只展示一个模块. 支持点击、方向键和 Home/End 键；禁用 JavaScript 时，各模块仍可通过锚点阅读.

下载按钮统一指向 https://github.com/LoscX-X/Moons/releases ，源码入口指向项目仓库。版本与安装说明来自项目当前 README，后续支持范围变化时请同步更新 `dist/index.html` 和 `dist/en/index.html`。

客户端面板为网页界面示意，不是实际截图。背景为原创生成插图；Inter 字体复用客户端已有字体，许可证见 `dist/assets/Inter-LICENSE.txt`。

## 本地预览

在此目录运行 `node preview.mjs`，访问 http://127.0.0.1:4173 。

## 文件

- `dist/index.html`：内容、导航、版本和下载入口。
- `dist/styles.css`：黑白灰主题、桌面与移动布局、减少动态效果设置。
- `dist/modules.css`、`dist/modules.js`：功能模块的布局与无障碍切换.
- `dist/assets/`：插图和字体。

上传 `dist/` 中的文件到任意静态托管服务即可部署。不需要服务端、密钥或数据库。

## Language URLs

- 中文: https://moons-client.loscxme.chatgpt.site/
- English: https://moons-client.loscxme.chatgpt.site/en/
- 本地英文预览: http://127.0.0.1:4173/en/

顶部导航支持中英文互相切换. 英文页面位于 `dist/en/index.html`，与中文共用样式、脚本和图片.

## Vercel deployment

Run `npx vercel login`, then `npx vercel --prod` from this `website/` directory. The included `vercel.json` serves `dist/` without a build step. Chinese is at `/` and English is at `/en/`.

For a Git import, select `website` as the Root Directory. Framework: Other. Output Directory: dist. Leave the build and install commands empty.

### Live Vercel URLs

- Chinese: https://moons-client.vercel.app/
- English: https://moons-client.vercel.app/en/

Deploy updates from this directory with `npx vercel --prod`.
