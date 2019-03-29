package mil.dds.anet.beans;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.leangen.graphql.annotations.GraphQLIgnore;
import io.leangen.graphql.annotations.GraphQLQuery;
import javax.ws.rs.WebApplicationException;
import mil.dds.anet.views.AbstractAnetBean;

public class AdminSetting extends AbstractAnetBean {

  private String key;
  private String value;

  @Override
  @JsonIgnore
  @GraphQLIgnore
  public String getUuid() {
    throw new WebApplicationException("no UUID field on AdminSetting");
  }

  @GraphQLQuery(name = "key")
  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  @GraphQLQuery(name = "value")
  public String getValue() {
    return value;
  }

  public void setValue(String value) {
    this.value = value;
  }



}
