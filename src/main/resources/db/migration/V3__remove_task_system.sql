-- Retain V1/V2 unchanged for existing Flyway history; retire task storage here.
DROP TABLE IF EXISTS task_templates;
DROP TABLE IF EXISTS tasks;
