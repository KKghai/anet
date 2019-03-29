package mil.dds.anet.database.mappers;

import java.sql.ResultSet;
import java.sql.SQLException;
import mil.dds.anet.beans.Task;
import mil.dds.anet.beans.Task.TaskStatus;
import mil.dds.anet.utils.DaoUtils;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

public class TaskMapper implements RowMapper<Task> {

  @Override
  public Task map(ResultSet r, StatementContext ctx) throws SQLException {
    Task p = new Task();
    DaoUtils.setCommonBeanFields(p, r, null);
    p.setLongName(r.getString("longName"));
    p.setShortName(r.getString("shortName"));
    p.setCategory(r.getString("category"));
    p.setCustomField(r.getString("customField"));
    p.setCustomFieldEnum1(r.getString("customFieldEnum1"));
    p.setCustomFieldEnum2(r.getString("customFieldEnum2"));
    p.setPlannedCompletion(DaoUtils.getInstantAsLocalDateTime(r, "plannedCompletion"));
    p.setProjectedCompletion(DaoUtils.getInstantAsLocalDateTime(r, "projectedCompletion"));
    p.setStatus(MapperUtils.getEnumIdx(r, "status", TaskStatus.class));
    p.setCustomFieldRef1Uuid(r.getString("customFieldRef1Uuid"));
    p.setResponsibleOrgUuid(r.getString("organizationUuid"));

    if (MapperUtils.containsColumnNamed(r, "totalCount")) {
      ctx.define("totalCount", r.getInt("totalCount"));
    }

    return p;
  }


}
