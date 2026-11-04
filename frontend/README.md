# 差评智控 - 前端项目

## 项目结构

```
frontend/
├── src/
│   ├── api/              # API 接口层
│   ├── assets/           # 静态资源
│   ├── components/       # 公共组件
│   ├── layouts/          # 布局组件
│   ├── router/           # 路由配置
│   ├── stores/           # 状态管理
│   ├── styles/           # 全局样式
│   ├── types/            # TypeScript 类型定义
│   ├── views/            # 页面视图
│   ├── App.vue          # 根组件
│   └── main.ts          # 入口文件
├── index.html
├── package.json
├── tsconfig.json
└── vite.config.ts
```

## 技术栈

- Vue 3
- TypeScript
- Vite
- Element Plus
- Vue Router
- Pinia
- Axios

## 开发

```bash
# 安装依赖
npm install

# 启动开发服务器
npm run dev

# 构建生产版本
npm run build
```

## 功能模块

### 1. 用户认证
- 登录
- 退出
- 账户状态显示

### 2. 店铺管理
- 店铺列表
- 添加/编辑/删除店铺
- 同步评价
- 启动自动化

### 3. 差评管理
- 评论列表
- 筛选（未回复/差评/有内容）
- 评论回复
- 回复模板选择

### 4. 回复模板
- 模板列表
- 新增/编辑/删除模板

### 5. 自动化任务
- 自动化状态监控
- 实时日志
- 任务控制（开始/暂停/继续/停止）
- 智能滑屏状态展示

## API 接口

所有 API 请求通过 `/api` 代理到后端服务（默认 `http://localhost:8080`）

### 认证相关
- `POST /api/auth/login` - 登录
- `GET /api/auth/user` - 获取当前用户
- `POST /api/auth/logout` - 退出登录

### 店铺相关
- `GET /api/shops` - 获取店铺列表
- `POST /api/shops` - 创建店铺
- `PUT /api/shops/:id` - 更新店铺
- `DELETE /api/shops/:id` - 删除店铺
- `POST /api/shops/:id/sync` - 同步评价
- `POST /api/shops/:id/automation/start` - 启动自动化

### 评论相关
- `GET /api/reviews` - 获取评论列表
- `GET /api/reviews/:id` - 获取评论详情
- `POST /api/reviews/:id/reply` - 回复评论

### 模板相关
- `GET /api/templates` - 获取模板列表
- `POST /api/templates` - 创建模板
- `PUT /api/templates/:id` - 更新模板
- `DELETE /api/templates/:id` - 删除模板

### 自动化相关
- `GET /api/automation/status` - 获取自动化状态
- `GET /api/automation/logs` - 获取自动化日志
- `POST /api/automation/pause` - 暂停自动化
- `POST /api/automation/resume` - 继续自动化
- `POST /api/automation/stop` - 停止自动化

## 注意事项

1. **不包含支付功能** - 本系统不涉及充值、支付、套餐购买等功能
2. **Mock数据** - 当后端API未就绪时，使用Mock数据进行演示
3. **权限控制** - 服务商用户可管理多个店铺，普通用户仅管理自己的店铺
4. **自动化状态** - 实时显示自动化任务的执行阶段和日志
5. **智能滑屏** - 展示智能滑屏恢复机制的状态和进度
