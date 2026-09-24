-- MySQL 8, standalone local development instance (localhost:3306) only.
-- Stop the local backend and take a complete ai-demo backup before running.
-- This intentionally fails if ai_chatbot already exists; do not use mysql --force.
-- Only the nine tables still used by this application's source are copied.
-- The Spring backend creates the three newer ai_* tables and upgrades ticket
-- action columns on its next startup. Five legacy course/chat tables are not
-- copied; preserve them in a full backup before removing the old schema.

CREATE DATABASE `ai_chatbot`
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE TABLE `ai_chatbot`.`ai_business_subject` LIKE `ai-demo`.`ai_business_subject`;
INSERT INTO `ai_chatbot`.`ai_business_subject` SELECT * FROM `ai-demo`.`ai_business_subject`;

CREATE TABLE `ai_chatbot`.`ai_customer_profile` LIKE `ai-demo`.`ai_customer_profile`;
INSERT INTO `ai_chatbot`.`ai_customer_profile` SELECT * FROM `ai-demo`.`ai_customer_profile`;

CREATE TABLE `ai_chatbot`.`ai_scenario` LIKE `ai-demo`.`ai_scenario`;
INSERT INTO `ai_chatbot`.`ai_scenario` SELECT * FROM `ai-demo`.`ai_scenario`;

CREATE TABLE `ai_chatbot`.`ai_service_order` LIKE `ai-demo`.`ai_service_order`;
INSERT INTO `ai_chatbot`.`ai_service_order` SELECT * FROM `ai-demo`.`ai_service_order`;

CREATE TABLE `ai_chatbot`.`ai_service_ticket` LIKE `ai-demo`.`ai_service_ticket`;
INSERT INTO `ai_chatbot`.`ai_service_ticket` SELECT * FROM `ai-demo`.`ai_service_ticket`;

CREATE TABLE `ai_chatbot`.`ai_user` LIKE `ai-demo`.`ai_user`;
INSERT INTO `ai_chatbot`.`ai_user` SELECT * FROM `ai-demo`.`ai_user`;

CREATE TABLE `ai_chatbot`.`ai_workflow` LIKE `ai-demo`.`ai_workflow`;
INSERT INTO `ai_chatbot`.`ai_workflow` SELECT * FROM `ai-demo`.`ai_workflow`;

CREATE TABLE `ai_chatbot`.`ai_workflow_run` LIKE `ai-demo`.`ai_workflow_run`;
INSERT INTO `ai_chatbot`.`ai_workflow_run` SELECT * FROM `ai-demo`.`ai_workflow_run`;

CREATE TABLE `ai_chatbot`.`ai_workflow_run_state` LIKE `ai-demo`.`ai_workflow_run_state`;
INSERT INTO `ai_chatbot`.`ai_workflow_run_state` SELECT * FROM `ai-demo`.`ai_workflow_run_state`;

-- Report exact source/target row counts. Verify every pair before removing
-- the old database; this script never drops source data.
SELECT 'ai_business_subject' AS table_name,
       (SELECT COUNT(*) FROM `ai-demo`.`ai_business_subject`) AS source_rows,
       (SELECT COUNT(*) FROM `ai_chatbot`.`ai_business_subject`) AS target_rows
UNION ALL SELECT 'ai_customer_profile', (SELECT COUNT(*) FROM `ai-demo`.`ai_customer_profile`), (SELECT COUNT(*) FROM `ai_chatbot`.`ai_customer_profile`)
UNION ALL SELECT 'ai_scenario', (SELECT COUNT(*) FROM `ai-demo`.`ai_scenario`), (SELECT COUNT(*) FROM `ai_chatbot`.`ai_scenario`)
UNION ALL SELECT 'ai_service_order', (SELECT COUNT(*) FROM `ai-demo`.`ai_service_order`), (SELECT COUNT(*) FROM `ai_chatbot`.`ai_service_order`)
UNION ALL SELECT 'ai_service_ticket', (SELECT COUNT(*) FROM `ai-demo`.`ai_service_ticket`), (SELECT COUNT(*) FROM `ai_chatbot`.`ai_service_ticket`)
UNION ALL SELECT 'ai_user', (SELECT COUNT(*) FROM `ai-demo`.`ai_user`), (SELECT COUNT(*) FROM `ai_chatbot`.`ai_user`)
UNION ALL SELECT 'ai_workflow', (SELECT COUNT(*) FROM `ai-demo`.`ai_workflow`), (SELECT COUNT(*) FROM `ai_chatbot`.`ai_workflow`)
UNION ALL SELECT 'ai_workflow_run', (SELECT COUNT(*) FROM `ai-demo`.`ai_workflow_run`), (SELECT COUNT(*) FROM `ai_chatbot`.`ai_workflow_run`)
UNION ALL SELECT 'ai_workflow_run_state', (SELECT COUNT(*) FROM `ai-demo`.`ai_workflow_run_state`), (SELECT COUNT(*) FROM `ai_chatbot`.`ai_workflow_run_state`);
