package vatm.aerosync.worker.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitImportRepository;

import java.util.ArrayList;
import java.util.List;

/** Localizes legacy English ERROR_MESSAGE values in bounded database batches. */
@Component
public class StoredErrorMessageLocalizationMigration implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StoredErrorMessageLocalizationMigration.class);
    private static final int BATCH_SIZE = 500;

    private final FileRecordRepository fileRecordRepository;
    private final PermitImportRepository permitImportRepository;

    public StoredErrorMessageLocalizationMigration(FileRecordRepository fileRecordRepository,
                                                   PermitImportRepository permitImportRepository) {
        this.fileRecordRepository = fileRecordRepository;
        this.permitImportRepository = permitImportRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int fileRecords = localizeFileRecords();
        int permitImports = localizePermitImports();
        if (fileRecords > 0 || permitImports > 0) {
            log.info("Đã chuyển errorMessage cũ sang tiếng Việt: fileRecord={}, permitImport={}",
                    fileRecords, permitImports);
        }
    }

    private int localizeFileRecords() {
        int updated = 0;
        Pageable pageable = firstPage();
        while (pageable.isPaged()) {
            Page<FileRecord> page = fileRecordRepository.findAll(pageable);
            List<FileRecord> changed = new ArrayList<>();
            for (FileRecord record : page.getContent()) {
                String translated = VietnameseStoredErrorMessage.translate(record.getErrorMessage());
                if (translated != null && !translated.equals(record.getErrorMessage())) {
                    record.setErrorMessage(translated);
                    changed.add(record);
                }
            }
            if (!changed.isEmpty()) {
                fileRecordRepository.saveAll(changed);
                updated += changed.size();
            }
            pageable = page.hasNext() ? page.nextPageable() : Pageable.unpaged();
        }
        return updated;
    }

    private int localizePermitImports() {
        int updated = 0;
        Pageable pageable = firstPage();
        while (pageable.isPaged()) {
            Page<PermitImport> page = permitImportRepository.findAll(pageable);
            List<PermitImport> changed = new ArrayList<>();
            for (PermitImport permitImport : page.getContent()) {
                String translated = VietnameseStoredErrorMessage.translate(permitImport.getErrorMessage());
                if (translated != null && !translated.equals(permitImport.getErrorMessage())) {
                    permitImport.setErrorMessage(translated);
                    changed.add(permitImport);
                }
            }
            if (!changed.isEmpty()) {
                permitImportRepository.saveAll(changed);
                updated += changed.size();
            }
            pageable = page.hasNext() ? page.nextPageable() : Pageable.unpaged();
        }
        return updated;
    }

    private Pageable firstPage() {
        return PageRequest.of(0, BATCH_SIZE, Sort.by(Sort.Order.asc("id")));
    }
}
