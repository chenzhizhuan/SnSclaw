# SearXNG 定制镜像源码

这两个文件是 `snsclaw-searxng` 镜像的构建源码，**不是部署必需品** —— 部署时
直接拉取 registry 里已构建好的 `221.237.179.2:5000/snsclaw/searxng:v1`。

放在这里供日后需要修改搜索行为时参考。

## 为什么需要定制镜像

上游 `searxng/searxng:latest` 的默认配置有两处会**静默破坏** SnSclaw 的搜索功能：

1. **JSON 输出被禁用** —— SnSclaw 的 `SearXNGSearchProvider` 通过
   `/search?q=...&format=json` 取结果，上游默认只开 html 格式，
   请求会返回 403 而不是报错，表现为"搜索没结果"而非"搜索失败"。
2. **Limiter 插件启用** —— 会对来自容器网络的请求做频率限制/机器人检测，
   导致 agent 的连续搜索被拦。

`settings.yml` 关掉了这两项，Dockerfile 把它烘焙进 `/etc/searxng/settings.yml`，
因此容器无需宿主机 bind-mount 即可开箱工作。

## 如何重建并推送

```bash
cd docs/searxng-custom-image
docker build -t 221.237.179.2:5000/snsclaw/searxng:v2 .
docker push 221.237.179.2:5000/snsclaw/searxng:v2
```

推送后记得同步修改 `deploy/docker-compose.yml` 里 searxng 服务的镜像 tag。

## 与源仓库的关系

对应源仓库路径 `docker/searxng/`。若上游仓库更新了该目录，这里的副本需要
手动同步 —— 两者没有自动关联。
