package cn.cdi8.workbench;

import cn.cdi8.workbench.api.WorkbenchPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WorkbenchPageTest {
    @Test
    void servesWorkbenchAtRootWithoutRedirect() throws Exception {
        MockMvcBuilders.standaloneSetup(new WorkbenchPage()).build().perform(get("/"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/index.html"))
            .andExpect(header().doesNotExist("Location"))
            .andExpect(header().string("Cache-Control", "no-store"));
    }

    @ParameterizedTest
    @ValueSource(strings={"/workbench", "/workbench/", "/workbench/index.html", "/runtime.html"})
    void legacyEntryPathsAreNotMapped(String path) throws Exception {
        MockMvcBuilders.standaloneSetup(new WorkbenchPage()).build().perform(get(path))
            .andExpect(status().isNotFound());
    }
}
