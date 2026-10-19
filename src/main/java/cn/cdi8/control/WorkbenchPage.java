package cn.cdi8.control;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WorkbenchPage {
    @GetMapping({"/workbench", "/workbench/"}) public String workbench(){return "forward:/workbench/index.html";}
}
