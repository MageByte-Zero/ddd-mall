package com.magebyte.ddd.mall.inventory;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test void inventory_layers_keep_dependencies_inward() {
        var classes=new ClassFileImporter().importPackages("com.magebyte.ddd.mall.inventory");
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideInAnyPackage("..application..","..infrastructure..","..interfaces..","org.springframework..","org.apache.ibatis..","com.baomidou..")
                .check(classes);
        noClasses().that().resideInAPackage("..infrastructure..")
                .should().dependOnClassesThat().resideInAnyPackage("..application..","..interfaces..")
                .check(classes);
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..","..interfaces..")
                .check(classes);
    }
}
