package vatm.aerosync.api.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;
import vatm.aerosync.api.service.ReportFileType;

import java.util.Locale;

@Component
public class ReportFileTypeConverter implements Converter<String, ReportFileType> {
    @Override
    public ReportFileType convert(String source) {
        if (source == null || source.isBlank()) return null;
        return ReportFileType.valueOf(source.trim().toUpperCase(Locale.ROOT));
    }
}
