(() => {
    "use strict";

    const state = {
        isAdmin: false,
        reviews: [],
        currentReview: null,
        permitDraft: null,
        trainingGroups: [],
        currentGroup: null,
        currentCandidate: null,
        currentPreflight: null
    };

    const permitFields = [
        {key: "sourcePermitNumber", label: "Source permit number"},
        {key: "normalizedPermitId", label: "Normalized permit ID *"},
        {key: "permitNumber", label: "Permit number"},
        {key: "authorId", label: "Authority / author ID"},
        {key: "permitType", label: "Permit type *"},
        {key: "version", label: "Revision / version"},
        {key: "season", label: "Season"},
        {key: "permitDate", label: "Permit date *", type: "date"},
        {key: "operatorId", label: "Operator ICAO *", maxlength: 3},
        {key: "reference", label: "Reference", className: "span-two"},
        {key: "validHours", label: "Valid hours", type: "number", min: 0},
        {key: "billingAddress", label: "Billing address", className: "span-two"},
        {key: "flightType", label: "Flight type *"},
        {key: "rawContent", label: "Raw source content", type: "textarea", className: "span-four", readonly: true}
    ];

    const flightFields = [
        {key: "flightNumber", type: "text"},
        {key: "registration", type: "text"},
        {key: "craftId", type: "number"},
        {key: "sourceAircraftType", type: "text"},
        {key: "purposeId", type: "text"},
        {key: "mtow", type: "number"},
        {key: "fromAirport", type: "text"},
        {key: "toAirport", type: "text"},
        {key: "etd", type: "text"},
        {key: "eta", type: "text"},
        {key: "serviceDays", type: "text"},
        {key: "beginDate", type: "date"},
        {key: "endDate", type: "date"},
        {key: "via", type: "text", className: "cell-wide"},
        {key: "remark", type: "text", className: "cell-wide"}
    ];

    const byId = id => document.getElementById(id);
    const workspace = byId("workspace");
    const rolePill = byId("role-pill");
    const trainingTab = byId("training-tab");

    class ApiError extends Error {
        constructor(status, message) {
            super(message);
            this.status = status;
        }
    }

    async function request(path, options = {}) {
        const headers = new Headers(options.headers || {});
        headers.set("Accept", "application/json");
        if (options.body !== undefined && options.body !== null) {
            headers.set("Content-Type", "application/json");
        }

        const response = await fetch(path, {
            method: options.method || "GET",
            headers,
            credentials: "same-origin",
            body: options.body === undefined || options.body === null
                ? undefined
                : JSON.stringify(options.body)
        });

        const text = await response.text();
        let payload = null;
        if (text) {
            try {
                payload = JSON.parse(text);
            } catch {
                payload = text;
            }
        }

        if (!response.ok) {
            const message = typeof payload === "object" && payload !== null
                ? payload.detail || payload.message || payload.error
                : payload;
            throw new ApiError(
                response.status,
                message || `${response.status} ${response.statusText}`);
        }
        return payload;
    }

    function escapeHtml(value) {
        return String(value ?? "")
            .replaceAll("&", "&amp;")
            .replaceAll("<", "&lt;")
            .replaceAll(">", "&gt;")
            .replaceAll("\"", "&quot;")
            .replaceAll("'", "&#039;");
    }

    function formatDateTime(value) {
        if (!value) {
            return "—";
        }
        const date = new Date(value);
        return Number.isNaN(date.getTime())
            ? String(value)
            : new Intl.DateTimeFormat(undefined, {
                dateStyle: "medium",
                timeStyle: "short"
            }).format(date);
    }

    function formatPercent(value) {
        return value === null || value === undefined
            ? "—"
            : `${Math.round(Number(value) * 100)}%`;
    }

    function humanize(value) {
        return String(value || "UNKNOWN")
            .toLowerCase()
            .replaceAll("_", " ")
            .replace(/\b\w/g, letter => letter.toUpperCase());
    }

    function statusClass(value) {
        return `pill-${String(value || "neutral").toLowerCase().replaceAll("_", "-")}`;
    }

    function setPill(element, value) {
        element.className = `pill ${statusClass(value)}`;
        element.textContent = humanize(value);
    }

    function setMessage(element, message, kind = "") {
        element.textContent = message || "";
        element.className = `inline-message${kind ? ` is-${kind}` : ""}`;
    }

    function showToast(message, isError = false) {
        const toast = document.createElement("div");
        toast.className = `toast${isError ? " is-error" : ""}`;
        toast.textContent = message;
        byId("toast-region").appendChild(toast);
        window.setTimeout(() => toast.remove(), 4500);
    }

    function setBusy(button, busy, busyLabel) {
        if (!button.dataset.defaultLabel) {
            button.dataset.defaultLabel = button.textContent.trim();
        }
        button.disabled = busy;
        button.textContent = busy ? busyLabel : button.dataset.defaultLabel;
    }

    async function initialize() {
        const message = byId("connection-message");
        try {
            await request("/api/permit-reviews?page=0&size=1");
            let isAdmin = false;
            try {
                await request("/api/permit-training-candidates/groups");
                isAdmin = true;
            } catch (error) {
                if (!(error instanceof ApiError) || error.status !== 403) {
                    throw error;
                }
            }

            state.isAdmin = isAdmin;
            rolePill.textContent = isAdmin ? "Administrator access" : "Operator access";
            rolePill.className = "pill pill-neutral";
            trainingTab.classList.toggle("is-locked", !isAdmin);
            trainingTab.setAttribute("aria-disabled", String(!isAdmin));
            byId("publish-review").hidden = !isAdmin;

            await loadReviews();
            if (isAdmin) {
                await loadTrainingGroups();
            }
            message.hidden = true;
        } catch (error) {
            rolePill.textContent = "Access unavailable";
            const messageText = error instanceof ApiError
                    && (error.status === 401 || error.status === 403)
                ? "Your AeroSync session is missing or expired. Sign in through the admin system, then reload this page."
                : `Could not load the review API: ${error.message}`;
            message.textContent = messageText;
            message.className = "connection-message is-error";
        } finally {
            workspace.setAttribute("aria-busy", "false");
        }
    }

    function activateTab(tabName) {
        if (tabName === "training" && !state.isAdmin) {
            showToast("Alias training requires an administrator account.", true);
            return;
        }
        document.querySelectorAll(".tab").forEach(tab => {
            tab.classList.toggle("is-active", tab.dataset.tab === tabName);
        });
        document.querySelectorAll(".tab-panel").forEach(panel => {
            panel.hidden = panel.dataset.panel !== tabName;
        });
    }

    async function loadReviews() {
        const filter = byId("review-status-filter").value;
        const query = new URLSearchParams({page: "0", size: "100"});
        if (filter) {
            query.set("status", filter);
        }
        const list = byId("review-list");
        list.setAttribute("aria-busy", "true");
        try {
            const page = await request(`/api/permit-reviews?${query}`);
            state.reviews = page.content || [];
            byId("review-total").textContent =
                `${page.totalElements || 0} record${page.totalElements === 1 ? "" : "s"}`;
            byId("review-count-badge").textContent = String(page.totalElements || 0);
            renderReviewList();
        } catch (error) {
            showToast(`Review queue failed: ${error.message}`, true);
        } finally {
            list.removeAttribute("aria-busy");
        }
    }

    function renderReviewList() {
        const list = byId("review-list");
        const selectedId = state.currentReview?.id;
        list.innerHTML = state.reviews.map(review => `
            <button class="record-card ${selectedId === review.id ? "is-selected" : ""}"
                    type="button" data-review-id="${review.id}">
                <span class="record-card-title">
                    <strong>${escapeHtml(review.normalizedPermitId || `Review #${review.id}`)}</strong>
                    <span class="pill ${statusClass(review.status)}">${escapeHtml(humanize(review.status))}</span>
                </span>
                <span class="record-card-meta">
                    <span>${escapeHtml(review.profileId || "Adaptive")}</span>
                    <span>${escapeHtml(formatPercent(review.confidence))}</span>
                </span>
                <span class="record-card-meta">
                    <span>Review #${review.id}</span>
                    <span>${escapeHtml(formatDateTime(review.updatedAt))}</span>
                </span>
            </button>
        `).join("");
        byId("review-empty").hidden = state.reviews.length > 0;
        list.querySelectorAll("[data-review-id]").forEach(button => {
            button.addEventListener("click", () => selectReview(Number(button.dataset.reviewId)));
        });
    }

    async function selectReview(id) {
        try {
            state.currentReview = await request(`/api/permit-reviews/${id}`);
            const source = state.currentReview.correctedPermit || state.currentReview.originalPermit;
            state.permitDraft = source
                ? structuredClone(source)
                : createEmptyPermit();
            renderReviewList();
            renderReviewDetail();
        } catch (error) {
            showToast(`Could not open review #${id}: ${error.message}`, true);
        }
    }

    function createEmptyPermit() {
        return {
            sourcePermitNumber: "",
            normalizedPermitId: "",
            permitNumber: "",
            authorId: "",
            permitType: "",
            version: "",
            season: "",
            permitDate: "",
            operatorId: "",
            reference: "",
            validHours: 0,
            billingAddress: "",
            flightType: "",
            iataAirportsAllowed: false,
            emptyAirwaysAllowed: false,
            rawContent: "",
            flights: []
        };
    }

    function renderReviewDetail() {
        const review = state.currentReview;
        const permit = state.permitDraft;
        byId("review-detail-empty").hidden = true;
        byId("review-detail").hidden = false;
        byId("review-kicker").textContent =
            `REVIEW #${review.id} · SYNC JOB #${review.syncJobId || "—"}`;
        byId("review-title").textContent =
            review.normalizedPermitId || permit.normalizedPermitId || "Unidentified permit";
        byId("review-reason").textContent =
            review.reviewReason || "Adaptive extraction requested operator confirmation.";
        setPill(byId("review-status-pill"), review.status);
        byId("review-profile").textContent =
            review.profileId ? `${review.profileId} · v${review.profileVersion}` : "Adaptive";
        byId("review-confidence").textContent = formatPercent(review.confidence);
        byId("review-margin").textContent = formatPercent(review.runnerUpMargin);
        byId("review-flight-count").textContent = String(permit.flights?.length || 0);

        renderPermitFields();
        renderFlights();
        renderWarnings(review.warnings || []);
        renderDiagnostics(review.fields || []);

        byId("review-comment").value =
            review.correctionComment || review.approvalComment || review.rejectionReason || "";
        updateReviewActions();
        setMessage(byId("review-action-message"), "");
    }

    function renderPermitFields() {
        const permit = state.permitDraft;
        const container = byId("permit-fields");
        container.innerHTML = permitFields.map(field => {
            const value = permit[field.key] ?? "";
            const common = `
                data-permit-field="${field.key}"
                ${field.readonly ? "readonly" : ""}
                ${field.maxlength ? `maxlength="${field.maxlength}"` : ""}
                ${field.min !== undefined ? `min="${field.min}"` : ""}`;
            const control = field.type === "textarea"
                ? `<textarea rows="5" ${common}>${escapeHtml(value)}</textarea>`
                : `<input type="${field.type || "text"}" value="${escapeHtml(value)}" ${common}>`;
            return `
                <label class="${field.className || ""}">
                    ${escapeHtml(field.label)}
                    ${control}
                </label>`;
        }).join("");

        container.querySelectorAll("[data-permit-field]").forEach(input => {
            input.addEventListener("input", event => {
                const key = event.target.dataset.permitField;
                state.permitDraft[key] = event.target.type === "number"
                    ? Number(event.target.value || 0)
                    : event.target.value;
            });
        });

        byId("iata-airports-allowed").checked = Boolean(permit.iataAirportsAllowed);
        byId("empty-airways-allowed").checked = Boolean(permit.emptyAirwaysAllowed);
        byId("iata-airports-allowed").onchange = event => {
            state.permitDraft.iataAirportsAllowed = event.target.checked;
        };
        byId("empty-airways-allowed").onchange = event => {
            state.permitDraft.emptyAirwaysAllowed = event.target.checked;
        };
    }

    function createEmptyFlight() {
        return {
            purposeId: "",
            craftId: 0,
            mtow: null,
            flightNumber: "",
            registration: "",
            serviceDays: "",
            fromAirport: "",
            toAirport: "",
            etd: "",
            eta: "",
            via: "",
            beginDate: "",
            endDate: "",
            remark: "",
            sourceAircraftType: ""
        };
    }

    function renderFlights() {
        const flights = state.permitDraft.flights || [];
        const body = byId("flight-table-body");
        body.innerHTML = flights.map((flight, index) => `
            <tr>
                ${flightFields.map(field => `
                    <td>
                        <input type="${field.type}" class="${field.className || ""}"
                               value="${escapeHtml(flight[field.key] ?? "")}"
                               data-flight-index="${index}" data-flight-field="${field.key}"
                               aria-label="${escapeHtml(field.key)} for flight row ${index + 1}">
                    </td>`).join("")}
                <td>
                    <button class="remove-row" type="button" data-remove-flight="${index}"
                            aria-label="Remove flight row ${index + 1}">×</button>
                </td>
            </tr>
        `).join("");

        body.querySelectorAll("[data-flight-field]").forEach(input => {
            input.addEventListener("input", event => {
                const index = Number(event.target.dataset.flightIndex);
                const key = event.target.dataset.flightField;
                state.permitDraft.flights[index][key] = event.target.type === "number"
                    ? Number(event.target.value || 0)
                    : event.target.value;
            });
        });
        body.querySelectorAll("[data-remove-flight]").forEach(button => {
            button.addEventListener("click", () => {
                state.permitDraft.flights.splice(Number(button.dataset.removeFlight), 1);
                renderFlights();
                byId("review-flight-count").textContent =
                    String(state.permitDraft.flights.length);
            });
        });
    }

    function renderWarnings(warnings) {
        const list = byId("warning-list");
        list.innerHTML = warnings.length
            ? warnings.map(warning => `
                <div class="compact-item ${warning.reviewRequired ? "is-warning" : ""}">
                    <strong>${escapeHtml(warning.code || "Parser warning")}</strong>
                    ${escapeHtml(warning.message || "No message")}
                </div>`).join("")
            : `<div class="compact-item"><strong>No warnings</strong>The parser reported no warnings.</div>`;
    }

    function renderDiagnostics(fields) {
        const list = byId("diagnostic-list");
        list.innerHTML = fields.length
            ? fields.map(field => `
                <div class="compact-item">
                    <strong>${escapeHtml(field.field)} · ${escapeHtml(formatPercent(field.confidence))}</strong>
                    ${escapeHtml(field.observedValue || "No value")} ·
                    ${escapeHtml(field.method || field.source || "unknown method")}
                </div>`).join("")
            : `<div class="compact-item"><strong>No diagnostics</strong>No field evidence was retained.</div>`;
    }

    function updateReviewActions() {
        const status = state.currentReview.status;
        const reviewable = status === "PENDING" || status === "CORRECTED";
        byId("save-correction").disabled = !reviewable;
        byId("approve-review").disabled = !reviewable;
        byId("reject-review").disabled =
            !["PENDING", "CORRECTED", "APPROVED", "PUBLISH_FAILED"].includes(status);
        byId("publish-review").hidden = !state.isAdmin;
        byId("publish-review").disabled =
            !state.isAdmin || !["APPROVED", "PUBLISH_FAILED"].includes(status);
        byId("add-flight").disabled = !reviewable;
        document.querySelectorAll("[data-permit-field]:not([readonly]), [data-flight-field], [data-remove-flight]")
            .forEach(element => {
                element.disabled = !reviewable;
            });
        byId("iata-airports-allowed").disabled = !reviewable;
        byId("empty-airways-allowed").disabled = !reviewable;
    }

    async function runReviewAction(action) {
        if (!state.currentReview) {
            return;
        }
        const message = byId("review-action-message");
        const comment = byId("review-comment").value.trim();
        const buttons = {
            correction: byId("save-correction"),
            approve: byId("approve-review"),
            reject: byId("reject-review"),
            publish: byId("publish-review")
        };
        const button = buttons[action];
        if (action === "reject" && !comment) {
            setMessage(message, "A rejection reason is required.", "error");
            return;
        }
        if (action === "correction" && (!state.permitDraft.flights?.length)) {
            setMessage(message, "At least one schedule flight is required.", "error");
            return;
        }

        setBusy(button, true, "Working…");
        setMessage(message, "");
        const id = state.currentReview.id;
        try {
            if (action === "correction") {
                await request(`/api/permit-reviews/${id}/correction`, {
                    method: "PUT",
                    body: {permit: state.permitDraft, comment: comment || null}
                });
            } else if (action === "approve") {
                await request(`/api/permit-reviews/${id}/approve`, {
                    method: "POST",
                    body: {comment: comment || null}
                });
            } else if (action === "reject") {
                await request(`/api/permit-reviews/${id}/reject`, {
                    method: "POST",
                    body: {reason: comment}
                });
            } else if (action === "publish") {
                await request(`/api/permit-reviews/${id}/publish`, {method: "POST"});
            }

            await Promise.all([loadReviews(), selectReview(id)]);
            if (state.isAdmin) {
                await loadTrainingGroups();
            }
            const completed = {
                correction: "Correction saved.",
                approve: "Permit approved. Training evidence can now be generated.",
                reject: "Permit rejected.",
                publish: "Publication queued for the worker."
            };
            setMessage(message, completed[action], "success");
            showToast(completed[action]);
        } catch (error) {
            setMessage(message, error.message, "error");
        } finally {
            setBusy(button, false, "");
            updateReviewActions();
        }
    }

    async function loadTrainingGroups() {
        if (!state.isAdmin) {
            return;
        }
        const profile = byId("training-profile-filter").value.trim();
        const query = new URLSearchParams();
        if (profile) {
            query.set("profileId", profile);
        }
        const list = byId("training-list");
        list.setAttribute("aria-busy", "true");
        try {
            const groups = await request(
                `/api/permit-training-candidates/groups${query.size ? `?${query}` : ""}`);
            state.trainingGroups = groups || [];
            renderTrainingMetrics();
            renderTrainingList();
        } catch (error) {
            showToast(`Training evidence failed: ${error.message}`, true);
        } finally {
            list.removeAttribute("aria-busy");
        }
    }

    function filteredTrainingGroups() {
        const status = byId("training-status-filter").value;
        return status
            ? state.trainingGroups.filter(group => group.status === status)
            : state.trainingGroups;
    }

    function renderTrainingMetrics() {
        const groups = state.trainingGroups;
        byId("pending-group-count").textContent =
            String(groups.filter(group => group.status === "PENDING").length);
        byId("active-group-count").textContent =
            String(groups.filter(group => group.status === "APPROVED").length);
        byId("validation-group-count").textContent =
            String(groups.filter(group =>
                group.validationStatus === "NOT_RUN" || group.validationStatus === "FAILED").length);
        byId("alias-usage-count").textContent =
            String(groups.reduce((total, group) => total + Number(group.usageCount || 0), 0));
        byId("training-count-badge").textContent = String(groups.length);
    }

    function renderTrainingList() {
        const groups = filteredTrainingGroups();
        const list = byId("training-list");
        const selectedKey = state.currentGroup
            ? `${state.currentGroup.profileId}|${state.currentGroup.canonicalAlias}|${state.currentGroup.semanticField}`
            : "";
        list.innerHTML = groups.map((group, index) => {
            const key = `${group.profileId}|${group.canonicalAlias}|${group.semanticField}`;
            return `
                <button class="record-card ${selectedKey === key ? "is-selected" : ""}"
                        type="button" data-training-index="${index}">
                    <span class="record-card-title">
                        <strong>${escapeHtml(group.aliasValue)}</strong>
                        <span class="pill ${statusClass(group.status)}">${escapeHtml(humanize(group.status))}</span>
                    </span>
                    <span class="record-card-meta">
                        <span>${escapeHtml(group.semanticField)}</span>
                        <span>${group.evidenceCount}/${group.minimumEvidence} evidence</span>
                    </span>
                    <span class="record-card-meta">
                        <span>${escapeHtml(group.profileId)} · v${group.profileVersion}</span>
                        <span>${escapeHtml(humanize(group.validationStatus))}</span>
                    </span>
                </button>`;
        }).join("");
        byId("training-total").textContent =
            `${groups.length} group${groups.length === 1 ? "" : "s"}`;
        byId("training-empty").hidden = groups.length > 0;
        list.querySelectorAll("[data-training-index]").forEach(button => {
            button.addEventListener("click", () => {
                const group = groups[Number(button.dataset.trainingIndex)];
                selectTrainingGroup(group);
            });
        });
    }

    async function selectTrainingGroup(group) {
        const candidateId = group.activeCandidateId || group.candidateIds?.[0];
        if (!candidateId) {
            showToast("This evidence group has no candidate record.", true);
            return;
        }
        state.currentGroup = group;
        renderTrainingList();
        try {
            const [candidate, preflight, history] = await Promise.all([
                request(`/api/permit-training-candidates/${candidateId}`),
                request(`/api/permit-training-candidates/${candidateId}/preflight`)
                    .catch(error => ({ready: false, blockers: [error.message]})),
                request(`/api/permit-training-candidates/${candidateId}/history`)
                    .catch(() => [])
            ]);
            state.currentCandidate = candidate;
            state.currentPreflight = preflight;
            renderTrainingDetail(history || []);
        } catch (error) {
            showToast(`Could not open candidate #${candidateId}: ${error.message}`, true);
        }
    }

    function renderTrainingDetail(history) {
        const candidate = state.currentCandidate;
        const preflight = state.currentPreflight;
        byId("training-detail-empty").hidden = true;
        byId("training-detail").hidden = false;
        byId("training-kicker").textContent =
            `CANDIDATE #${candidate.id} · SOURCE REVIEW #${candidate.sourceReviewId}`;
        byId("training-title").textContent =
            `${candidate.aliasValue} → ${humanize(candidate.semanticField)}`;
        byId("training-subtitle").textContent =
            `${candidate.profileId} · profile version ${candidate.profileVersion} · ${candidate.matchMethod}`;
        setPill(byId("training-status-pill"), candidate.status);
        byId("training-evidence").textContent =
            `${candidate.evidenceCount} / ${candidate.minimumEvidence}`;
        byId("training-confidence").textContent = formatPercent(candidate.confidence);
        byId("training-validation").textContent = humanize(candidate.validationStatus);
        byId("training-usage").textContent =
            `${candidate.usageCount || 0}${candidate.lastUsedAt ? ` · ${formatDateTime(candidate.lastUsedAt)}` : ""}`;

        setPill(byId("preflight-pill"), preflight.ready ? "PASSED" : "FAILED");
        byId("preflight-title").textContent =
            preflight.ready ? "Ready for activation" : "Activation is blocked";
        const blockers = preflight.blockers || [];
        byId("preflight-list").innerHTML = blockers.length
            ? blockers.map(blocker => `
                <div class="compact-item is-blocker">
                    <strong>Blocker</strong>${escapeHtml(blocker)}
                </div>`).join("")
            : `
                <div class="compact-item">
                    <strong>Safety gates passed</strong>
                    Independent evidence and corpus validation are ready.
                </div>`;

        const passed = candidate.validationPassedCount ?? 0;
        const failed = candidate.validationFailedCount ?? 0;
        const corpus = candidate.validationCorpusSize ?? 0;
        byId("validation-summary").innerHTML = `
            <span class="validation-chip">Corpus ${corpus}</span>
            <span class="validation-chip">Passed ${passed}</span>
            <span class="validation-chip">Failed ${failed}</span>
            <span class="validation-chip">${escapeHtml(humanize(candidate.validationStatus))}</span>`;
        byId("validation-report").textContent =
            candidate.validationReport || "No corpus replay report is available yet.";

        byId("history-table-body").innerHTML = history.length
            ? history.map(item => `
                <tr>
                    <td>${escapeHtml(formatDateTime(item.createdAt))}</td>
                    <td>${escapeHtml(humanize(item.action))}</td>
                    <td>${escapeHtml(item.actor || "system")}</td>
                    <td>${escapeHtml(item.comment || "—")}</td>
                </tr>`).join("")
            : `<tr><td colspan="4" class="muted">No decisions have been recorded.</td></tr>`;
        byId("training-comment").value = "";
        setMessage(byId("training-action-message"), "");
        updateTrainingActions();
    }

    function updateTrainingActions() {
        const candidate = state.currentCandidate;
        const preflight = state.currentPreflight;
        if (!candidate) {
            return;
        }
        byId("validate-training").disabled = candidate.validationStatus === "RUNNING";
        byId("approve-training").disabled =
            candidate.status !== "PENDING" || !preflight?.ready;
        byId("reject-training").disabled = candidate.status !== "PENDING";
        byId("disable-training").disabled = candidate.status !== "APPROVED";
        byId("reactivate-training").disabled =
            candidate.status !== "DISABLED" || candidate.validationStatus !== "PASSED";
    }

    async function runTrainingAction(action) {
        const candidate = state.currentCandidate;
        if (!candidate) {
            return;
        }
        const comment = byId("training-comment").value.trim();
        const message = byId("training-action-message");
        if ((action === "reject" || action === "disable") && !comment) {
            setMessage(
                message,
                `${action === "reject" ? "A rejection" : "A disabling"} reason is required.`,
                "error");
            return;
        }

        const buttons = {
            validate: byId("validate-training"),
            approve: byId("approve-training"),
            reject: byId("reject-training"),
            disable: byId("disable-training"),
            reactivate: byId("reactivate-training")
        };
        const button = buttons[action];
        setBusy(button, true, "Working…");
        setMessage(message, "");
        try {
            const options = {
                method: "POST",
                body: action === "validate" ? null : {comment: comment || null}
            };
            await request(
                `/api/permit-training-candidates/${candidate.id}/${action}`,
                options);
            await loadTrainingGroups();
            const refreshedGroup = state.trainingGroups.find(group =>
                group.candidateIds?.includes(candidate.id))
                || state.currentGroup;
            await selectTrainingGroup(refreshedGroup);
            const completed = {
                validate: "Corpus replay was queued. Refresh to see the worker result.",
                approve: "The profile-scoped alias is now active.",
                reject: "The training candidate was rejected.",
                disable: "The active alias was disabled immediately.",
                reactivate: "The validated alias was reactivated."
            };
            setMessage(message, completed[action], "success");
            showToast(completed[action]);
        } catch (error) {
            setMessage(message, error.message, "error");
        } finally {
            setBusy(button, false, "");
            updateTrainingActions();
        }
    }

    document.querySelectorAll("[data-tab]").forEach(tab => {
        tab.addEventListener("click", () => activateTab(tab.dataset.tab));
    });
    byId("refresh-reviews").addEventListener("click", loadReviews);
    byId("review-status-filter").addEventListener("change", loadReviews);
    byId("add-flight").addEventListener("click", () => {
        state.permitDraft.flights.push(createEmptyFlight());
        renderFlights();
        byId("review-flight-count").textContent = String(state.permitDraft.flights.length);
    });
    byId("save-correction").addEventListener("click", () => runReviewAction("correction"));
    byId("approve-review").addEventListener("click", () => runReviewAction("approve"));
    byId("reject-review").addEventListener("click", () => runReviewAction("reject"));
    byId("publish-review").addEventListener("click", () => runReviewAction("publish"));
    byId("refresh-training").addEventListener("click", loadTrainingGroups);
    byId("training-status-filter").addEventListener("change", renderTrainingList);
    byId("training-profile-filter").addEventListener("keydown", event => {
        if (event.key === "Enter") {
            loadTrainingGroups();
        }
    });
    byId("validate-training").addEventListener("click", () => runTrainingAction("validate"));
    byId("approve-training").addEventListener("click", () => runTrainingAction("approve"));
    byId("reject-training").addEventListener("click", () => runTrainingAction("reject"));
    byId("disable-training").addEventListener("click", () => runTrainingAction("disable"));
    byId("reactivate-training").addEventListener("click", () => runTrainingAction("reactivate"));
    initialize();
})();
