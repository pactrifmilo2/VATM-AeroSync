package vatm.aerosync.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.api.dto.PagedResponse;
import vatm.aerosync.api.dto.PermitTrainingProfileCreateRequest;
import vatm.aerosync.api.dto.PermitTrainingProfileDetailResponse;
import vatm.aerosync.api.dto.PermitTrainingProfileEvidenceRequest;
import vatm.aerosync.api.dto.PermitTrainingProfileEvidenceResponse;
import vatm.aerosync.api.dto.PermitTrainingProfileEventResponse;
import vatm.aerosync.api.dto.PermitTrainingProfileSummaryResponse;
import vatm.aerosync.common.dto.PermitReviewSnapshot;
import vatm.aerosync.common.dto.PermitTrainingDocument;
import vatm.aerosync.common.dto.PermitTrainingProfileDefinition;
import vatm.aerosync.common.entity.PermitTrainingProfileEvent;
import vatm.aerosync.common.entity.PermitTrainingProfileEvidence;
import vatm.aerosync.common.entity.PermitTrainingProfileVersion;
import vatm.aerosync.common.entity.PermitTrainingSource;
import vatm.aerosync.common.enums.PermitTrainingEvidenceKind;
import vatm.aerosync.common.enums.PermitTrainingEvidenceResult;
import vatm.aerosync.common.enums.PermitTrainingProfileStatus;
import vatm.aerosync.common.repository.PermitTrainingProfileEventRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileEvidenceRepository;
import vatm.aerosync.common.repository.PermitTrainingProfileVersionRepository;
import vatm.aerosync.common.repository.PermitTrainingSourceRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;

@Service
public class PermitTrainingProfileService {

    static final int MAX_PAGE_SIZE = 100;
    private static final int DEFINITION_SCHEMA_VERSION = 1;
    private static final int MAX_FIELD_MAPPINGS = 32;
    private static final int MAX_TABLE_MAPPINGS = 8;
    private static final int MAX_COLUMNS_PER_TABLE = 24;

    private static final Set<String> SCALAR_FIELDS = Set.of(
            "permit.sourceNumber",
            "permit.date",
            "operator.icao",
            "operator.iata",
            "billing.address",
            "reference",
            "purpose");
    private static final Set<String> REQUIRED_SCALAR_FIELDS = Set.of(
            "permit.sourceNumber",
            "permit.date",
            "operator.icao");
    private static final Set<String> SCHEDULE_COLUMNS = Set.of(
            "flightNumber",
            "effectiveFrom",
            "effectiveTo",
            "serviceDays",
            "fromAirport",
            "etd",
            "toAirport",
            "eta",
            "aircraftType",
            "originalPermit",
            "remark");
    private static final Set<String> REQUIRED_SCHEDULE_COLUMNS = Set.of(
            "flightNumber",
            "effectiveFrom",
            "effectiveTo",
            "serviceDays",
            "fromAirport",
            "etd",
            "toAirport");
    private static final Set<String> ROUTE_COLUMNS = Set.of(
            "sector",
            "airways");
    private static final Set<String> AIRCRAFT_COLUMNS = Set.of(
            "aircraftType",
            "registrationMarks",
            "nationality");

    private final PermitTrainingProfileVersionRepository profileRepository;
    private final PermitTrainingProfileEvidenceRepository evidenceRepository;
    private final PermitTrainingProfileEventRepository eventRepository;
    private final PermitTrainingSourceRepository sourceRepository;
    private final ObjectMapper objectMapper =
            new ObjectMapper().findAndRegisterModules();

    public PermitTrainingProfileService(
            PermitTrainingProfileVersionRepository profileRepository,
            PermitTrainingProfileEvidenceRepository evidenceRepository,
            PermitTrainingProfileEventRepository eventRepository,
            PermitTrainingSourceRepository sourceRepository) {
        this.profileRepository = profileRepository;
        this.evidenceRepository = evidenceRepository;
        this.eventRepository = eventRepository;
        this.sourceRepository = sourceRepository;
    }

    @Transactional(readOnly = true)
    public PagedResponse<PermitTrainingProfileSummaryResponse> list(
            PermitTrainingProfileStatus status,
            int page,
            int size) {
        validatePage(page, size);
        PageRequest request = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        Page<PermitTrainingProfileVersion> profiles = status == null
                ? profileRepository.findAll(request)
                : profileRepository.findByStatus(status, request);
        return PagedResponse.from(profiles.map(this::toSummary));
    }

    @Transactional(readOnly = true)
    public PermitTrainingProfileDetailResponse get(Long id) {
        return toDetail(find(id));
    }

    @Transactional
    public PermitTrainingProfileDetailResponse create(
            PermitTrainingProfileCreateRequest request,
            String actor) {
        validateCreateRequest(request);
        PermitTrainingSource source = requireRetainedSource(request.sourceId());
        readDocument(source);
        int profileVersion = profileRepository
                .findByProfileKeyOrderByProfileVersionDesc(request.profileKey())
                .stream()
                .findFirst()
                .map(existing -> existing.getProfileVersion() + 1)
                .orElse(1);

        PermitTrainingProfileDefinition definition =
                new PermitTrainingProfileDefinition(
                        DEFINITION_SCHEMA_VERSION,
                        request.displayName().trim(),
                        request.family().trim(),
                        List.of(),
                        List.of(),
                        null);
        String definitionJson = writeJson(definition, "profile definition");

        PermitTrainingProfileVersion profile =
                new PermitTrainingProfileVersion();
        profile.setProfileKey(request.profileKey().trim());
        profile.setProfileVersion(profileVersion);
        profile.setStatus(PermitTrainingProfileStatus.DRAFT);
        profile.setBaseProfileId(blankToNull(request.baseProfileId()));
        if (profile.getBaseProfileId() != null
                && profile.getBaseProfileId().equals(source.getProfileId())) {
            profile.setBaseProfileVersion(source.getProfileVersion());
        }
        profile.setSchemaVersion(DEFINITION_SCHEMA_VERSION);
        profile.setDefinitionJson(definitionJson);
        profile.setDefinitionChecksum(checksum(definitionJson));
        profile.setCreatedBy(requireActor(actor));
        try {
            profile = profileRepository.saveAndFlush(profile);
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalStateException(
                    "A profile version was created concurrently; retry the request",
                    exception);
        }

        PermitTrainingProfileEvidence evidence =
                new PermitTrainingProfileEvidence();
        evidence.setTrainingProfile(profile);
        evidence.setTrainingSource(source);
        evidence.setKind(PermitTrainingEvidenceKind.TRAINING);
        evidence.setResult(PermitTrainingEvidenceResult.PENDING);
        evidence.setActor(requireActor(actor));
        evidence.setDetail("Initial draft source selected");
        evidenceRepository.saveAndFlush(evidence);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("sourceId", source.getId());
        detail.put("profileKey", profile.getProfileKey());
        detail.put("profileVersion", profile.getProfileVersion());
        recordEvent(profile, "CREATED", actor, detail);
        return toDetail(profile);
    }

    @Transactional
    public PermitTrainingProfileDetailResponse updateDefinition(
            Long id,
            long expectedVersion,
            PermitTrainingProfileDefinition submitted,
            String actor) {
        PermitTrainingProfileVersion profile = findForUpdate(id);
        requireStatus(profile, PermitTrainingProfileStatus.DRAFT,
                "Only draft profiles can be edited");
        requireVersion(profile, expectedVersion);
        List<PermitTrainingProfileEvidence> evidence = evidence(profile);
        if (evidence.isEmpty()) {
            throw new IllegalStateException(
                    "The draft does not have a source document");
        }
        PermitTrainingDocument primaryDocument =
                readDocument(evidence.getFirst().getTrainingSource());
        PermitTrainingProfileDefinition definition = normalizeDefinition(
                submitted,
                primaryDocument,
                false,
                true);
        String definitionJson = writeJson(definition, "profile definition");
        profile.setSchemaVersion(DEFINITION_SCHEMA_VERSION);
        profile.setDefinitionJson(definitionJson);
        profile.setDefinitionChecksum(checksum(definitionJson));
        profile.setLastError(null);
        profile = profileRepository.saveAndFlush(profile);

        recordEvent(
                profile,
                "DEFINITION_UPDATED",
                actor,
                Map.of(
                        "fieldMappings", definition.fields().size(),
                        "tableMappings", definition.tables().size()));
        return toDetail(profile);
    }

    @Transactional
    public PermitTrainingProfileDetailResponse attachEvidence(
            Long id,
            PermitTrainingProfileEvidenceRequest request,
            String actor) {
        PermitTrainingProfileVersion profile = findForUpdate(id);
        requireEditableEvidenceStatus(profile);
        requireVersion(profile, request.expectedVersion());
        PermitTrainingEvidenceKind kind = request.kind() == null
                ? PermitTrainingEvidenceKind.TRAINING
                : request.kind();
        if (kind != PermitTrainingEvidenceKind.TRAINING) {
            throw new IllegalStateException(
                    "Canary evidence is introduced in a later phase");
        }
        PermitTrainingSource source = requireRetainedSource(request.sourceId());
        PermitTrainingDocument document = readDocument(source);
        PermitTrainingProfileDefinition definition = readDefinition(profile);
        if (!definition.fields().isEmpty() || !definition.tables().isEmpty()) {
            normalizeDefinition(definition, document, false, false);
        }
        validateExpectedPermit(request.expectedPermit());

        PermitTrainingProfileEvidence item = evidenceRepository
                .findByTrainingProfileIdAndTrainingSourceId(
                        profile.getId(), source.getId())
                .orElseGet(PermitTrainingProfileEvidence::new);
        boolean firstCorrection = !hasText(item.getExpectedSnapshotJson());
        if (item.getTrainingProfile() == null) {
            item.setTrainingProfile(profile);
            item.setTrainingSource(source);
        }
        item.setKind(kind);
        item.setResult(PermitTrainingEvidenceResult.CORRECTED);
        item.setExpectedSnapshotJson(writeJson(
                request.expectedPermit(), "expected permit"));
        item.setActor(requireActor(actor));
        item.setEvaluatedAt(LocalDateTime.now());
        item.setDetail("Operator-confirmed expected extraction");
        item = evidenceRepository.saveAndFlush(item);

        refreshEvidenceCount(profile);
        profile = profileRepository.saveAndFlush(profile);
        recordEvent(
                profile,
                firstCorrection ? "EVIDENCE_ATTACHED" : "EVIDENCE_UPDATED",
                actor,
                Map.of(
                        "evidenceId", item.getId(),
                        "sourceId", source.getId(),
                        "result", item.getResult().name(),
                        "expectedChecksum", checksum(
                                item.getExpectedSnapshotJson())));
        return toDetail(profile);
    }

    @Transactional
    public PermitTrainingProfileDetailResponse removeEvidence(
            Long profileId,
            Long evidenceId,
            long expectedVersion,
            String actor) {
        PermitTrainingProfileVersion profile = findForUpdate(profileId);
        requireStatus(profile, PermitTrainingProfileStatus.DRAFT,
                "Evidence can be removed only while the profile is a draft");
        requireVersion(profile, expectedVersion);
        List<PermitTrainingProfileEvidence> existing = evidence(profile);
        if (existing.size() <= 1) {
            throw new IllegalStateException(
                    "A draft must keep at least one source document");
        }
        PermitTrainingProfileEvidence item = evidenceRepository
                .findByIdAndTrainingProfileId(evidenceId, profileId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Profile evidence not found: " + evidenceId));
        Long sourceId = item.getTrainingSource().getId();
        evidenceRepository.delete(item);
        evidenceRepository.flush();
        refreshEvidenceCount(profile);
        profile = profileRepository.saveAndFlush(profile);
        recordEvent(
                profile,
                "EVIDENCE_REMOVED",
                actor,
                Map.of("evidenceId", evidenceId, "sourceId", sourceId));
        return toDetail(profile);
    }

    @Transactional
    public PermitTrainingProfileDetailResponse confirmMapping(
            Long id,
            long expectedVersion,
            String actor) {
        PermitTrainingProfileVersion profile = findForUpdate(id);
        requireStatus(profile, PermitTrainingProfileStatus.DRAFT,
                "Only a draft profile can be confirmed");
        requireVersion(profile, expectedVersion);
        List<PermitTrainingProfileEvidence> items = evidence(profile);
        if (items.isEmpty()) {
            throw new IllegalStateException(
                    "At least one retained training source is required");
        }
        PermitTrainingProfileDefinition definition = readDefinition(profile);
        for (int index = 0; index < items.size(); index++) {
            PermitTrainingProfileEvidence item = items.get(index);
            requireRetained(item.getTrainingSource());
            normalizeDefinition(
                    definition,
                    readDocument(item.getTrainingSource()),
                    true,
                    index == 0);
        }
        long correctedEvidence = items.stream()
                .filter(item -> item.getResult()
                        == PermitTrainingEvidenceResult.CORRECTED)
                .filter(item -> hasText(item.getExpectedSnapshotJson()))
                .count();
        if (correctedEvidence < 1) {
            throw new IllegalStateException(
                    "At least one operator-confirmed expected permit is required");
        }
        profile.setEvidenceCount(Math.toIntExact(correctedEvidence));
        profile.setStatus(PermitTrainingProfileStatus.COLLECTING_EVIDENCE);
        profile.setConfirmedBy(requireActor(actor));
        profile.setConfirmedAt(LocalDateTime.now());
        profile.setLastError(null);
        profile = profileRepository.saveAndFlush(profile);
        recordEvent(
                profile,
                "MAPPING_CONFIRMED",
                actor,
                Map.of("evidenceCount", correctedEvidence));
        return toDetail(profile);
    }

    private PermitTrainingProfileDefinition normalizeDefinition(
            PermitTrainingProfileDefinition definition,
            PermitTrainingDocument document,
            boolean requireComplete,
            boolean requireTextPresence) {
        if (definition == null) {
            throw new IllegalArgumentException("Profile definition is required");
        }
        if (definition.schemaVersion() != DEFINITION_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported profile definition schemaVersion: "
                            + definition.schemaVersion());
        }
        String displayName = requiredText(
                definition.displayName(), 160, "displayName");
        String family = requiredSlug(
                definition.family(), 120, "family");
        if (definition.fields().size() > MAX_FIELD_MAPPINGS) {
            throw new IllegalArgumentException(
                    "A profile can define at most "
                            + MAX_FIELD_MAPPINGS + " field mappings");
        }
        if (definition.tables().size() > MAX_TABLE_MAPPINGS) {
            throw new IllegalArgumentException(
                    "A profile can define at most "
                            + MAX_TABLE_MAPPINGS + " table mappings");
        }

        DocumentIndex index = index(document);
        Set<String> semanticFields = new HashSet<>();
        List<PermitTrainingProfileDefinition.FieldMapping> fields =
                new ArrayList<>();
        for (PermitTrainingProfileDefinition.FieldMapping field
                : definition.fields()) {
            if (field == null || !SCALAR_FIELDS.contains(field.semanticField())) {
                throw new IllegalArgumentException(
                        "Unsupported scalar semantic field: "
                                + (field == null ? null : field.semanticField()));
            }
            if (!semanticFields.add(field.semanticField())) {
                throw new IllegalArgumentException(
                        "Duplicate scalar semantic field: "
                                + field.semanticField());
            }
            if (field.source() == null) {
                throw new IllegalArgumentException(
                        "A source is required for " + field.semanticField());
            }
            String cellId = blankToNull(field.cellId());
            String selectedText = blankToNull(field.selectedText());
            String confirmedValue = blankToNull(field.confirmedValue());
            switch (field.source()) {
                case CELL -> {
                    CellLocation cell = requireCell(index, cellId);
                    if (!hasText(cell.value())) {
                        throw new IllegalArgumentException(
                                "The selected source cell is empty for "
                                        + field.semanticField());
                    }
                    selectedText = cell.value();
                }
                case TEXT -> {
                    if (!hasText(selectedText)) {
                        throw new IllegalArgumentException(
                                "selectedText is required for "
                                        + field.semanticField());
                    }
                    if (selectedText.length() > 1000) {
                        throw new IllegalArgumentException(
                                "selectedText must not exceed 1000 characters");
                    }
                    if (requireTextPresence
                            && (!hasText(document.rawContent())
                                    || !document.rawContent().contains(selectedText))) {
                        throw new IllegalArgumentException(
                                "selectedText was not found in the training source for "
                                        + field.semanticField());
                    }
                    cellId = null;
                }
                case CONSTANT -> {
                    cellId = null;
                    selectedText = null;
                }
            }
            if (!hasText(confirmedValue)) {
                throw new IllegalArgumentException(
                        "confirmedValue is required for "
                                + field.semanticField());
            }
            if (confirmedValue.length() > 1000) {
                throw new IllegalArgumentException(
                        "confirmedValue must not exceed 1000 characters");
            }
            confirmedValue = normalizeConfirmedValue(
                    field.semanticField(), confirmedValue);
            fields.add(new PermitTrainingProfileDefinition.FieldMapping(
                    field.semanticField(),
                    field.source(),
                    cellId,
                    selectedText,
                    confirmedValue,
                    field.required()));
        }

        Set<String> tableIdentities = new HashSet<>();
        List<PermitTrainingProfileDefinition.TableMapping> tables =
                new ArrayList<>();
        for (PermitTrainingProfileDefinition.TableMapping table
                : definition.tables()) {
            if (table == null || table.role() == null) {
                throw new IllegalArgumentException(
                        "Every table mapping requires a role");
            }
            PermitTrainingDocument.Table sourceTable = index.tables()
                    .get(table.tableIndex());
            if (sourceTable == null) {
                throw new IllegalArgumentException(
                        "Unknown training source table: " + table.tableIndex());
            }
            String identity = table.role() + ":" + table.tableIndex();
            if (!tableIdentities.add(identity)) {
                throw new IllegalArgumentException(
                        "Duplicate table mapping: " + identity);
            }
            if (table.dataStartRowIndex() < 1
                    || table.dataStartRowIndex() >= sourceTable.rows().size()) {
                throw new IllegalArgumentException(
                        "dataStartRowIndex must identify a data row in table "
                                + table.tableIndex());
            }
            if (table.columns().isEmpty()
                    || table.columns().size() > MAX_COLUMNS_PER_TABLE) {
                throw new IllegalArgumentException(
                        "Each table mapping requires 1 to "
                                + MAX_COLUMNS_PER_TABLE + " columns");
            }
            Set<String> supportedColumns = supportedColumns(table.role());
            Map<String, String> columns = new TreeMap<>();
            Set<Integer> physicalColumns = new HashSet<>();
            table.columns().forEach((semanticColumn, headerCellId) -> {
                if (!supportedColumns.contains(semanticColumn)) {
                    throw new IllegalArgumentException(
                            "Unsupported " + table.role()
                                    + " column: " + semanticColumn);
                }
                CellLocation cell = requireCell(index, headerCellId);
                if (cell.tableIndex() != table.tableIndex()) {
                    throw new IllegalArgumentException(
                            "Header cell " + headerCellId
                                    + " does not belong to table "
                                    + table.tableIndex());
                }
                if (cell.rowIndex() >= table.dataStartRowIndex()) {
                    throw new IllegalArgumentException(
                            "Header cell " + headerCellId
                                    + " must be before dataStartRowIndex");
                }
                if (!hasText(cell.value())) {
                    throw new IllegalArgumentException(
                            "Header cell " + headerCellId + " is empty");
                }
                if (!physicalColumns.add(cell.columnIndex())) {
                    throw new IllegalArgumentException(
                            "A source column cannot map to multiple semantic columns");
                }
                columns.put(semanticColumn, headerCellId);
            });
            tables.add(new PermitTrainingProfileDefinition.TableMapping(
                    table.role(),
                    table.tableIndex(),
                    table.dataStartRowIndex(),
                    columns));
        }

        PermitTrainingProfileDefinition.Options options =
                normalizeOptions(definition.options(), requireComplete);
        if (requireComplete) {
            if (!semanticFields.containsAll(REQUIRED_SCALAR_FIELDS)) {
                throw new IllegalStateException(
                        "Required scalar mappings are missing: "
                                + difference(REQUIRED_SCALAR_FIELDS, semanticFields));
            }
            boolean completeSchedule = tables.stream()
                    .filter(table -> table.role()
                            == PermitTrainingProfileDefinition.TableRole.SCHEDULE)
                    .anyMatch(table -> table.columns().keySet()
                            .containsAll(REQUIRED_SCHEDULE_COLUMNS));
            if (!completeSchedule) {
                throw new IllegalStateException(
                        "A schedule mapping must include: "
                                + REQUIRED_SCHEDULE_COLUMNS);
            }
        }
        return new PermitTrainingProfileDefinition(
                DEFINITION_SCHEMA_VERSION,
                displayName,
                family,
                fields,
                tables,
                options);
    }

    private PermitTrainingProfileDefinition.Options normalizeOptions(
            PermitTrainingProfileDefinition.Options options,
            boolean requireComplete) {
        if (options == null) {
            if (requireComplete) {
                throw new IllegalStateException(
                        "Profile business options are required");
            }
            return null;
        }
        String authorId = optionalUpper(options.authorId(), 20, "authorId");
        String permitType = optionalUpper(
                options.permitType(), 20, "permitType");
        String version = optionalUpper(options.version(), 10, "version");
        String season = optionalUpper(options.season(), 20, "season");
        String flightType = optionalUpper(
                options.flightType(), 20, "flightType");
        Integer validHours = options.validHours();
        if (validHours != null && (validHours < 1 || validHours > 720)) {
            throw new IllegalArgumentException(
                    "validHours must be between 1 and 720");
        }
        if (requireComplete
                && (!hasText(authorId)
                || !hasText(permitType)
                || !hasText(version)
                || !hasText(season)
                || !hasText(flightType)
                || validHours == null)) {
            throw new IllegalStateException(
                    "authorId, permitType, version, season, validHours, and flightType are required");
        }
        if (requireComplete && !options.reviewOnly()) {
            throw new IllegalStateException(
                    "New learned profiles must remain reviewOnly during guided training");
        }
        return new PermitTrainingProfileDefinition.Options(
                authorId,
                permitType,
                version,
                season,
                validHours,
                flightType,
                options.allowIataAirports(),
                options.emptyAirwaysAllowed(),
                options.reviewOnly());
    }

    private DocumentIndex index(PermitTrainingDocument document) {
        Map<Integer, PermitTrainingDocument.Table> tables = new HashMap<>();
        Map<String, CellLocation> cells = new HashMap<>();
        for (PermitTrainingDocument.Table table : document.tables()) {
            if (tables.put(table.index(), table) != null) {
                throw new IllegalStateException(
                        "Training document contains duplicate table indexes");
            }
            for (PermitTrainingDocument.Row row : table.rows()) {
                for (PermitTrainingDocument.Cell cell : row.cells()) {
                    if (cell == null || !hasText(cell.id())) {
                        throw new IllegalStateException(
                                "Training document contains a cell without an id");
                    }
                    CellLocation location = new CellLocation(
                            table.index(),
                            row.index(),
                            cell.columnIndex(),
                            cell.value());
                    if (cells.put(cell.id(), location) != null) {
                        throw new IllegalStateException(
                                "Training document contains duplicate cell ids");
                    }
                }
            }
        }
        return new DocumentIndex(Map.copyOf(tables), Map.copyOf(cells));
    }

    private String normalizeConfirmedValue(
            String semanticField,
            String value) {
        return switch (semanticField) {
            case "operator.icao" -> {
                String normalized = value.toUpperCase(Locale.ROOT);
                if (!normalized.matches("^[A-Z0-9]{3}$")) {
                    throw new IllegalArgumentException(
                            "operator.icao must be a three-character ICAO code");
                }
                yield normalized;
            }
            case "operator.iata" -> {
                String normalized = value.toUpperCase(Locale.ROOT);
                if (!normalized.matches("^[A-Z0-9]{2}$")) {
                    throw new IllegalArgumentException(
                            "operator.iata must be a two-character IATA code");
                }
                yield normalized;
            }
            case "permit.date" -> {
                try {
                    yield java.time.LocalDate.parse(value).toString();
                } catch (java.time.format.DateTimeParseException exception) {
                    throw new IllegalArgumentException(
                            "permit.date confirmedValue must use YYYY-MM-DD");
                }
            }
            case "purpose" -> value.toUpperCase(Locale.ROOT);
            default -> value;
        };
    }

    private Set<String> supportedColumns(
            PermitTrainingProfileDefinition.TableRole role) {
        return switch (role) {
            case SCHEDULE, SUPPLEMENTAL_SCHEDULE -> SCHEDULE_COLUMNS;
            case ROUTE -> ROUTE_COLUMNS;
            case AIRCRAFT -> AIRCRAFT_COLUMNS;
        };
    }

    private CellLocation requireCell(DocumentIndex index, String cellId) {
        if (!hasText(cellId)) {
            throw new IllegalArgumentException("A source cellId is required");
        }
        CellLocation cell = index.cells().get(cellId.trim());
        if (cell == null) {
            throw new IllegalArgumentException(
                    "Unknown training source cell: " + cellId);
        }
        return cell;
    }

    private void validateExpectedPermit(PermitReviewSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("Expected permit is required");
        }
        if (!hasText(snapshot.normalizedPermitId())
                || snapshot.normalizedPermitId().length() > 100) {
            throw new IllegalArgumentException(
                    "A valid normalizedPermitId is required");
        }
        if (snapshot.permitDate() == null) {
            throw new IllegalArgumentException("permitDate is required");
        }
        if (snapshot.operatorId() == null
                || !snapshot.operatorId().matches("^[A-Z0-9]{3}$")) {
            throw new IllegalArgumentException(
                    "operatorId must be a three-character ICAO code");
        }
        if (!hasText(snapshot.permitType())
                || !hasText(snapshot.flightType())) {
            throw new IllegalArgumentException(
                    "permitType and flightType are required");
        }
        if (snapshot.flights().isEmpty()) {
            throw new IllegalArgumentException(
                    "At least one schedule flight is required");
        }
    }

    private PermitTrainingSource requireRetainedSource(Long sourceId) {
        PermitTrainingSource source = sourceRepository.findById(sourceId)
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training source not found: " + sourceId));
        requireRetained(source);
        return source;
    }

    private void requireRetained(PermitTrainingSource source) {
        if (!hasText(source.getCorpusPath()) || source.getRetainedAt() == null) {
            throw new IllegalStateException(
                    "Retain the source document before using it as training evidence");
        }
    }

    private PermitTrainingDocument readDocument(PermitTrainingSource source) {
        if (!hasText(source.getDocumentJson())) {
            throw new IllegalStateException(
                    "Training source does not contain a structured Word document");
        }
        try {
            return objectMapper.readValue(
                    source.getDocumentJson(), PermitTrainingDocument.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored training document is invalid", exception);
        }
    }

    private PermitTrainingProfileDefinition readDefinition(
            PermitTrainingProfileVersion profile) {
        if (!hasText(profile.getDefinitionJson())) {
            throw new IllegalStateException(
                    "Training profile does not contain a definition");
        }
        try {
            return objectMapper.readValue(
                    profile.getDefinitionJson(),
                    PermitTrainingProfileDefinition.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored training profile definition is invalid",
                    exception);
        }
    }

    private void refreshEvidenceCount(
            PermitTrainingProfileVersion profile) {
        int count = (int) evidence(profile).stream()
                .filter(item -> item.getResult()
                        == PermitTrainingEvidenceResult.CORRECTED
                        || item.getResult()
                        == PermitTrainingEvidenceResult.PASSED)
                .count();
        profile.setEvidenceCount(count);
    }

    private List<PermitTrainingProfileEvidence> evidence(
            PermitTrainingProfileVersion profile) {
        return evidenceRepository
                .findByTrainingProfileIdOrderByCreatedAtAsc(profile.getId());
    }

    private void recordEvent(
            PermitTrainingProfileVersion profile,
            String action,
            String actor,
            Map<String, ?> detail) {
        PermitTrainingProfileEvent event = new PermitTrainingProfileEvent();
        event.setTrainingProfile(profile);
        event.setAction(action);
        event.setActor(requireActor(actor));
        event.setEventDetail(writeJson(detail, "profile event"));
        eventRepository.saveAndFlush(event);
    }

    private PermitTrainingProfileVersion find(Long id) {
        return profileRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training profile not found: " + id));
    }

    private PermitTrainingProfileVersion findForUpdate(Long id) {
        return profileRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NoSuchElementException(
                        "Permit training profile not found: " + id));
    }

    private void requireStatus(
            PermitTrainingProfileVersion profile,
            PermitTrainingProfileStatus expected,
            String message) {
        if (profile.getStatus() != expected) {
            throw new IllegalStateException(message);
        }
    }

    private void requireEditableEvidenceStatus(
            PermitTrainingProfileVersion profile) {
        if (profile.getStatus() != PermitTrainingProfileStatus.DRAFT
                && profile.getStatus()
                != PermitTrainingProfileStatus.COLLECTING_EVIDENCE) {
            throw new IllegalStateException(
                    "Evidence can be attached only to draft or collecting profiles");
        }
    }

    private void requireVersion(
            PermitTrainingProfileVersion profile,
            long expectedVersion) {
        if (profile.getVersion() != expectedVersion) {
            throw new IllegalStateException(
                    "The training profile changed; reload it before saving");
        }
    }

    private PermitTrainingProfileSummaryResponse toSummary(
            PermitTrainingProfileVersion profile) {
        PermitTrainingProfileDefinition definition = readDefinition(profile);
        return new PermitTrainingProfileSummaryResponse(
                profile.getId(),
                profile.getProfileKey(),
                profile.getProfileVersion(),
                profile.getStatus(),
                definition.displayName(),
                definition.family(),
                profile.getBaseProfileId(),
                profile.getBaseProfileVersion(),
                profile.getEvidenceCount(),
                profile.getCanarySuccessCount(),
                profile.getCreatedBy(),
                profile.getConfirmedBy(),
                profile.getConfirmedAt(),
                profile.getLastError(),
                profile.getVersion(),
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }

    private PermitTrainingProfileDetailResponse toDetail(
            PermitTrainingProfileVersion profile) {
        List<PermitTrainingProfileEvidenceResponse> evidence = evidence(profile)
                .stream()
                .map(this::toEvidence)
                .toList();
        List<PermitTrainingProfileEventResponse> history = eventRepository
                .findByTrainingProfileIdOrderByCreatedAtAsc(profile.getId())
                .stream()
                .map(event -> new PermitTrainingProfileEventResponse(
                        event.getId(),
                        event.getAction(),
                        event.getActor(),
                        event.getEventDetail(),
                        event.getCreatedAt()))
                .toList();
        return new PermitTrainingProfileDetailResponse(
                profile.getId(),
                profile.getProfileKey(),
                profile.getProfileVersion(),
                profile.getStatus(),
                profile.getBaseProfileId(),
                profile.getBaseProfileVersion(),
                profile.getSchemaVersion(),
                readDefinition(profile),
                profile.getDefinitionChecksum(),
                profile.getEvidenceCount(),
                profile.getCanarySuccessCount(),
                profile.getCreatedBy(),
                profile.getConfirmedBy(),
                profile.getConfirmedAt(),
                profile.getLastError(),
                profile.getVersion(),
                profile.getCreatedAt(),
                profile.getUpdatedAt(),
                evidence,
                history);
    }

    private PermitTrainingProfileEvidenceResponse toEvidence(
            PermitTrainingProfileEvidence item) {
        PermitTrainingSource source = item.getTrainingSource();
        return new PermitTrainingProfileEvidenceResponse(
                item.getId(),
                source.getId(),
                source.getFileRecord().getId(),
                source.getOriginalFileName(),
                source.getState(),
                hasText(source.getCorpusPath()),
                item.getKind(),
                item.getResult(),
                readExpectedPermit(item.getExpectedSnapshotJson()),
                item.getActor(),
                item.getEvaluatedAt(),
                item.getDetail(),
                item.getCreatedAt());
    }

    private PermitReviewSnapshot readExpectedPermit(String json) {
        if (!hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PermitReviewSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Stored expected permit is invalid", exception);
        }
    }

    private void validateCreateRequest(
            PermitTrainingProfileCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException(
                    "Profile creation request is required");
        }
        requiredSlug(request.profileKey(), 120, "profileKey");
        requiredText(request.displayName(), 160, "displayName");
        requiredSlug(request.family(), 120, "family");
        if (request.sourceId() == null) {
            throw new IllegalArgumentException("sourceId is required");
        }
        optionalText(request.baseProfileId(), 120, "baseProfileId");
    }

    private String requiredSlug(
            String value,
            int maximum,
            String label) {
        String result = requiredText(value, maximum, label);
        if (!result.matches("^[a-z0-9][a-z0-9-]{1,119}$")) {
            throw new IllegalArgumentException(
                    label + " must use lowercase letters, numbers, and hyphens");
        }
        return result;
    }

    private String requiredText(
            String value,
            int maximum,
            String label) {
        String result = optionalText(value, maximum, label);
        if (!hasText(result)) {
            throw new IllegalArgumentException(label + " is required");
        }
        return result;
    }

    private String optionalText(
            String value,
            int maximum,
            String label) {
        String result = blankToNull(value);
        if (result != null && result.length() > maximum) {
            throw new IllegalArgumentException(
                    label + " must not exceed " + maximum + " characters");
        }
        return result;
    }

    private String optionalUpper(
            String value,
            int maximum,
            String label) {
        String result = optionalText(value, maximum, label);
        return result == null ? null : result.toUpperCase(Locale.ROOT);
    }

    private String requireActor(String actor) {
        String result = blankToNull(actor);
        if (result == null) {
            throw new IllegalArgumentException("Authenticated actor is required");
        }
        return result.length() <= 100 ? result : result.substring(0, 100);
    }

    private String writeJson(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Could not serialize " + label, exception);
        }
    }

    private String checksum(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Set<String> difference(
            Set<String> required,
            Set<String> actual) {
        Set<String> missing = new java.util.TreeSet<>(required);
        missing.removeAll(actual);
        return missing;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void validatePage(int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException(
                    "page must be greater than or equal to 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "size must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    private record DocumentIndex(
            Map<Integer, PermitTrainingDocument.Table> tables,
            Map<String, CellLocation> cells
    ) {
    }

    private record CellLocation(
            int tableIndex,
            int rowIndex,
            int columnIndex,
            String value
    ) {
    }
}
