@echo off
chcp 65001 >nul
rem 复制到 Tomcat/bin/setenv.bat；将占位值改为自己的配置。
set "TRAINING_DB_URL=jdbc:sqlserver://localhost:1433;databaseName=XuriTraining;encrypt=true;trustServerCertificate=true"
set "TRAINING_DB_USER=training_app"
set "TRAINING_DB_PASSWORD=REPLACE_WITH_DATABASE_PASSWORD"
set "TRAINING_STORAGE=C:\Users\anzhipeng\Documents\AI大赛\sys\data\files"
set "TRAINING_ADMIN_USER=admin"
set "TRAINING_ADMIN_PASSWORD=REPLACE_WITH_A_STRONG_INITIAL_PASSWORD"
rem AI为可选配置；配置前相关接口返回503。
set "TRAINING_AI_ENDPOINT="
set "TRAINING_AI_KEY="
set "TRAINING_AI_MODEL="
