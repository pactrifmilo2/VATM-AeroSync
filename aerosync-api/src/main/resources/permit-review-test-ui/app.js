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
        currentPreflight: null,
        profileTrainingSources: [],
        guidedProfiles: [],
        currentGuidedProfile: null,
        currentGuidedSource: null,
        currentProfileReadiness: null,
        scalarMappings: [],
        scheduleMapping: {tableIndex: 0, dataStartRowIndex: 1, columns: {}},
        trainingWorkflow: null,
        wizardPermit: null,
        wizardSelectedCell: null,
        wizardActiveSemantic: null,
        wizardResolutionSelections: {}
    };

    let wizardAutosaveTimer = null;
    let wizardSaveChain = Promise.resolve();

    const scalarSemanticFields = [
        {value: "permit.sourceNumber", label: "Source permit number *"},
        {value: "permit.date", label: "Permit date *"},
        {value: "operator.icao", label: "Operator ICAO *"},
        {value: "operator.iata", label: "Operator IATA"},
        {value: "billing.address", label: "Billing address"},
        {value: "reference", label: "Reference"},
        {value: "purpose", label: "Purpose"}
    ];

    const scheduleSemanticColumns = [
        {value: "flightNumber", label: "Flight number *", required: true},
        {value: "effectiveFrom", label: "Effective from *", required: true},
        {value: "effectiveTo", label: "Effective to *", required: true},
        {value: "serviceDays", label: "Days of service *", required: true},
        {value: "fromAirport", label: "Departure airport *", required: true},
        {value: "etd", label: "ETD *", required: true},
        {value: "toAirport", label: "Arrival airport *", required: true},
        {value: "eta", label: "ETA", required: false},
        {value: "aircraftType", label: "Aircraft type", required: false},
        {value: "originalPermit", label: "Original permit", required: false},
        {value: "remark", label: "Remark", required: false}
    ];

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

            await Promise.all([
                loadReviews(),
                loadProfileTrainingSources(),
                loadGuidedProfiles()
            ]);
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

    function sourceLabel(source) {
        const retained = source.retained ? "retained" : "not retained";
        return `#${source.id} · ${source.originalFileName} · ${retained}`;
    }

    function sourceOptions(selectedId, retainedOnly = false) {
        const sources = retainedOnly
            ? state.profileTrainingSources.filter(source => source.retained)
            : state.profileTrainingSources;
        if (!sources.length) {
            return `<option value="">No captured documents available</option>`;
        }
        return `<option value="">Select a document</option>${sources.map(source => `
            <option value="${source.id}" ${Number(selectedId) === source.id ? "selected" : ""}>
                ${escapeHtml(sourceLabel(source))}
            </option>`).join("")}`;
    }

    function populateProfileSourceSelects() {
        const currentEvidence = byId("profile-evidence-source").value;
        const currentCanary = byId("profile-canary-source").value;
        const currentNew = byId("new-profile-source").value;
        const currentWizard = byId("wizard-source").value;
        const currentWizardExample = byId("wizard-example-source").value;
        byId("new-profile-source").innerHTML = sourceOptions(currentNew);
        byId("profile-evidence-source").innerHTML = sourceOptions(
            currentEvidence || state.currentGuidedSource?.id,
            true);
        byId("profile-canary-source").innerHTML = sourceOptions(currentCanary, true);
        byId("wizard-source").innerHTML = sourceOptions(currentWizard);
        byId("wizard-example-source").innerHTML = sourceOptions(
            currentWizardExample,
            false);
    }

    async function loadProfileTrainingSources() {
        try {
            const page = await request("/api/permit-training-sources?page=0&size=100");
            state.profileTrainingSources = page.content || [];
            populateProfileSourceSelects();
        } catch (error) {
            showToast(`Training documents failed: ${error.message}`, true);
        }
    }

    async function loadGuidedProfiles() {
        const status = byId("profile-training-status-filter").value;
        const query = new URLSearchParams({page: "0", size: "100"});
        if (status) {
            query.set("status", status);
        }
        const list = byId("profile-training-list");
        list.setAttribute("aria-busy", "true");
        try {
            const page = await request(`/api/permit-training-profiles?${query}`);
            state.guidedProfiles = page.content || [];
            byId("profile-training-count-badge").textContent =
                String(page.totalElements || 0);
            renderGuidedProfileList();
        } catch (error) {
            showToast(`Profile training failed: ${error.message}`, true);
        } finally {
            list.removeAttribute("aria-busy");
        }
    }

    function renderGuidedProfileList() {
        const profiles = state.guidedProfiles;
        const selectedId = state.currentGuidedProfile?.id;
        const list = byId("profile-training-list");
        list.innerHTML = profiles.map(profile => `
            <button class="record-card ${selectedId === profile.id ? "is-selected" : ""}"
                    type="button" data-guided-profile-id="${profile.id}">
                <span class="record-card-title">
                    <strong>${escapeHtml(profile.displayName || profile.profileKey)}</strong>
                    <span class="pill ${statusClass(profile.status)}">${escapeHtml(humanize(profile.status))}</span>
                </span>
                <span class="record-card-meta">
                    <span>${escapeHtml(profile.profileKey)} · v${profile.profileVersion}</span>
                    <span>${profile.evidenceCount} evidence</span>
                </span>
                <span class="record-card-meta">
                    <span>${escapeHtml(profile.family)}</span>
                    <span>${profile.canarySuccessCount} canaries</span>
                </span>
            </button>`).join("");
        byId("profile-training-total").textContent =
            `${profiles.length} profile${profiles.length === 1 ? "" : "s"}`;
        byId("profile-training-empty").hidden = profiles.length > 0;
        list.querySelectorAll("[data-guided-profile-id]").forEach(button => {
            button.addEventListener("click", () =>
                selectGuidedProfile(Number(button.dataset.guidedProfileId)));
        });
    }

    async function selectGuidedProfile(id) {
        try {
            const profile = await request(`/api/permit-training-profiles/${id}`);
            const sourceId = profile.evidence?.[0]?.sourceId;
            const [source, readiness] = await Promise.all([
                sourceId
                    ? request(`/api/permit-training-sources/${sourceId}`)
                    : Promise.resolve(null),
                request(`/api/permit-training-profiles/${id}/canary-readiness`)
                    .catch(error => ({
                        readyForActivationReview: false,
                        minimumSuccesses: 0,
                        passedCount: 0,
                        failedCount: 0,
                        pendingCount: 0,
                        blockers: [error.message]
                    }))
            ]);
            state.currentGuidedProfile = profile;
            state.currentGuidedSource = source;
            state.currentProfileReadiness = readiness;
            state.scalarMappings = profile.definition?.fields?.length
                ? structuredClone(profile.definition.fields)
                : defaultScalarMappings();
            const schedule = profile.definition?.tables?.find(table => table.role === "SCHEDULE");
            state.scheduleMapping = schedule
                ? structuredClone(schedule)
                : {tableIndex: source?.document?.tables?.[0]?.index || 0,
                    dataStartRowIndex: 1, columns: {}};
            renderGuidedProfileList();
            renderGuidedProfileDetail();
        } catch (error) {
            showToast(`Could not open profile #${id}: ${error.message}`, true);
        }
    }

    function defaultScalarMappings() {
        return [
            {semanticField: "permit.sourceNumber", source: "TEXT", cellId: null,
                selectedText: "", confirmedValue: "", required: true},
            {semanticField: "permit.date", source: "TEXT", cellId: null,
                selectedText: "", confirmedValue: "", required: true},
            {semanticField: "operator.icao", source: "CONSTANT", cellId: null,
                selectedText: null, confirmedValue: "", required: true}
        ];
    }

    function expectedPermitTemplate() {
        const options = state.currentGuidedProfile?.definition?.options || {};
        return {
            sourcePermitNumber: "",
            normalizedPermitId: "",
            permitNumber: "",
            authorId: options.authorId || "",
            permitType: options.permitType || "",
            version: options.version || "",
            season: options.season || "",
            permitDate: "",
            operatorId: "",
            reference: "",
            validHours: options.validHours || 24,
            billingAddress: "",
            flightType: options.flightType || "",
            iataAirportsAllowed: Boolean(options.allowIataAirports),
            emptyAirwaysAllowed: Boolean(options.emptyAirwaysAllowed),
            rawContent: "",
            flights: [{
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
            }]
        };
    }

    function renderGuidedProfileDetail() {
        const profile = state.currentGuidedProfile;
        const definition = profile.definition || {};
        const options = definition.options || {};
        const readiness = state.currentProfileReadiness || {};
        byId("profile-training-detail-empty").hidden = true;
        byId("profile-training-detail").hidden = false;
        byId("profile-training-kicker").textContent =
            `PROFILE #${profile.id} · ${profile.profileKey} · VERSION ${profile.profileVersion}`;
        byId("profile-training-title").textContent =
            definition.displayName || profile.profileKey;
        byId("profile-training-subtitle").textContent =
            `${definition.family || "Unknown family"}${profile.baseProfileId ? ` · baseline ${profile.baseProfileId}` : ""}`;
        setPill(byId("profile-training-status-pill"), profile.status);
        byId("profile-training-version").textContent = String(profile.version);
        byId("profile-training-evidence-count").textContent = String(profile.evidenceCount || 0);
        byId("profile-training-canary-count").textContent =
            `${readiness.passedCount || 0} / ${readiness.minimumSuccesses || 0}`;
        byId("profile-training-ready").textContent =
            readiness.readyForActivationReview ? "Ready" : "Blocked";

        renderGuidedSource();
        renderScalarMappings();
        renderScheduleMapping();
        byId("profile-option-author").value = options.authorId || "";
        byId("profile-option-type").value = options.permitType || "";
        byId("profile-option-version").value = options.version || "";
        byId("profile-option-season").value = options.season || "";
        byId("profile-option-valid-hours").value = options.validHours || "";
        byId("profile-option-flight-type").value = options.flightType || "";
        byId("profile-option-iata").checked = Boolean(options.allowIataAirports);
        byId("profile-option-empty-airways").checked = Boolean(options.emptyAirwaysAllowed);

        const firstExpected = profile.evidence?.find(item => item.expectedPermit)?.expectedPermit;
        const expected = firstExpected || expectedPermitTemplate();
        byId("profile-expected-json").value = JSON.stringify(expected, null, 2);
        byId("profile-canary-json").value = JSON.stringify(expected, null, 2);
        populateProfileSourceSelects();
        if (state.currentGuidedSource?.id) {
            byId("profile-evidence-source").value = String(state.currentGuidedSource.id);
        }
        renderProfileEvidenceAndHistory();
        renderProfileReadiness();
        updateGuidedProfileActions();
        ["profile-definition-message", "profile-evidence-message", "profile-canary-message"]
            .forEach(id => setMessage(byId(id), ""));
        byId("compiled-profile-report").hidden = true;
    }

    function allSourceCells() {
        return (state.currentGuidedSource?.document?.tables || [])
            .flatMap(table => (table.rows || [])
                .flatMap(row => (row.cells || []).map(cell => ({...cell, tableIndex: table.index}))));
    }

    function cellOptions(selectedId, tableIndex = null) {
        const cells = allSourceCells().filter(cell =>
            tableIndex === null || Number(cell.tableIndex) === Number(tableIndex));
        return `<option value="">Choose a cell</option>${cells.map(cell => `
            <option value="${escapeHtml(cell.id)}" ${selectedId === cell.id ? "selected" : ""}>
                ${escapeHtml(cell.id)} — ${escapeHtml(cell.value || "(empty)")}
            </option>`).join("")}`;
    }

    function renderGuidedSource() {
        const source = state.currentGuidedSource;
        const tables = source?.document?.tables || [];
        const paragraphText = source?.document?.paragraphText || "";
        byId("profile-source-title").textContent = source
            ? source.originalFileName
            : "No source document attached";
        setPill(byId("profile-source-retained"), source?.retained ? "PASSED" : "PENDING");
        byId("profile-source-copy").textContent = source
            ? `Source #${source.id} · ${tables.length} table${tables.length === 1 ? "" : "s"}. Cell IDs stay stable for this retained document.`
            : "Select a profile with attached evidence to inspect its cells.";
        const paragraphBlock = paragraphText
            ? `<details class="source-table" open>
                    <summary>Document paragraphs</summary>
                    <pre class="source-text">${escapeHtml(paragraphText)}</pre>
               </details>`
            : "";
        const tableBlocks = tables.length
            ? tables.map(table => `
                <details class="source-table" ${table.index === tables[0].index ? "open" : ""}>
                    <summary>Table ${table.index} · ${escapeHtml(table.context || "No context")}</summary>
                    <div class="table-scroll">
                        <table><tbody>${(table.rows || []).map(row => `
                            <tr>${(row.cells || []).map(cell => `
                                <td><strong>${escapeHtml(cell.value || "(empty)")}</strong>
                                    <code>${escapeHtml(cell.id)}</code></td>`).join("")}</tr>`).join("")}</tbody></table>
                    </div>
                </details>`).join("")
            : `<div class="compact-item is-blocker"><strong>No structured tables</strong>This source cannot provide schedule cell mappings.</div>`;
        byId("profile-source-tables").innerHTML = paragraphBlock + tableBlocks;
    }

    function renderScalarMappings() {
        const body = byId("scalar-mapping-body");
        body.innerHTML = state.scalarMappings.map((mapping, index) => `
            <tr>
                <td><select data-scalar-index="${index}" data-scalar-field="semanticField">
                    ${scalarSemanticFields.map(item => `<option value="${item.value}" ${mapping.semanticField === item.value ? "selected" : ""}>${escapeHtml(item.label)}</option>`).join("")}
                </select></td>
                <td><select data-scalar-index="${index}" data-scalar-field="source">
                    ${["CELL", "TEXT", "CONSTANT"].map(value => `<option value="${value}" ${mapping.source === value ? "selected" : ""}>${value}</option>`).join("")}
                </select></td>
                <td><select class="mapping-cell-select" data-scalar-index="${index}" data-scalar-field="cellId">${cellOptions(mapping.cellId)}</select></td>
                <td><input value="${escapeHtml(mapping.selectedText || "")}" data-scalar-index="${index}" data-scalar-field="selectedText"></td>
                <td><input value="${escapeHtml(mapping.confirmedValue || "")}" data-scalar-index="${index}" data-scalar-field="confirmedValue"></td>
                <td><input class="mapping-required" type="checkbox" ${mapping.required ? "checked" : ""} data-scalar-index="${index}" data-scalar-field="required"></td>
                <td><button class="remove-row" type="button" data-remove-scalar="${index}" aria-label="Remove mapping">×</button></td>
            </tr>`).join("");
        body.querySelectorAll("[data-scalar-field]").forEach(control => {
            control.addEventListener("change", event => {
                const index = Number(event.target.dataset.scalarIndex);
                const field = event.target.dataset.scalarField;
                state.scalarMappings[index][field] = event.target.type === "checkbox"
                    ? event.target.checked
                    : (event.target.value || null);
            });
            control.addEventListener("input", event => {
                const index = Number(event.target.dataset.scalarIndex);
                const field = event.target.dataset.scalarField;
                if (event.target.type !== "checkbox") {
                    state.scalarMappings[index][field] = event.target.value || null;
                }
            });
        });
        body.querySelectorAll("[data-remove-scalar]").forEach(button => {
            button.addEventListener("click", () => {
                state.scalarMappings.splice(Number(button.dataset.removeScalar), 1);
                renderScalarMappings();
            });
        });
    }

    function renderScheduleMapping() {
        const tables = state.currentGuidedSource?.document?.tables || [];
        byId("schedule-table-index").innerHTML = tables.length
            ? tables.map(table => `<option value="${table.index}" ${Number(state.scheduleMapping.tableIndex) === table.index ? "selected" : ""}>Table ${table.index} · ${escapeHtml(table.context || "No context")}</option>`).join("")
            : `<option value="0">No tables</option>`;
        byId("schedule-data-row").value = state.scheduleMapping.dataStartRowIndex ?? 1;
        renderScheduleColumns();
    }

    function renderScheduleColumns() {
        const tableIndex = Number(state.scheduleMapping.tableIndex || 0);
        byId("schedule-column-grid").innerHTML = scheduleSemanticColumns.map(column => `
            <label>
                ${escapeHtml(column.label)}
                <select data-schedule-column="${column.value}">
                    ${cellOptions(state.scheduleMapping.columns?.[column.value], tableIndex)}
                </select>
            </label>`).join("");
        byId("schedule-column-grid").querySelectorAll("[data-schedule-column]")
            .forEach(select => select.addEventListener("change", event => {
                const column = event.target.dataset.scheduleColumn;
                if (event.target.value) {
                    state.scheduleMapping.columns[column] = event.target.value;
                } else {
                    delete state.scheduleMapping.columns[column];
                }
            }));
    }

    function renderProfileEvidenceAndHistory() {
        const profile = state.currentGuidedProfile;
        const evidence = profile.evidence || [];
        const history = profile.history || [];
        byId("profile-evidence-list").innerHTML = evidence.length
            ? evidence.map(item => `
                <div class="compact-item ${item.result === "FAILED" ? "is-blocker" : ""}">
                    <strong>${escapeHtml(humanize(item.kind))} · ${escapeHtml(humanize(item.result))}</strong>
                    Source #${item.sourceId} · ${escapeHtml(item.originalFileName)}<br>
                    ${escapeHtml(item.detail || "No evaluation detail")}
                </div>`).join("")
            : `<div class="compact-item"><strong>No evidence</strong>Attach a corrected expected result.</div>`;
        byId("profile-history-list").innerHTML = history.length
            ? history.slice().reverse().map(item => `
                <div class="compact-item">
                    <strong>${escapeHtml(humanize(item.action))} · ${escapeHtml(item.actor)}</strong>
                    ${escapeHtml(formatDateTime(item.createdAt))}
                </div>`).join("")
            : `<div class="compact-item"><strong>No events</strong>No profile history is available.</div>`;
    }

    function renderProfileReadiness() {
        const readiness = state.currentProfileReadiness || {};
        setPill(
            byId("profile-canary-ready-pill"),
            readiness.readyForActivationReview ? "PASSED" : "PENDING");
        const blockers = readiness.blockers || [];
        byId("profile-readiness-list").innerHTML = `
            <div class="compact-item">
                <strong>Canary results</strong>
                Passed ${readiness.passedCount || 0} of ${readiness.minimumSuccesses || 0} required ·
                Pending ${readiness.pendingCount || 0} · Failed ${readiness.failedCount || 0}
            </div>${blockers.map(blocker => `
                <div class="compact-item is-blocker"><strong>Blocker</strong>${escapeHtml(humanize(blocker))}</div>`).join("")}`;
    }

    function updateGuidedProfileActions() {
        const status = state.currentGuidedProfile?.status;
        const draft = status === "DRAFT";
        const collecting = status === "COLLECTING_EVIDENCE";
        byId("add-scalar-mapping").disabled = !draft;
        byId("save-profile-definition").disabled = !draft;
        byId("attach-profile-evidence").disabled = !(draft || collecting);
        byId("confirm-profile-mapping").disabled = !draft;
        byId("validate-profile").disabled = !collecting;
        byId("view-compiled-profile").disabled = !["CANARY", "NEEDS_REVISION"].includes(status);
        byId("run-profile-canary").disabled = status !== "CANARY";
        document.querySelectorAll(
            "#scalar-mapping-body input, #scalar-mapping-body select, "
            + "#schedule-table-index, #schedule-data-row, #schedule-column-grid select, "
            + "#profile-option-author, #profile-option-type, #profile-option-version, "
            + "#profile-option-season, #profile-option-valid-hours, #profile-option-flight-type, "
            + "#profile-option-iata, #profile-option-empty-airways")
            .forEach(control => control.disabled = !draft);
    }

    function parseExpectedPermit(elementId) {
        try {
            return JSON.parse(byId(elementId).value);
        } catch (error) {
            throw new Error(`Correct permit JSON is invalid: ${error.message}`);
        }
    }

    async function retainSelectedProfileSource() {
        const sourceId = Number(byId("new-profile-source").value);
        const message = byId("profile-create-message");
        if (!sourceId) {
            setMessage(message, "Select a captured document first.", "error");
            return;
        }
        const button = byId("retain-profile-source");
        setBusy(button, true, "Retaining…");
        try {
            await request(`/api/permit-training-sources/${sourceId}/retain`, {method: "POST"});
            await loadProfileTrainingSources();
            byId("new-profile-source").value = String(sourceId);
            setMessage(message, "The document is retained and ready for training.", "success");
        } catch (error) {
            setMessage(message, error.message, "error");
        } finally {
            setBusy(button, false, "");
        }
    }

    async function createGuidedProfile() {
        const message = byId("profile-create-message");
        const body = {
            profileKey: byId("new-profile-key").value.trim(),
            displayName: byId("new-profile-name").value.trim(),
            family: byId("new-profile-family").value.trim(),
            baseProfileId: byId("new-profile-base").value.trim() || null,
            sourceId: Number(byId("new-profile-source").value)
        };
        if (!body.sourceId || !body.profileKey || !body.displayName || !body.family) {
            setMessage(message, "Document, profile key, display name, and family are required.", "error");
            return;
        }
        const button = byId("create-profile-draft");
        setBusy(button, true, "Creating…");
        try {
            const created = await request("/api/permit-training-profiles", {method: "POST", body});
            await loadGuidedProfiles();
            await selectGuidedProfile(created.id);
            setMessage(message, "Draft profile created. Continue with the field mapping below.", "success");
            showToast("Draft profile created.");
        } catch (error) {
            setMessage(message, error.message, "error");
        } finally {
            setBusy(button, false, "");
        }
    }

    function buildProfileDefinition() {
        const profile = state.currentGuidedProfile;
        const existingTables = (profile.definition?.tables || [])
            .filter(table => table.role !== "SCHEDULE");
        const fields = state.scalarMappings.map(mapping => ({
            semanticField: mapping.semanticField,
            source: mapping.source,
            cellId: mapping.source === "CELL" ? mapping.cellId || null : null,
            selectedText: mapping.source === "TEXT" ? mapping.selectedText || null : null,
            confirmedValue: mapping.confirmedValue || null,
            required: Boolean(mapping.required)
        }));
        return {
            schemaVersion: 1,
            displayName: profile.definition?.displayName || profile.profileKey,
            family: profile.definition?.family || "caav-english",
            fields,
            tables: [...existingTables, {
                role: "SCHEDULE",
                tableIndex: Number(state.scheduleMapping.tableIndex),
                dataStartRowIndex: Number(state.scheduleMapping.dataStartRowIndex),
                columns: {...state.scheduleMapping.columns}
            }],
            options: {
                authorId: byId("profile-option-author").value.trim(),
                permitType: byId("profile-option-type").value.trim(),
                version: byId("profile-option-version").value.trim(),
                season: byId("profile-option-season").value.trim(),
                validHours: Number(byId("profile-option-valid-hours").value),
                flightType: byId("profile-option-flight-type").value.trim(),
                allowIataAirports: byId("profile-option-iata").checked,
                emptyAirwaysAllowed: byId("profile-option-empty-airways").checked,
                reviewOnly: true
            }
        };
    }

    async function saveGuidedDefinition() {
        const profile = state.currentGuidedProfile;
        const message = byId("profile-definition-message");
        const button = byId("save-profile-definition");
        setBusy(button, true, "Saving…");
        try {
            const updated = await request(`/api/permit-training-profiles/${profile.id}/definition`, {
                method: "PUT",
                body: {expectedVersion: profile.version, definition: buildProfileDefinition()}
            });
            await loadGuidedProfiles();
            await selectGuidedProfile(updated.id);
            setMessage(message, "Field and schedule mapping saved.", "success");
            showToast("Profile mapping saved.");
        } catch (error) {
            setMessage(message, error.message, "error");
        } finally {
            setBusy(button, false, "");
        }
    }

    async function runGuidedProfileAction(action) {
        const profile = state.currentGuidedProfile;
        const message = byId("profile-evidence-message");
        const buttons = {
            evidence: byId("attach-profile-evidence"),
            confirm: byId("confirm-profile-mapping"),
            validate: byId("validate-profile")
        };
        const button = buttons[action];
        setBusy(button, true, "Working…");
        try {
            let updated;
            if (action === "evidence") {
                const sourceId = Number(byId("profile-evidence-source").value);
                if (!sourceId) {
                    throw new Error("Select a retained evidence document.");
                }
                updated = await request(`/api/permit-training-profiles/${profile.id}/evidence`, {
                    method: "POST",
                    body: {
                        expectedVersion: profile.version,
                        sourceId,
                        kind: "TRAINING",
                        expectedPermit: parseExpectedPermit("profile-expected-json")
                    }
                });
            } else if (action === "confirm") {
                updated = await request(`/api/permit-training-profiles/${profile.id}/confirm`, {
                    method: "POST",
                    body: {expectedVersion: profile.version}
                });
            } else {
                updated = await request(`/api/permit-training-profiles/${profile.id}/validate`, {
                    method: "POST",
                    body: {expectedVersion: profile.version}
                });
            }
            await loadGuidedProfiles();
            await selectGuidedProfile(updated.id);
            const completed = {
                evidence: "Corrected training result attached.",
                confirm: "Mapping confirmed. You can add more evidence or validate it.",
                validate: "Compilation and replay queued. Refresh after the worker finishes."
            };
            setMessage(message, completed[action], "success");
            showToast(completed[action]);
        } catch (error) {
            setMessage(message, error.message, "error");
        } finally {
            setBusy(button, false, "");
        }
    }

    async function viewCompiledProfile() {
        const profile = state.currentGuidedProfile;
        const report = byId("compiled-profile-report");
        try {
            const compiled = await request(`/api/permit-training-profiles/${profile.id}/compiled`);
            report.textContent = JSON.stringify(compiled, null, 2);
            report.hidden = false;
        } catch (error) {
            setMessage(byId("profile-evidence-message"), error.message, "error");
        }
    }

    async function refreshProfileReadiness() {
        const profile = state.currentGuidedProfile;
        if (!profile) {
            return;
        }
        try {
            state.currentProfileReadiness = await request(
                `/api/permit-training-profiles/${profile.id}/canary-readiness`);
            renderProfileReadiness();
            byId("profile-training-canary-count").textContent =
                `${state.currentProfileReadiness.passedCount} / ${state.currentProfileReadiness.minimumSuccesses}`;
            byId("profile-training-ready").textContent =
                state.currentProfileReadiness.readyForActivationReview ? "Ready" : "Blocked";
        } catch (error) {
            setMessage(byId("profile-canary-message"), error.message, "error");
        }
    }

    async function runProfileCanary() {
        const profile = state.currentGuidedProfile;
        const sourceId = Number(byId("profile-canary-source").value);
        const message = byId("profile-canary-message");
        if (!sourceId) {
            setMessage(message, "Select an unseen retained document.", "error");
            return;
        }
        const button = byId("run-profile-canary");
        setBusy(button, true, "Queueing…");
        try {
            const updated = await request(`/api/permit-training-profiles/${profile.id}/canaries`, {
                method: "POST",
                body: {
                    expectedVersion: profile.version,
                    sourceId,
                    expectedPermit: parseExpectedPermit("profile-canary-json")
                }
            });
            await loadGuidedProfiles();
            await selectGuidedProfile(updated.id);
            setMessage(message, "Canary queued. Refresh readiness after the worker evaluates it.", "success");
            showToast("Canary evaluation queued.");
        } catch (error) {
            setMessage(message, error.message, "error");
        } finally {
            setBusy(button, false, "");
        }
    }

    async function startTrainingWizard() {
        const sourceId = Number(byId("wizard-source").value);
        const button = byId("wizard-start");
        const message = byId("wizard-start-message");
        if (!sourceId) {
            setMessage(message, "Choose a document first.", "error");
            return;
        }
        setBusy(button, true, "Preparing…");
        setMessage(message, "");
        try {
            const workflow = await request("/api/permit-training-workflows", {
                method: "POST",
                body: {sourceId}
            });
            openTrainingWorkflow(workflow);
            setMessage(message,
                "Training draft is ready and saved in AeroSync.", "success");
        } catch (error) {
            setMessage(message, error.message, "error");
        } finally {
            setBusy(button, false, "");
        }
    }

    function openTrainingWorkflow(workflow, preservePermit = false) {
        state.trainingWorkflow = workflow;
        state.wizardSelectedCell = null;
        state.wizardActiveSemantic = null;
        state.wizardResolutionSelections = {};
        if (!preservePermit) {
            state.wizardPermit = workflow.expectedPermit
                ? structuredClone(workflow.expectedPermit)
                : createEmptyPermit();
            if (!state.wizardPermit.rawContent && workflow.source?.document?.rawContent) {
                state.wizardPermit.rawContent = workflow.source.document.rawContent;
            }
        }
        byId("wizard-workspace").hidden = false;
        renderTrainingWizard(preservePermit);
    }

    function wizardStepNumber(step) {
        if (step === "CHECK_RESULT") return 2;
        if (step === "RESOLVE_FIELDS") return 3;
        if (["VALIDATE", "TEST_MORE_DOCUMENTS"].includes(step)) return 4;
        if (["ACTIVATE", "ACTIVE"].includes(step)) return 5;
        return 1;
    }

    function renderTrainingWizard(preservePermit = false) {
        const workflow = state.trainingWorkflow;
        if (!workflow) return;
        const stepNumber = wizardStepNumber(workflow.currentStep);
        document.querySelectorAll("[data-wizard-step]").forEach((item, index) => {
            item.classList.toggle("is-current", index + 1 === stepNumber);
            item.classList.toggle("is-complete", index + 1 < stepNumber);
        });
        byId("wizard-kicker").textContent =
            `TRAINING DRAFT · ${workflow.source?.originalFileName || "WORD DOCUMENT"}`;
        byId("wizard-name").textContent = workflow.source?.originalFileName || "Permit format";
        byId("wizard-guidance").textContent = wizardGuidance(workflow.currentStep);
        setPill(byId("wizard-status"), workflow.status);
        byId("wizard-current-step").textContent = `${stepNumber} of 5`;
        byId("wizard-training-progress").textContent =
            `${workflow.progress.trainingExamples} / ${workflow.progress.requiredTrainingExamples}`;
        byId("wizard-test-progress").textContent =
            `${workflow.progress.unseenPassed} / ${workflow.progress.requiredUnseen}`;
        byId("wizard-version").textContent = String(workflow.version);
        byId("wizard-unresolved-count").textContent =
            `${workflow.unresolved.length} to resolve`;
        renderWizardDocument();
        if (!preservePermit) {
            renderWizardPermit();
        }
        renderWizardResolutions();
        renderWizardBlockers();
        updateWizardActions();
    }

    function wizardGuidance(step) {
        const guidance = {
            CHECK_RESULT: "Check the permit form and correct anything AeroSync did not know.",
            RESOLVE_FIELDS: "A few values need your confirmation before testing.",
            VALIDATE: "The mapping is ready. Build it and begin unseen-document tests.",
            TEST_MORE_DOCUMENTS: "Add different same-layout permits until three unseen tests pass.",
            ACTIVATE: "All safety checks passed. You may activate this format.",
            ACTIVE: "This format can now assist future extraction; every result still enters review."
        };
        return guidance[step] || "Follow the five steps below.";
    }

    function renderWizardDocument() {
        const documentModel = state.trainingWorkflow.source?.document;
        const container = byId("wizard-document-preview");
        if (!documentModel) {
            container.innerHTML = `<div class="compact-item is-blocker"><strong>Preview unavailable</strong>The captured Word structure is missing.</div>`;
            return;
        }
        const paragraphs = (documentModel.paragraphText || "").split(/\r?\n/)
            .filter(line => line.trim())
            .map(line => `<div class="compact-item" role="button" tabindex="0"
                data-wizard-text="${escapeHtml(line)}">${escapeHtml(line)}</div>`)
            .join("");
        const tables = (documentModel.tables || []).map(table => `
            <details class="source-table" open>
                <summary>Document table ${Number(table.index) + 1}</summary>
                <div class="table-scroll"><table><tbody>
                    ${(table.rows || []).map(row => `<tr>${(row.cells || []).map(cell => `
                        <td role="button" tabindex="0" data-wizard-cell="${escapeHtml(cell.id)}"
                            data-wizard-value="${escapeHtml(cell.value || "")}">
                            ${escapeHtml(cell.value || "(empty)")}
                        </td>`).join("")}</tr>`).join("")}
                </tbody></table></div>
            </details>`).join("");
        container.innerHTML = `
            <details class="source-table" open><summary>Document paragraphs</summary>
                <div class="compact-list">${paragraphs || "No separate paragraphs"}</div>
            </details>${tables}`;
        const selectValue = element => {
                container.querySelectorAll(".is-selected")
                    .forEach(item => item.classList.remove("is-selected"));
                element.classList.add("is-selected");
                state.wizardSelectedCell = element.dataset.wizardCell
                    ? {source: "CELL", cellId: element.dataset.wizardCell,
                        value: element.dataset.wizardValue}
                    : {source: "TEXT", selectedText: element.dataset.wizardText,
                        value: element.dataset.wizardText};
                if (state.wizardActiveSemantic) {
                    assignWizardResolution(
                        state.wizardActiveSemantic,
                        {...state.wizardSelectedCell,
                            confirmedValue: semanticExpectedValue(
                                state.wizardActiveSemantic)});
                    showToast("Value linked. Continue with the next highlighted field.");
                } else {
                    showToast("Value selected. Choose the field it belongs to below.");
                }
        };
        container.querySelectorAll("[data-wizard-cell], [data-wizard-text]")
            .forEach(element => {
                element.addEventListener("click", () => selectValue(element));
                element.addEventListener("keydown", event => {
                    if (event.key === "Enter" || event.key === " ") {
                        event.preventDefault();
                        selectValue(element);
                    }
                });
            });
    }

    function renderWizardPermit() {
        const permit = state.wizardPermit;
        const editable = Boolean(state.trainingWorkflow.actions.canEditPermit);
        const fields = permitFields.filter(field => field.key !== "rawContent");
        byId("wizard-permit-fields").innerHTML = fields.map(field => {
            const value = permit[field.key] ?? "";
            return `<label class="${field.className || ""}">
                ${escapeHtml(field.label)}
                <input type="${field.type || "text"}" value="${escapeHtml(value)}"
                       data-wizard-permit-field="${field.key}"
                       ${field.maxlength ? `maxlength="${field.maxlength}"` : ""}
                       ${field.min !== undefined ? `min="${field.min}"` : ""}
                       ${editable ? "" : "disabled"}>
            </label>`;
        }).join("");
        byId("wizard-permit-fields").querySelectorAll("[data-wizard-permit-field]")
            .forEach(input => input.addEventListener("change", event => {
                const key = event.target.dataset.wizardPermitField;
                state.wizardPermit[key] = event.target.type === "number"
                    ? Number(event.target.value || 0) : event.target.value;
                scheduleWizardAutosave();
            }));
        byId("wizard-iata").checked = Boolean(permit.iataAirportsAllowed);
        byId("wizard-empty-airways").checked = Boolean(permit.emptyAirwaysAllowed);
        byId("wizard-iata").disabled = !editable;
        byId("wizard-empty-airways").disabled = !editable;
        byId("wizard-iata").onchange = event => {
            state.wizardPermit.iataAirportsAllowed = event.target.checked;
            scheduleWizardAutosave();
        };
        byId("wizard-empty-airways").onchange = event => {
            state.wizardPermit.emptyAirwaysAllowed = event.target.checked;
            scheduleWizardAutosave();
        };
        renderWizardFlights();
    }

    function renderWizardFlights() {
        const editable = Boolean(state.trainingWorkflow.actions.canEditPermit);
        const flights = state.wizardPermit.flights || [];
        const body = byId("wizard-flight-body");
        body.innerHTML = flights.map((flight, index) => `<tr>
            ${flightFields.map(field => `<td><input type="${field.type}"
                class="${field.className || ""}" value="${escapeHtml(flight[field.key] ?? "")}"
                data-wizard-flight-index="${index}" data-wizard-flight-field="${field.key}"
                ${editable ? "" : "disabled"}></td>`).join("")}
            <td><button class="remove-row" type="button" data-wizard-remove-flight="${index}"
                ${editable ? "" : "disabled"}>×</button></td>
        </tr>`).join("");
        body.querySelectorAll("[data-wizard-flight-field]").forEach(input =>
            input.addEventListener("change", event => {
                const index = Number(event.target.dataset.wizardFlightIndex);
                const key = event.target.dataset.wizardFlightField;
                const value = event.target.type === "number"
                    ? (event.target.value === "" ? null : Number(event.target.value))
                    : event.target.value;
                state.wizardPermit.flights[index][key] = value;
                scheduleWizardAutosave();
            }));
        body.querySelectorAll("[data-wizard-remove-flight]").forEach(button =>
            button.addEventListener("click", () => {
                state.wizardPermit.flights.splice(Number(button.dataset.wizardRemoveFlight), 1);
                renderWizardFlights();
                scheduleWizardAutosave();
            }));
    }

    function wizardPermitReady() {
        const permit = state.wizardPermit;
        return Boolean(permit?.normalizedPermitId && permit?.permitDate
            && /^[A-Z0-9]{3}$/i.test(permit?.operatorId || "")
            && permit?.permitType && permit?.flightType && permit?.flights?.length);
    }

    function scheduleWizardAutosave() {
        window.clearTimeout(wizardAutosaveTimer);
        if (!state.trainingWorkflow?.actions?.canEditPermit || !wizardPermitReady()) return;
        wizardAutosaveTimer = window.setTimeout(() => {
            wizardSaveChain = wizardSaveChain
                .then(() => saveWizardPermit(true))
                .catch(error => setMessage(byId("wizard-permit-message"),
                    `Autosave paused: ${error.message}`, "error"));
        }, 900);
    }

    async function saveWizardPermit(silent = false) {
        const workflow = state.trainingWorkflow;
        if (!workflow) return;
        const response = await request(
            `/api/permit-training-workflows/${workflow.profileId}/expected-permit`, {
                method: "PUT",
                body: {
                    expectedVersion: state.trainingWorkflow.version,
                    sourceId: state.trainingWorkflow.source.id,
                    permit: structuredClone(state.wizardPermit)
                }
            });
        state.trainingWorkflow = response;
        if (!silent) {
            state.wizardPermit = structuredClone(response.expectedPermit);
            setMessage(byId("wizard-permit-message"),
                "Correct permit saved. Mapping suggestions were generated automatically.",
                "success");
        } else {
            setMessage(byId("wizard-permit-message"), "Draft saved.", "success");
        }
        renderTrainingWizard(silent);
    }

    function semanticExpectedValue(semantic) {
        const permit = state.wizardPermit || {};
        const values = {
            "permit.sourceNumber": permit.sourcePermitNumber,
            "permit.date": permit.permitDate,
            "operator.icao": permit.operatorId,
            "billing.address": permit.billingAddress,
            reference: permit.reference,
            purpose: permit.flights?.[0]?.purposeId
        };
        return values[semantic] ?? "";
    }

    function friendlySemantic(value) {
        const semantic = String(value).replace(/^confirm\./, "");
        if (semantic === "aircraft.aircraftType") return "Aircraft type";
        if (semantic === "aircraft.registrationMarks") return "Registration marks";
        if (semantic === "route.sector") return "Route sector";
        if (semantic === "route.airways") return "Airways";
        const known = [...scalarSemanticFields, ...scheduleSemanticColumns]
            .find(item => item.value === semantic || `schedule.${item.value}` === semantic);
        return known?.label?.replace(" *", "") || humanize(semantic.replace("schedule.", ""));
    }

    function isTableSemantic(semantic) {
        return semantic.startsWith("schedule.")
            || semantic.startsWith("route.")
            || semantic.startsWith("aircraft.");
    }

    function renderWizardResolutions() {
        const workflow = state.trainingWorkflow;
        const container = byId("wizard-resolutions");
        if (!workflow.unresolved.length) {
            container.innerHTML = `<div class="compact-item is-success"><strong>All fields resolved</strong>AeroSync has enough information to build this format.</div>`;
            return;
        }
        const unresolvedSemantics = workflow.unresolved
            .map(item => String(item).replace(/^confirm\./, ""));
        if (!state.wizardActiveSemantic
                || !unresolvedSemantics.includes(state.wizardActiveSemantic)
                || state.wizardResolutionSelections[state.wizardActiveSemantic]) {
            state.wizardActiveSemantic = unresolvedSemantics
                .find(semantic => !state.wizardResolutionSelections[semantic]) || null;
        }
        container.innerHTML = workflow.unresolved.map((item, index) => {
            const semantic = String(item).replace(/^confirm\./, "");
            const suggestion = workflow.suggestions.find(value => value.semanticField === semantic);
            const tableField = isTableSemantic(semantic);
            const selected = state.wizardResolutionSelections[semantic];
            const active = state.wizardActiveSemantic === semantic;
            return `<div class="resolution-card${selected ? " is-resolved" : ""}${active ? " is-active" : ""}" data-resolution-card="${index}">
                <div><strong>${escapeHtml(friendlySemantic(item))}</strong><br>
                    <span class="muted">${escapeHtml(active
                        ? "Now click the matching value in the document above"
                        : suggestion?.message || "Choose the correct value in the document")}</span></div>
                <div class="muted" data-resolution-choice="${escapeHtml(semantic)}">
                    ${selected
                        ? `Selected: ${escapeHtml(selected.value || selected.confirmedValue || "document value")}`
                        : suggestion
                            ? `Suggested: ${escapeHtml(suggestion.confirmedValue || suggestion.selectedText || "document cell")}`
                            : active ? "Waiting for you to click a value above" : "No value selected yet"}
                </div>
                <div class="resolution-actions">
                    ${selected ? `
                        <button class="button button-quiet" type="button"
                            data-undo-resolution="${escapeHtml(semantic)}">Undo</button>
                        <button class="button button-secondary" type="button"
                            data-change-resolution="${escapeHtml(semantic)}">Choose another</button>` : `
                        ${tableField ? "" : `<label class="checkbox-label"><input type="checkbox" data-resolution-constant="${escapeHtml(semantic)}">Always the same for this format</label>`}
                        <button class="button button-secondary" type="button"
                            data-use-resolution="${escapeHtml(semantic)}">${active ? "Waiting for document value…" : "Select from document"}</button>`}
                </div>
            </div>`;
        }).join("");
        container.querySelectorAll("[data-use-resolution]").forEach(button =>
            button.addEventListener("click", () => chooseWizardResolution(button)));
        container.querySelectorAll("[data-undo-resolution]").forEach(button =>
            button.addEventListener("click", () =>
                resetWizardResolution(button.dataset.undoResolution, false)));
        container.querySelectorAll("[data-change-resolution]").forEach(button =>
            button.addEventListener("click", () =>
                resetWizardResolution(button.dataset.changeResolution, true)));
    }

    function chooseWizardResolution(button) {
        const semantic = button.dataset.useResolution;
        state.wizardActiveSemantic = semantic;
        const suggestion = state.trainingWorkflow.suggestions
            .find(value => value.semanticField === semantic);
        const constant = byId("wizard-resolutions")
            .querySelector(`[data-resolution-constant="${CSS.escape(semantic)}"]`)?.checked;
        let selection;
        if (constant) {
            selection = {source: "CONSTANT", confirmedValue: semanticExpectedValue(semantic)};
        } else if (state.wizardSelectedCell) {
            selection = {...state.wizardSelectedCell,
                confirmedValue: semanticExpectedValue(semantic)};
        } else if (suggestion?.cellId) {
            selection = {source: "CELL", cellId: suggestion.cellId,
                confirmedValue: suggestion.confirmedValue};
        } else if (suggestion?.selectedText) {
            selection = {source: "TEXT", selectedText: suggestion.selectedText,
                confirmedValue: suggestion.confirmedValue};
        } else {
            renderWizardResolutions();
            showToast("Now click the correct value in the document above.");
            return;
        }
        assignWizardResolution(semantic, selection);
    }

    function assignWizardResolution(semantic, selection) {
        state.wizardResolutionSelections[semantic] = selection;
        const unresolvedSemantics = state.trainingWorkflow.unresolved
            .map(item => String(item).replace(/^confirm\./, ""));
        state.wizardActiveSemantic = unresolvedSemantics
            .find(item => !state.wizardResolutionSelections[item]) || null;
        renderWizardResolutions();
    }

    function resetWizardResolution(semantic, chooseReplacement) {
        delete state.wizardResolutionSelections[semantic];
        state.wizardActiveSemantic = semantic;
        state.wizardSelectedCell = null;
        byId("wizard-document-preview").querySelectorAll(".is-selected")
            .forEach(item => item.classList.remove("is-selected"));
        renderWizardResolutions();
        showToast(chooseReplacement
            ? "Selection cleared. Click the replacement value in the document."
            : "Selection undone. You can select it again whenever you are ready.");
    }

    function parseCellLocation(cellId) {
        const match = /^table-(\d+)-row-(\d+)-cell-(\d+)$/.exec(cellId || "");
        return match ? {table: Number(match[1]), row: Number(match[2])} : null;
    }

    async function saveWizardResolutions() {
        const workflow = state.trainingWorkflow;
        const fields = [];
        const scheduleColumns = {};
        const routeColumns = {};
        const aircraftColumns = {};
        let scheduleTable = null;
        let routeTable = null;
        let aircraftTable = null;
        let scheduleStart = 1;
        let routeStart = 1;
        let aircraftStart = 1;
        workflow.suggestions.filter(item => item.semanticField.startsWith("schedule.") && item.cellId)
            .forEach(item => {
                scheduleColumns[item.semanticField.replace("schedule.", "")] = item.cellId;
                const location = parseCellLocation(item.cellId);
                if (location) {
                    scheduleTable = location.table;
                    scheduleStart = Math.max(scheduleStart, location.row + 1);
                }
            });
        Object.entries(state.wizardResolutionSelections)
            .filter(([semantic]) => semantic.startsWith("schedule."))
            .forEach(([semantic, selection]) => {
                if (selection.source !== "CELL") return;
                scheduleColumns[semantic.replace("schedule.", "")] = selection.cellId;
                const location = parseCellLocation(selection.cellId);
                if (location) {
                    scheduleTable = location.table;
                    scheduleStart = Math.max(scheduleStart, location.row + 1);
                }
            });
        Object.entries(state.wizardResolutionSelections).forEach(([semantic, selection]) => {
            if (semantic.startsWith("schedule.")) {
                return;
            } else if (semantic.startsWith("route.") || semantic.startsWith("aircraft.")) {
                if (selection.source !== "CELL") return;
                const location = parseCellLocation(selection.cellId);
                if (!location) return;
                const column = semantic.substring(semantic.indexOf(".") + 1);
                if (scheduleTable !== null && location.table === scheduleTable) {
                    scheduleColumns[column] = selection.cellId;
                    scheduleStart = Math.max(scheduleStart, location.row + 1);
                } else if (semantic.startsWith("route.")) {
                    routeColumns[column] = selection.cellId;
                    routeTable = location.table;
                    routeStart = Math.max(routeStart, location.row + 1);
                } else {
                    aircraftColumns[column] = selection.cellId;
                    aircraftTable = location.table;
                    aircraftStart = Math.max(aircraftStart, location.row + 1);
                }
            } else {
                fields.push({
                    semanticField: semantic,
                    source: selection.source,
                    cellId: selection.cellId || null,
                    selectedText: selection.selectedText || null,
                    confirmedValue: selection.confirmedValue || semanticExpectedValue(semantic),
                    required: ["permit.sourceNumber", "permit.date", "operator.icao"].includes(semantic)
                });
            }
        });
        const unresolvedWithoutSelection = workflow.unresolved
            .map(item => String(item).replace(/^confirm\./, ""))
            .filter(semantic => !state.wizardResolutionSelections[semantic]
                && !workflow.suggestions.some(item => item.semanticField === semantic));
        if (unresolvedWithoutSelection.length) {
            setMessage(byId("wizard-resolution-message"),
                "Select a value for every highlighted field.", "error");
            return;
        }
        const response = await request(
            `/api/permit-training-workflows/${workflow.profileId}/resolutions`, {
                method: "PUT",
                body: {
                    expectedVersion: workflow.version,
                    fields,
                    schedule: scheduleTable === null ? null : {
                        tableIndex: scheduleTable,
                        dataStartRowIndex: scheduleStart,
                        columns: scheduleColumns
                    },
                    route: routeTable === null ? null : {
                        tableIndex: routeTable,
                        dataStartRowIndex: routeStart,
                        columns: routeColumns
                    },
                    aircraft: aircraftTable === null ? null : {
                        tableIndex: aircraftTable,
                        dataStartRowIndex: aircraftStart,
                        columns: aircraftColumns
                    }
                }
            });
        openTrainingWorkflow(response, true);
        setMessage(byId("wizard-resolution-message"),
            "Selections saved. Another operator can resume from here.", "success");
    }

    function friendlyBlocker(blocker) {
        const messages = {
            PROFILE_NOT_IN_CANARY: "Build and validate the format first.",
            COMPILED_PROFILE_REQUIRED: "The tested format has not been built yet.",
            TRAINING_EXAMPLE_REQUIRED: "At least one corrected training example must pass.",
            CANARY_FAILURE_REQUIRES_REVISION: "An unseen test failed. Edit the mapping and test again.",
            CANARY_EVALUATION_PENDING: "An unseen test is still being checked.",
            MINIMUM_CANARY_SUCCESSES_REQUIRED: "Three unique unseen documents must pass."
        };
        return messages[blocker] || humanize(blocker);
    }

    function renderWizardBlockers() {
        const blockers = state.trainingWorkflow.readiness?.blockers || [];
        byId("wizard-blockers").innerHTML = blockers.length
            ? blockers.map(blocker => `<div class="compact-item is-blocker"><strong>Not ready yet</strong>${escapeHtml(friendlyBlocker(blocker))}</div>`).join("")
            : `<div class="compact-item is-success"><strong>Safety checks passed</strong>This format is ready for activation.</div>`;
    }

    function updateWizardActions() {
        const actions = state.trainingWorkflow.actions;
        byId("wizard-save-permit").disabled = !actions.canEditPermit;
        byId("wizard-add-flight").disabled = !actions.canEditPermit;
        byId("wizard-save-resolutions").disabled = !actions.canResolve;
        byId("wizard-add-example").disabled = !actions.canAddExample;
        byId("wizard-validate").disabled = !actions.canValidate;
        byId("wizard-activate").disabled = !actions.canActivate;
    }

    async function addWizardExample() {
        const workflow = state.trainingWorkflow;
        const sourceId = Number(byId("wizard-example-source").value);
        if (!sourceId) {
            setMessage(byId("wizard-test-message"), "Choose another document.", "error");
            return;
        }
        try {
            const response = await request(
                `/api/permit-training-workflows/${workflow.profileId}/examples`, {
                    method: "POST",
                    body: {
                        expectedVersion: workflow.version,
                        sourceId,
                        permit: structuredClone(state.wizardPermit)
                    }
                });
            openTrainingWorkflow(response, true);
            setMessage(byId("wizard-test-message"),
                response.status === "CANARY"
                    ? "Unseen test queued. Refresh this workflow after the worker finishes."
                    : "Training example added.", "success");
        } catch (error) {
            setMessage(byId("wizard-test-message"), error.message, "error");
        }
    }

    async function validateWizard() {
        const workflow = state.trainingWorkflow;
        try {
            const response = await request(
                `/api/permit-training-workflows/${workflow.profileId}/validate`, {
                    method: "POST",
                    body: {expectedVersion: workflow.version}
                });
            openTrainingWorkflow(response, true);
            setMessage(byId("wizard-test-message"),
                "The format was built and validation was queued.", "success");
        } catch (error) {
            setMessage(byId("wizard-test-message"), error.message, "error");
        }
    }

    async function activateWizardProfile() {
        const workflow = state.trainingWorkflow;
        try {
            await request(`/api/permit-training-profiles/${workflow.profileId}/activate`, {
                method: "POST",
                body: {expectedVersion: workflow.version, acknowledgement: true}
            });
            const response = await request(
                `/api/permit-training-workflows/${workflow.profileId}`);
            openTrainingWorkflow(response, true);
            setMessage(byId("wizard-activation-message"),
                "Format activated. Future matching permits will enter operator review.",
                "success");
        } catch (error) {
            setMessage(byId("wizard-activation-message"), error.message, "error");
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
    byId("refresh-profile-training").addEventListener("click", loadGuidedProfiles);
    byId("profile-training-status-filter").addEventListener("change", loadGuidedProfiles);
    byId("refresh-training-sources").addEventListener("click", loadProfileTrainingSources);
    byId("retain-profile-source").addEventListener("click", retainSelectedProfileSource);
    byId("create-profile-draft").addEventListener("click", createGuidedProfile);
    byId("add-scalar-mapping").addEventListener("click", () => {
        state.scalarMappings.push({
            semanticField: "billing.address",
            source: "TEXT",
            cellId: null,
            selectedText: "",
            confirmedValue: "",
            required: false
        });
        renderScalarMappings();
        updateGuidedProfileActions();
    });
    byId("schedule-table-index").addEventListener("change", event => {
        state.scheduleMapping.tableIndex = Number(event.target.value);
        state.scheduleMapping.columns = {};
        renderScheduleColumns();
        updateGuidedProfileActions();
    });
    byId("schedule-data-row").addEventListener("input", event => {
        state.scheduleMapping.dataStartRowIndex = Number(event.target.value || 1);
    });
    byId("save-profile-definition").addEventListener("click", saveGuidedDefinition);
    byId("attach-profile-evidence").addEventListener("click", () => runGuidedProfileAction("evidence"));
    byId("confirm-profile-mapping").addEventListener("click", () => runGuidedProfileAction("confirm"));
    byId("validate-profile").addEventListener("click", () => runGuidedProfileAction("validate"));
    byId("view-compiled-profile").addEventListener("click", viewCompiledProfile);
    byId("run-profile-canary").addEventListener("click", runProfileCanary);
    byId("refresh-profile-readiness").addEventListener("click", refreshProfileReadiness);
    byId("wizard-refresh-documents").addEventListener("click", loadProfileTrainingSources);
    byId("wizard-start").addEventListener("click", startTrainingWizard);
    byId("wizard-add-flight").addEventListener("click", () => {
        state.wizardPermit.flights.push(createEmptyFlight());
        renderWizardFlights();
        scheduleWizardAutosave();
    });
    byId("wizard-save-permit").addEventListener("click", async () => {
        try {
            await saveWizardPermit(false);
        } catch (error) {
            setMessage(byId("wizard-permit-message"), error.message, "error");
        }
    });
    byId("wizard-save-resolutions").addEventListener("click", async () => {
        try {
            await saveWizardResolutions();
        } catch (error) {
            setMessage(byId("wizard-resolution-message"), error.message, "error");
        }
    });
    byId("wizard-add-example").addEventListener("click", addWizardExample);
    byId("wizard-validate").addEventListener("click", validateWizard);
    byId("wizard-activate").addEventListener("click", activateWizardProfile);
    initialize();
})();
