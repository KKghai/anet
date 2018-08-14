package mil.dds.anet.resources;

import java.io.StringWriter;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import org.joda.time.DateTime;
import org.joda.time.DateTimeConstants;
import org.skife.jdbi.v2.Handle;
import org.skife.jdbi.v2.TransactionCallback;
import org.skife.jdbi.v2.TransactionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.annotation.Timed;

import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import freemarker.template.Template;
import io.dropwizard.auth.Auth;
import io.leangen.graphql.annotations.GraphQLArgument;
import io.leangen.graphql.annotations.GraphQLQuery;
import io.leangen.graphql.annotations.GraphQLRootContext;
import mil.dds.anet.AnetObjectEngine;
import mil.dds.anet.beans.AnetEmail;
import mil.dds.anet.beans.ApprovalAction;
import mil.dds.anet.beans.ApprovalAction.ApprovalType;
import mil.dds.anet.beans.ApprovalStep;
import mil.dds.anet.beans.AuthorizationGroup;
import mil.dds.anet.beans.Comment;
import mil.dds.anet.beans.Organization;
import mil.dds.anet.beans.Organization.OrganizationType;
import mil.dds.anet.beans.Person;
import mil.dds.anet.beans.Person.Role;
import mil.dds.anet.beans.Task;
import mil.dds.anet.beans.Position;
import mil.dds.anet.beans.Report;
import mil.dds.anet.beans.Report.ReportState;
import mil.dds.anet.beans.ReportPerson;
import mil.dds.anet.beans.RollupGraph;
import mil.dds.anet.beans.Tag;
import mil.dds.anet.beans.lists.AnetBeanList;
import mil.dds.anet.beans.search.ReportSearchQuery;
import mil.dds.anet.config.AnetConfiguration;
import mil.dds.anet.database.AdminDao.AdminSettingKeys;
import mil.dds.anet.database.ReportDao;
import mil.dds.anet.emails.ApprovalNeededEmail;
import mil.dds.anet.emails.DailyRollupEmail;
import mil.dds.anet.emails.NewReportCommentEmail;
import mil.dds.anet.emails.ReportEditedEmail;
import mil.dds.anet.emails.ReportEmail;
import mil.dds.anet.emails.ReportRejectionEmail;
import mil.dds.anet.emails.ReportReleasedEmail;
import mil.dds.anet.threads.AnetEmailWorker;
import mil.dds.anet.utils.AnetAuditLogger;
import mil.dds.anet.utils.AuthUtils;
import mil.dds.anet.utils.DaoUtils;
import mil.dds.anet.utils.ResponseUtils;
import mil.dds.anet.utils.Utils;

@Path("/api/reports")
@Produces(MediaType.APPLICATION_JSON)
@PermitAll
public class ReportResource {

	private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	ReportDao dao;
	AnetObjectEngine engine;
	AnetConfiguration config;

	private final RollupGraphComparator rollupGraphComparator;

	public ReportResource(AnetObjectEngine engine, AnetConfiguration config) {
		this.engine = engine;
		this.dao = engine.getReportDao();
		this.config = config;

		@SuppressWarnings("unchecked")
		List<String> pinnedOrgNames = (List<String>)this.config.getDictionary().get("pinned_ORGs");

		this.rollupGraphComparator = new RollupGraphComparator(pinnedOrgNames);

	}

	@GET
	@Timed
	@GraphQLQuery(name="reports")
	@Path("/")
	public AnetBeanList<Report> getAll(@GraphQLRootContext Map<String, Object> context, @GraphQLArgument(name="_") @Auth Person user,
			@DefaultValue("0") @QueryParam("pageNum") @GraphQLArgument(name="pageNum", defaultValue="0") Integer pageNum,
			@DefaultValue("100") @QueryParam("pageSize") @GraphQLArgument(name="pageSize", defaultValue="100") Integer pageSize) {
		user = DaoUtils.getUser(context, user);
		return dao.getAll(pageNum, pageSize, user);
	}

	@GET
	@Timed
	@Path("/{uuid}")
	@GraphQLQuery(name="report")
	public Report getByUuid(@GraphQLRootContext Map<String, Object> context, @GraphQLArgument(name="_") @Auth Person user,
			@PathParam("uuid") @GraphQLArgument(name="uuid") String uuid) {
		user = DaoUtils.getUser(context, user);
		final Report r = dao.getByUuid(uuid, user);
		if (r == null) { throw new WebApplicationException(Status.NOT_FOUND); }
		return r;
	}

	//Returns a dateTime representing the very end of today.
	// Used to determine if a date is tomorrow or later.
	private DateTime tomorrow() {
		return DateTime.now().withHourOfDay(23).withMinuteOfHour(59).withSecondOfMinute(59);
	}

	// Helper method to determine if a report should be pushed into FUTURE state.
	private boolean shouldBeFuture(Report r) {
		return r.getEngagementDate() != null && r.getEngagementDate().isAfter(tomorrow()) && r.getCancelledReason() == null;
	}

	@POST
	@Timed
	@Path("/new")
	public Report createNewReport(@Auth Person author, Report r) {
		if (r.getState() == null) { r.setState(ReportState.DRAFT); }
		if (r.getAuthor() == null) { r.setAuthor(author); }

		Person primaryAdvisor = findPrimaryAttendee(r, Role.ADVISOR);
		if (r.getAdvisorOrg() == null && primaryAdvisor != null) {
			logger.debug("Setting advisor org for new report based on {}", primaryAdvisor);
			r.setAdvisorOrg(engine.getOrganizationForPerson(primaryAdvisor));
		}
		Person primaryPrincipal = findPrimaryAttendee(r, Role.PRINCIPAL);
		if (r.getPrincipalOrg() == null && primaryPrincipal != null) {
			logger.debug("Setting principal org for new report based on {}", primaryPrincipal);
			r.setPrincipalOrg(engine.getOrganizationForPerson(primaryPrincipal));
		}

		if (shouldBeFuture(r)) {
			r.setState(ReportState.FUTURE);
		}

		r.setReportText(Utils.sanitizeHtml(r.getReportText()));
		r = dao.insert(r, author);
		AnetAuditLogger.log("Report {} created by author {} ", r, author);
		return r;
	}

	private Person findPrimaryAttendee(Report r, Role role) {
		if (r.getAttendees() == null) { return null; }
		return r.getAttendees().stream().filter(p ->
				p.isPrimary() && p.getRole().equals(role)
			).findFirst().orElse(null);
	}

	@POST
	@Timed
	@Path("/update")
	public Response editReport(@Auth Person editor, Report r, @DefaultValue("true") @QueryParam("sendEditEmail") Boolean sendEmail) {
		// perform all modifications to the report and its tasks and steps in a single transaction, returning the original state of the report
		final Report existing = engine.executeInTransaction(this::executeReportUpdates, editor, r);

		if (sendEmail && existing.getState() == ReportState.PENDING_APPROVAL) {
			boolean canApprove = engine.canUserApproveStep(editor.getUuid(), existing.getApprovalStep().getUuid());
			if (canApprove) {
				AnetEmail email = new AnetEmail();
				ReportEditedEmail action = new ReportEditedEmail();
				action.setReport(existing);
				action.setEditor(editor);
				email.setAction(action);
				try {
					email.setToAddresses(Collections.singletonList(existing.loadAuthor(engine.getContext()).get().getEmailAddress()));
					AnetEmailWorker.sendEmailAsync(email);
				} catch (InterruptedException | ExecutionException e) {
					throw new WebApplicationException("failed to load Author", e);
				}
			}
		}

		// Possibly load sensitive information; needed in case of autoSave by the client form
		r.setUser(editor);
		try {
			r.loadReportSensitiveInformation(engine.getContext()).get();
		} catch (InterruptedException | ExecutionException e) {
			throw new WebApplicationException("failed to load ReportSensitiveInformation", e);
		}

		// Return the report in the response; used in autoSave by the client form
		return Response.ok(r).build();
	}

	/** Perform all modifications to the report and its tasks and steps, returning the original state of the report.  Should be wrapped in
	 * a single transaction to ensure consistency.
	 * @param editor the current user (for authorization checks)
	 * @param r a Report object with the desired modifications
	 * @return the report as it was stored in the database before this method was called.
	 */
	private Report executeReportUpdates(Person editor, Report r) {
		//Verify this person has access to edit this report
		//Either they are the author, or an approver for the current step.
		final Report existing = dao.getByUuid(r.getUuid(), editor);
		r.setState(existing.getState());
		r.setApprovalStep(existing.getApprovalStep());
		r.setAuthor(existing.getAuthor());
		assertCanEditReport(r, editor);

		//If this report is in draft and in the future, set state to Future.
		if (ReportState.DRAFT.equals(r.getState()) && shouldBeFuture(r)) {
			r.setState(ReportState.FUTURE);
		} else if (ReportState.FUTURE.equals(r.getState()) && (r.getEngagementDate() == null || r.getEngagementDate().isBefore(tomorrow()))) {
			//This catches a user editing the report to change date back to the past.
			r.setState(ReportState.DRAFT);
		} else if (ReportState.FUTURE.equals(r.getState()) && r.getCancelledReason() != null) {
			//Cancelled future engagements become draft.
			r.setState(ReportState.DRAFT);
		}

		//If there is a change to the primary advisor, change the advisor Org.
		Person primaryAdvisor = findPrimaryAttendee(r, Role.ADVISOR);
		ReportPerson exstingPrimaryAdvisor;
		try {
			exstingPrimaryAdvisor = existing.loadPrimaryAdvisor(engine.getContext()).get();
			if (Utils.uuidEqual(primaryAdvisor, exstingPrimaryAdvisor) == false || existing.getAdvisorOrg() == null) {
				r.setAdvisorOrg(engine.getOrganizationForPerson(primaryAdvisor));
			} else {
				r.setAdvisorOrg(existing.getAdvisorOrg());
			}
		} catch (InterruptedException | ExecutionException e) {
			throw new WebApplicationException("failed to load PrimaryAdvisor", e);
		}

		Person primaryPrincipal = findPrimaryAttendee(r, Role.PRINCIPAL);
		ReportPerson existingPrimaryPrincipal;
		try {
			existingPrimaryPrincipal = existing.loadPrimaryPrincipal(engine.getContext()).get();
			if (Utils.uuidEqual(primaryPrincipal, existingPrimaryPrincipal) ==  false || existing.getPrincipalOrg() == null) {
				r.setPrincipalOrg(engine.getOrganizationForPerson(primaryPrincipal));
			} else {
				r.setPrincipalOrg(existing.getPrincipalOrg());
			}
		} catch (InterruptedException | ExecutionException e) {
			throw new WebApplicationException("failed to load PrimaryPrincipal", e);
		}

		r.setReportText(Utils.sanitizeHtml(r.getReportText()));

		// begin DB modifications
		dao.update(r, editor);

		//Update Attendees:
		if (r.getAttendees() != null) {
			try {
				//Fetch the people associated with this report
				List<ReportPerson> existingPeople = dao.getAttendeesForReport(engine.getContext(), r.getUuid()).get();
				//Find any differences and fix them.
				for (ReportPerson rp : r.getAttendees()) {
					Optional<ReportPerson> existingPerson = existingPeople.stream().filter(el -> el.getUuid().equals(rp.getUuid())).findFirst();
					if (existingPerson.isPresent()) {
						if (existingPerson.get().isPrimary() != rp.isPrimary()) {
							dao.updateAttendeeOnReport(rp, r);
						}
						existingPeople.remove(existingPerson.get());
					} else {
						dao.addAttendeeToReport(rp, r);
					}
				}
				//Any attendees left in existingPeople needs to be removed.
				for (ReportPerson rp : existingPeople) {
					dao.removeAttendeeFromReport(rp, r);
				}
			} catch (InterruptedException | ExecutionException e) {
				throw new WebApplicationException("failed to load Attendees", e);
			}
		}

		//Update Tasks:
		if (r.getTasks() != null) {
			try {
				List<Task> existingTasks = dao.getTasksForReport(engine.getContext(), r.getUuid()).get();
				List<String> existingTaskUuids = existingTasks.stream().map(p -> p.getUuid()).collect(Collectors.toList());
				for (Task p : r.getTasks()) {
					int idx = existingTaskUuids.indexOf(p.getUuid());
					if (idx == -1) {
						dao.addTaskToReport(p, r);
					} else {
						existingTaskUuids.remove(idx);
					}
				}
				for (String uuid : existingTaskUuids) {
					dao.removeTaskFromReport(Task.createWithUuid(uuid), r);
				}
			} catch (InterruptedException | ExecutionException e) {
				throw new WebApplicationException("failed to load Tasks", e);
			}
		}

		// Update Tags:
		if (r.getTags() != null) {
			try {
				List<Tag> existingTags = dao.getTagsForReport(engine.getContext(), r.getUuid()).get();
				for (final Tag t : r.getTags()) {
					Optional<Tag> existingTag = existingTags.stream().filter(el -> el.getUuid().equals(t.getUuid())).findFirst();
					if (existingTag.isPresent()) {
						existingTags.remove(existingTag.get());
					} else {
						dao.addTagToReport(t, r);
					}
				}
				for (Tag t : existingTags) {
					dao.removeTagFromReport(t, r);
				}
			} catch (InterruptedException | ExecutionException e) {
				throw new WebApplicationException("failed to load Attendees", e);
			}
		}

		// Update AuthorizationGroups:
		if (r.getAuthorizationGroups() != null) {
			final List<AuthorizationGroup> existingAuthorizationGroups = dao.getAuthorizationGroupsForReport(r.getUuid());
			for (final AuthorizationGroup t : r.getAuthorizationGroups()) {
				Optional<AuthorizationGroup> existingAuthorizationGroup = existingAuthorizationGroups.stream().filter(el -> el.getUuid().equals(t.getUuid())).findFirst();
				if (existingAuthorizationGroup.isPresent()) {
					existingAuthorizationGroups.remove(existingAuthorizationGroup.get());
				} else {
					dao.addAuthorizationGroupToReport(t, r);
				}
			}
			for (final AuthorizationGroup t : existingAuthorizationGroups) {
				dao.removeAuthorizationGroupFromReport(t, r);
			}
		}

		// Possibly load sensitive information; needed in case of autoSave by the client form
		r.setUser(editor);
		try {
			r.loadReportSensitiveInformation(engine.getContext()).get();
		} catch (InterruptedException | ExecutionException e) {
			throw new WebApplicationException("failed to load ReportSensitiveInformation", e);
		}

		// Return the report in the response; used in autoSave by the client form
		return existing;
	}

	private void assertCanEditReport(Report report, Person editor) {
		String permError = "You do not have permission to edit this report. ";
		switch (report.getState()) {
		case DRAFT:
		case REJECTED:
		case FUTURE:
			//Must be the author
			if (!report.getAuthor().getUuid().equals(editor.getUuid())) {
				throw new WebApplicationException(permError + "Must be the author of this report.", Status.FORBIDDEN);
			}
			break;
		case PENDING_APPROVAL:
			//Either the author, or the approver
			if (report.getAuthor().getUuid().equals(editor.getUuid())) {
				//This is okay, but move it back to draft
				report.setState(ReportState.DRAFT);
				report.setApprovalStep(null);
			} else {
				boolean canApprove = engine.canUserApproveStep(editor.getUuid(), report.getApprovalStep().getUuid());
				if (!canApprove) {
					throw new WebApplicationException(permError + "Must be the author or the current approver", Status.FORBIDDEN);
				}
			}
			break;
		case RELEASED:
		case CANCELLED:
			AnetAuditLogger.log("attempt to edit released report {} by editor {} (uuid: {}) was forbidden",
					report.getUuid(), editor.getName(), editor.getUuid());
			throw new WebApplicationException("Cannot edit a released report", Status.FORBIDDEN);
		}
	}

	/* Submit a report for approval
	 * Kicks a report from DRAFT to PENDING_APPROVAL and sets the approval step uuid
	 */
	@POST
	@Timed
	@Path("/{uuid}/submit")
	public Report submitReport(@Auth Person user, @PathParam("uuid") String uuid) {
		final Report r = dao.getByUuid(uuid, user);
		logger.debug("Attempting to submit report {}, which has advisor org {} and primary advisor {}", r, r.getAdvisorOrg(), r.getPrimaryAdvisor());

		// TODO: this needs to be done by either the Author, a Superuser for the AO, or an Administrator
		if (r.getAdvisorOrg() == null) {
			ReportPerson advisor;
			try {
				advisor = r.loadPrimaryAdvisor(engine.getContext()).get();
				if (advisor == null) {
					throw new WebApplicationException("Report missing primary advisor", Status.BAD_REQUEST);
				}
				r.setAdvisorOrg(engine.getOrganizationForPerson(advisor));
			} catch (InterruptedException | ExecutionException e) {
				throw new WebApplicationException("failed to load PrimaryAdvisor", e);
			}
		}
		if (r.getPrincipalOrg() == null) {
			ReportPerson principal;
			try {
				principal = r.loadPrimaryPrincipal(engine.getContext()).get();
				if (principal == null) {
					throw new WebApplicationException("Report missing primary principal", Status.BAD_REQUEST);
				}
				r.setPrincipalOrg(engine.getOrganizationForPerson(principal));
			} catch (InterruptedException | ExecutionException e) {
				throw new WebApplicationException("failed to load PrimaryPrincipal", e);
			}
		}

		if (r.getEngagementDate() == null) {
			throw new WebApplicationException("Missing engagement date", Status.BAD_REQUEST);
		} else if (r.getEngagementDate().isAfter(tomorrow()) && r.getCancelledReason() == null) {
			throw new WebApplicationException("You cannot submit future engagements less they are cancelled", Status.BAD_REQUEST);
		}

		Organization org = engine.getOrganizationForPerson(r.getAuthor());
		if (org == null) {
			// Author missing Org, use the Default Approval Workflow
			org = Organization.createWithUuid(
				engine.getAdminSetting(AdminSettingKeys.DEFAULT_APPROVAL_ORGANIZATION));
		}
		List<ApprovalStep> steps = engine.getApprovalStepsForOrg(org);
		throwExceptionNoApprovalSteps(steps);

		//Push the report into the first step of this workflow
		r.setApprovalStep(steps.get(0));
		r.setState(ReportState.PENDING_APPROVAL);
		final int numRows = engine.executeInTransaction(dao::update, r, user);
		sendApprovalNeededEmail(r);
		logger.info("Putting report {} into step {} because of org {} on author {}",
				r.getUuid(), steps.get(0).getUuid(), org.getUuid(), r.getAuthor().getUuid());

		if (numRows != 1) {
			throw new WebApplicationException("No records updated", Status.BAD_REQUEST);
		}

		AnetAuditLogger.log("report {} submitted by author {} (uuid: {})", r.getUuid(), r.getAuthor().getName(), r.getAuthor().getUuid());
		return r;
	}

	/***
	 * Throws a WebApplicationException when the report does not have an approval chain belonging to the advisor organization
	 */
	private void throwExceptionNoApprovalSteps(List<ApprovalStep> steps) {
		if (Utils.isEmptyOrNull(steps)) {
			final String supportEmailAddr = (String)this.config.getDictionary().get("SUPPORT_EMAIL_ADDR");
			final String messageBody = "Advisor organization is missing a report approval chain. In order to have an approval chain created for the primary advisor attendee's advisor organization, please contact the ANET support team";
			final String errorMessage = Utils.isEmptyOrNull(supportEmailAddr) ? messageBody : String.format("%s at %s", messageBody, supportEmailAddr);
			throw new WebApplicationException(errorMessage, Status.BAD_REQUEST);
		}
	}

	private void sendApprovalNeededEmail(Report r) {
		final ApprovalStep step;
		try {
			step = r.loadApprovalStep(engine.getContext()).get();
		} catch (InterruptedException | ExecutionException e) {
			throw new WebApplicationException("failed to load ApprovalStep", e);
		}
		final List<Position> approvers;
		try {
			approvers = step.loadApprovers(engine.getContext()).get();
		} catch (InterruptedException | ExecutionException e) {
			throw new WebApplicationException("failed to load Approvers", e);
		}
		AnetEmail approverEmail = new AnetEmail();
		ApprovalNeededEmail action = new ApprovalNeededEmail();
		action.setReport(r);
		approverEmail.setAction(action);
		approverEmail.setToAddresses(approvers.stream()
				.filter(a -> a.getPerson() != null)
				.map(a -> {
					try {
						return a.loadPerson(engine.getContext()).get().getEmailAddress();
					} catch (InterruptedException | ExecutionException e) {
						throw new WebApplicationException("failed to load Person", e);
					}
				})
				.collect(Collectors.toList()));
		AnetEmailWorker.sendEmailAsync(approverEmail);
	}

	/*
	 * Approve this report for the current step.
	 */
	@POST
	@Timed
	@Path("/{uuid}/approve")
	public Report approveReport(@Auth Person approver, @PathParam("uuid") String uuid, Comment comment) {
		final Handle dbHandle = AnetObjectEngine.getInstance().getDbHandle();
		return dbHandle.inTransaction(new TransactionCallback<Report>() {
			public Report inTransaction(Handle conn, TransactionStatus status) throws Exception {
				final Report r = dao.getByUuid(uuid, approver);
				if (r == null) {
					throw new WebApplicationException("Report not found", Status.NOT_FOUND);
				}
				final ApprovalStep step = r.loadApprovalStep(engine.getContext()).get();
				if (step == null) {
					logger.info("Report UUID {} does not currently need an approval", r.getUuid());
					throw new WebApplicationException("This report is not pending approval", Status.BAD_REQUEST);
				}

				//Verify that this user can approve for this step.
				boolean canApprove = engine.canUserApproveStep(approver.getUuid(), step.getUuid());
				if (canApprove == false) {
					logger.info("User UUID {} cannot approve report UUID {} for step UUID {}", approver.getUuid(), r.getUuid(), step.getUuid());
					throw new WebApplicationException("User cannot approve report", Status.FORBIDDEN);
				}

				//Write the approval
				ApprovalAction approval = new ApprovalAction();
				approval.setReport(r);
				approval.setStep(ApprovalStep.createWithUuid(step.getUuid()));
				approval.setPerson(approver);
				approval.setType(ApprovalType.APPROVE);
				engine.getApprovalActionDao().insert(approval);

				//Update the report
				r.setApprovalStep(ApprovalStep.createWithUuid(step.getNextStepUuid()));
				if (step.getNextStepUuid() == null) {
					//Done with approvals, move to released (or cancelled) state!
					r.setState((r.getCancelledReason() != null) ? ReportState.CANCELLED : ReportState.RELEASED);
					r.setReleasedAt(DateTime.now());
					sendReportReleasedEmail(r);
				} else {
					sendApprovalNeededEmail(r);
				}
				dao.update(r, approver);

				//Add the comment
				if (comment != null && comment.getText() != null && comment.getText().trim().length() > 0)  {
					comment.setReportUuid(r.getUuid());
					comment.setAuthor(approver);
					engine.getCommentDao().insert(comment);
				}

				AnetAuditLogger.log("report {} approved by {} (uuid: {})", r.getUuid(), approver.getName(), approver.getUuid());
				return r;
			}
		});
	}

	private void sendReportReleasedEmail(Report r) {
		AnetEmail email = new AnetEmail();
		ReportReleasedEmail action = new ReportReleasedEmail();
		action.setReport(r);
		email.setAction(action);
		try {
			email.addToAddress(r.loadAuthor(engine.getContext()).get().getEmailAddress());
			AnetEmailWorker.sendEmailAsync(email);
		} catch (InterruptedException | ExecutionException e) {
			throw new WebApplicationException("failed to load Author", e);
		}
	}

	/**
	 * Rejects a report and moves it back to the author with state REJECTED.
	 * @param uuid the Report UUID to reject
	 * @param reason : A @link Comment object which will be posted to the report with the reason why the report was rejected.
	 * @return 200 on a successful reject, 401 if you don't have privileges to reject this report.
	 */
	@POST
	@Timed
	@Path("/{uuid}/reject")
	public Report rejectReport(@Auth Person approver, @PathParam("uuid") String uuid, Comment reason) {
		final Handle dbHandle = AnetObjectEngine.getInstance().getDbHandle();
		return dbHandle.inTransaction(new TransactionCallback<Report>() {
			public Report inTransaction(Handle conn, TransactionStatus status) throws Exception {
				final Report r = dao.getByUuid(uuid, approver);
				if (r == null) { throw new WebApplicationException(Status.NOT_FOUND); }
				final ApprovalStep step = r.loadApprovalStep(engine.getContext()).get();
				if (step == null) {
					logger.info("Report UUID {} does not currently need an approval", r.getUuid());
					throw new WebApplicationException("This report is not pending approval", Status.BAD_REQUEST);
				}

				//Verify that this user can reject for this step.
				boolean canApprove = engine.canUserApproveStep(approver.getUuid(), step.getUuid());
				if (canApprove == false) {
					logger.info("User UUID {} cannot reject report UUID {} for step UUID {}", approver.getUuid(), r.getUuid(), step.getUuid());
					throw new WebApplicationException("User cannot approve report", Status.FORBIDDEN);
				}

				//Write the rejection
				ApprovalAction approval = new ApprovalAction();
				approval.setReport(r);
				approval.setStep(ApprovalStep.createWithUuid(step.getUuid()));
				approval.setPerson(approver);
				approval.setType(ApprovalType.REJECT);
				engine.getApprovalActionDao().insert(approval);

				//Update the report
				r.setApprovalStep(null);
				r.setState(ReportState.REJECTED);
				dao.update(r, approver);

				//Add the comment
				reason.setReportUuid(r.getUuid());
				reason.setAuthor(approver);
				engine.getCommentDao().insert(reason);

				sendReportRejectEmail(r, approver, reason);
				AnetAuditLogger.log("report {} rejected by {} (uuid: {})", r.getUuid(), approver.getName(), approver.getUuid());
				return r;
			}
		});
	}

	private void sendReportRejectEmail(Report r, Person rejector, Comment rejectionComment) {
		ReportRejectionEmail action = new ReportRejectionEmail();
		action.setReport(r);
		action.setRejector(rejector);
		action.setComment(rejectionComment);
		AnetEmail email = new AnetEmail();
		email.setAction(action);
		try {
			email.addToAddress(r.loadAuthor(engine.getContext()).get().getEmailAddress());
			AnetEmailWorker.sendEmailAsync(email);
		} catch (InterruptedException | ExecutionException e) {
			throw new WebApplicationException("failed to load Author", e);
		}
	}

	@POST
	@Timed
	@Path("/{uuid}/comments")
	public Comment postNewComment(@Auth Person author, @PathParam("uuid") String reportUuid, Comment comment) {
		comment.setReportUuid(reportUuid);
		comment.setAuthor(author);
		comment = engine.getCommentDao().insert(comment);
		sendNewCommentEmail(dao.getByUuid(reportUuid, author), comment);
		return comment;
	}

	private void sendNewCommentEmail(Report r, Comment comment) {
		AnetEmail email = new AnetEmail();
		NewReportCommentEmail action = new NewReportCommentEmail();
		action.setReport(r);
		action.setComment(comment);
		email.setAction(action);
		try {
			email.addToAddress(r.loadAuthor(engine.getContext()).get().getEmailAddress());
			AnetEmailWorker.sendEmailAsync(email);
		} catch (InterruptedException | ExecutionException e) {
			throw new WebApplicationException("failed to load Author", e);
		}
	}

	@GET
	@Timed
	@Path("/{uuid}/comments")
	public List<Comment> getCommentsForReport(@PathParam("uuid") String reportUuid) {
		return engine.getCommentDao().getCommentsForReport(Report.createWithUuid(reportUuid));
	}

	@DELETE
	@Timed
	@Path("/{uuid}/comments/{commentUuid}")
	public Response deleteComment(@Auth Person user, @PathParam("commentUuid") String commentUuid) {
		// For now, only admins are allowed to delete a comment
		// (even though there's no action for it in the front-end).
		AuthUtils.assertAdministrator(user);
		int numRows = engine.getCommentDao().delete(commentUuid);
		if (numRows != 1) {
			throw new WebApplicationException("Unable to delete comment", Status.NOT_FOUND);
		}
		return Response.ok().build();
	}

	@POST
	@Timed
	@Path("/{uuid}/email")
	public Response emailReport(@Auth Person user, @PathParam("uuid") String reportUuid, AnetEmail email) {
		final Report r = dao.getByUuid(reportUuid, user);
		if (r == null) { return Response.status(Status.NOT_FOUND).build(); }

		ReportEmail action = new ReportEmail();
		action.setReport(Report.createWithUuid(reportUuid));
		action.setSender(user);
		action.setComment(email.getComment());
		email.setAction(action);
		AnetEmailWorker.sendEmailAsync(email);
		return Response.ok().build();
	}

	/*
	 * Delete a draft report. Authors can delete DRAFT, REJECTED reports. Admins can delete any report
	 */
	@DELETE
	@Timed
	@Path("/{uuid}/delete")
	public Response deleteReport(@Auth Person user, @PathParam("uuid") String reportUuid) {
		final Report report = dao.getByUuid(reportUuid, user);
		assertCanDeleteReport(report, user);

		dao.deleteReport(report);
		return Response.ok().build();
	}

	private void assertCanDeleteReport(Report report, Person user) {
		if (AuthUtils.isAdmin(user)) { return; }

		if (report.getState() == ReportState.DRAFT || report.getState() == ReportState.REJECTED) {
			//only the author may delete these reports
			if (Objects.equals(report.getAuthor().getUuid(), user.getUuid())) {
				return;
			}
		}
		throw new WebApplicationException("You cannot delete this report", Status.FORBIDDEN);
	}

	@GET
	@Timed
	@Path("/search")
	public AnetBeanList<Report> search(@Auth Person user, @Context HttpServletRequest request) {
		try {
			return search(user, ResponseUtils.convertParamsToBean(request, ReportSearchQuery.class));
		} catch (IllegalArgumentException e) {
			throw new WebApplicationException(e.getMessage(), e.getCause(), Status.BAD_REQUEST);
		}
	}

	// Note: Split off from the GraphQL Query, as Jersey would otherwise complain about the @GraphQLRootContext
	@POST
	@Timed
	@Path("/search")
	public AnetBeanList<Report> search(@Auth Person user, ReportSearchQuery query) {
		return search(null, user, query);
	}

	@GraphQLQuery(name="reportList")
	public AnetBeanList<Report> search(@GraphQLRootContext Map<String, Object> context, @GraphQLArgument(name="_") @Auth Person user,
			@GraphQLArgument(name="query") ReportSearchQuery query) {
		user = DaoUtils.getUser(context, user);
		return dao.search(query, user);
	}

	/**
	 *
	 * @param start Start timestamp for the rollup period
	 * @param end end timestamp for the rollup period
	 * @param engagementDateStart minimum date on reports to include
	 * @param orgType  If orgUuid is NULL then the type of organization (ADVISOR_ORG or PRINCIPAL_ORG) that the chart should filter on
	 * @param orgUuid if orgType is NULL then the parent org to create the graph off of. All reports will be by/about this org or a child org.
	 */
	@GET
	@Timed
	@Path("/rollupGraph")
	public List<RollupGraph> getDailyRollupGraph(@QueryParam("startDate") Long start,
			@QueryParam("endDate") Long end,
			@QueryParam("orgType") OrganizationType orgType,
			@QueryParam("advisorOrganizationUuid") String advisorOrgUuid,
			@QueryParam("principalOrganizationUuid") String principalOrgUuid) {
		DateTime startDate = new DateTime(start);
		DateTime endDate = new DateTime(end);

		final List<RollupGraph> dailyRollupGraph;

		@SuppressWarnings("unchecked")
		final List<String> nonReportingOrgsShortNames = (List<String>) config.getDictionary().get("non_reporting_ORGs");
		final Map<String, Organization> nonReportingOrgs = getOrgsByShortNames(nonReportingOrgsShortNames);

		if (principalOrgUuid != null) {
			dailyRollupGraph = dao.getDailyRollupGraph(startDate, endDate, principalOrgUuid, OrganizationType.PRINCIPAL_ORG, nonReportingOrgs);
		} else if (advisorOrgUuid != null) {
			dailyRollupGraph = dao.getDailyRollupGraph(startDate, endDate, advisorOrgUuid, OrganizationType.ADVISOR_ORG, nonReportingOrgs);
		} else {
			if (orgType == null) {
				orgType = OrganizationType.ADVISOR_ORG;
			}
			dailyRollupGraph = dao.getDailyRollupGraph(startDate, endDate, orgType, nonReportingOrgs);
		}

		Collections.sort(dailyRollupGraph, rollupGraphComparator);

		return dailyRollupGraph;

	}

	@POST
	@Timed
	@Path("/rollup/email")
	public Response emailRollup(@Auth Person user,
			@QueryParam("startDate") Long start,
			@QueryParam("endDate") Long end,
			@QueryParam("orgType") OrganizationType orgType,
			@QueryParam("advisorOrganizationUuid") String advisorOrgUuid,
			@QueryParam("principalOrganizationUuid") String principalOrgUuid,
			AnetEmail email) {
		DailyRollupEmail action = new DailyRollupEmail();
		action.setStartDate(new DateTime(start));
		action.setEndDate(new DateTime(end));
		action.setComment(email.getComment());
		action.setAdvisorOrganizationUuid(advisorOrgUuid);
		action.setPrincipalOrganizationUuid(principalOrgUuid);
		action.setChartOrgType(orgType);

		email.setAction(action);
		AnetEmailWorker.sendEmailAsync(email);

		return Response.ok().build();
	}

	/* Used to generate an HTML view of the daily rollup email
	 *
	 */
	@GET
	@Timed
	@Path("/rollup")
	@Produces(MediaType.TEXT_HTML)
	public Response showRollupEmail(@Auth Person user, @QueryParam("startDate") Long start,
			@QueryParam("endDate") Long end,
			@QueryParam("orgType") OrganizationType orgType,
			@QueryParam("advisorOrganizationUuid") String advisorOrgUuid,
			@QueryParam("principalOrganizationUuid") String principalOrgUuid,
			@QueryParam("showText") @DefaultValue("false") Boolean showReportText) {
		DailyRollupEmail action = new DailyRollupEmail();
		action.setStartDate(new DateTime(start));
		action.setEndDate(new DateTime(end));
		action.setChartOrgType(orgType);
		action.setAdvisorOrganizationUuid(advisorOrgUuid);
		action.setPrincipalOrganizationUuid(principalOrgUuid);

		Map<String,Object> context = action.execute();

		@SuppressWarnings("unchecked")
		final Map<String,Object> fields = (Map<String, Object>) config.getDictionary().get("fields");

		context.put("context", engine.getContext());
		context.put("serverUrl", config.getServerUrl());
		context.put(AdminSettingKeys.SECURITY_BANNER_TEXT.name(), engine.getAdminSetting(AdminSettingKeys.SECURITY_BANNER_TEXT));
		context.put(AdminSettingKeys.SECURITY_BANNER_COLOR.name(), engine.getAdminSetting(AdminSettingKeys.SECURITY_BANNER_COLOR));
		context.put(DailyRollupEmail.SHOW_REPORT_TEXT_FLAG, showReportText);
		context.put("fields", fields);

		try {
			Configuration freemarkerConfig = new Configuration(Configuration.getVersion());
			freemarkerConfig.setObjectWrapper(new DefaultObjectWrapperBuilder(Configuration.getVersion()).build());
			freemarkerConfig.loadBuiltInEncodingMap();
			freemarkerConfig.setDefaultEncoding(StandardCharsets.UTF_8.name());
			freemarkerConfig.setClassForTemplateLoading(this.getClass(), "/");
			freemarkerConfig.setAPIBuiltinEnabled(true);

			Template temp = freemarkerConfig.getTemplate(action.getTemplateName());
			StringWriter writer = new StringWriter();
			temp.process(context, writer);

			return Response.ok(writer.toString(), MediaType.TEXT_HTML_TYPE).build();
		} catch (Exception e) {
			throw new WebApplicationException(e);
		}
	}

	/**
	 * Gets aggregated data per organization for engagements attended and reports submitted
	 * for each advisor in a given organization.
	 * @param weeksAgo Weeks ago integer for the amount of weeks before the current week
	 *
	 */
	@GET
	@Timed
	@Path("/insights/advisors")
	@RolesAllowed("SUPER_USER")
	public List<Map<String, Object>> getAdvisorReportInsights(
		@DefaultValue("3") 	@QueryParam("weeksAgo") int weeksAgo,
		@DefaultValue(Organization.DUMMY_ORG_UUID) @QueryParam("orgUuid") String orgUuid) {

		DateTime now = DateTime.now();
		DateTime weekStart = now.withDayOfWeek(DateTimeConstants.MONDAY).withTimeAtStartOfDay();
		DateTime startDate = weekStart.minusWeeks(weeksAgo);
		final List<Map<String, Object>> list = dao.getAdvisorReportInsights(startDate, now, orgUuid);

		if (Organization.DUMMY_ORG_UUID.equals(orgUuid)) {
			final Set<String> tlf = Stream.of("organizationShortName").collect(Collectors.toSet());
			return Utils.resultGrouper(list, "stats", "organizationUuid", tlf);
		} else {
			final Set<String> tlf = Stream.of("name").collect(Collectors.toSet());
			return Utils.resultGrouper(list, "stats", "personUuid", tlf);
		}
	}

	private Map<String, Organization> getOrgsByShortNames(List<String> orgShortNames) {
		final Map<String, Organization> result = new HashMap<>();
		for (final Organization organization : engine.getOrganizationDao().getOrgsByShortNames(orgShortNames)) {
			result.put(organization.getUuid(), organization);
		}
		return result;
	}

	/**
	 * The comparator to be used when ordering the roll up graph results to ensure
	 * that any pinned organisation names are returned at the start of the list.
	 */
	public static class RollupGraphComparator implements Comparator<RollupGraph> {

		private final List<String> pinnedOrgNames;

		/**
		 * Creates an instance of this comparator using the supplied pinned organisation
		 * names.
		 *
		 * @param pinnedOrgNames
		 *            the pinned organisation names
		 */
		public RollupGraphComparator(final List<String> pinnedOrgNames) {
			this.pinnedOrgNames = pinnedOrgNames;
		}

		/**
		 * Compare the suppled objects, based on whether they are in the list of pinned
		 * org names and their short names.
		 *
		 * @param o1
		 *            the first object
		 * @param o2
		 *            the second object
		 * @return the result of the comparison.
		 */
		@Override
		public int compare(final RollupGraph o1, final RollupGraph o2) {

			int result = 0;

			if (o1.getOrg() != null && o2.getOrg() == null) {
				result = -1;
			} else if (o2.getOrg() != null && o1.getOrg() == null) {
				result = 1;
			} else if (o2.getOrg() == null && o1.getOrg() == null) {
				result = 0;
			} else if (pinnedOrgNames.contains(o1.getOrg().getShortName())) {
				if (pinnedOrgNames.contains(o2.getOrg().getShortName())) {
					result = pinnedOrgNames.indexOf(o1.getOrg().getShortName())
							- pinnedOrgNames.indexOf(o2.getOrg().getShortName());
				} else {
					result = -1;
				}
			} else if (pinnedOrgNames.contains(o2.getOrg().getShortName())) {
				result = 1;
			} else {
				final int c = o1.getOrg().getShortName().compareTo(o2.getOrg().getShortName());

				if (c != 0) {
					result = c;
				} else {
					result = o1.getOrg().getUuid().compareTo(o2.getOrg().getUuid());
				}
			}

			return result;
		}
	}
}
