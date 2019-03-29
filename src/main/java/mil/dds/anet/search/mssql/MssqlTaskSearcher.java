package mil.dds.anet.search.mssql;

import com.google.common.base.Joiner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import mil.dds.anet.beans.Task;
import mil.dds.anet.beans.lists.AnetBeanList;
import mil.dds.anet.beans.search.ISearchQuery.SortOrder;
import mil.dds.anet.beans.search.TaskSearchQuery;
import mil.dds.anet.beans.search.TaskSearchQuery.TaskSearchSortBy;
import mil.dds.anet.database.mappers.TaskMapper;
import mil.dds.anet.search.AbstractSearcherBase;
import mil.dds.anet.search.ITaskSearcher;
import mil.dds.anet.utils.DaoUtils;
import mil.dds.anet.utils.Utils;
import org.jdbi.v3.core.statement.Query;

public class MssqlTaskSearcher extends AbstractSearcherBase implements ITaskSearcher {

  @Override
  public AnetBeanList<Task> runSearch(TaskSearchQuery query) {
    final List<String> whereClauses = new LinkedList<String>();
    final Map<String, Object> args = new HashMap<String, Object>();
    final StringBuilder sql = new StringBuilder("/* MssqlTaskSearch */ SELECT tasks.*");

    final String text = query.getText();
    final boolean doFullTextSearch = (text != null && !text.trim().isEmpty());
    if (doFullTextSearch) {
      // If we're doing a full-text search, add a pseudo-rank (giving LIKE matches the highest
      // possible score)
      // so we can sort on it (show the most relevant hits at the top).
      sql.append(", ISNULL(c_tasks.rank, 0)"
          + " + CASE WHEN tasks.shortName LIKE :likeQuery THEN 1000 ELSE 0 END");
      sql.append(" AS search_rank");
    }
    sql.append(", COUNT(*) OVER() AS totalCount FROM tasks");

    if (doFullTextSearch) {
      sql.append(" LEFT JOIN CONTAINSTABLE (tasks, (longName), :containsQuery) c_tasks"
          + " ON tasks.uuid = c_tasks.[Key]");
      whereClauses.add("(c_tasks.rank IS NOT NULL" + " OR tasks.shortName LIKE :likeQuery)");
      args.put("containsQuery", Utils.getSqlServerFullTextQuery(text));
      args.put("likeQuery", Utils.prepForLikeQuery(text) + "%");
    }

    if (query.getResponsibleOrgUuid() != null) {
      if (Boolean.TRUE.equals(query.getIncludeChildrenOrgs())) {
        sql.insert(0, "WITH parent_orgs(uuid) AS ( "
            + "SELECT uuid FROM organizations WHERE uuid = :orgUuid " + "UNION ALL "
            + "SELECT o.uuid from parent_orgs po, organizations o WHERE o.parentOrgUuid = po.uuid "
            + ") ");
        whereClauses.add(" organizationUuid IN (SELECT uuid from parent_orgs)");
      } else {
        whereClauses.add("organizationUuid = :orgUuid");
      }
      args.put("orgUuid", query.getResponsibleOrgUuid());
    }

    if (query.getCategory() != null) {
      whereClauses.add("category = :category");
      args.put("category", query.getCategory());
    }

    if (query.getStatus() != null) {
      whereClauses.add("status = :status");
      args.put("status", DaoUtils.getEnumId(query.getStatus()));
    }

    if (query.getProjectStatus() != null) {
      whereClauses.add("customFieldEnum1 LIKE :projectStatus");
      args.put("projectStatus", query.getProjectStatus());
    }

    if (query.getPlannedCompletionStart() != null) {
      whereClauses.add("plannedCompletion >= :plannedCompletionStart");
      DaoUtils.addInstantAsLocalDateTime(args, "plannedCompletionStart",
          query.getPlannedCompletionStart());
    }

    if (query.getPlannedCompletionEnd() != null) {
      whereClauses.add("plannedCompletion <= :plannedCompletionEnd");
      DaoUtils.addInstantAsLocalDateTime(args, "plannedCompletionEnd",
          query.getPlannedCompletionEnd());
    }

    if (query.getProjectedCompletionStart() != null) {
      whereClauses.add("projectedCompletion >= :projectedCompletionStart");
      DaoUtils.addInstantAsLocalDateTime(args, "projectedCompletionStart",
          query.getProjectedCompletionStart());
    }

    if (query.getProjectedCompletionEnd() != null) {
      whereClauses.add("projectedCompletion <= :projectedCompletionEnd");
      DaoUtils.addInstantAsLocalDateTime(args, "projectedCompletionEnd",
          query.getProjectedCompletionEnd());
    }

    if (query.getCustomField() != null) {
      whereClauses.add("customField LIKE :customField");
      args.put("customField", Utils.prepForLikeQuery(query.getCustomField()) + "%");
    }

    if (query.getCustomFieldRef1Uuid() != null) {
      if (Boolean.TRUE.equals(query.getCustomFieldRef1Recursively())) {
        sql.insert(0, "WITH parent_tasks(uuid) AS ( "
            + "SELECT uuid FROM tasks WHERE uuid = :customFieldRef1Uuid " + "UNION ALL "
            + "SELECT t.uuid from parent_tasks pt, tasks t WHERE t.customFieldRef1Uuid = pt.uuid AND t.uuid != :customFieldRef1Uuid"
            + ") ");
        whereClauses.add("( tasks.customFieldRef1Uuid IN (SELECT uuid from parent_tasks) "
            + "OR tasks.uuid = :customFieldRef1Uuid)");
      } else {
        whereClauses.add("tasks.customFieldRef1Uuid = :customFieldRef1Uuid");
      }
      args.put("customFieldRef1Uuid", query.getCustomFieldRef1Uuid());
    }

    if (whereClauses.isEmpty()) {
      return new AnetBeanList<Task>(query.getPageNum(), query.getPageSize(), new ArrayList<Task>());
    }

    sql.append(" WHERE ");
    sql.append(Joiner.on(" AND ").join(whereClauses));
    // Sort Ordering
    final List<String> orderByClauses = new LinkedList<>();
    if (doFullTextSearch && query.getSortBy() == null) {
      // We're doing a full-text search without an explicit sort order,
      // so sort first on the search pseudo-rank.
      orderByClauses.addAll(Utils.addOrderBy(SortOrder.DESC, null, "search_rank"));
    }

    if (query.getSortBy() == null) {
      query.setSortBy(TaskSearchSortBy.NAME);
    }
    if (query.getSortOrder() == null) {
      query.setSortOrder(SortOrder.ASC);
    }
    switch (query.getSortBy()) {
      case CREATED_AT:
        orderByClauses.addAll(Utils.addOrderBy(query.getSortOrder(), "tasks", "createdAt"));
        break;
      case CATEGORY:
        orderByClauses.addAll(Utils.addOrderBy(query.getSortOrder(), "tasks", "category"));
        break;
      case NAME:
      default:
        orderByClauses
            .addAll(Utils.addOrderBy(query.getSortOrder(), "tasks", "shortName", "longName"));
        break;
    }
    orderByClauses.addAll(Utils.addOrderBy(SortOrder.ASC, "tasks", "uuid"));
    sql.append(" ORDER BY ");
    sql.append(Joiner.on(", ").join(orderByClauses));

    final Query sqlQuery = MssqlSearcher.addPagination(query, getDbHandle(), sql, args);
    return new AnetBeanList<Task>(sqlQuery, query.getPageNum(), query.getPageSize(),
        new TaskMapper(), null);
  }

}
