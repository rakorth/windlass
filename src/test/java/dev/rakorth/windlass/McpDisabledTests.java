package dev.rakorth.windlass;

import dev.rakorth.windlass.mcp.NotificationTools;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite::memory:", "WINDLASS_MCP_ENABLED=false"
})
@AutoConfigureMockMvc
class McpDisabledTests {
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;

    @Test
    void disablingMcpRemovesEndpointAndToolsWhileRestRemainsAvailable() throws Exception {
        assertTrue(context.getBeansOfType(NotificationTools.class).isEmpty());
        mvc.perform(post("/mcp").contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/notifications")).andExpect(status().isOk());
    }
}
