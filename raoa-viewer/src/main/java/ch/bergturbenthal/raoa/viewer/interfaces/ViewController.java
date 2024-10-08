package ch.bergturbenthal.raoa.viewer.interfaces;

import ch.bergturbenthal.raoa.viewer.properties.ViewerProperties;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

@Controller
public class ViewController {
  private final ViewerProperties viewerProperties;

  public ViewController(final ViewerProperties viewerProperties) {
    this.viewerProperties = viewerProperties;
  }

  @RequestMapping({
    "/",
    "/album",
    "/album/{id}",
    "/album/{id}/manage",
    "/album/{id}/media/{imageId}",
    "/requestAccess",
    "/admin/{page}",
    "/manage-users",
    "/manage-users/user/{id}",
    "/manage-teams",
    "/manage-teams/{id}",
    "/sync",
    "/import",
    "/login",
    "/welcome",
    "/svps"
  })
  public String index() {
    if (viewerProperties.isNewUi()) return "forward:/index-yew.html";
    else return "forward:/index.html";
  }

  @RequestMapping("/config")
  @ResponseBody
  public ViewerProperties.ClientProperties clientProperties() {
    return viewerProperties.getClientProperties();
  }
}
