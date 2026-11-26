# Escape Velocity 玩家手册

使用 Node.js 22.22 或更新版本。在本目录运行：

```sh
npm ci
npm run dev
```

`npm run build` 输出静态站到 `dist/`。运行 `npm run preview` 可预览构建并测试 Pagefind 搜索。

正文在 `src/content/docs/en/` 和 `src/content/docs/zh-cn/`。两种语言的对应页使用相同的文件路径。维护正文时一起更新两种语言。

手册使用的实机图在 `public/media/`。正文中的 `<Figure>` 没有 `image` 参数时显示占位。补图后添加 WebP、`image` 和本地化的 `alt`。蓝图下载文件来自 `modpack/schematics/`，版本更新时一起同步。

手册部署到 `starfold.saya.love`，`/` 跳转到 `/en/`。GitHub Actions 在 `main` 上的手册或部署工作流更新时构建并发布到 Cloudflare Workers Static Assets，也可在 Actions 中手动运行。

首次运行前，在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 配置 `CLOUDFLARE_API_TOKEN` 密钥和 `CLOUDFLARE_ACCOUNT_ID` 变量。令牌使用 Cloudflare 的 **Edit Cloudflare Workers** 模板，范围限定到部署账号和 `saya.love`。首次绑定域名需要该区域的 **Workers Routes: Write** 权限。构建产物 `dist/` 由工作流生成。
