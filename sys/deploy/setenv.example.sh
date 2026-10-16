#!/usr/bin/env sh
# 将本文件内容合并到 Tomcat/bin/setenv.sh，自行填写配置。
export TRAINING_DB_URL='jdbc:sqlserver://localhost:1433;databaseName=XuriTraining;encrypt=true;trustServerCertificate=true'
export TRAINING_DB_USER='training_app'
export TRAINING_DB_PASSWORD='REPLACE_WITH_DATABASE_PASSWORD'
export TRAINING_STORAGE='/var/lib/xuri-training/files'
export TRAINING_ADMIN_USER='admin'
export TRAINING_ADMIN_PASSWORD='REPLACE_WITH_A_STRONG_INITIAL_PASSWORD'
# export TRAINING_AI_ENDPOINT='https://your-provider.example/full-chat-endpoint'
# export TRAINING_AI_KEY='YOUR_KEY'
# export TRAINING_AI_MODEL='YOUR_MODEL'
