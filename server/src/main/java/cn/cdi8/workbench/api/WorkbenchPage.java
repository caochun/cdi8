package cn.cdi8.workbench.api;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** A single Workbench application, including compatibility entry paths. */
@Controller
public class WorkbenchPage {
    @GetMapping({"/", "/workbench", "/workbench/", "/workbench/index.html", "/runtime.html"})
    public String workbench(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return "forward:/index.html";
    }
}
