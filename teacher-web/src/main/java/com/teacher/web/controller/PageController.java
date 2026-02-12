package com.teacher.web.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 页面路由控制器。
 */
@Controller
public class PageController {

    private final String pageVersion = Long.toHexString(System.currentTimeMillis());

    @GetMapping("/")
    public String index(@RequestParam(value = "v", required = false) String version,
                        HttpServletResponse response) {
        if (version == null || !pageVersion.equals(version)) {
            return "redirect:/?v=" + pageVersion;
        }

        response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
        response.setHeader("Pragma", "no-cache");
        response.setDateHeader("Expires", 0);
        return "index";
    }
}
