package com.blog.hyowon.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;



@Controller
public class HomeController {

    @GetMapping("/")
    public String index() throws Exception {
        System.out.println("HomeController");
        return "index.html"; // templates/index.html 렌더링
    }
}
