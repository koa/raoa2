package ch.bergturbenthal.raoa.viewer.interfaces;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class ViewController {
  @RequestMapping({"/", "/album", "/album/{id}", "/requestAccess", "/admin/{page}"})
  public String index() {
    return "forward:/index.html";
  }
}
