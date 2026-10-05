package dev.rakorth.windlass.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskTemplateService extends TaskService {
    public TaskTemplateService(JdbcTemplate jdbc, ObjectMapper json) {
        super(jdbc, json, "task_templates");
    }
}
