# Change log 25102026

## Accept duplicate ICAO airport reference rows

- Objective: distinguish a missing ICAO airport from duplicate reference rows in M_AERO.
- Before: AtfmAirportCodeResolver treated any ICAO AE_CODE count other than exactly one as missing, even when matching rows existed; this could trigger VVVV fallback for an existing code such as YPPH.
- After: four-character ICAO codes are considered missing only when the M_AERO.AE_CODE count is zero. Duplicate rows retain the supplied ICAO code; IATA resolution remains fail-closed on ambiguous mappings.
- Files changed: AtfmAirportCodeResolver.java and AtfmAirportCodeResolverTest.java.
- Verification: mvn -f ../pom.xml -pl aerosync-worker -am "-Dtest=AtfmAirportCodeResolverTest,ViaResolutionStepTest,BusinessRuleValidatorStepTest,VietnameseStoredErrorMessageTest,DatabaseWriterStepTest,FileProcessingPipelineTest" "-Dsurefire.failIfNoSpecifiedTests=false" test.
- Result: BUILD SUCCESS; 54 tests, 0 failures, 0 errors, 0 skipped.
- Limitation: production M_AERO rows were not queried. Deploy the rebuilt worker JAR and retry the quarantined job after confirming the reference rows.
