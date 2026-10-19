package cn.cdi8.workbench.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WorkbenchPage {
    @GetMapping({"/workbench", "/workbench/"}) public String workbench(){return "forward:/workbench/index.html";}
}
