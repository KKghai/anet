package mil.dds.anet.views;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.core.MediaType;
import mil.dds.anet.config.AnetConfiguration;

public class ViewResponseFilter implements ContainerResponseFilter {

  AnetConfiguration config;

  public ViewResponseFilter(AnetConfiguration config) {
    this.config = config;
  }

  @Override
  public void filter(ContainerRequestContext requestContext,
      ContainerResponseContext responseContext) throws IOException {
    if (MediaType.APPLICATION_JSON_TYPE.equals(responseContext.getMediaType())) {
      responseContext.getHeaders().put("Cache-Control",
          ImmutableList.of("no-store, no-cache, must-revalidate, post-check=0, pre-check=0"));
      responseContext.getHeaders().put("Pragma", ImmutableList.of("no-cache"));
    }
  }

}
