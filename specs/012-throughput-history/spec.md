# Feature Specification: Full-Run Throughput History

**Feature Branch**: `012-throughput-history`

**Created**: 2026-09-07

**Status**: Draft

**Input**: The dashboard chart shows only recent throughput and loses the rest of a long run. Keep the
whole run visible, preserve the detail that matters (stalls and bursts), stop the chart from being
rebuilt blank when navigating back to it, and make sure the history is saved.

---

## Summary

Reported from use: *"graphs are not reseting … we show realtime and then i think last 2-3 mins of data
because imagine we are working on large task. show the details everything. save the information."*

Investigation found three separate defects behind that one report.

**1. The live chart threw history away.** `LIVE_CHART_POINTS = 600` trimmed the series to the last 600
points at 1 Hz — ten minutes. On a four-hour job you could see the last ten minutes and nothing else,
and the earlier history was gone from the screen permanently. This is the main complaint.

**2. Navigating back to the dashboard produced a blank chart.** `DashboardController.init` is called
only when a job *starts*. `View → Dashboard` went through `ScreenNavigator.navigateTo`, which reloaded
the FXML and built a **second, uninitialised controller** — no chart, no timeline, no job. Meanwhile the
original controller's refresh `Timeline` kept firing once a second forever against a node no longer in
the scene. This is almost certainly what "not resetting" describes, and it also leaked a timer per job.

**3. The x-axis was not time.** Elapsed seconds came from `++chartElapsedSeconds`, a counter
incremented once per UI refresh. Under load the FX thread delays and drops ticks, so the live chart's
timeline drifted from the engine's own elapsed clock. The same job told two different stories about
when something happened, and only the persisted one was true.

**Saving was already correct** and is left alone: the engine writes a sample every second through
`JobEventRecorder` into `JOB_THROUGHPUT_SAMPLE`, batched on a 5-second flush. At 1 Hz the queue is never
under pressure and nothing is dropped.

The fix keeps every sample of the run in memory and reduces it *for display only*, so the whole job is
always on screen no matter how long it runs.

### Why an envelope rather than an average

The obvious reduction — and what the stored-job SQL query does — is a mean per bucket. On a long job
each bucket spans minutes, and a 40-second stall averaged across two minutes of healthy throughput
becomes a shallow dip indistinguishable from noise. The stall is the most interesting event in the run,
and averaging is precisely the operation that erases it.

Each bucket therefore contributes its **minimum and maximum**, in chronological order. The line becomes
an envelope whose lower edge is the worst throughput in that span and upper edge the best. A stall
touches zero and stays visible at any duration; a burst is not flattened into the mean around it.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — See the Whole Run (Priority: P1)

A user runs a four-hour migration. At any point the chart shows the entire job so far, not a rolling
window. A stall that happened ninety minutes ago is still visible.

**Independent Test**: Feed 14 400 synthetic samples with a 40-second stall at the two-hour mark. Assert
the reduced series still contains a zero value and still contains the healthy rate either side.

**Acceptance Scenarios**:

1. **Given** a job running longer than ten minutes, **When** the user looks at the chart, **Then** the
   full elapsed range is plotted, starting at zero.
2. **Given** a run containing a stall, **When** the chart reduces it for display, **Then** the stall is
   still visible.
3. **Given** a run containing a burst, **When** the chart reduces it, **Then** the peak is not averaged
   away.
4. **Given** a very long job, **When** the chart renders, **Then** the plotted point count stays bounded
   regardless of duration.

---

### User Story 2 — Watch Current Activity, Task Manager Style (Priority: P1)

**This is the default view, and the priority was corrected after the first implementation.** The initial
build made whole-run the default, and the response was immediate: *"i can still see throuput chart show
everything since beginning. it's large size. we need something like windows task manager. it shows latest
progress."*

The original report — *"we show realtime and then i think last 2-3 mins of data"* — was describing the
wanted behaviour, not the defect. It was read as a complaint about losing history, and the genuine
data-loss bugs found alongside it (US1, US3) reinforced that misreading. Both fixes were right; making
whole-run the default was not.

What a live dashboard needs is a **fixed-width window that scrolls**. The horizontal scale stays
constant, new samples enter at the right, old ones leave at the left. A spike is the same width an hour
in as it was in the first minute, so the eye can compare them. A view that keeps stretching to fit the
whole job compresses current activity until it is unreadable — which is exactly what "it's large size"
describes.

**Acceptance Scenarios**:

1. **Given** a running job, **When** the user opens the dashboard, **Then** the chart defaults to a
   rolling window, not the whole run.
2. **Given** a job running longer than the window, **When** samples arrive, **Then** the x-axis span
   stays constant and slides — it MUST NOT stretch to fit.
3. **Given** a job shorter than the window, **When** the chart draws, **Then** the axis is held at full
   window width rather than growing, so the trace does not appear to speed up as it fills.
4. **Given** any window, **When** the user changes it, **Then** the chart repaints immediately and the
   retained history is unaffected — the control changes what is drawn, never what is kept or saved.

---

### User Story 3 — The Dashboard Survives Navigation (Priority: P1)

A user on the Job History screen presses `Ctrl+2` to check progress. The dashboard returns exactly as
they left it, with the running job's chart intact.

**Acceptance Scenarios**:

1. **Given** a running job, **When** the user navigates away and back, **Then** the same dashboard
   returns with its history intact and no second controller is created.
2. **Given** a running job, **When** the user starts a new job, **Then** the chart starts empty.
3. **Given** a dashboard that is discarded, **When** it is replaced, **Then** its refresh timeline and
   resource monitor are stopped.

---

## Requirements *(mandatory)*

- **FR-077**: The live chart MUST plot the entire run, reducing for display rather than discarding
  samples. Plotted points MUST stay bounded regardless of job duration.
- **FR-078**: Every sample taken during a job MUST be retained in memory for the life of that job.
- **FR-079**: Display reduction MUST preserve each bucket's minimum and maximum. Averaging alone is not
  acceptable, because it hides stalls.
- **FR-080**: Series MUST be reduced independently — files/sec and MB/sec do not peak together, and
  reducing on one would misrepresent the other.
- **FR-081**: Elapsed time on the chart MUST come from a wall clock, not a count of UI refreshes.
- **FR-082**: The user MUST be able to switch between the whole run and the recent window, and the
  switch MUST repaint immediately.
- **FR-086**: The default view MUST be a rolling window whose x-axis is a **fixed width that scrolls**,
  not an axis that auto-ranges over the data it holds. Before a full window has elapsed the axis MUST be
  held at full width rather than growing.
- **FR-087**: Whole run MUST remain available as an explicit choice. It is the right view for reviewing
  a finished job and the wrong one for watching a live job.
- **FR-083**: Navigating to the dashboard while a job is running MUST return the running dashboard, not
  a new one. Only starting a job MUST reset the chart.
- **FR-084**: A discarded dashboard MUST stop its refresh timeline and resource monitor.
- **FR-085**: A finished job's stored chart MUST render with the same reduction as the live chart, so a
  job looks the same after it ends as it did while running.

---

## Success Criteria

- **SC-001**: A 40-second stall two hours into a four-hour job is visible on the chart without zooming.
- **SC-002**: A 20-hour job plots no more than ~800 points per series.
- **SC-003**: Navigating away from and back to a running dashboard preserves the chart and creates no
  additional refresh timeline.
- **SC-004**: Chart elapsed time matches the persisted samples' elapsed time.

---

## Not in scope

- **The exported HTML SVG still uses the averaged SQL reduction.** It is a fixed-size static image and
  a follow-up, not a regression: it behaves exactly as before. Noted rather than silently left.
- **Persisting the live in-memory samples separately.** The engine already writes every sample to
  `JOB_THROUGHPUT_SAMPLE`; a second copy would be two sources of truth.
- **CPU and memory series on the dashboard chart.** Sampled and stored, but not plotted. Adding a third
  and fourth chart is a layout question, not a data one.
