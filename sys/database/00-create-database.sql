-- 在 master 执行；正式环境由 DBA 创建数据库和最小权限应用账号。
IF DB_ID(N'XuriTraining') IS NULL CREATE DATABASE XuriTraining;
GO
