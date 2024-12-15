# 发布方式:
## 1. 构建
### Windows
```
./gradlew.bat downloadPluginPresets
```
### macOS / Linux
```
./gradlew downloadPluginPresets
```
### 2.运行dockerfile
运行dockerfile


# 本地运行:
## 运行 UI 服务
```
cd path/to/halo/ui

pnpm install

pnpm build:packages

pnpm dev 
```

## 运行 Halo
### 下载预设插件（可选）
```aiignore
# Windows
gradlew.bat downloadPluginPresets

# macOS / Linux
./gradlew downloadPluginPresets

```

### 修改 IntelliJ IDEA 的运行配置
```aiignore
# windows
将 Active Profiles 改为 dev,win

#macOS / Linux
将 Active Profiles 改为 dev
```

### 点击 IntelliJ IDEA 的运行按钮，等待项目启动完成。

### 或者使用 Gradle 运行

```aiignore
# macOS / Linux
./gradlew bootRun --args="--spring.profiles.active=dev"

# Windows
gradlew.bat bootRun --args="--spring.profiles.active=dev,win"
```


# 访问
```aiignore
最终提供以下访问地址：

网站首页：http://localhost:8090
Console 控制台：http://localhost:8090/console
UC 个人中心：http://localhost:8090/uc
```

