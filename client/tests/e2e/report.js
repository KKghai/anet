let _includes = require("lodash/includes")
let moment = require("moment")
let test = require("../util/test")

test("Draft and submit a report", async t => {
  t.plan(14)

  let {
    pageHelpers,
    $,
    $$,
    assertElementText,
    assertElementNotPresent,
    By,
    until,
    shortWaitMs
  } = t.context

  await pageHelpers.goHomeAndThenToReportsPage()
  await pageHelpers.writeInForm("#intent", "meeting goal")

  let $engagementDate = await $("#engagementDate")
  await $engagementDate.click()
  await t.context.driver.sleep(shortWaitMs) // wait for the datepicker to pop up

  await pageHelpers.clickTodayButton()

  let $locationAutocomplete = await pageHelpers.chooseAutocompleteOption(
    "#location",
    "general hospita"
  )

  t.is(
    await $locationAutocomplete.getAttribute("value"),
    "General Hospital",
    "Clicking a location autocomplete suggestion populates the autocomplete field."
  )

  let $positiveAtmosphereButton = await $("#positiveAtmos")
  await $positiveAtmosphereButton.click()

  let $attendeesAutocomplete = await pageHelpers.chooseAutocompleteOption(
    "#attendees",
    "christopf topferness"
  )

  t.is(
    await $attendeesAutocomplete.getAttribute("value"),
    "",
    "Clicking an attendee autocomplete suggestion empties the autocomplete field."
  )

  let [
    $principalPrimaryCheckbox,
    $principalName,
    $principalPosition,
    $principalLocation,
    $principalOrg
  ] = await $$("#attendeesTable tbody tr:last-child td")

  t.is(
    await $principalPrimaryCheckbox
      .findElement(By.css("input"))
      .getAttribute("value"),
    "on",
    "Principal primary attendee checkbox should be checked"
  )
  await assertElementText(t, $principalName, "CIV TOPFERNESS, Christopf")
  await assertElementText(
    t,
    $principalPosition,
    "Planning Captain, MOD-FO-00004"
  )
  await assertElementText(t, $principalOrg, "MoD")

  let $tasksAutocomplete = await pageHelpers.chooseAutocompleteOption(
    "#tasks",
    "1.1.B"
  )

  t.is(
    await $tasksAutocomplete.getAttribute("value"),
    "",
    "Clicking a Task autocomplete suggestion empties the autocomplete field."
  )

  let $newTaskRow = await $(".tasks-selector table tbody tr td")
  await assertElementText(
    t,
    $newTaskRow,
    "1.1.B - Milestone the Second in EF 1.1"
  )

  await pageHelpers.writeInForm("#keyOutcomes", "key outcomes")
  await pageHelpers.writeInForm("#nextSteps", "next steps")
  await pageHelpers.writeInForm(
    ".reportTextField .public-DraftEditor-content",
    "engagement details"
  )

  let editorCssPath =
    ".reportSensitiveInformationField .public-DraftEditor-content"
  let $reportSensitiveInformationField = await $(editorCssPath)
  t.false(
    await $reportSensitiveInformationField.isDisplayed(),
    'Report sensitive info should not be present before "add sensitive information" button is clicked"'
  )

  let $addSensitiveInfoButton = await $("#toggleSensitiveInfo")
  await $addSensitiveInfoButton.click()

  await t.context.driver.wait(
    until.elementIsVisible($reportSensitiveInformationField)
  )
  await pageHelpers.writeInForm(editorCssPath, "sensitive info")
  let $addAuthGroupShortcutButtons = await $$(
    "#meeting-details .shortcut-list button"
  )
  // Add all recent authorization groups
  await Promise.all(
    $addAuthGroupShortcutButtons.map($button => $button.click())
  )

  let $formButtonSubmit = await $("#formBottomSubmit")
  await t.context.driver.wait(until.elementIsEnabled($formButtonSubmit))
  await $formButtonSubmit.click()
  await pageHelpers.assertReportShowStatusText(
    t,
    "This is a DRAFT report and hasn't been submitted."
  )

  let currentPathname = await t.context.getCurrentPathname()
  t.regex(
    currentPathname,
    /reports\/[0-9a-f-]+/,
    "URL is updated to reports/show page"
  )

  let $submitReportButton = await $("#submitReportButton")
  await $submitReportButton.click()
  await t.context.driver.wait(until.stalenessOf($submitReportButton))
  await assertElementNotPresent(
    t,
    "#submitReportButton",
    "Submit button should be gone",
    shortWaitMs
  )
  await pageHelpers.assertReportShowStatusText(
    t,
    "This report is PENDING approvals."
  )

  let $allertSuccess = await t.context.driver.findElement(
    By.css(".alert-success")
  )
  await t.context.driver.wait(until.elementIsVisible($allertSuccess))
  await assertElementText(
    t,
    $allertSuccess,
    "Report submitted",
    "Clicking the submit report button displays a message telling the user that the action was successful."
  )
})

test("Publish report chain", async t => {
  t.plan(6)

  let {
    pageHelpers,
    $,
    $$,
    assertElementText,
    assertElementNotPresent,
    By,
    Key,
    until,
    shortWaitMs,
    longWaitMs
  } = t.context
  // Try to have Erin approve her own report
  await t.context.get("/", "erin")
  let $homeTileErin = await $$(".home-tile")
  let [
    $draftReportsErin,
    $reportsPendingErin,
    $orgReportsErin,
    $upcomingEngagementsErin
  ] = $homeTileErin
  await t.context.driver.wait(until.elementIsVisible($reportsPendingErin))
  await $reportsPendingErin.click()

  await t.context.driver.wait(until.stalenessOf($reportsPendingErin))
  await assertElementNotPresent(
    t,
    ".read-report-button",
    "Erin should not be allowed to approve her own reports",
    shortWaitMs
  )

  // First Jacob needs to approve the report, then rebecca can approve the report
  await t.context.get("/", "jacob")
  let $homeTileJacob = await $$(".home-tile")
  let [
    $draftReportsJacob,
    $reportsPendingJacob,
    $orgReportsJacob,
    $upcomingEngagementsJacob
  ] = $homeTileJacob
  await t.context.driver.wait(until.elementIsVisible($reportsPendingJacob))
  await $reportsPendingJacob.click()

  await t.context.driver.wait(until.stalenessOf($reportsPendingJacob))
  let $firstReadReportButtonJacob = await $(".read-report-button")
  await t.context.driver.wait(
    until.elementIsEnabled($firstReadReportButtonJacob)
  )
  await $firstReadReportButtonJacob.click()

  await pageHelpers.assertReportShowStatusText(
    t,
    "This report is PENDING approvals."
  )
  let $jacobApproveButton = await $(".approve-button")
  await t.context.driver.wait(until.elementIsEnabled($jacobApproveButton))
  await $jacobApproveButton.click()
  await t.context.driver.wait(until.stalenessOf($jacobApproveButton))

  await t.context.get("/", "rebecca")
  let $homeTile = await $$(".home-tile")
  let [
    $draftReports,
    $reportsPending,
    $orgReports,
    $upcomingEngagements
  ] = $homeTile
  await t.context.driver.wait(until.elementIsVisible($reportsPending))
  await $reportsPending.click()

  await t.context.driver.wait(until.stalenessOf($reportsPending))
  let $firstReadReportButton = await $(".read-report-button")
  await t.context.driver.wait(until.elementIsEnabled($firstReadReportButton))
  await $firstReadReportButton.click()

  await pageHelpers.assertReportShowStatusText(
    t,
    "This report is PENDING approvals."
  )
  let $rebeccaApproveButton = await $(".approve-button")
  await $rebeccaApproveButton.click()
  await t.context.driver.wait(until.stalenessOf($rebeccaApproveButton))

  // Admin user needs to publish the report
  await t.context.get("/", "arthur")
  let $homeTileArthur = await $$(".home-tile")
  let [
    $draftReportsArthur,
    $reportsPendingAll,
    $reportsPendingArthur,
    $upcomingEngagementsArthur,
    $reportsSensitiveInfo,
    $approvedReports
  ] = $homeTileArthur
  await t.context.driver.wait(until.elementIsVisible($approvedReports))
  await $approvedReports.click()

  await t.context.driver.wait(until.stalenessOf($approvedReports))
  let $firstReadApprovedReportButton = await $(".read-report-button")
  await t.context.driver.wait(
    until.elementIsEnabled($firstReadApprovedReportButton)
  )
  await $firstReadApprovedReportButton.click()

  await pageHelpers.assertReportShowStatusText(t, "This report is APPROVED.")
  let $arthurPublishButton = await $(".publish-button")
  await $arthurPublishButton.click()
  await t.context.driver.wait(until.stalenessOf($arthurPublishButton))

  // check if page is redirected to search results

  // let $notificationDailyRollup = await t.context.driver.findElement(By.css('.Toastify__toast-body'))
  // await assertElementText(
  //     t,
  //     $notificationDailyRollup,
  //     'Successfully approved report. It has been added to the daily rollup',
  //     'When a report is approved, the user sees a message indicating that it has been added to the daily rollup'
  // )

  let $rollupLink = await t.context.driver.findElement(
    By.linkText("Daily rollup")
  )
  await t.context.driver.wait(until.elementIsEnabled($rollupLink))
  await $rollupLink.click()
  let currentPathname = await t.context.getCurrentPathname()
  t.is(
    currentPathname,
    "/rollup",
    'Clicking the "daily rollup" link takes the user to the rollup page'
  )
  await $("#daily-rollup")

  let $$rollupDateRange = await $$(".rollupDateRange .bp3-input")
  await $$rollupDateRange[0].click()
  let $todayButton = await t.context.driver.findElement(
    By.xpath('//a/div[text()="Today"]')
  )
  await $todayButton.click()
  // Now dismiss the date popup
  await $$rollupDateRange[0].sendKeys(Key.TAB)
  await $$rollupDateRange[1].sendKeys(Key.TAB)
  await t.context.driver.sleep(longWaitMs) // wait for report collection to load

  let $reportCollection = await $(".report-collection table")
  await t.context.driver.wait(until.elementIsVisible($reportCollection))
  let $approvedIntent = await $reportCollection.findElement(
    By.linkText("meeting goal")
  )
  await assertElementText(
    t,
    $approvedIntent,
    "meeting goal",
    "Daily rollup report list includes the recently approved report"
  )
})

test("Verify that validation and other reports/new interactions work", async t => {
  t.plan(28)

  let {
    assertElementText,
    $,
    $$,
    assertElementNotPresent,
    pageHelpers,
    shortWaitMs,
    By
  } = t.context

  await pageHelpers.goHomeAndThenToReportsPage()
  await assertElementText(
    t,
    await $(".legend .title-text"),
    "Create a new Report"
  )

  let $searchBarInput = await $("#searchBarInput")

  async function verifyFieldIsRequired(
    $fieldGroup,
    $input,
    warningClass,
    fieldName
  ) {
    await $input.click()
    await $input.clear()
    await $searchBarInput.click()

    t.true(
      _includes(await $fieldGroup.getAttribute("class"), warningClass),
      `${fieldName} enters invalid state when the user leaves the field without entering anything`
    )

    await $input.sendKeys("user input")
    await $input.sendKeys(t.context.Key.TAB) // fire blur event
    t.false(
      _includes(await $fieldGroup.getAttribute("class"), warningClass),
      `After typing in ${fieldName} field, warning state goes away`
    )
  }

  let $meetingGoalInput = await $("#intent")
  let $meetingGoalDiv = await t.context.driver.findElement(
    By.xpath(
      '//textarea[@id="intent"]/ancestor::div[contains(concat(" ", normalize-space(@class), " "), " form-group ")]'
    )
  )
  // check that parent div.form-group does not have class 'has-error'
  t.false(
    _includes(await $meetingGoalDiv.getAttribute("class"), "has-error"),
    "Meeting goal does not start in an invalid state"
  )
  t.is(
    await $meetingGoalInput.getAttribute("value"),
    "",
    "Meeting goal field starts blank"
  )

  // check that parent div.form-group now has a class 'has-error'
  await verifyFieldIsRequired(
    $meetingGoalDiv,
    $meetingGoalInput,
    "has-error",
    "Meeting goal"
  )

  let $engagementDate = await $("#engagementDate")
  t.is(
    await $engagementDate.getAttribute("value"),
    "",
    "Engagement date field starts blank"
  )
  await $engagementDate.click()
  await t.context.driver.sleep(shortWaitMs) // wait for the datepicker to pop up

  await pageHelpers.clickTodayButton()

  t.is(
    await $engagementDate.getAttribute("value"),
    moment().format("DD-MM-YYYY"),
    'Clicking the "today" button puts the current date in the engagement field'
  )

  let $locationInput = await $("#location")
  t.is(
    await $locationInput.getAttribute("value"),
    "",
    "Location field starts blank"
  )

  let $locationShortcutButton = await $(
    ".location-form-group.shortcut-list button"
  )
  await $locationShortcutButton.click()
  t.is(
    await $locationInput.getAttribute("value"),
    "General Hospital",
    "Clicking the shortcut adds a location"
  )

  await assertElementNotPresent(
    t,
    "#cancelledReason",
    "Cancelled reason should not be present initially",
    shortWaitMs
  )
  let $atmosphereFormGroup = await $(".atmosphere-form-group")
  t.true(
    await $atmosphereFormGroup.isDisplayed(),
    "Atmospherics form group should be shown by default"
  )

  await assertElementNotPresent(
    t,
    "#atmosphere-details",
    "Atmospherics details should not be displayed before choosing atmospherics",
    shortWaitMs
  )

  let $positiveAtmosphereButton = await $("#positiveAtmos")
  await $positiveAtmosphereButton.click()

  let $atmosphereDetails = await $("#atmosphereDetails")
  t.is(
    await $atmosphereDetails.getAttribute("placeholder"),
    "Why was this engagement positive? (optional)"
  )

  let $neutralAtmosphereButton = await $("#neutralAtmos")
  await $neutralAtmosphereButton.click()
  t.is(
    (await $atmosphereDetails.getAttribute("placeholder")).trim(),
    "Why was this engagement neutral?"
  )

  let $negativeAtmosphereButton = await $("#negativeAtmos")
  await $negativeAtmosphereButton.click()
  t.is(
    (await $atmosphereDetails.getAttribute("placeholder")).trim(),
    "Why was this engagement negative?"
  )

  let $atmosphereDetailsGroup = await t.context.driver.findElement(
    By.xpath(
      '//input[@id="atmosphereDetails"]/ancestor::div[contains(concat(" ", normalize-space(@class), " "), " form-group ")]'
    )
  )

  await $neutralAtmosphereButton.click()
  // check that parent div.form-group now has a class 'has-error'
  await verifyFieldIsRequired(
    $atmosphereDetailsGroup,
    $atmosphereDetails,
    "has-error",
    "Neutral atmospherics details"
  )

  let $attendanceFieldsetTitle = await $("#attendance-fieldset .title-text")
  await assertElementText(
    t,
    $attendanceFieldsetTitle,
    "Meeting attendance",
    "Meeting attendance fieldset should have correct title for an uncancelled enagement"
  )

  let $cancelledCheckbox = await $(".cancelled-checkbox")
  await $cancelledCheckbox.click()

  await assertElementNotPresent(
    t,
    ".atmosphere-form-group",
    "After cancelling the enagement, the atmospherics should be hidden",
    shortWaitMs
  )
  let $cancelledReason = await $(".cancelled-reason-form-group")
  t.true(
    await $cancelledReason.isDisplayed(),
    "After cancelling the engagement, the cancellation reason should appear"
  )
  await assertElementText(
    t,
    $attendanceFieldsetTitle,
    "Planned attendance",
    "Meeting attendance fieldset should have correct title for a cancelled enagement"
  )

  let $attendeesRows = await $$("#attendeesTable tbody tr")
  t.is($attendeesRows.length, 2, "Attendees table starts with 2 body rows")

  let [
    $advisorPrimaryCheckbox,
    $advisorName,
    $advisorPosition,
    $advisorLocation,
    $advisorOrg
  ] = await $$("#attendeesTable tbody tr:first-child td")

  t.is(
    await $advisorPrimaryCheckbox
      .findElement(By.css("input"))
      .getAttribute("value"),
    "on",
    "Advisor primary attendee checkbox should be checked"
  )
  await assertElementText(t, $advisorName, "CIV ERINSON, Erin")
  await assertElementText(t, $advisorPosition, "EF 2.2 Advisor D")
  await assertElementText(t, $advisorOrg, "EF 2.2")

  $attendeesRows = await $$("#attendeesTable tbody tr")
  let $addAttendeeShortcutButtons = await $$(
    "#attendance-fieldset .shortcut-list button"
  )
  // Add all recent attendees
  await Promise.all($addAttendeeShortcutButtons.map($button => $button.click()))

  t.is(
    (await $$("#attendeesTable tbody tr")).length,
    $attendeesRows.length + $addAttendeeShortcutButtons.length,
    "Clicking the shortcut buttons adds rows to the table"
  )

  let $submitButton = await $("#formBottomSubmit")
  await $submitButton.click()
  await pageHelpers.assertReportShowStatusText(
    t,
    "This is a DRAFT report and hasn't been submitted."
  )
})
