# 旅迹 AI
<!-- impeccable:product-schema 1 -->

## Platform
web

## Product Purpose
AI-native Travel Workspace：把旅行项目、收藏地点、每日行程、旅行资料和 AI 规划集中在一个工作台。以下内容均来自本轮用户明确需求及现有实现。

## Capabilities and Constraints
保留 Vue 3、TypeScript、Vite、Element Plus、现有认证、FastAPI 接口和数据库。不得引入路由、全局状态库或新的业务 API。保留旅行创建/删除、地点管理、自动排程、上传/OCR/多模态识别、联网规划、调整草案确认/拒绝、预算计算和总结。

## Brand Commitments
用户确认的参考图为最高视觉标准；品牌为旅迹 AI。以 Linear、Notion Calendar、Airbnb Trips 和 AI Copilot 为产品参照。禁止旅游 Banner、水墨、毛笔字体、卡片墙和传统后台视觉。

## Operating Context
桌面 1440/1600px 为主，兼容 1280px；1100px 以下 Copilot 收起，760px 以下时间轴/地图切换。真实资料不足时明确回退，不以示例数据替换用户记录。

## Product Principles
- 所有已有业务入口仍可到达。
- AI 提案先预览，用户应用后才修改记录。
- 预算、地图、推荐均表明数据来源和缺失状态。
- 不为参考图中的装饰字段迁移基础设施。
