# Change log 25102026

## Airport fallback VVVV

- Objective: allow a permit to be saved when an airport code cannot be mapped in ATFM, using the explicit placeholder VVVV and retaining a warning.
- Before: missing IATA/ICAO airport reference data raised AtfmReferenceDataException, quarantining the permit before the ATFM write.
- After: unresolved airport codes are replaced with VVVV, receive BR-AIRPORT-FALLBACK, pass validation, and are written with a row-specific warning containing the source code and fallback code. Unknown non-airport ATFM references remain blocking.
- Files changed: airport resolver, via resolution, scheduled-permit validation, warning translation, and focused tests.
- Verification: mvn -f ../pom.xml -pl aerosync-worker -am "-Dtest=AtfmAirportCodeResolverTest,ViaResolutionStepTest,BusinessRuleValidatorStepTest,VietnameseStoredErrorMessageTest,DatabaseWriterStepTest,FileProcessingPipelineTest" "-Dsurefire.failIfNoSpecifiedTests=false" test.
- Result: BUILD SUCCESS; 54 tests, 0 failures, 0 errors, 0 skipped.
- Limitation: VVVV is an explicit data-quality placeholder; affected rows require later operational correction.
