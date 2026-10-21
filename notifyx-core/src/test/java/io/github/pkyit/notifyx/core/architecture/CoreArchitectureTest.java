package io.github.pkyit.notifyx.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 设计书 5.3 铁律 2：core 不得依赖 Spring / MyBatis / JPA。
 *
 * @since 1.0
 */
@AnalyzeClasses(packages = "io.github.pkyit.notifyx.core")
class CoreArchitectureTest {

    @ArchTest
    static final ArchRule core_must_not_depend_on_spring = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("org.springframework..", "com.baomidou..", "org.apache.ibatis..", "jakarta.persistence..");
}
