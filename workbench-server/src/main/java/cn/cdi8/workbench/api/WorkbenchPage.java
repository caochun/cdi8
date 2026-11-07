package cn.cdi8.workbench.api;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the Workbench application at the root path. */
@Controller
public class WorkbenchPage {
    @GetMapping("/")
    public String workbench(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return "forward:/index.html";
    }
}
