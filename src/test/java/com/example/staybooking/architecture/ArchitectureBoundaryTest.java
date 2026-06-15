package com.example.staybooking.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

@AnalyzeClasses(packages = "com.example.staybooking", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureBoundaryTest {

    @ArchTest
    static final ArchRule domain_is_independent =
            noClasses().that().resideInAPackage("..domain..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..api..",
                            "..application..",
                            "..infra..",
                            "..config..");

    @ArchTest
    static final ArchRule application_does_not_depend_on_api_or_infra_adapters =
            noClasses().that().resideInAPackage("..application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "..api..",
                            "..infra..");
}
