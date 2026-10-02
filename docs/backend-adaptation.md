# 当前后端与数据边界

本次按用户选择接入 NewsBlur 的免费托管账号。Android 平台、Compose、Room、
阅读页和离线队列沿用现有结构，没有搭建服务器或重写为本地 RSS 抓取器。

## NewsBlur 适配

- API 固定为 https://www.newsblur.com/；普通账号登录返回 session Cookie。
- 登录请求带 User-Agent；密码只用于本次请求，不落盘。
- Cookie 仅发送给 HTTPS NewsBlur 阅读接口，登录接口不附带旧 Cookie；
  禁止自动跟随重定向，避免凭据跨站转发。
- 免费 River of News 翻页存在限制，因此逐订阅调用 reader/feed/{id}，
  每个订阅独立保存页码，在本地合并。
- story_hash 是文章和待操作的稳定 ID；story_feed_id 映射订阅来源。
- 已读按最多五条批次提交，收藏使用显式 star / unstar，避免盲目 toggle 重放。
- 进入列表、手动刷新与分页同步；无后台轮询。

免费账号验收使用固定提交的服务端合同与非 Premium / 非试用夹具；见 [验证记录](validation-2026-10-02.md)。

依据：[官方 API](https://www.newsblur.com/api)、
[官方服务端实现](https://github.com/samuelclay/NewsBlur/blob/master/apps/reader/views.py)。
免费账号的启用订阅和历史保留限制仍由服务端控制。

## 数据保留

当前 NewsBlur 使用 newsblur.db，旧 fluxa.db 与旧令牌不删除、不跨后端重放。
首次登录绑定用户名，阻止把同一缓存用于不同账户。
Room v3 迁移覆盖原 v1 和三个实验 v2 schema，保留旧同步与笔记等操作。
迁移逻辑保留为回归保障；默认 NewsBlur 首次安装会创建新库，旧内容不会自动显示。

## 后续适配

ArticleRepository、Article / Subscription、Room 和阅读 UI 均不直接依赖 NewsBlur DTO。
未来如需 FreshRSS / Miniflux，应替换认证与具体网络仓库，并给新服务器 / 账户独立的数据命名空间。
当前没有多后端选择界面或插件框架，也没有自托管部署。
