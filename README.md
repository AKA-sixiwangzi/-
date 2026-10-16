# -
旭日培训

企业内部培训系统，后端使用传统 SSM（Spring、Spring MVC、MyBatis），数据库使用 SQL Server，前端规划使用 Vue 3。

当前已保存后端首版，支持员工兼任讲师、课程审核、岗位成长路线、培训计划、有效播放校验、课程关联考试、预警处理记录，以及课程问答和 AI 出题草稿接口。

- [后端源码与部署说明](sys/README.md)
- [详细设计 V1.1](sys/docs/需求与设计V1.1.md)
- [接口与联调说明](sys/docs/API.md)
- [验证记录](sys/docs/验证记录.md)
- [SQL Server 数据库脚本](sys/database)

构建：在 `sys` 目录使用完整 JDK 17 或 21 执行 `mvn clean verify`，将生成的 WAR 部署到 Tomcat 10.1。

当前测试为 49 项通过、1 项 SQL Server 实测跳过。数据库实际联调和真实 AI 调用尚未验证；Vue 页面尚未开发。数据库密码、初始管理员密码和模型密钥通过部署环境变量配置，仓库仅保留配置模板。
