package vatm.aerosync.worker.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import vatm.aerosync.common.entity.FileRecord;
import vatm.aerosync.common.entity.PermitImport;
import vatm.aerosync.common.repository.FileRecordRepository;
import vatm.aerosync.common.repository.PermitImportRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoredErrorMessageLocalizationMigrationTest {

    @Test
    void convertsLegacyEnglishErrorMessageValues() throws Exception {
        FileRecordRepository fileRecords = mock(FileRecordRepository.class);
        PermitImportRepository permitImports = mock(PermitImportRepository.class);
        FileRecord fileRecord = new FileRecord();
        fileRecord.setErrorMessage("Unsupported Word permit format; no format profile matched");
        PermitImport permitImport = new PermitImport();
        permitImport.setErrorMessage("ATFM writes are disabled");
        when(fileRecords.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(fileRecord)));
        when(permitImports.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(permitImport)));

        new StoredErrorMessageLocalizationMigration(fileRecords, permitImports)
                .run(new DefaultApplicationArguments(new String[0]));

        assertThat(fileRecord.getErrorMessage())
                .isEqualTo("Không hỗ trợ định dạng file Word này vì chưa có format YAML phù hợp.");
        assertThat(permitImport.getErrorMessage())
                .isEqualTo("Chức năng ghi dữ liệu vào ATFM đang bị tắt trong cấu hình.");
        verify(fileRecords).saveAll(List.of(fileRecord));
        verify(permitImports).saveAll(List.of(permitImport));
    }
}
