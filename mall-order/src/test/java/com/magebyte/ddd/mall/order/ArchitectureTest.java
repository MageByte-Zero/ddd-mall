package com.magebyte.ddd.mall.order;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构守护：用 ArchUnit 把四层架构的依赖方向变成自动执行的测试，
 * 不依赖人肉代码评审。
 *
 * <p>四层合法依赖方向：接口层 → 应用层 → 领域层 ← 基础设施层。
 * 反向写成四条禁令：
 * <ol>
 *   <li>领域层不依赖应用层、接口层、基础设施层；</li>
 *   <li>领域层不绑定 Spring、MyBatis-Plus、RocketMQ 等框架包
 *       （注解也算依赖，领域类挂 {@code @Component} 同样会被抓住）；</li>
 *   <li>应用层不依赖接口层、基础设施层；</li>
 *   <li>基础设施层不依赖接口层、应用层。</li>
 * </ol>
 *
 * <p>{@code allowEmptyShould(true)}：骨架阶段四层包里还没有业务类，
 * 允许规则暂时空转；第 6 讲订单聚合根进 domain 层后规则自动开始真查。
 * ArchUnit 1.x 默认把"规则没匹配到任何类"判为失败，
 * 用来防包名拼写错误导致规则静默失效。
 */
@AnalyzeClasses(packages = "com.magebyte.ddd.mall.order",
        importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    private static final String DOMAIN = "..order.domain..";
    private static final String APPLICATION = "..order.application..";
    private static final String INTERFACES = "..order.interfaces..";
    private static final String INFRASTRUCTURE = "..order.infrastructure..";

    @ArchTest
    static final ArchRule domain_layer_should_not_depend_on_outer_layers =
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(APPLICATION, INTERFACES, INFRASTRUCTURE)
                    .allowEmptyShould(true)
                    .because("领域层是四层架构的核心，不依赖外层，外层才能依赖它");

    @ArchTest
    static final ArchRule domain_layer_should_not_bind_frameworks =
            noClasses().that().resideInAPackage(DOMAIN)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage("org.springframework..",
                            "com.baomidou..", "org.apache.rocketmq..")
                    .allowEmptyShould(true)
                    .because("业务规则只属于业务，不属于技术栈");

    @ArchTest
    static final ArchRule application_layer_should_not_skip_into_infrastructure_or_interfaces =
            noClasses().that().resideInAPackage(APPLICATION)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(INTERFACES, INFRASTRUCTURE)
                    .allowEmptyShould(true)
                    .because("应用层只编排领域对象，不碰技术细节与协议适配");

    @ArchTest
    static final ArchRule infrastructure_layer_should_not_depend_on_upper_layers =
            noClasses().that().resideInAPackage(INFRASTRUCTURE)
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(INTERFACES, APPLICATION)
                    .allowEmptyShould(true)
                    .because("基础设施层只依赖领域层，通过依赖倒置实现仓储接口");
}
